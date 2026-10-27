// Sukrara — https://github.com/mauriciabad/Sukrara
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
import app.mauri.sukrara.sensor.ble.GattConnector
import app.mauri.sukrara.sensor.ble.GattLink
import app.mauri.sukrara.sensor.ble.LinkStatus
import app.mauri.sukrara.sensor.ble.SensorEvents
import app.mauri.sukrara.sensor.ble.SensorGlucose
import app.mauri.sukrara.sensor.juggluco.JugglucoSink
import app.mauri.sukrara.sensor.juggluco.JugglucoSource
import app.mauri.sukrara.sensor.juggluco.JugglucoStatus
import app.mauri.sukrara.sensor.libre3.Libre3Driver
import app.mauri.sukrara.sensor.libre3.Libre3Security
import app.mauri.sukrara.sensor.pairing.Pairing
import app.mauri.sukrara.sensor.pairing.PairingLoad
import app.mauri.sukrara.sensor.pairing.PairingStore
import kotlinx.coroutines.CoroutineDispatcher
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
import kotlinx.coroutines.withContext
import java.time.Instant

/**
 * The wearer's [ReadingSource]: the Libre 3 read directly, or Juggluco, per the setting ([kind]). Everything runs on
 * ONE serial [dispatcher] (`limitedParallelism(1)`): a source switch, a restart ([retry], a new pairing, Bluetooth on)
 * fully cancels and closes the old source before the new one starts (no PoC `restartSource()` race).
 *
 * Safety: readings come only from the sensor / Juggluco, never invented; every problem becomes a [SensorStatus] with
 * an action; a no-data watchdog restarts a Libre 3 link that has gone quiet without saying why.
 */
