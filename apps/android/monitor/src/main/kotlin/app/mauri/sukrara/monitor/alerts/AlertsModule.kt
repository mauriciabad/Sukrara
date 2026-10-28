// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.monitor.alerts

import android.content.Context
import android.util.Log
import app.mauri.sukrara.alerts.AlertEvent
import app.mauri.sukrara.alerts.AlertSettings
import app.mauri.sukrara.core.data.settings.SurfaceSettings
import app.mauri.sukrara.core.model.AlertControls
import app.mauri.sukrara.core.model.AlertReadinessSource
import app.mauri.sukrara.core.model.Clock
import app.mauri.sukrara.core.model.DispatcherProvider
import app.mauri.sukrara.core.model.GlucoseHub
import app.mauri.sukrara.core.model.Role
import app.mauri.sukrara.core.model.SensorEndConfig
import app.mauri.sukrara.core.model.SensorState
import app.mauri.sukrara.core.model.SourceStatus
import app.mauri.sukrara.core.model.Treatment
import app.mauri.sukrara.core.model.storage.SettingsRepository
import app.mauri.sukrara.core.model.storage.StateStore
import app.mauri.sukrara.monitor.readiness.AndroidAlertReadiness
import app.mauri.sukrara.monitor.sensorend.AndroidSensorEndPresenter
import app.mauri.sukrara.monitor.sensorend.SensorEndMemory
import app.mauri.sukrara.monitor.sensorend.SensorEndNotifier
import app.mauri.sukrara.monitor.sensorend.SensorEndSettings
import app.mauri.sukrara.monitor.service.AndroidAlarms
import app.mauri.sukrara.settings.SettingsSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The alert runtime's wiring (the `AppGraph` composes it; the Application implements [AlertsHost]).
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
    /** The diagnostic log (`DiagLog`): what failed and its exception class, never values. */
    private val log: (String) -> Unit = { Log.w(TAG, it) },
) {
    private val ctx = context.applicationContext
    private val alarms = AndroidAlarms(ctx)
    val store = AlertStateStore(alertSettings, alertRecord)
    val soundPicker = AlertSoundPicker()
    val notifier = AlertNotifier(ctx)

    /** THE alert runtime of this process; also every surface's [AlertControls]. */
    val runtime =
        AlertRuntime(
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

    /**
     * THE alert checklist (home's reliability row, the settings Reliability page, the setup permission step): one
     * instance so every surface agrees.
     */
    val readiness: AlertReadinessSource = AndroidAlertReadiness(ctx, scope, dispatchers, store.settings, needsBluetooth)

    private val sensorEndSettings: Flow<SensorEndSettings> =
        combine(
            SurfaceSettings.sensorEnd(alertSettings),
            alertSettings.flow(SurfaceSettings.CountdownHours),
            alertSettings.flow(SurfaceSettings.StatusChip),
        ) { config, hours, chip -> SensorEndSettings(config, hours.toInt(), chip) }
            .distinctUntilChanged()
            // a settings store that fails must never take the service down: the defaults hold
            .catch {
                log("sensor-end settings failed: ${it.javaClass.simpleName}")
                emit(SensorEndSettings(SensorEndConfig(), SettingsSpec.COUNTDOWN_HOURS.default.toInt(), chip = true))
            }

    val sensorEnd =
        SensorEndNotifier(
            sensorEndRecord,
            AndroidSensorEndPresenter(ctx),
            alarms.sensorEnd(clock),
            clock,
        ) { sensorEndSettings.first() }

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

    /** "Start fresh" / leaving a role: alerts and their saved state go, the sensor-end notices too. */
    suspend fun reset() {
        runtime.reset()
        sensorEnd.update(wearer = false, SensorState.None, null, sensorEndSettings.first())
    }

    /** Called by the monitoring service once it runs (cancelled with its [scope]). */
    fun attach(scope: CoroutineScope) {
        AlertChannels.ensure(ctx)
        runtime.observe(scope, hub.state, wearerSettings, remoteEvents)
        scope.launch {
            // the sensor, its first reading and the settings re-plan; the newest reading only matters as "since the start"
            combine(
                hub.state
                    // a fresh process starts at "nothing known" (no role, no sensor): that is no reason to forget the plan
                    .filter { it.role != null && it.source != SourceStatus.NotStarted }
                    .map { Triple(it.role == Role.Wearer, it.sensor, it.latest?.t?.epochSecond) }
                    .distinctUntilChanged(),
                sensorEndSettings,
            ) { (wearer, sensor, latest), s -> SensorEndInputs(wearer, sensor, latest, s) }
                .collect { sensorEnd.update(it.wearer, it.sensor, it.latestS, it.settings) }
        }
    }
}

private data class SensorEndInputs(val wearer: Boolean, val sensor: SensorState, val latestS: Long?, val settings: SensorEndSettings)

private const val TAG = "Sukrara.alerts"
