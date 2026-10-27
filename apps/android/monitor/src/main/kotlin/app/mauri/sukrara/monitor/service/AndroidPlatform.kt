// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.monitor.service

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.ServiceStartNotAllowedException
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import app.mauri.sukrara.monitor.HealthCheckReceiver
import app.mauri.sukrara.monitor.MonitorReceiver
import app.mauri.sukrara.monitor.MonitoringService
import app.mauri.sukrara.monitor.R
import app.mauri.sukrara.monitor.notify.Channels
import app.mauri.sukrara.monitor.notify.NotificationIds
import java.time.Duration
import java.time.Instant

private const val TAG = "Sukrara.monitor"

/** Starts [MonitoringService] in the foreground; false when Android refuses (background start, missing permission). */
class AndroidServiceLauncher(
    private val context: Context,
) : ServiceLauncher {
    override fun launch(): Boolean =
        try {
            context.startForegroundService(Intent(context, MonitoringService::class.java))
            true
        } catch (e: ServiceStartNotAllowedException) {
            Log.w(TAG, "start refused: ${e.javaClass.simpleName}")
            false
        } catch (e: SecurityException) {
            Log.w(TAG, "start refused: ${e.javaClass.simpleName}")
            false
        }
}

/** "Monitoring stopped — tap to resume": the tap starts the service (a notification tap may start one). */
class AndroidStoppedNotice(
    private val context: Context,
) : StoppedNotice {
    private val nm = context.getSystemService(NotificationManager::class.java)

    override fun show() {
        val resume =
            PendingIntent.getForegroundService(
                context,
                0,
                Intent(context, MonitoringService::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val notification =
            Notification
                .Builder(context, Channels.STATUS)
                .setSmallIcon(R.drawable.monitor_ic_no_readings)
                .setContentTitle(context.getString(R.string.monitor_stopped_title))
                .setContentText(context.getString(R.string.monitor_stopped_text))
                .setStyle(Notification.BigTextStyle().bigText(context.getString(R.string.monitor_stopped_text)))
                .setCategory(Notification.CATEGORY_ERROR)
                .setContentIntent(resume)
                .addAction(Notification.Action.Builder(null, context.getString(R.string.monitor_action_resume), resume).build())
                .setAutoCancel(true)
                .build()
        runCatching { nm.notify(NotificationIds.MONITORING_STOPPED, notification) }
            .onFailure { Log.w(TAG, "stopped notice: ${it.javaClass.simpleName}") }
    }

    override fun cancel() = nm.cancel(NotificationIds.MONITORING_STOPPED)
}

/** The monitor's alarms on AlarmManager (`USE_EXACT_ALARM`; inexact fallback if exact alarms are ever refused). */
class AndroidAlarms(
    private val context: Context,
) : Alarms {
    private val am = context.getSystemService(AlarmManager::class.java)

    override fun scheduleHealthCheck(after: Duration) {
        val at = SystemClock.elapsedRealtime() + after.toMillis()
        val pi = broadcast(MonitorReceiver.ACTION_HEALTH_CHECK)
        runCatching {
            if (am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
            }
        }.onFailure { Log.w(TAG, "health check not scheduled: ${it.javaClass.simpleName}") }
    }

    override fun scheduleFreshnessStep(at: Instant?) {
        val pi = broadcast(MonitorReceiver.ACTION_FRESHNESS_STEP)
        if (at == null) {
            am.cancel(pi)
            return
        }
        // RTC: with the screen off nobody sees the shade; the alarm is delivered when the phone wakes
        runCatching {
            if (am.canScheduleExactAlarms()) {
                am.setExact(AlarmManager.RTC, at.toEpochMilli(), pi)
            } else {
                am.setWindow(AlarmManager.RTC, at.toEpochMilli(), Duration.ofMinutes(1).toMillis(), pi)
            }
        }.onFailure { Log.w(TAG, "freshness step not scheduled: ${it.javaClass.simpleName}") }
    }

    private fun broadcast(action: String): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            action.hashCode(),
            Intent(context, HealthCheckReceiver::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}

/** Bluetooth adapter power from `BluetoothAdapter.ACTION_STATE_CHANGED`'s state; null = in between (ignored). */
fun bluetoothPower(state: Int): Boolean? =
    when (state) {
        BluetoothAdapter.STATE_ON -> true
        BluetoothAdapter.STATE_OFF, BluetoothAdapter.STATE_TURNING_OFF -> false
        else -> null
    }