class SourceManager internal constructor(
    private val kind: Flow<SensorSourceKind>,
    private val pairings: PairingStore,
    /** Null when this build has no Libre 3 native core. */
    private val security: Libre3Security?,
    private val connector: GattConnector,
    private val juggluco: () -> JugglucoSource,
    private val showcaseInstalled: () -> Boolean,
    private val clock: Clock,
    private val dispatcher: CoroutineDispatcher,
    /** Blocking I/O (Juggluco's HTTP). */
    private val io: CoroutineDispatcher,
    private val log: (String) -> Unit = {},
) : ReadingSource {
    companion object {
        /** No reading for this long on a running (not warming, not ended) Libre 3 → restart the link. */
        const val WATCHDOG_MS = 10 * 60_000L
        private const val WATCHDOG_TICK_MS = 60_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val readingsOut = Channel<List<Reading>>(Channel.UNLIMITED)
    private val restarts = MutableStateFlow(0)
    private val _detail = MutableStateFlow<SensorStatus>(SensorStatus.NoSensor)
    private val _sensor = MutableStateFlow<SensorState>(SensorState.None)
    private val _status = MutableStateFlow<SourceStatus>(SourceStatus.NotStarted)
    private val _sensorGlucose = MutableSharedFlow<Pair<SensorGlucose?, List<SensorGlucose>>>(extraBufferCapacity = 64)
    private var info: SensorInfo? = null
    private var latestReading: Instant? = null
    private var link: GattLink? = null
    private var started: Job? = null

    override val readings: Flow<List<Reading>> = readingsOut.receiveAsFlow()
    override val sensor: StateFlow<SensorState> = _sensor.asStateFlow()
    override val status: StateFlow<SourceStatus> = _status.asStateFlow()

    /** The detailed state for the sensor screen / new-sensor flow (chunk 18). */
    val detail: StateFlow<SensorStatus> = _detail.asStateFlow()

    /** Libre 3 readings by life count, for the LibreView upload (chunk 26). */
    val sensorGlucose: Flow<Pair<SensorGlucose?, List<SensorGlucose>>> = _sensorGlucose.asSharedFlow()

    /** Starts following the setting; idempotent. */
    fun start() {
        if (started != null) return
        started = scope.launch {
            combine(kind.distinctUntilChanged(), restarts) { k, _ -> k }.collectLatest { k ->
                resetState()
                when (k) {
                    SensorSourceKind.Libre3 -> runLibre3()
                    SensorSourceKind.Juggluco -> runJuggluco()
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

    // MARK: Libre 3

    private suspend fun runLibre3() {
        val core = security ?: return publish(SensorStatus.CoreMissing)
        val pairing = when (val load = pairings.load()) {
            PairingLoad.None -> return publish(SensorStatus.NoSensor)
            is PairingLoad.Corrupt -> return publish(SensorStatus.PairingUnreadable(load.reason))
            is PairingLoad.Ok -> load.pairing
        }
        info = pairing.info
        if (pairing.securityVersion != 0) return publish(SensorStatus.Unsupported("security version ${pairing.securityVersion}"))
        if (pairing.ended) return publish(SensorStatus.Ended)
        publish(SensorStatus.Libre3(pairing.serial, LinkStatus.Connecting))
        _status.value = SourceStatus.Connecting
        coroutineScope {
            val events = Libre3Events(pairing)
            val driver = Libre3Driver(pairing, events, core, clockMs = { clock.now().toEpochMilli() }, log = log)
            val l = GattLink(connector, pairing.address, driver, events, this, log = log)
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

    /** Restarts the link when a running sensor has sent nothing for [WATCHDOG_MS] (a stuck stack, a silent link). */
    private suspend fun watchdog(startedAt: Instant) {
        while (true) {
            delay(WATCHDOG_TICK_MS)
            refreshSensor()
            val d = _detail.value as? SensorStatus.Libre3 ?: return // ended, lost, no permission: nothing to restart
            if (d.link == LinkStatus.WaitingForBluetooth) continue
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

    private inner class Libre3Events(private val pairing: Pairing) : SensorEvents {
        override fun onStatus(status: LinkStatus) {
            when (status) {
                LinkStatus.PermissionMissing -> publish(SensorStatus.BluetoothPermissionMissing)
                LinkStatus.SensorEnded -> publish(SensorStatus.Ended)
                else -> publish(SensorStatus.Libre3(pairing.serial, status))
            }
            if (status is LinkStatus.Reconnecting || status == LinkStatus.WaitingForSensor) interrupted()
        }

        override fun onReading(reading: Reading) = deliver(listOf(reading))

        override fun onHistory(readings: List<Reading>) = deliver(readings)

        override fun onSensorGlucose(current: SensorGlucose?, history: List<SensorGlucose>) {
            _sensorGlucose.tryEmit(current to history)
        }

        override fun onSensorEnded(serial: String) {
            val now = clock.now().epochSecond
            info = info?.let { if (it.endEpoch > now) it.copy(endEpoch = now) else it }
            refreshSensor()
        }

        override fun onEndedForGood(serial: String) {
            scope.launch { pairings.markEnded(serial) }
        }

        override fun onPairingState(state: ByteArray) {
            scope.launch { pairings.setAuthorization(pairing.serial, state) }
        }

        override fun onOwnershipLost(serial: String) = publish(SensorStatus.OwnedByAnotherApp(showcaseInstalled()))
    }

    // MARK: Juggluco

    private suspend fun runJuggluco() {
        publish(SensorStatus.Juggluco(JugglucoStatus.FetchingHistory))
        _status.value = SourceStatus.Connecting
        val source = juggluco()
        val sink = object : JugglucoSink {
            override fun onReadings(readings: List<Reading>) {
                scope.launch { deliver(readings) }
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
        try {
            withContext(io) { source.run(sink) }
        } finally {
            source.cancelCall()
        }
    }

    // MARK: state

    private fun resetState() {
        info = null
        latestReading = null
        _status.value = SourceStatus.NotStarted
    }

    private fun deliver(readings: List<Reading>) {
        if (readings.isEmpty()) return
        readingsOut.trySend(readings)
        val newest = readings.maxOf { it.t }
        if (latestReading == null || newest > latestReading) latestReading = newest
        if (readings.size == 1) _status.value = SourceStatus.Live(newest)
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
        _sensor.value = when (val d = _detail.value) {
            SensorStatus.NoSensor -> SensorState.None
            SensorStatus.CoreMissing -> SensorState.CoreMissing
            SensorStatus.Ended -> SensorState.Ended
            is SensorStatus.Libre3 ->
                if (d.link == LinkStatus.WaitingForBluetooth) SensorState.BluetoothOff else lifeState()
            is SensorStatus.Juggluco -> lifeState()
            else -> SensorState.ActionNeeded(d.action ?: SensorAction.ScanSensor)
        }
    }

    private fun lifeState(): SensorState =
        info?.let { SensorLife.state(it, latestReading?.epochSecond, clock.now().epochSecond) } ?: SensorState.Active(null)
}
