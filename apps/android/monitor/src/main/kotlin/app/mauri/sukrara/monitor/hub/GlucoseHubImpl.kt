// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.monitor.hub

import app.mauri.sukrara.core.model.AlertUiState
import app.mauri.sukrara.core.model.Clock
import app.mauri.sukrara.core.model.DispatcherProvider
import app.mauri.sukrara.core.model.GlucoseHub
import app.mauri.sukrara.core.model.GlucoseState
import app.mauri.sukrara.core.model.LinkState
import app.mauri.sukrara.core.model.Reading
import app.mauri.sukrara.core.model.ReadingSource
import app.mauri.sukrara.core.model.Role
import app.mauri.sukrara.core.model.SensorState
import app.mauri.sukrara.core.model.ServiceState
import app.mauri.sukrara.core.model.SourceId
import app.mauri.sukrara.core.model.SourceStatus
import app.mauri.sukrara.core.model.storage.ReadingsRepository
import app.mauri.sukrara.core.model.storage.StateStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.time.Duration
import java.time.Instant

/** What the hub keeps across process restarts (a typed state file). */
@Serializable
data class MonitorRecord(
    /** [GlucoseState.monitoringStartedAt] in epoch ms; null = not monitoring yet. */
    val startedAtEpochMs: Long? = null,
)

