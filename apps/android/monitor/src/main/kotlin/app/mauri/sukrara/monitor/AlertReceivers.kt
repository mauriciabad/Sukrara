// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.monitor

import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.mauri.sukrara.alerts.AlertLimits
import app.mauri.sukrara.core.model.Durations
import app.mauri.sukrara.monitor.alerts.AlertsModule
import app.mauri.sukrara.monitor.notify.NotificationGroups
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** The Application implements this (chunk 02 wires it), so receivers and the provider reach the one [AlertsModule]. */
interface AlertsHost {
    val alerts: AlertsModule
}

internal val Context.alertsModule: AlertsModule?
    get() = (applicationContext as? AlertsHost)?.alerts

/** Runs [block] off the main thread within the broadcast's time ([goAsync], 10 s budget), then finishes it. */
private fun BroadcastReceiver.runAsync(block: suspend () -> Unit) {
    val pending = goAsync()
    receiverScope.launch {
        try {
            withTimeoutOrNull(BUDGET_MS) { block() }
        } finally {
            pending.finish()
        }
    }
}

private val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
private const val BUDGET_MS = 9_000L

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
        runAsync {
            val runtime = module.runtime
            when (intent.action) {
                MUTE -> {
                    val typed = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(TYPED)?.toString()
                    val minutes = typed?.let(Durations::parse)?.takeIf { it > 0 && AlertLimits.ACTION_MUTE.valid(it.toDouble()) }
                    if (minutes != null) runtime.mute(kind, minutes) else runtime.reshow(kind)
                }
                REMIND_LESS -> runtime.remindLess(kind)
                REMIND_AS_USUAL -> runtime.remindAsUsual(kind)
                UNMUTE -> runtime.unmute(kind)
                TIDY -> NotificationGroups.tidy(context, NotificationGroups.Group.ALERTS)
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
 * The alert runtime's exact alarm, and boot / app update / clock or time zone change (non-exported: only the system
 * and [AlertScheduler] send these). Evaluates from the saved record **without** the monitoring service (no data still
 * alerts on time when Android killed it), re-arms the next alarm, checks the sensor end, then asks chunk 10's starter
 * to bring the service back (it shows "Monitoring stopped — tap to resume" when Android refuses).
 */
class AlertCheckReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val module = context.alertsModule ?: return
        runAsync {
            try {
                module.runtime.tick()
                module.sensorEnd.check()
            } finally {
                module.restarter.restart()
            }
        }
    }

    companion object {
        const val ACTION_CHECK = "app.mauri.sukrara.alert.CHECK"
    }
}
