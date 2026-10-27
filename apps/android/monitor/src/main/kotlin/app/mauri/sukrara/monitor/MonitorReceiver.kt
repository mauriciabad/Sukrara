// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.monitor

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import app.mauri.sukrara.monitor.service.StartReason
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The monitor's broadcasts (not exported; system broadcasts still arrive):
 *  - boot, app update, time / time-zone change → evaluate alerts and the sensor end from the saved state, reschedule
 *    the health check, start the service once (each is an allowed background start);
 *  - exact-alarm permission changed → reschedule;
 *  - the health check alarm → start the service if it should run, schedule the next check;
 *  - the freshness step → redraw the glucose surfaces;
 *  - the glucose notification was swiped away → post it again.
 */
abstract class MonitorReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val action = intent.action ?: return
        runAsync(action) { handle(context, action) }
    }

    /**
     * Boot, update and clock changes check alerts alongside the rest (the alert runtime is one object under a mutex, so
     * the service starting meanwhile can't post twice): a slow check never costs the health check or the start.
     */
    private suspend fun handle(
        context: Context,
        action: String,
    ) = coroutineScope {
        if (action in SYSTEM_EVENTS) launch { checkAlerts(context) }
        handleMonitor(context, action)
    }

    private suspend fun handleMonitor(
        context: Context,
        action: String,
    ) {
        val host = context.monitorHost
        host.awaitReady()
        val monitor = host.monitor
        when (action) {
            Intent.ACTION_BOOT_COMPLETED -> {
                restart(monitor, StartReason.Boot)
            }

            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                restart(monitor, StartReason.PackageReplaced)
            }

            Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED -> {
                restart(monitor, StartReason.TimeChanged)
            }

            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED -> {
                monitor.healthCheck.schedule()
            }

            ACTION_HEALTH_CHECK -> {
                monitor.healthCheck.onFire()
            }

            ACTION_FRESHNESS_STEP -> {
                monitor.onFreshnessStep()
            }

            ACTION_GLUCOSE_DISMISSED -> {
                val now = monitor.clock.now()
                if (MonitoringService.isRunning) {
                    monitor.notification.post(
                        monitor.notification.content(monitor.hubImpl.state.value, now),
                        now,
                        force = true,
                    )
                }
            }
        }
    }

    /** Alerts first (they don't wait for the graph): no data, a mute ending or a sensor end while the phone was off. */
    private suspend fun checkAlerts(context: Context) {
        val alerts = context.alertsModule ?: return
        withTimeoutOrNull(ALERT_CHECK_BUDGET_MS) { alerts.check() } ?: Log.w(TAG, "alert check timed out")
    }

    private suspend fun restart(
        monitor: MonitorModule,
        reason: StartReason,
    ) {
        monitor.healthCheck.schedule()
        monitor.starter.start(reason)
    }

    companion object {
        private const val TAG = "Sukrara.monitor"

        /** Boot, app update, clock and time-zone changes: the alert check's other triggers besides its own alarm. */
        private val SYSTEM_EVENTS =
            setOf(
                Intent.ACTION_BOOT_COMPLETED,
                Intent.ACTION_MY_PACKAGE_REPLACED,
                Intent.ACTION_TIME_CHANGED,
                Intent.ACTION_TIMEZONE_CHANGED,
            )

        const val ACTION_HEALTH_CHECK = "app.mauri.sukrara.monitor.HEALTH_CHECK"
        const val ACTION_FRESHNESS_STEP = "app.mauri.sukrara.monitor.FRESHNESS_STEP"
        const val ACTION_GLUCOSE_DISMISSED = "app.mauri.sukrara.monitor.GLUCOSE_DISMISSED"
    }
}

/** The system's broadcasts: boot, app update, time / time-zone change, exact-alarm permission change. */
class BootReceiver : MonitorReceiver()

/** The monitor's own alarms and intents: the health check, the freshness step, the glucose notification swiped away. */
class HealthCheckReceiver : MonitorReceiver()
