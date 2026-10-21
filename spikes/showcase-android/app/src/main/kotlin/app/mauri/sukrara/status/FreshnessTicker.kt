// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.status

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import app.mauri.sukrara.core.Proto
import app.mauri.sukrara.graph.Freshness
import app.mauri.sukrara.widgets.RecentHistory
import app.mauri.sukrara.widgets.SukraraHooks

/**
 * Redraws every glucose surface at the latest reading's next freshness step ([Freshness.nextChangeAt]: 90 s, 5 min,
 * 20 min, 3 h) when no new reading comes (the user, 2026-10-21: the sensor stopped at night and the status bar kept
 * the old value as if it were current, the widget stayed struck through). Before, only a once-a-minute coroutine
 * re-posted, at most 60 s late and not at all while the CPU slept (`delay` runs on the monotonic clock).
 *
 * One AlarmManager alarm at a time, re-planned on every reading ([SukraraHooks.onReadings]) and at every step. It
 * doesn't wake the phone (`RTC`, exact): with the screen off nobody sees these surfaces and the screen-on render
 * catches up; with the screen on it fires on time. At the step: the role's notification + status bar ([onStep]:
 * WearerExtras / FollowerExtras `render`) and the widgets ([SukraraHooks.refresh], deduped by freshness step).
 */
object FreshnessTicker {
    private const val TAG = "Sukrara.freshness"

    /** The role's redraw of its notification and status bar (set while its service runs). */
    @Volatile var onStep: (() -> Unit)? = null

    /** The alarm armed now (epoch s), null = none. For tests and the debug state. */
    @Volatile var planned: Long? = null; private set

    /** When the surfaces must redraw next for a latest reading at [t] (epoch s), seen at [nowS]; null = never. */
    fun next(t: Long?, nowS: Long): Long? = Freshness.nextChangeAt(t, nowS)

    /** Arm (or move, or cancel) the alarm for the newest reading [t]. */
    fun plan(c: Context, t: Long?, nowS: Long = Proto.nowS()) {
        val at = next(t, nowS)
        if (at == planned) return
        val am = c.getSystemService(AlarmManager::class.java) ?: return
        val pi = intent(c)
        planned = at
        if (at == null) { am.cancel(pi); return }
        runCatching {
            // USE_EXACT_ALARM (alerts): exact; without it a 1-min window
            if (am.canScheduleExactAlarms()) am.setExact(AlarmManager.RTC, at * 1000, pi)
            else am.setWindow(AlarmManager.RTC, at * 1000, 60_000, pi)
        }.onFailure { Log.w(TAG, "plan: $it"); planned = null }
    }

    /** Re-plan from the role's store (process start, a follower reload). */
    fun replan(c: Context) = plan(c, runCatching { RecentHistory.readings(c).lastOrNull()?.t }.getOrNull())

    private fun intent(c: Context): PendingIntent = PendingIntent.getBroadcast(c, 0,
        Intent(c, Receiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /** The step came: redraw everything, then arm the next one. */
    fun step(c: Context) {
        planned = null
        runCatching { onStep?.invoke() }.onFailure { Log.w(TAG, "render: $it") }
        SukraraHooks.refresh(c, "freshness step")
        replan(c)
    }

    class Receiver : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = step(c.applicationContext)
    }
}
