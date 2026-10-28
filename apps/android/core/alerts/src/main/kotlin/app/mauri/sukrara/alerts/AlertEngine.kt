// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.alerts

import java.security.SecureRandom
import java.util.EnumMap

/** What one run of the rules decided (SPEC §1–§2). */
sealed interface RuleAction {
    /** Alert now. */
    data class Fire(val firing: Firing) : RuleAction

    /** The notification of firing [id] goes (the condition left, a mute started, the kind changed). */
    data class Clear(val kind: AlertKind, val id: String, val at: Long) : RuleAction

    /** [side]'s Mute / Remind less ended: back by the margin (no notification; the app notes it). */
    data class Back(val side: Side, val at: Long) : RuleAction
}

/**
 * The alert rules of contracts/alerts/SPEC.md §1–§2 as a state machine: readings, doses, settings, this device's
 * actions and an explicit `now` (epoch s) in, [RuleAction]s out; no clock, no I/O. Not thread-safe: the runtime
 * calls it from one serial dispatcher. Guarded by the timeline scenarios (`AlertScenarioTest`).
 *
 * Three groups (level, rate, no data), each with at most one [Episode] on one side. Within an episode the first
 * match alerts at once, a more severe kind of the side at once, then every `repeatMin` from the last alert (0 = once).
 * A treatment that answers it quiets it for its window and restarts the count (at once when the window ends if it
 * still matches); the falling-high mute only postpones. This device's *Mute* / *Remind less* act on a side, outlive
 * the episode and end at their time or once back by the margin; *Mute all* quiets everything until its end.
 */
class AlertEngine(private val newId: () -> String = ::randomId) {
    private val episodes = EnumMap<AlertGroup, Episode>(AlertGroup::class.java)
    private val snoozes = EnumMap<Side, Snooze>(Side::class.java)

    var muteAllUntil: Long = 0
        private set
    var last: AlertReading? = null
        private set

    /** The current sensor: no `no_data` while it has ended or warms up (a running one clears). */
    var sensor: SensorWindow? = null

    private fun sensorSilent(now: Long) = sensor?.silent(last?.t, now) ?: false

    /** A stored reading newer than [last] (after a restart): no data counts from it. No evaluation: old news never alerts. */
    fun seed(r: AlertReading?) {
        if (r != null && last.let { it == null || r.t > it.t }) last = r
    }

    fun snapshot(): AlertMemory = AlertMemory(
        episodes = episodes.mapValues { it.value.copy() },
        snoozes = snoozes.toMap(),
        muteAllUntil = muteAllUntil,
        last = last,
    )

    /**
     * Back to [m] (episodes over by [now] dropped, an expired Mute all off). Returns the firings whose notification was
     * up: the runtime shows them again without sound. The next [evaluate] goes on from there.
     */
    fun restore(m: AlertMemory, s: AlertSettings, now: Long): List<Firing> {
        episodes.clear()
        snoozes.clear()
        snoozes.putAll(m.snoozes.filterValues { !it.isEmpty })
        muteAllUntil = m.muteAllUntil.takeIf { it > now } ?: 0
        seed(m.last)
        for ((g, e) in m.episodes) if (e.kind.group == g && now - e.lastIn < hold(e, s)) episodes[g] = e.copy()
        return episodes.values.mapNotNull { it.shown }
    }

    // region this device's actions (SPEC §2 "Notification actions", "States and exits")

    /** *Mute…* [kind]'s side for [min] minutes (on a muted side: Change). */
    fun mute(kind: AlertKind, min: Int, s: AlertSettings, d: Doses, now: Long): List<RuleAction> {
        if (min <= 0) return emptyList()
        snoozes[kind.side] = (snoozes[kind.side] ?: Snooze()).copy(until = now + min * 60L, backSince = 0)
        return evaluate(s, d, now)
    }

    /** *Remind less* on [kind]'s side: repeats every `relaxMin` at least. */
    fun relax(kind: AlertKind, s: AlertSettings, d: Doses, now: Long): List<RuleAction> {
        snoozes[kind.side] = (snoozes[kind.side] ?: Snooze()).copy(relaxMin = s.actions.relaxMin, backSince = 0)
        return evaluate(s, d, now)
    }

