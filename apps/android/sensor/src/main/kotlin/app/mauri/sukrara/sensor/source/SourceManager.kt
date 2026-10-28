// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.sensor.source

import app.mauri.sukrara.core.model.Clock
import app.mauri.sukrara.core.model.Reading
import app.mauri.sukrara.core.model.ReadingSource
import app.mauri.sukrara.core.model.SensorAction
import app.mauri.sukrara.core.model.SensorState
import app.mauri.sukrara.core.model.SourceStatus
import app.mauri.sukrara.sensor.SensorInfo
import app.mauri.sukrara.sensor.SensorLife
import app.mauri.sukrara.sensor.SensorProfile
import app.mauri.sukrara.sensor.aidex.AidexXDriver
import app.mauri.sukrara.sensor.ble.BleScanner
import app.mauri.sukrara.sensor.ble.GattConnector
import app.mauri.sukrara.sensor.ble.GattLink
import app.mauri.sukrara.sensor.ble.LinkStatus
import app.mauri.sukrara.sensor.ble.SensorDriver
import app.mauri.sukrara.sensor.ble.SensorEvents
import app.mauri.sukrara.sensor.ble.SensorGlucose
import app.mauri.sukrara.sensor.ble.SensorGlucoseBatch
import app.mauri.sukrara.sensor.ble.SensorScanner
import app.mauri.sukrara.sensor.brand.BrandDriver
import app.mauri.sukrara.sensor.cgms.SmartGuideDriver
import app.mauri.sukrara.sensor.dexcom.G6Driver
import app.mauri.sukrara.sensor.dexcom.G7Driver
import app.mauri.sukrara.sensor.juggluco.JugglucoSink
import app.mauri.sukrara.sensor.juggluco.JugglucoSource
import app.mauri.sukrara.sensor.juggluco.JugglucoStatus
import app.mauri.sukrara.sensor.libre3.Libre3Driver
import app.mauri.sukrara.sensor.libre3.Libre3Security
import app.mauri.sukrara.sensor.pairing.Pairing
import app.mauri.sukrara.sensor.pairing.PairingLoad
import app.mauri.sukrara.sensor.pairing.PairingStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * The wearer's [ReadingSource]: the saved sensor read directly (a Libre 3, or one of the untested scanned families:
 * Dexcom G7 / G6, Accu-Chek SmartGuide, Aidex X), or Juggluco, per the setting ([kind]). Everything runs on
 * ONE serial [dispatcher] (`limitedParallelism(1)`): a source switch, a restart ([retry], a new pairing, Bluetooth on)
 * fully cancels and closes the old source before the new one starts (no restart race).
 *
 * Safety: readings come only from the sensor / Juggluco, never invented; every problem becomes a [SensorStatus] with
 * an action; a no-data watchdog restarts a sensor link that has gone quiet without saying why; a live value is never
 * delivered twice as live (a restarted driver repeating its last value gives history).
 */
