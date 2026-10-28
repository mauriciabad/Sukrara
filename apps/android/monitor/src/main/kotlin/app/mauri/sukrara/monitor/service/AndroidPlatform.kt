// Sukrara — https://github.com/mauriciabad/sukrara
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
import app.mauri.sukrara.core.model.Clock
import app.mauri.sukrara.monitor.AlertCheckReceiver
import app.mauri.sukrara.monitor.HealthCheckReceiver
import app.mauri.sukrara.monitor.MonitorReceiver
import app.mauri.sukrara.monitor.MonitoringService
import app.mauri.sukrara.monitor.R
import app.mauri.sukrara.monitor.alerts.AlertAlarm
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

/**
 * THE monitor's alarms on AlarmManager: the health check, the freshness step and the alert checks ([alertCheck]), all
 * through [AlarmPolicy]. The health check and the freshness step log a refusal (they are retried on the next fire,
 * boot or reading); an alert check throws it, so the alert runtime counts and shows it.
 */
class AndroidAlarms(
    private val context: Context,
) : Alarms {
    private val am = context.getSystemService(AlarmManager::class.java)

    override fun scheduleHealthCheck(after: Duration) {
        runCatching {
            set(Wakeup.HealthCheck, SystemClock.elapsedRealtime() + after.toMillis(), monitorIntent(MonitorReceiver.ACTION_HEALTH_CHECK))
        }.onFailure { Log.w(TAG, "health check not scheduled: ${it.javaClass.simpleName}") }
    }

    override fun scheduleFreshnessStep(at: Instant?) {
        val pi = monitorIntent(MonitorReceiver.ACTION_FRESHNESS_STEP)
        runCatching {
            if (at == null) am.cancel(pi) else set(Wakeup.FreshnessStep, at.toEpochMilli(), pi)
        }.onFailure { Log.w(TAG, "freshness step not scheduled: ${it.javaClass.simpleName}") }
    }

    /**
     * One exact wake-up of [AlertCheckReceiver] at an epoch second the caller asks for; each call replaces the previous
     * one of [requestCode], null cancels it. On the elapsed clock, so a wall-clock change doesn't move it (TIME_SET
     * re-evaluates anyway). [requestCode]: [ALERT_CHECK] (the alert runtime) or [SENSOR_END_CHECK].
     */
    fun alertCheck(
        requestCode: Int,
        clock: Clock,
    ): AlertAlarm =
        AlertAlarm { at ->
            val pi = alertIntent(requestCode)
            if (at == null) {
                am.cancel(pi)
            } else {
                val inMs = ((at - clock.now().epochSecond) * 1000).coerceAtLeast(0)
                set(Wakeup.AlertCheck, SystemClock.elapsedRealtime() + inMs, pi)
            }
        }

    private fun set(
        wakeup: Wakeup,
        at: Long,
        pi: PendingIntent,
    ) {
        val canExact = am.canScheduleExactAlarms()
        if (!canExact) Log.w(TAG, "exact alarms refused: ${wakeup.name} inexact")
        when (AlarmPolicy.call(wakeup, canExact)) {
            AlarmCall.ExactWhileIdle -> am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
            AlarmCall.InexactWhileIdle -> am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
            AlarmCall.ExactRtc -> am.setExact(AlarmManager.RTC, at, pi)
            AlarmCall.WindowRtc -> am.setWindow(AlarmManager.RTC, at, Duration.ofMinutes(1).toMillis(), pi)
        }
    }

    private fun monitorIntent(action: String): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            action.hashCode(),
            Intent(context, HealthCheckReceiver::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun alertIntent(requestCode: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent(context, AlertCheckReceiver::class.java).setAction(AlertCheckReceiver.ACTION_CHECK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    companion object {
        const val ALERT_CHECK = 1
        const val SENSOR_END_CHECK = 2
    }
}

/** Bluetooth adapter power from `BluetoothAdapter.ACTION_STATE_CHANGED`'s state; null = in between (ignored). */
fun bluetoothPower(state: Int): Boolean? =
    when (state) {
        BluetoothAdapter.STATE_ON -> true
        BluetoothAdapter.STATE_OFF, BluetoothAdapter.STATE_TURNING_OFF -> false
        else -> null
    }