    /**
     * *Unmute* / *Alert me anyway*: the side's Mute ends and what quiets its episode now (treatments so far, the
     * falling mute) is lifted for the rest of the episode; still matching → alerts at once. Nothing quiet → nothing.
     */
    fun unmute(kind: AlertKind, s: AlertSettings, d: Doses, now: Long): List<RuleAction> {
        val side = kind.side
        val sn = snoozes[side]
        val muted = sn != null && sn.until > now
        val e = episodes[kind.group]?.takeIf { it.side == side }
        val quiet = e != null && (e.quiet != null || Rules.coveringDose(e.kind, s, d, now, e.lifted) != null)
        if (!muted && !quiet) return emptyList()
        if (sn != null) putSnooze(side, sn.copy(until = 0))
        e?.let { it.lifted = now; it.liftPending = true }
        return evaluate(s, d, now)
    }

    /** *Remind as usual*: the kind's own interval again (a repeat already due alerts at once). */
    fun unrelax(kind: AlertKind, s: AlertSettings, d: Doses, now: Long): List<RuleAction> {
        val sn = snoozes[kind.side]?.takeIf { it.relaxMin > 0 } ?: return emptyList()
        putSnooze(kind.side, sn.copy(relaxMin = 0))
        return evaluate(s, d, now)
    }

    /** *Mute all* until [until] ([Rules.NEVER] = until turned back on; a later call changes it). */
    fun muteAll(until: Long, s: AlertSettings, d: Doses, now: Long): List<RuleAction> {
        if (until <= now) return emptyList()
        muteAllUntil = until
        return evaluate(s, d, now)
    }

    /** *Unmute* (Mute all): whatever matches and isn't muted otherwise alerts at once. */
    fun unmuteAll(s: AlertSettings, d: Doses, now: Long): List<RuleAction> {
        if (muteAllUntil <= now) return emptyList()
        muteAllUntil = 0
        return evaluate(s, d, now)
    }

    // endregion

    /** Mute all's end at [now] (0 = off, [Rules.NEVER] = until turned back on). */
    fun allMuted(now: Long): Long = muteAllUntil.takeIf { it > now } ?: 0

    /** This device's actions in force per side (ended mutes as 0). */
    fun snoozes(now: Long): Map<Side, Snooze> = snoozes.filterValues { it.until > now || it.relaxMin > 0 }
        .mapValues { (_, sn) -> sn.copy(until = sn.until.takeIf { it > now } ?: 0) }

    fun relaxed(kind: AlertKind): Boolean = (snoozes[kind.side]?.relaxMin ?: 0) > 0

    /** The open episodes (a read-only copy, for [AlertUiState]). */
    fun episodes(): Map<AlertGroup, Episode> = episodes.mapValues { it.value.copy() }

    /**
     * A new reading (an older one than [last] only re-evaluates), then [evaluate]. A reading dated after [now] (a
     * sensor or phone clock ahead) counts as taken now: otherwise it would hide every reading until the clock caught up.
     */
    fun reading(r: AlertReading, s: AlertSettings, d: Doses, now: Long): List<RuleAction> {
        seed(r.notAfter(now))
        return evaluate(s, d, now)
    }

    /**
     * The clock was set back by [delta] seconds: every time this engine keeps moves back with it, so a repeat, a
     * window, a Mute or Mute all stays as far away as it was, the last reading keeps its age, and nothing goes quiet
     * for the length of the jump (a forward jump needs nothing: what came due alerts at once).
     */
    fun shiftBack(delta: Long) {
        episodes.values.forEach { it.shiftBack(delta) }
        snoozes.replaceAll { _, sn -> sn.copy(until = sn.until.shiftedBack(delta), backSince = sn.backSince.shiftedBack(delta)) }
        muteAllUntil = muteAllUntil.shiftedBack(delta)
        last = last?.let { it.copy(t = it.t - delta) }
    }

