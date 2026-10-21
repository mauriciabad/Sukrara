// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.alerts

import app.mauri.sukrara.core.Reading

/**
 * A follower that mirrors the wearer's alerts (source "wearer", spikes/alerts/SPEC.md §2, §4), without Android: which of
 * the wearer's `alert` events show on this device, and this device's own notification actions on them. Those are
 * strictly local (user, 2026-10-21: "if someone wants to mute, it shouldn't mute the other"): the wearer never hears of
 * them, and the wearer's own mutes never reach here (it keeps publishing what its rules decide).
 *
 * - *Mute* drops the wearer's alerts of that side while it lasts (every kind of the side, very low and extremely high
 *   included; round f30), *Mute all* every alert;
 * - *Relax* shows a repeat only once `relaxMin` has passed since the last one shown here (a more severe kind of the
 *   side always shows);
 * - Mute and Relax end at their time or once the side is back by the margin on the readings this device gets
 *   ([reading], the same rule as [AlertEngine]), not with the wearer's episode;
 * - *Unmute* ([unmute]), *Stop relaxing* ([unrelax]) and *Turn alerts back on* ([unmuteAll]) end them on this device;
 *   the wearer's newest firing that's still up (dropped or skipped here) then shows at once.
 *
 * The same class drives the app (Alerts) and the timeline scenarios (AlertScenarioTest, `mirror`).
 */
class AlertMirror {
    /** side → this device's notification actions in force. */
    private val snooze = HashMap<String, AlertEngine.Snooze>()
    /** *Mute all* on this device until then (0 = off, [AlertEngine.NEVER] = until turned back on). */
    var allUntil = 0L
    /** group → when and which kind this device last showed (Relax spaces the wearer's repeats). */
    private val last = HashMap<String, Pair<Long, String>>()
    /** group → id of the wearer's firing whose notification is up here. */
    private val shown = HashMap<String, String>()
    /** group → id and kind of the wearer's newest firing, shown here or not. */
    private val newest = HashMap<String, Pair<String, String>>()
    /** group → the wearer's newest firing was cleared by the wearer. */
    private val newestCleared = HashSet<String>()

    /** The wearer fired [k] (firing [id], the episode's [n]th, at [at]): true = show it here. */
    fun fire(k: String, id: String, n: Int, at: Long): Boolean {
        val side = Kind.side(k)
        val group = Kind.group(k)
        newest[group] = id to k
        newestCleared.remove(group)
        val sn = snooze[side]
        val muted = (sn != null && sn.until > at) || allUntil > at
        val relaxed = sn != null && sn.relaxMin > 0 && last[group]?.let { (t, lk) ->
            Kind.side(lk) == side && Kind.severity(k) <= Kind.severity(lk) && at - t < sn.relaxMin * 60L } == true
        if (muted || relaxed) return false
        shown[group] = id
        last[group] = at to k
        return true
    }

    /**
     * The wearer cleared its firing [id] of [k]'s group: true = the notification shown here goes. That's the firing
     * shown here, or the wearer's newest one: a repeat this device skipped (relaxed) left the earlier firing up, and
     * the wearer's clear of the repeat ends it too. An older clear arriving late changes nothing.
     */
    fun clear(k: String, id: String): Boolean {
        val g = Kind.group(k)
        if (newest[g]?.first == id) newestCleared += g
        val cur = shown[g] ?: return false
        if (cur != id && newest[g]?.first != id) return false
        shown.remove(g)
        return true
    }

    /**
     * A reading this device got at [now] ([r] null = none recent): ends a side's Mute / Relax once it is back by the
     * margin ([AlertEngine.backBy]); returns those sides. A Mute's time ending needs no reading ([snooze] drops it).
     */
    fun reading(r: Reading?, s: AlertSettings, now: Long): List<String> {
        val out = mutableListOf<String>()
        for (side in snooze.keys.toList()) {
            val sn = snooze.getValue(side)
            if (sn.until in 1..now) put(side, sn.copy(until = 0))
            val cur = snooze[side] ?: continue
            if (r == null || now - r.t > AlertEngine.FRESH_S) continue
            if (!AlertEngine.backBy(side, r, s)) { snooze[side] = cur.copy(backSince = 0); continue }
            val since = cur.backSince.takeIf { it > 0 } ?: now
            if (now - since >= s.backMin * 60L) { snooze.remove(side); out += side } else snooze[side] = cur.copy(backSince = since)
        }
        return out
    }

