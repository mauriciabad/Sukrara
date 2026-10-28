// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.monitor.alerts

import app.mauri.sukrara.alerts.AlertEffect
import app.mauri.sukrara.alerts.AlertGroup
import app.mauri.sukrara.alerts.AlertSettings

/**
 * The order the runtime applies one batch of [effects] in: the alert that must be heard is posted first.
 *
 * Android lets an app make one sound per second (NotificationUsageStats' AlertRateLimiter: a second sounding
 * notification within 1 s is muted, `MUTE_REASON_RATE_LIMIT`, whatever its category) and a newer sound would cut the
 * first off anyway, so when one reading fires several alerts (Low and Falling fast) only the first posted rings; the
 * others show without sound.
 *
 * Only ringing [AlertEffect.Fire]s of different groups that follow each other move, among themselves: Back / Mirror /
 * Schedule between them stay where they are; a silent re-post, a Clear or a second Fire of a group already in the run
 * ends it, so a notification is never replaced or cleared out of the engine's order (after a restart the engine re-posts
 * what was showing, then clears or replaces it). Within a run: an alarm-level kind first, then the most severe, then
 * level → rate → no data.
 */
internal fun ringOrder(effects: List<AlertEffect>, settings: AlertSettings): List<AlertEffect> {
    val out = effects.toMutableList()
    val first =
        compareBy<AlertEffect.Fire>(
            { !settings.rule(it.firing.kind).alarm },
            { -it.firing.kind.severity },
            { it.firing.kind.group.ordinal },
        )
    var i = 0
    while (i < out.size) {
        // the positions of one run of ringing Fires in different groups
        val at = mutableListOf<Int>()
        val groups = mutableSetOf<AlertGroup>()
        var j = i
        while (j < out.size) {
            val e = out[j]
            when {
                e is AlertEffect.Fire && !e.silent && groups.add(e.firing.kind.group) -> at += j
                e is AlertEffect.Back || e is AlertEffect.Mirror || e is AlertEffect.Schedule -> Unit
                else -> break
            }
            j++
        }
        if (at.size > 1) {
            val sorted = at.map { out[it] as AlertEffect.Fire }.sortedWith(first)
            at.forEachIndexed { k, pos -> out[pos] = sorted[k] }
        }
        i = if (at.isEmpty()) j + 1 else at.last() + 1
    }
    return out
}