    /** Re-check everything against the newest reading at [now]: no data, repeats, windows ending, the margin. */
    fun evaluate(s: AlertSettings, d: Doses, now: Long): List<RuleAction> {
        val out = mutableListOf<RuleAction>()
        if (!s.on) {
            episodes.values.forEach { e -> e.shown?.let { out += RuleAction.Clear(it.kind, it.id, now) } }
            episodes.clear()
            return out
        }
        val r = last
        val fresh = Rules.fresh(r, now)
        val level = Rules.levelOf(r, s, now)
        val rateKind = Rules.rateOf(r, s, now)
        val noDataRule = s.rule(AlertKind.NO_DATA)
        val noData = r != null && noDataRule.on && now - r.t >= noDataRule.threshold * 60 && !sensorSilent(now)
        for (side in snoozes.keys.toList()) {
            val sn = snoozes.getValue(side)
            if (sn.until in 1..now) putSnooze(side, sn.copy(until = 0)) // the Mute's time is over
            val cur = snoozes[side] ?: continue
            if (!fresh && side != Side.NO_DATA) continue // nothing new to judge the margin by
            val back = when (side) {
                Side.LOW, Side.HIGH -> r != null && Rules.backBy(side, r, s)
                Side.NO_DATA -> !noData
                else -> rateKind?.side != side
            }
            if (!back) {
                snoozes[side] = cur.copy(backSince = 0)
                continue
            }
            val since = cur.backSince.takeIf { it > 0 } ?: now
            if (now - since >= s.actions.backMin * 60L) {
                snoozes.remove(side)
                out += RuleAction.Back(side, now)
            } else {
                snoozes[side] = cur.copy(backSince = since)
            }
        }
        step(AlertGroup.LEVEL, level, s, d, now, out)
        step(AlertGroup.RATE, rateKind, s, d, now, out)
        step(AlertGroup.NO_DATA, if (noData) AlertKind.NO_DATA else null, s, d, now, out)
        return out
    }

    /** When a timer check is next useful (no-data deadline, a repeat, a window or mute ending, the margin), or null. */
    fun nextCheckAt(s: AlertSettings, now: Long): Long? {
        if (!s.on) return null
        val c = mutableListOf<Long>()
        val r = last
        val nd = s.rule(AlertKind.NO_DATA)
        if (r != null && nd.on && episodes[AlertGroup.NO_DATA]?.on != true) c += r.t + (nd.threshold * 60).toLong()
        if (r != null) c += r.t + Rules.FRESH_S + 1 // level/rate alerts end when the reading goes stale
        sensor?.let { if (it.warming(r?.t, now)) c += it.warmupEndEpoch + SensorWindow.WARMUP_GRACE_S }
        for (e in episodes.values) if (e.on && e.next != Long.MAX_VALUE) c += e.next
        for (sn in snoozes.values) {
            if (sn.until > now) c += sn.until
            if (sn.backSince > 0) c += sn.backSince + s.actions.backMin * 60L
        }
        if (muteAllUntil in (now + 1) until Rules.NEVER) c += muteAllUntil
        return c.filter { it > now }.minOrNull()
    }

    private fun putSnooze(side: Side, sn: Snooze) {
        if (sn.isEmpty) snoozes.remove(side) else snoozes[side] = sn
    }

    private fun muteEnd(side: Side, now: Long): Long = snoozes[side]?.until?.takeIf { it > now } ?: 0

    private fun hold(e: Episode, s: AlertSettings) = maxOf(s.rule(e.kind).repeatMin, Rules.MIN_HOLD_MIN) * 60L

    private fun hide(e: Episode, now: Long, out: MutableList<RuleAction>) {
        e.shown?.let { out += RuleAction.Clear(it.kind, it.id, now) }
        e.shown = null
    }

