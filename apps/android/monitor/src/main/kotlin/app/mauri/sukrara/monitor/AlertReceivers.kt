// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.monitor

import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import app.mauri.sukrara.alerts.AlertLimits
import app.mauri.sukrara.core.model.Durations
import app.mauri.sukrara.monitor.alerts.AlertsModule
import app.mauri.sukrara.monitor.notify.NotificationGroups
import kotlinx.coroutines.withTimeoutOrNull

/** The Application implements this, so receivers and the provider reach the one [AlertsModule]. */
interface AlertsHost {
    val alerts: AlertsModule
}

internal val Context.alertsModule: AlertsModule?
    get() = (applicationContext as? AlertsHost)?.alerts

private const val TAG = "Sukrara.alerts"

/**
 * The alert notification's buttons (non-exported; immutable PendingIntents except the direct reply's): *Mute…* /
 * *Change time* (a chip like "1 h" or a typed length), *Remind less*, *Remind as usual*, *Unmute*, and the swipe's
 * tidy-up. They work from the lock screen without opening the app. A reply that isn't a length in range changes
 * nothing and shows the notification again, which also takes the reply's spinner away.
 */
class AlertActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val module = context.alertsModule ?: return
        val kind = intent.getStringExtra(KIND) ?: return
        runAsync("alert action") {
            val runtime = module.runtime
            when (intent.action) {
                MUTE -> {
                    val typed = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(TYPED)?.toString()
                    val minutes = typed?.let(Durations::parse)?.takeIf { it > 0 && AlertLimits.ACTION_MUTE.valid(it.toDouble()) }
                    if (minutes != null) runtime.mute(kind, minutes) else runtime.reshow(kind)
                }

                REMIND_LESS -> {
                    runtime.remindLess(kind)
                }

                REMIND_AS_USUAL -> {
                    runtime.remindAsUsual(kind)
                }

                UNMUTE -> {
                    runtime.unmute(kind)
                }

                TIDY -> {
                    NotificationGroups.tidy(context, NotificationGroups.Group.ALERTS)
                }
            }
        }
    }

    companion object {
        const val MUTE = "app.mauri.sukrara.alert.MUTE"
        const val REMIND_LESS = "app.mauri.sukrara.alert.REMIND_LESS"
        const val REMIND_AS_USUAL = "app.mauri.sukrara.alert.REMIND_AS_USUAL"
        const val UNMUTE = "app.mauri.sukrara.alert.UNMUTE"
        const val TIDY = "app.mauri.sukrara.alert.TIDY"
        val ACTIONS = listOf(MUTE, REMIND_LESS, REMIND_AS_USUAL, UNMUTE, TIDY)
        const val KIND = "kind"
        const val TYPED = "minutes"
    }
}

/**
 * The alert checks' exact alarms (non-exported: only `AndroidAlarms.alertCheck` sets them). Evaluates from the saved
 * record **without** the monitoring service (no data still alerts on time when Android killed it), re-arms the next
 * alarm, checks the sensor end, then asks the starter to bring the service back (it shows "Monitoring stopped —
 * tap to resume" when Android refuses). Boot, app update and clock changes reach the same check through
 * [BootReceiver], which starts the service once.
 */
class AlertCheckReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_CHECK) return
        val module = context.alertsModule ?: return
        runAsync(ACTION_CHECK) {
            withTimeoutOrNull(ALERT_CHECK_BUDGET_MS) { module.check() } ?: Log.w(TAG, "alert check timed out")
            module.restarter.restart()
        }
    }

    companion object {
        const val ACTION_CHECK = "app.mauri.sukrara.alert.CHECK"
    }
}