    /** This device's *Mute* on [k]'s side until [until]: true = an alert of it was showing (it goes). */
    fun mute(k: String, until: Long): Boolean {
        val side = Kind.side(k)
        snooze[side] = (snooze[side] ?: AlertEngine.Snooze(0, 0)).copy(until = until, backSince = 0)
        return shown.remove(Kind.group(k)) != null
    }

    /** This device's *Relax* on [k]'s side. */
    fun relax(k: String, relaxMin: Int) {
        val side = Kind.side(k)
        snooze[side] = (snooze[side] ?: AlertEngine.Snooze(0, 0)).copy(relaxMin = relaxMin, backSince = 0)
    }

    /** This device's *Mute all* until [until]: returns the groups whose alert was showing (they go). */
    fun muteAll(until: Long): List<String> {
        allUntil = until
        return shown.keys.toList().also { shown.clear() }
    }

    /**
     * This device's *Unmute* on [k]'s side at [now]: returns the id of the wearer's newest firing to show at once (it
     * is still up on the wearer and isn't showing here), or null.
     */
    fun unmute(k: String, now: Long): String? {
        val side = Kind.side(k)
        val sn = snooze[side] ?: return null
        if (sn.until <= now) return null
        put(side, sn.copy(until = 0))
        return if (allUntil > now) null else again(Kind.group(k), now)
    }

    /** This device's *Stop relaxing* on [k]'s side: like [unmute], a skipped repeat still up shows at once. */
    fun unrelax(k: String, now: Long): String? {
        val side = Kind.side(k)
        val sn = snooze[side] ?: return null
        if (sn.relaxMin == 0) return null
        put(side, sn.copy(relaxMin = 0))
        return if (sn.until > now || allUntil > now) null else again(Kind.group(k), now)
    }

    /** *Turn alerts back on* at [now]: the ids of the wearer's newest firings still up to show at once (not muted otherwise). */
    fun unmuteAll(now: Long): List<String> {
        if (allUntil <= now) return emptyList()
        allUntil = 0
        return newest.keys.toList().mapNotNull { g ->
            val k = newest.getValue(g).second
            if ((snooze[Kind.side(k)]?.until ?: 0) > now) null else again(g, now)
        }
    }

    /**
     * *Mute all*'s time is over at [now] (the app's timer): it ends, and like [unmuteAll] the wearer's newest firings
     * still up show at once. Returns their ids; nothing while it lasts or when it was off.
     */
    fun expire(now: Long): List<String> {
        if (allUntil == 0L || allUntil > now) return emptyList()
        allUntil = now + 1
        return unmuteAll(now)
    }

    private fun put(side: String, sn: AlertEngine.Snooze) { if (sn.until == 0L && sn.relaxMin == 0) snooze.remove(side) else snooze[side] = sn }

    private fun again(g: String, now: Long): String? {
        val (id, k) = newest[g] ?: return null
        if (g in newestCleared || shown[g] == id) return null
        shown[g] = id
        last[g] = now to k
        return id
    }

    /** This device's actions in force on [k]'s side (the sheet and Active now). */
    fun snooze(k: String): AlertEngine.Snooze? = snooze[Kind.side(k)]

    /** Every side with an action in force (the sheet and Active now list the ones without an alert too). */
    fun snoozes(now: Long): Map<String, AlertEngine.Snooze> = snooze.filterValues { it.until > now || it.relaxMin > 0 }
        .mapValues { (_, sn) -> sn.copy(until = sn.until.takeIf { it > now } ?: 0) }

    fun relaxed(k: String): Boolean = (snooze[Kind.side(k)]?.relaxMin ?: 0) > 0
    fun showing(k: String): Boolean = Kind.group(k) in shown

    /** Side → muted until (Mute all on every side), for the badge. */
    fun paused(now: Long): Map<String, Long> {
        val m = snooze.filterValues { it.until > now }.mapValues { it.value.until }.toMutableMap()
        if (allUntil > now) for (side in listOf("low", "high", Kind.FALLING_FAST, Kind.RISING_FAST, Kind.NO_DATA)) m[side] = allUntil
        return m
    }
}
