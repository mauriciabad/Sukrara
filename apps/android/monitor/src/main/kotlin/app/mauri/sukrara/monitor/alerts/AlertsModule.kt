// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.monitor.alerts

import android.content.Context
import app.mauri.sukrara.alerts.AlertEvent
import app.mauri.sukrara.alerts.AlertSettings
import app.mauri.sukrara.core.model.AlertControls
import app.mauri.sukrara.core.model.AlertReadinessSource
import app.mauri.sukrara.core.model.Clock
import app.mauri.sukrara.core.model.DispatcherProvider
import app.mauri.sukrara.core.model.GlucoseHub
import app.mauri.sukrara.core.model.Treatment
import app.mauri.sukrara.core.model.storage.SettingsRepository
import app.mauri.sukrara.core.model.storage.StateStore
import app.mauri.sukrara.monitor.readiness.AndroidAlertReadiness
import app.mauri.sukrara.monitor.sensorend.AndroidSensorEndPresenter
import app.mauri.sukrara.monitor.sensorend.SensorEndMemory
import app.mauri.sukrara.monitor.sensorend.SensorEndNotifier
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
    /** The process scope: the readiness checklist shares its state there while a screen collects it. */
    scope: CoroutineScope,
    dispatchers: DispatcherProvider,
    /** Whether the role's source talks to the sensor over Bluetooth (the checklist's Bluetooth item). */
    needsBluetooth: Flow<Boolean>,
    events: AlertEventSink = AlertEventSink { },
    private val wearerSettings: Flow<AlertSettings?> = flowOf(null),
    private val remoteEvents: Flow<AlertEvent> = emptyFlow(),
) {
    private val ctx = context.applicationContext
    val store = AlertStateStore(alertSettings, alertRecord)
    val soundPicker = AlertSoundPicker()
    val notifier = AlertNotifier(ctx)

    /** THE alert runtime of this process; also every surface's [AlertControls]. */
    val runtime = AlertRuntime(store, treatments, notifier, AlertScheduler(ctx, clock), events, hub::setAlerts, clock)
    val controls: AlertControls get() = runtime

    /**
     * THE alert checklist (home's reliability row, the settings Reliability page, the setup permission step): one
     * instance so every surface agrees.
     */
    val readiness: AlertReadinessSource = AndroidAlertReadiness(ctx, scope, dispatchers, store.settings, needsBluetooth)

    val sensorEnd = SensorEndNotifier(
        sensorEndRecord,
        AndroidSensorEndPresenter(ctx),
        AlertScheduler(ctx, clock, requestCode = 2),
        clock,
    )

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
