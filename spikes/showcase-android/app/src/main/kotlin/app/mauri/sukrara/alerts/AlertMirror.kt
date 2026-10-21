// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.alerts

/**
 * A follower that mirrors the wearer's alerts (source "wearer", spikes/alerts/SPEC.md §2, §4), without Android: which of
 * the wearer's `alert` events show on this device, and this device's own notification actions on them. Those are
 * strictly local (user, 2026-10-21: "if someone wants to mute, it shouldn't mute the other"): the wearer never hears of
 * them, and the wearer's own mutes never reach here (it keeps publishing what its rules decide).
 *
 * - *Mute* drops the wearer's alerts of that side while it lasts (very low and extremely high always show);
 * - *Slow down* shows a repeat only once `slowMin` has passed since the last one shown here (a more severe kind of the
 *   side always shows);
 * - both end with the wearer's episode (a firing with `n` = 1).
 *
 * The same class drives the app (Alerts) and the timeline scenarios (AlertScenarioTest, `mirror`).
 */
class AlertMirror {
    /** side → this device's notification actions in force. */
    private val snooze = HashMap<String, AlertEngine.Snooze>()
    /** group → when and which kind this device last showed (Slow down spaces the wearer's repeats). */
    private val last = HashMap<String, Pair<Long, String>>()
    /** group → id of the wearer's firing whose notification is up here. */
    private val shown = HashMap<String, String>()
    /** group → id of the wearer's newest firing, shown here or not. */
    private val newest = HashMap<String, String>()

    /** The wearer fired [k] (firing [id], the episode's [n]th, at [at]): true = show it here. */
    fun fire(k: String, id: String, n: Int, at: Long): Boolean {
        val side = Kind.side(k)
        val group = Kind.group(k)
        newest[group] = id
        if (n <= 1) snooze.remove(side)   // a new episode on the wearer
        val sn = snooze[side]
        val muted = sn != null && sn.until > at && k !in Kind.ALWAYS
        val slowed = sn != null && sn.slowMin > 0 && last[group]?.let { (t, lk) ->
            Kind.side(lk) == side && Kind.severity(k) <= Kind.severity(lk) && at - t < sn.slowMin * 60L } == true
        if (muted || slowed) return false
        shown[group] = id
        last[group] = at to k
        return true
    }

    /**
     * The wearer cleared its firing [id] of [k]'s group: true = the notification shown here goes. That's the firing
     * shown here, or the wearer's newest one: a repeat this device skipped (slowed) left the earlier firing up, and
     * the wearer's clear of the repeat ends it too. An older clear arriving late changes nothing.
     */
    fun clear(k: String, id: String): Boolean {
        val g = Kind.group(k)
        val cur = shown[g] ?: return false
        if (cur != id && newest[g] != id) return false
        shown.remove(g)
        return true
    }

    /** This device's *Mute* on [k]'s side until [until]: true = an alert of it was showing (it goes). */
    fun mute(k: String, until: Long): Boolean {
        val side = Kind.side(k)
        snooze[side] = (snooze[side] ?: AlertEngine.Snooze(0, 0)).copy(until = until)
        return shown.remove(Kind.group(k)) != null
    }

    /** This device's *Slow down* on [k]'s side. */
    fun slow(k: String, slowMin: Int) {
        val side = Kind.side(k)
        snooze[side] = (snooze[side] ?: AlertEngine.Snooze(0, 0)).copy(slowMin = slowMin)
    }

    fun slowed(k: String): Boolean = (snooze[Kind.side(k)]?.slowMin ?: 0) > 0
    fun showing(k: String): Boolean = Kind.group(k) in shown

    /** Side → muted until, for the badge. */
    fun paused(now: Long): Map<String, Long> = snooze.filterValues { it.until > now }.mapValues { it.value.until }
}