    private fun step(group: AlertGroup, want: AlertKind?, s: AlertSettings, d: Doses, now: Long, out: MutableList<RuleAction>) {
        var e = episodes[group]
        if (want == null) {
            if (e == null) return
            hide(e, now, out)
            e.on = false
            e.quiet = null
            if (now - e.lastIn >= hold(e, s)) episodes.remove(group)
            return
        }
        if (e != null && (e.side != want.side || (!e.on && now - e.lastIn >= hold(e, s)))) {
            hide(e, now, out)
            e = null
        }
        val ep = e ?: Episode(want.side, now, want).also { episodes[group] = it }
        ep.on = true
        ep.lastIn = now
        ep.kind = want
        if (ep.shown.let { it != null && it.kind != want }) hide(ep, now, out)

        val cover = Rules.coveringDose(want, s, d, now, ep.lifted)
        val treatmentQuiet = when {
            cover != null -> EpisodeQuiet.TREATMENT
            ep.lifted == 0L && Rules.fallingBackIntoRange(want, last, s) -> EpisodeQuiet.FALLING
            else -> null
        }
        if (treatmentQuiet != null) {
            hide(ep, now, out)
            ep.quiet = treatmentQuiet
            ep.quietUntil = if (cover != null) cover + s.quietSeconds(s.mutedBy(want)!!) else 0
            ep.next = ep.quietUntil
            return
        }
        val all = allMuted(now)
        val userQuietUntil = maxOf(muteEnd(ep.side, now), all)
        if (userQuietUntil > 0) {
            hide(ep, now, out)
            ep.quiet = if (userQuietUntil == all) EpisodeQuiet.ALL else EpisodeQuiet.NOTIFICATION
            ep.quietUntil = userQuietUntil
            ep.next = userQuietUntil
            return
        }
        ep.quiet = null
        ep.quietUntil = 0
        ep.next = due(ep, want, s, d, now)
        if (now < ep.next) return
        ep.n++
        ep.firedAt = now
        ep.firedKind = want
        ep.liftPending = false
        val firing = Firing(want, newId(), ep.n, last, s.rule(want).threshold, now, treated(ep, want, s, d, now))
        ep.shown = firing
        ep.next = due(ep, want, s, d, now)
        out += RuleAction.Fire(firing)
    }

    /** When [e] alerts next for [k] (see the class comment). */
    private fun due(e: Episode, k: AlertKind, s: AlertSettings, d: Doses, now: Long): Long {
        val fired = e.firedAt ?: return e.start
        if (k.side == e.firedKind.side && k.severity > e.firedKind.severity) return now
        if (e.liftPending) return now // an Unmute / Alert me anyway since the last alert: at once
        val by = s.mutedBy(k)
        val dose = by?.let { d.of(it).filter { t -> t in fired..now && t > e.lifted }.maxOrNull() }
        // a treatment, the side's Mute or Mute all after the last alert: at once when its window ends
        val ends = listOfNotNull(
            if (dose != null && by != null) dose + s.quietSeconds(by) else null,
            snoozes[e.side]?.until?.takeIf { it > fired },
            muteAllUntil.takeIf { it > fired && it != Rules.NEVER },
        )
        if (ends.isNotEmpty()) return maxOf(ends.max(), fired + 1)
        val base = s.rule(k).repeatMin
        val relax = snoozes[e.side]?.relaxMin ?: 0
        val repeatS = (if (relax > 0 && base > 0) maxOf(base, relax) else base) * 60L
        return if (repeatS > 0) fired + repeatS else Long.MAX_VALUE
    }

    /** Entries of the treatment answering level [k] logged in the episode or within its window before: 0, 1, 2. */
    private fun treated(e: Episode, k: AlertKind, s: AlertSettings, d: Doses, now: Long): Int {
        if (!k.isLevel) return 0
        val tk = k.answeredBy ?: return 0
        val from = e.start - s.quietSeconds(tk)
        return d.of(tk).count { it in from..now }.coerceAtMost(2)
    }

    companion object {
        private val rng = SecureRandom()

        /** A random firing id (16 hex), SPEC §4 `id`. */
        fun randomId(): String = ByteArray(8).also(rng::nextBytes).joinToString("") { "%02x".format(it) }
    }
}