/**
 * The one glucose state source ([GlucoseHub]). Its inputs:
 *  - the phone's role (setting),
 *  - the stored readings of the last [WINDOW] (`ReadingsRepository`, which every accepted reading goes through first),
 *  - [MonitorRecord] (when monitoring started),
 *  - exactly one attached [ReadingSource] at a time ([attach], run by the monitoring service),
 *  - the setters of the alert runtime / follower engine and the service's own state.
 *
 * Bookkeeping runs on one serial dispatcher; [state] is updated atomically.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GlucoseHubImpl(
    private val scope: CoroutineScope,
    dispatchers: DispatcherProvider,
    private val clock: Clock,
    private val readings: ReadingsRepository,
    private val record: StateStore<MonitorRecord>,
    private val role: kotlinx.coroutines.flow.Flow<Role?>,
) : GlucoseHub {
    private val serial = dispatchers.default.limitedParallelism(1)
    private val mutableState = MutableStateFlow(GlucoseState.Initial)
    override val state: StateFlow<GlucoseState> = mutableState.asStateFlow()

    private val sourceSensor = MutableStateFlow<SensorState>(SensorState.None)
    private val bluetoothOn = MutableStateFlow(true)

    // serial dispatcher only
    private var attachedJob: Job? = null

    @Volatile private var attachedSource: ReadingSource? = null

    private val roleLoaded = CompletableDeferred<Unit>()
    private val recordLoaded = CompletableDeferred<Unit>()

    /** Completes once the role and the record have been read (after [start]). */
    suspend fun awaitInputs() {
        roleLoaded.await()
        recordLoaded.await()
    }

    /** Follows the role, the stored readings and the record. Call once, after the app's storage is ready. */
    fun start(): Job =
        scope.launch(serial) {
            launch {
                role.collect { r ->
                    mutableState.update { it.copy(role = r) }
                    roleLoaded.complete(Unit)
                }
            }
            launch {
                record.data.collect { rec ->
                    mutableState.update { it.copy(monitoringStartedAt = rec.startedAtEpochMs?.let(Instant::ofEpochMilli)) }
                    recordLoaded.complete(Unit)
                }
            }
            launch { windowStarts().flatMapLatest { from -> readings.window(from) }.collect(::applyWindow) }
            launch {
                val wearer = state.map { it.role == Role.Wearer }.distinctUntilChanged()
                combine(sourceSensor, bluetoothOn, wearer) { sensor, on, isWearer ->
                    if (isWearer && !on) SensorState.BluetoothOff else sensor
                }.collect { shown -> mutableState.update { it.copy(sensor = shown) } }
            }
        }

    /**
     * Makes [source] the hub's one input until the caller is cancelled. Attaching another source first stops this one
     * (its collection is cancelled, so it stops), so two sources never feed the hub together.
     */
    suspend fun attach(source: ReadingSource) {
        withContext(serial) {
            attachedJob?.cancelAndJoin()
            val self = coroutineContext[Job]
            attachedJob = self
            attachedSource = source
            try {
                coroutineScope {
                    launch { source.sensor.collect(::onSourceSensor) }
                    launch { source.status.collect { s -> mutableState.update { it.copy(source = s) } } }
                    launch { source.readings.collect(::accept) }
                }
            } finally {
                if (attachedJob === self) {
                    attachedJob = null
                    attachedSource = null
                    mutableState.update { it.copy(source = SourceStatus.NotStarted) }
                }
            }
        }
    }

    /** Readings from outside the attached source's flow are accepted only while a source is attached. */
    override suspend fun ingest(
        readings: List<Reading>,
        from: SourceId,
    ) {
        if (attachedSource != null) accept(readings)
    }

    override fun setSource(status: SourceStatus) = mutableState.update { it.copy(source = status) }

    override fun setSensor(sensor: SensorState) = onSourceSensor(sensor)

    override fun setAlerts(alerts: AlertUiState) = mutableState.update { it.copy(alerts = alerts) }

    override fun setLink(link: LinkState?) = mutableState.update { it.copy(link = link) }

    /**
     * Back to "Not monitoring" after the person removed the sensor or changed the role (an explicit action only: a
     * source glitch never disarms the no-data alarm).
     */
    suspend fun resetMonitoring() = record.update { MonitorRecord() }

    fun setService(service: ServiceState) = mutableState.update { it.copy(service = service) }

    /** Bluetooth power changed (the service's receiver): the wearer sees "Bluetooth off"; the source reconnects. */
    fun onBluetoothChanged(on: Boolean) {
        if (bluetoothOn.value == on) return
        bluetoothOn.value = on
        attachedSource?.let { if (on) it.onBluetoothOn() else it.onBluetoothOff() }
    }

    private suspend fun accept(batch: List<Reading>) {
        val now = clock.now()
        // a reading stamped ahead of the phone's clock would look fresh for too long (and the same reading delivered
        // again can't be stored under a substitute time): dropped; the missing data then shows and alarms as no data
        val valid = batch.filter { it.mgdl in PLAUSIBLE_MGDL && !it.t.isAfter(now + FUTURE_TOLERANCE) }
        if (valid.isEmpty()) return
        readings.upsert(valid)
        val startedAt = now.toEpochMilli()
        record.update { if (it.startedAtEpochMs == null) it.copy(startedAtEpochMs = startedAt) else it }
    }

    private fun onSourceSensor(sensor: SensorState) {
        sourceSensor.value = sensor
    }

    private fun applyWindow(stored: List<Reading>) {
        val from = clock.now() - WINDOW
        val recent = stored.filter { !it.t.isBefore(from) }
        mutableState.update { it.copy(latest = recent.lastOrNull(), recent = recent) }
    }

    /** The window's start, moved forward every [WINDOW_REFRESH] so the subscription doesn't grow for days. */
    private fun windowStarts() =
        flow {
            while (true) {
                emit(clock.now() - WINDOW - WINDOW_REFRESH)
                delay(WINDOW_REFRESH.toMillis())
            }
        }

    companion object {
        /** The recent window every surface can draw (the graph's longest setting is 24 h). */
        val WINDOW: Duration = Duration.ofHours(24)
        private val WINDOW_REFRESH: Duration = Duration.ofHours(1)

        /**
         * Only malformed values are dropped. LO / HI readings stay (shown as LO / HI, alerted on): the most dangerous
         * lows must never disappear.
         */
        val PLAUSIBLE_MGDL = 1..1000

        /** A reading stamped further ahead of the phone's clock than this is dropped. */
        val FUTURE_TOLERANCE: Duration = Duration.ofMinutes(5)
    }
}
