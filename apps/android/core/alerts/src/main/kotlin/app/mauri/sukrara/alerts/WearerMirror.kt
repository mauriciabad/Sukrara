// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.alerts

import kotlinx.serialization.Serializable

/** [WearerMirror]'s state, persisted with [AlertState] so a follower's mutes survive a restart. */
@Serializable
data class MirrorMemory(
    val snoozes: Map<Side, Snooze> = emptyMap(),
    val muteAllUntil: Long = 0,
    /** group → when and which kind this device last showed (Remind less spaces the wearer's repeats). */
    val lastShown: Map<AlertGroup, ShownAt> = emptyMap(),
    /** group → id of the wearer's firing whose notification is up here. */
    val shown: Map<AlertGroup, String> = emptyMap(),
    /** group → the wearer's newest firing, shown here or not. */
    val newest: Map<AlertGroup, Firing> = emptyMap(),
    /** groups whose newest firing the wearer cleared. */
    val newestCleared: Set<AlertGroup> = emptySet(),
)

@Serializable
data class ShownAt(val at: Long, val kind: AlertKind)

/**
 * A follower that mirrors the wearer's alerts (source "wearer", SPEC §2, §4): which of the wearer's `alert` events
 * show on this device, and this device's own actions on them — strictly local, the wearer never hears of them.
 * *Mute* drops the side's alerts while it lasts, *Mute all* every alert; *Remind less* shows a repeat only once
 * `relaxMin` has passed since the last one shown here (a more severe kind always shows). They end at their time or
 * once back by the margin on the readings this device gets ([reading]). The ways out show the wearer's newest firing
 * at once if the wearer still has it up.
 */
class WearerMirror(memory: MirrorMemory = MirrorMemory()) {
    private val snoozes = memory.snoozes.toMutableMap()
    var muteAllUntil: Long = memory.muteAllUntil
        private set
    private val lastShown = memory.lastShown.toMutableMap()
    private val shown = memory.shown.toMutableMap()
    private val newest = memory.newest.toMutableMap()
    private val newestCleared = memory.newestCleared.toMutableSet()

    fun snapshot() = MirrorMemory(snoozes.toMap(), muteAllUntil, lastShown.toMap(), shown.toMap(), newest.toMap(), newestCleared.toSet())

    /** The wearer fired [f]: true = show it here. */
    fun fire(f: Firing): Boolean {
        val side = f.kind.side
        val group = f.kind.group
        newest[group] = f
        newestCleared.remove(group)
        val sn = snoozes[side]
        val muted = (sn != null && sn.until > f.at) || muteAllUntil > f.at
        val relaxed = sn != null && sn.relaxMin > 0 && lastShown[group]?.let { (t, lk) ->
            lk.side == side && f.kind.severity <= lk.severity && f.at - t < sn.relaxMin * 60L
        } == true
        if (muted || relaxed) return false
        shown[group] = f.id
        lastShown[group] = ShownAt(f.at, f.kind)
        return true
    }

    /**
     * The wearer cleared firing [id] of [kind]'s group: true = the notification shown here goes (the one shown, or —
     * when this device skipped a relaxed repeat — the wearer's newest). A late clear of an older firing changes nothing.
     */
    fun clear(kind: AlertKind, id: String): Boolean {
        val g = kind.group
        if (newest[g]?.id == id) newestCleared += g
        val cur = shown[g] ?: return false
        if (cur != id && newest[g]?.id != id) return false
        shown.remove(g)
        return true
    }

    /** A reading this device got at [now] (null = none recent): sides whose Mute / Remind less end by the margin. */
    fun reading(r: AlertReading?, s: AlertSettings, now: Long): List<Side> {
        val out = mutableListOf<Side>()
        for (side in snoozes.keys.toList()) {
            val sn = snoozes.getValue(side)
            if (sn.until in 1..now) putSnooze(side, sn.copy(until = 0))
            val cur = snoozes[side] ?: continue
            if (r == null || !Rules.fresh(r, now)) continue
            if (!Rules.backBy(side, r, s)) {
                snoozes[side] = cur.copy(backSince = 0)
                continue
            }
            val since = cur.backSince.takeIf { it > 0 } ?: now
            if (now - since >= s.actions.backMin * 60L) {
                snoozes.remove(side)
                out += side
            } else {
                snoozes[side] = cur.copy(backSince = since)
            }
        }
        return out
    }

