// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.monitor.alerts

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import app.mauri.sukrara.core.model.Clock
import app.mauri.sukrara.monitor.AlertCheckReceiver

/**
 * One exact alarm that wakes [AlertCheckReceiver] at a time the alert runtime asks for (no-data deadline, a mute or
 * Remind less ending, a repeat). `setExactAndAllowWhileIdle` on the elapsed clock (a wall-clock change doesn't move
 * it; TIME_SET re-evaluates anyway) and allowed in Doze; `USE_EXACT_ALARM` is granted at install for this app (minSdk
 * 36), so there is no inexact fallback. Each call replaces the previous alarm; null cancels it. No WorkManager.
 */
class AlertScheduler(private val ctx: Context, private val clock: Clock, private val requestCode: Int = 1, private val action: String = AlertCheckReceiver.ACTION_CHECK) : AlertAlarm {
    private val am get() = ctx.getSystemService(AlarmManager::class.java)

    private fun intent(): PendingIntent = PendingIntent.getBroadcast(
        ctx,
        requestCode,
        Intent(ctx, AlertCheckReceiver::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    override fun set(at: Long?) {
        if (at == null) {
            am.cancel(intent())
            return
        }
        val inMs = ((at - clock.now().epochSecond) * 1000).coerceAtLeast(0)
        am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + inMs, intent())
    }
}
