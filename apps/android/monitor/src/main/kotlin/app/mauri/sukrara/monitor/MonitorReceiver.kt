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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The monitor's broadcasts (not exported; system broadcasts still arrive):
 *  - boot, app update, time / time-zone change → start the service (each is an allowed background start) and
 *    reschedule the health check;
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
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                withTimeoutOrNull(TIMEOUT_MS) { handle(context, action) } ?: Log.w(TAG, "$action timed out")
            } catch (e: Exception) {
                Log.w(TAG, "$action failed: ${e.javaClass.simpleName}")
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun handle(
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

    private suspend fun restart(
        monitor: MonitorModule,
        reason: StartReason,
    ) {
        monitor.healthCheck.schedule()
        monitor.starter.start(reason)
    }

    companion object {
        private const val TAG = "Sukrara.monitor"

        /** Below the 10 s a receiver may run after goAsync. */
        private const val TIMEOUT_MS = 9_000L

        const val ACTION_HEALTH_CHECK = "app.mauri.sukrara.monitor.HEALTH_CHECK"
        const val ACTION_FRESHNESS_STEP = "app.mauri.sukrara.monitor.FRESHNESS_STEP"
        const val ACTION_GLUCOSE_DISMISSED = "app.mauri.sukrara.monitor.GLUCOSE_DISMISSED"
    }
}

/** The system's broadcasts: boot, app update, time / time-zone change, exact-alarm permission change. */
class BootReceiver : MonitorReceiver()

/** The monitor's own alarms and intents: the health check, the freshness step, the glucose notification swiped away. */
class HealthCheckReceiver : MonitorReceiver()
