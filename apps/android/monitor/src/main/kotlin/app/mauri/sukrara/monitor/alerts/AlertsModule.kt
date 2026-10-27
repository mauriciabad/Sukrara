// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.monitor.alerts

import android.content.Context
import android.util.Log
import app.mauri.sukrara.alerts.AlertEvent
import app.mauri.sukrara.alerts.AlertSettings
import app.mauri.sukrara.core.model.AlertControls
import app.mauri.sukrara.core.model.Clock
import app.mauri.sukrara.core.model.GlucoseHub
import app.mauri.sukrara.core.model.Treatment
import app.mauri.sukrara.core.model.storage.SettingsRepository
import app.mauri.sukrara.core.model.storage.StateStore
import app.mauri.sukrara.monitor.sensorend.AndroidSensorEndPresenter
import app.mauri.sukrara.monitor.service.AndroidAlarms
import app.mauri.sukrara.monitor.sensorend.SensorEndMemory
import app.mauri.sukrara.monitor.sensorend.SensorEndNotifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/**
 * The alert runtime's wiring (A4: chunk 02's `AppGraph` composes it; the Application implements [AlertsHost]).
 * Inputs from other chunks come in as ports with safe defaults until they land: the alert settings store and the
 * runtime's typed stores (09), the hub (10), treatments (09/19), the wearer's mirrored settings and `alert` events
 * (25), the publisher to the followers (13) and the service starter (10).
 */
class AlertsModule(
    context: Context,
    private val hub: GlucoseHub,
    alertSettings: SettingsRepository,
    alertRecord: StateStore<AlertRecord>,
    sensorEndRecord: StateStore<SensorEndMemory>,
    treatments: Flow<List<Treatment>>,
    val restarter: MonitorRestarter,
    clock: Clock,
    events: AlertEventSink = AlertEventSink { },
    private val wearerSettings: Flow<AlertSettings?> = flowOf(null),
    private val remoteEvents: Flow<AlertEvent> = emptyFlow(),
    /** The diagnostic log (`DiagLog`): what failed and its exception class, never values. */
    private val log: (String) -> Unit = { Log.w(TAG, it) },
) {
    private val ctx = context.applicationContext
    private val alarms = AndroidAlarms(ctx)
    val store = AlertStateStore(alertSettings, alertRecord)
    val soundPicker = AlertSoundPicker()
    val notifier = AlertNotifier(ctx)

    /** THE alert runtime of this process; also every surface's [AlertControls]. */
    val runtime = AlertRuntime(
        store,
        treatments,
        notifier,
        alarms.alertCheck(AndroidAlarms.ALERT_CHECK, clock),
        events,
        hub::setAlerts,
        clock,
        log = log,
    )
    val controls: AlertControls get() = runtime

    val sensorEnd = SensorEndNotifier(
        sensorEndRecord,
        AndroidSensorEndPresenter(ctx),
        alarms.alertCheck(AndroidAlarms.SENSOR_END_CHECK, clock),
        clock,
    )

    /**
     * Evaluates alerts and the sensor end now, from the saved state (the alert alarm, boot, app update, clock change):
     * also with the monitoring service dead. Each part runs on its own, so a broken sensor-end store never costs the
     * no-data alarm; a failure is logged.
     */
    suspend fun check() {
        step("alert check") { runtime.tick() }
        step("sensor end check") { sensorEnd.check() }
    }

    private suspend fun step(name: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("$name failed: ${e.javaClass.simpleName}")
        }
    }

    /** "Start fresh" / leaving a role (chunk 24): alerts and their saved state go, the sensor-end notices too. */
    suspend fun reset() {
        runtime.reset()
        sensorEnd.onSensor(app.mauri.sukrara.core.model.SensorState.None)
    }

    /** Called by the monitoring service once it runs (cancelled with its [scope]). */
    fun attach(scope: CoroutineScope) {
        AlertChannels.ensure(ctx)
        runtime.observe(scope, hub.state, wearerSettings, remoteEvents)
        scope.launch { hub.state.map { it.sensor }.distinctUntilChanged().collect { sensorEnd.onSensor(it) } }
    }
}

private const val TAG = "Sukrara.alerts"