    /** *Mute* [kind]'s side until [until]: the firing that was showing (it goes), or null. */
    fun mute(kind: AlertKind, until: Long): Firing? {
        snoozes[kind.side] = (snoozes[kind.side] ?: Snooze()).copy(until = until, backSince = 0)
        return takeShown(kind.group)
    }

    fun relax(kind: AlertKind, relaxMin: Int) {
        snoozes[kind.side] = (snoozes[kind.side] ?: Snooze()).copy(relaxMin = relaxMin, backSince = 0)
    }

    /** *Mute all* until [until]: the firings that were showing (they go). */
    fun muteAll(until: Long): List<Firing> {
        muteAllUntil = until
        return shown.keys.toList().mapNotNull(::takeShown)
    }

    /** *Unmute* [kind]'s side: the wearer's newest firing to show again at once, or null. */
    fun unmute(kind: AlertKind, now: Long): Firing? {
        val sn = snoozes[kind.side] ?: return null
        if (sn.until <= now) return null
        putSnooze(kind.side, sn.copy(until = 0))
        return if (muteAllUntil > now) null else again(kind.group, now)
    }

    /** *Remind as usual*: like [unmute], a skipped repeat still up shows at once. */
    fun unrelax(kind: AlertKind, now: Long): Firing? {
        val sn = snoozes[kind.side] ?: return null
        if (sn.relaxMin == 0) return null
        putSnooze(kind.side, sn.copy(relaxMin = 0))
        return if (sn.until > now || muteAllUntil > now) null else again(kind.group, now)
    }

    /** *Unmute* (Mute all): the wearer's newest firings still up and not muted otherwise, to show at once. */
    fun unmuteAll(now: Long): List<Firing> {
        if (muteAllUntil <= now) return emptyList()
        muteAllUntil = 0
        return newest.keys.toList().mapNotNull { g ->
            val k = newest.getValue(g).kind
            if ((snoozes[k.side]?.until ?: 0) > now) null else again(g, now)
        }
    }

    /** Mute all's time is over at [now] (the timer): like [unmuteAll]; nothing while it lasts or when off. */
    fun expire(now: Long): List<Firing> {
        if (muteAllUntil == 0L || muteAllUntil > now) return emptyList()
        muteAllUntil = now + 1
        return unmuteAll(now)
    }

    /** The next time a mute of this device ends (for the runtime's timer), or null. */
    fun nextCheckAt(now: Long): Long? = (snoozes.values.map { it.until } + muteAllUntil)
        .filter { it > now && it != Rules.NEVER }.minOrNull()

    fun snoozes(now: Long): Map<Side, Snooze> = snoozes.filterValues { it.until > now || it.relaxMin > 0 }
        .mapValues { (_, sn) -> sn.copy(until = sn.until.takeIf { it > now } ?: 0) }

    fun relaxed(kind: AlertKind): Boolean = (snoozes[kind.side]?.relaxMin ?: 0) > 0

    private fun takeShown(g: AlertGroup): Firing? {
        shown.remove(g) ?: return null
        return newest[g]
    }

    private fun putSnooze(side: Side, sn: Snooze) {
        if (sn.isEmpty) snoozes.remove(side) else snoozes[side] = sn
    }

    private fun again(g: AlertGroup, now: Long): Firing? {
        val f = newest[g] ?: return null
        if (g in newestCleared || shown[g] == f.id) return null
        shown[g] = f.id
        lastShown[g] = ShownAt(now, f.kind)
        return f
    }
}