class SourceManager internal constructor(
    private val kind: Flow<SensorSourceKind>,
    private val pairings: PairingStore,
    /** Null when this build has no Libre 3 native core. */
    private val security: Libre3Security?,
    private val connector: GattConnector,
    private val scanner: BleScanner,
    private val juggluco: () -> JugglucoSource,
    private val clock: Clock,
    private val dispatcher: CoroutineDispatcher,
    /** Blocking I/O (Juggluco's HTTP). */
    private val io: CoroutineDispatcher,
    private val log: (String) -> Unit = {},
) : ReadingSource {
    companion object {
        /** No reading for this long on a running (not warming, not ended) sensor → restart the link. */
        const val WATCHDOG_MS = 10 * 60_000L
        private const val WATCHDOG_TICK_MS = 60_000L

        /** A source that failed unexpectedly, or a secure store that was busy, is started again after this long. */
        const val RECOVERY_MS = 30_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + dispatcher + CoroutineExceptionHandler { _, e -> log("sensor task failed: $e") })
    private val readingsOut = Channel<List<Reading>>(Channel.UNLIMITED)
    private val restarts = MutableStateFlow(0)
    private val _detail = MutableStateFlow<SensorStatus>(SensorStatus.NoSensor)
    private val _sensor = MutableStateFlow<SensorState>(SensorState.None)
    private val _status = MutableStateFlow<SourceStatus>(SourceStatus.NotStarted)
    private val _sensorGlucose = MutableSharedFlow<SensorGlucoseBatch>(extraBufferCapacity = 64)
    private val infoState = MutableStateFlow<SensorInfo?>(null)
    private var info: SensorInfo?
        get() = infoState.value
        set(value) {
            infoState.value = value
        }
    private var latestReading: Instant? = null
    private var link: GattLink? = null
    private var started: Job? = null

    /** The newest live life count per sensor serial: a restarted driver never reports it again as a new reading. */
    private var lastLifeCount: Pair<String, Int>? = null

    /** The newest live reading's time per pairing ([SensorStatus.Direct.key]): an older or equal one is history. */
    private var lastLive: Pair<String, Instant>? = null

    override val readings: Flow<List<Reading>> = readingsOut.receiveAsFlow()
    override val sensor: StateFlow<SensorState> = _sensor.asStateFlow()
    override val status: StateFlow<SourceStatus> = _status.asStateFlow()

    /** The detailed state for the sensor screen / new-sensor flow. */
    val detail: StateFlow<SensorStatus> = _detail.asStateFlow()

    /** The current sensor's serial, start and end (Libre 3 pairing or Juggluco's sensor), null when none is known. */
    val sensorInfo: StateFlow<SensorInfo?> = infoState.asStateFlow()

    /** Libre 3 readings by life count, tagged with their sensor's serial, for the LibreView upload. */
    val sensorGlucose: Flow<SensorGlucoseBatch> = _sensorGlucose.asSharedFlow()

    /** Starts following the setting; idempotent. */
    fun start() {
        if (started != null) return
        started =
            scope.launch {
                combine(kind.distinctUntilChanged(), restarts) { k, _ -> k }.collectLatest { k ->
                    resetState()
                    try {
                        when (k) {
                            SensorSourceKind.Direct -> runDirect()
                            SensorSourceKind.Juggluco -> runJuggluco()
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // never leave the person without a source because of an unexpected failure: say so, start again
                        log("source failed: ${e.javaClass.simpleName}")
                        interrupted()
                        delay(RECOVERY_MS)
                        retry()
                    }
                }
            }
    }

    /** Stops reading (the phone stops being the wearer): the link is closed; [start] begins again. */
    fun stop() {
        started?.cancel()
        started = null
        scope.launch {
            resetState()
            publish(SensorStatus.NoSensor)
        }
    }

    /** Start the source over (new pairing saved, "Try again", Bluetooth back on): a new driver and connection. */
    fun retry() {
        restarts.value++
    }

    override fun onBluetoothOn() = retry()

    override fun onBluetoothOff() {
        scope.launch { link?.bluetoothOff() }
    }

    // MARK: the sensor, read directly

    private suspend fun runDirect() {
        val pairing =
            when (val load = pairings.load()) {
                PairingLoad.None -> {
                    return publish(SensorStatus.NoSensor)
                }

                is PairingLoad.Corrupt -> {
                    publish(SensorStatus.PairingUnreadable(load.reason))
                    if (load.transient) {
                        delay(RECOVERY_MS) // the Keystore was busy: try again by itself
                        retry()
                    }
                    return
                }

                is PairingLoad.Ok -> {
                    load.pairing
                }
            }
        info = pairing.info
        val core = security
        if (pairing.profile == SensorProfile.Libre3) {
            if (core == null) return publish(SensorStatus.CoreMissing)
            if (pairing.securityVersion != 0) return publish(SensorStatus.Unsupported("security version ${pairing.securityVersion}"))
        }
        if (pairing.ended) return publish(SensorStatus.Ended)
        // a failure only the person can fix is kept: never retried in the background after a restart (no PIN dialog
        // out of nowhere); the new-sensor flow's new pairing clears it
        pairing.failed?.let { why ->
            return publish(SensorStatus.Direct(pairing.profile, pairing.label, SensorStatus.Direct.keyOf(pairing), LinkStatus.Failed(why)))
        }
        val events = DirectEvents(pairing)
        events.onStatus(LinkStatus.Connecting)
        _status.value = SourceStatus.Connecting
        coroutineScope {
            val driver = driverFor(pairing, events, core)
            var address = pairing.address
            if (driver is BrandDriver && address.isEmpty()) {
                // a scanned family's first run: find it, keep its address (later runs connect straight to it)
                val found = SensorScanner(scanner, log).find(driver, events) ?: awaitCancellation()
                pairings.setFound(pairing, found.address, found.name.orEmpty())
                address = found.address
                found.name?.let { name -> events.found(name) }
            }
            events.address = address
            val l = GattLink(connector, address, driver, events, this, log = log)
            link = l
            val startedAt = clock.now()
            launch { watchdog(startedAt) }
            try {
                l.start()
                awaitCancellation()
            } finally {
                l.close()
                link = null
            }
        }
    }

    private fun driverFor(
        pairing: Pairing,
        events: DirectEvents,
        core: Libre3Security?,
    ): SensorDriver {
        val now = { clock.now().toEpochMilli() }
        return when (pairing.profile) {
            SensorProfile.DexcomG7, SensorProfile.DexcomG7Long -> {
                G7Driver(pairing, events, now, log)
            }

            SensorProfile.DexcomG6 -> {
                G6Driver(pairing, events, clockMs = now, log = log)
            }

            SensorProfile.SmartGuide -> {
                SmartGuideDriver(pairing, events, now, log)
            }

            SensorProfile.AidexX -> {
                AidexXDriver(pairing, events, now, log)
            }

            SensorProfile.Libre3, SensorProfile.Juggluco -> {
                val known = lastLifeCount?.takeIf { it.first == pairing.serial }?.second ?: -1
                Libre3Driver(pairing, events, checkNotNull(core), clockMs = now, log = log, lastLifeCount = known)
            }
        }
    }

    /** Restarts the link when a running sensor has sent nothing for [WATCHDOG_MS] (a stuck stack, a silent link). */
    private suspend fun watchdog(startedAt: Instant) {
        while (true) {
            delay(WATCHDOG_TICK_MS)
            refreshSensor()
            val d = _detail.value as? SensorStatus.Direct ?: return // ended, lost, no permission: nothing to restart
            if (d.link == LinkStatus.WaitingForBluetooth) continue
            if (d.link is LinkStatus.Failed) return
            val now = clock.now()
            val i = info
            if (i != null && SensorLife.warming(i, latestReading?.epochSecond, now.epochSecond)) continue
            val quietSince = maxOf(latestReading ?: startedAt, startedAt)
            if (now.toEpochMilli() - quietSince.toEpochMilli() >= WATCHDOG_MS) {
                log("watchdog: no reading for ${WATCHDOG_MS / 60_000} min, restarting the link")
                return retry()
            }
        }
    }

    private inner class DirectEvents(private val pairing: Pairing) : SensorEvents {
        private val key = SensorStatus.Direct.keyOf(pairing)
        private var label = pairing.label

        /** The device this run connects to (found by the scan or saved). */
        var address = pairing.address

        /** A scan found the sensor: the screens call it by its advertised name from now on (a family without an id). */
        fun found(name: String) {
            if (pairing.serial.isEmpty()) label = name
            info = info?.copy(serial = label)
            (_detail.value as? SensorStatus.Direct)?.let { publish(it.copy(label = label)) }
        }

        override fun onStatus(status: LinkStatus) {
            when (status) {
                LinkStatus.PermissionMissing -> publish(SensorStatus.BluetoothPermissionMissing)
                LinkStatus.SensorEnded -> publish(SensorStatus.Ended)
                else -> publish(SensorStatus.Direct(pairing.profile, label, key, status))
            }
            if (status is LinkStatus.Reconnecting || status == LinkStatus.WaitingForSensor || status is LinkStatus.NoValue) interrupted()
            // kept until a new pairing (which scans again: the scan may have picked the wrong device)
            if (status is LinkStatus.Failed && pairing.profile.needsScan) scope.launch { pairings.markFailed(pairing, status.why) }
        }

        override fun onWrongDevice() {
            val wrong = address
            scope.launch {
                if (wrong.isNotEmpty()) pairings.skipDevice(pairing, wrong)
                retry() // scan again, without it
            }
        }

        override fun onReading(reading: Reading) {
            val last = lastLive?.takeIf { it.first == key }?.second
            if (last != null && !reading.t.isAfter(last)) return deliver(listOf(reading), live = false)
            lastLive = key to reading.t
            deliver(listOf(reading), live = true)
        }

        override fun onHistory(readings: List<Reading>) = deliver(readings, live = false)

        override fun onSensorGlucose(current: SensorGlucose?, history: List<SensorGlucose>) {
            current?.let { lastLifeCount = pairing.serial to it.lifeCount }
            // LibreView takes only a Libre 3's readings (its record numbers come from the Libre serial)
            if (pairing.profile == SensorProfile.Libre3) _sensorGlucose.tryEmit(SensorGlucoseBatch(pairing.serial, current, history))
        }

        override fun onSensorEnded(serial: String) {
            val now = clock.now().epochSecond
            info = info?.let { if (it.endEpoch > now) it.copy(endEpoch = now) else it }
            refreshSensor()
        }

        override fun onEndedForGood(serial: String) {
            scope.launch { pairings.markEnded(pairing) }
        }

        override fun onSessionStart(startEpoch: Long) {
            info = SensorLife.fromStart(label, startEpoch, pairing.wearMin, pairing.warmupMin)
            refreshSensor()
            scope.launch { pairings.setStart(pairing, startEpoch) }
        }

        override fun onPairingState(state: ByteArray) {
            scope.launch { pairings.setAuthorization(pairing, state) }
        }

        override fun onOwnershipLost(serial: String) = publish(SensorStatus.OwnedByAnotherApp)
    }

    // MARK: Juggluco

    private suspend fun runJuggluco() {
        publish(SensorStatus.Juggluco(JugglucoStatus.FetchingHistory))
        _status.value = SourceStatus.Connecting
        val source = juggluco()
        val sink =
            object : JugglucoSink {
                override fun onReadings(readings: List<Reading>, live: Boolean) {
                    scope.launch { deliver(readings, live) }
                }

                override fun onStatus(status: JugglucoStatus) {
                    scope.launch {
                        publish(SensorStatus.Juggluco(status))
                        if (status is JugglucoStatus.Unreachable) interrupted()
                    }
                }

                override fun onSensor(info: SensorInfo) {
                    scope.launch {
                        this@SourceManager.info = info
                        refreshSensor()
                    }
                }
            }
        // The blocking SSE read only ends when its call is cancelled: run it in its own job and cancel the call as soon
        // as this source is cancelled (a switch, a restart, stop), so the next source never waits for it.
        coroutineScope {
            val reader = launch(io) { source.run(sink) }
            try {
                reader.join()
            } finally {
                source.cancelCall()
                reader.cancel()
            }
        }
    }

    // MARK: state

    private fun resetState() {
        info = null
        latestReading = null
        _status.value = SourceStatus.NotStarted
    }

    /** [live] = the sensor's current value (marks the source live); otherwise history / backfill. */
    private fun deliver(readings: List<Reading>, live: Boolean) {
        if (readings.isEmpty()) return
        readingsOut.trySend(readings)
        val newest = readings.maxOf { it.t }
        if (latestReading == null || newest > latestReading) latestReading = newest
        if (live) _status.value = SourceStatus.Live(newest)
        refreshSensor()
    }

    private fun interrupted() {
        val s = _status.value
        if (s is SourceStatus.Live) _status.value = SourceStatus.Interrupted(clock.now())
    }

    private fun publish(detail: SensorStatus) {
        _detail.value = detail
        refreshSensor()
    }

    private fun refreshSensor() {
        _sensor.value =
            when (val d = _detail.value) {
                SensorStatus.NoSensor -> {
                    SensorState.None
                }

                SensorStatus.CoreMissing -> {
                    SensorState.CoreMissing
                }

                SensorStatus.Ended -> {
                    SensorState.Ended
                }

                is SensorStatus.Direct -> {
                    when (d.link) {
                        LinkStatus.WaitingForBluetooth -> SensorState.BluetoothOff
                        is LinkStatus.Failed -> SensorState.ActionNeeded(SensorAction.ScanSensor)
                        else -> lifeState()
                    }
                }

                is SensorStatus.Juggluco -> {
                    lifeState()
                }

                else -> {
                    SensorState.ActionNeeded(d.action ?: SensorAction.ScanSensor)
                }
            }
    }

    private fun lifeState(): SensorState =
        info?.let { SensorLife.state(it, latestReading?.epochSecond, clock.now().epochSecond) } ?: SensorState.Active(null)
}
