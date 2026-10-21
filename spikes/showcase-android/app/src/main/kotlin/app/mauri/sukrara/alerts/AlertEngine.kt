// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.alerts

import app.mauri.sukrara.core.Durations
import app.mauri.sukrara.core.Reading
import app.mauri.sukrara.core.SensorInfo
import app.mauri.sukrara.core.SensorLife
import app.mauri.sukrara.core.Words
import app.mauri.sukrara.treatments.Treatment
import org.json.JSONObject
import java.security.SecureRandom

/**
 * The alert rules of spikes/alerts/SPEC.md §1–§2, without Android: readings, rapid-insulin and carb times and a clock in,
 * [Fire] / [Clear] actions out. One instance per role; not thread-safe (callers serialise).
 *
 * Three independent groups (level, rate, no data), each with at most one **episode**: a stretch of time on one side
 * (lows, highs, falling fast, rising fast, no data). An episode starts when a kind of that side matches and ends once
 * none has matched for the hold time (the kind's repeat interval, at least 5 min). So a reading back in range for a
 * moment removes the notification but doesn't start a new episode: no new "first" alert, the repeats keep counting,
 * and the treatments logged in the episode still count. Within an episode:
 * - the first match alerts at once; a more severe kind of the side alerts at once (low → very low);
 * - then it repeats every `repeatMin` (0 = never) counted from the last alert;
 * - a treatment that answers it (carbs for lows and falling fast, rapid insulin for highs and rising fast) quiets
 *   it for the mute window and restarts the count: when the window ends and it still matches, it alerts at once,
 *   and the repeats count from there;
 * - the falling-high mute only postpones: when it ends, whatever was due alerts;
 * - the alert notification's actions act on **one side** (lows incl. very low, highs incl. extremely high, falling
 *   fast, rising fast, no data; round f30): *Mute* ([mute]) quiets it like a treatment does (alerts at once when it
 *   ends), *Relax* ([relax]) repeats it at the slower interval. Neither ever touches the other side (a high's mute
 *   never silences a low). They outlive the episode and end at their time (Mute) or once the side is **back by the
 *   margin** ([AlertSettings.backMgdl] inside the threshold for [AlertSettings.backMin] minutes: [Back]), whichever
 *   comes first; crossing back out before that keeps them on, so glucose that oscillates at the edge stays quiet;
 * - *Mute all* ([muteAll]) quiets every kind on this device until its end ([NEVER] = until turned back on), very
 *   low and extremely high included; when it ends whatever still matches alerts at once;
 * - the ways out (SPEC §2 "States and exits"): *Unmute* / *Alert me anyway* ([unmute]) lifts the side's Mute, the
 *   treatments logged so far and the falling-high mute for the rest of the episode, and alerts at once if it still
 *   matches; *Stop relaxing* ([unrelax]) goes back to the kind's interval (an overdue repeat at once); a new [mute]
 *   on a muted side changes its end; *Turn alerts back on* ([unmuteAll]) ends Mute all.
 */
class AlertEngine {
    sealed interface Action { val kind: String; val id: String; val at: Long }

    /** [treated]: carbs (lows) or rapid-insulin (highs) entries in this episode, for the message; 0 for the other kinds. */
    data class Fire(override val kind: String, override val id: String, val n: Int, val reading: Reading?, val th: Double,
                    override val at: Long, val treated: Int = 0) : Action
    data class Clear(override val kind: String, override val id: String, override val at: Long) : Action
    /**
     * The Mute / Relax of [kind]'s side ended because glucose is back by the margin (no notification; the app notes it
     * and updates the badge). [kind] is the side's name ("low", "high", "falling_fast", …); [id] is empty.
     */
    data class Back(override val kind: String, override val at: Long) : Action { override val id: String get() = "" }

    /** Times of the logged rapid insulin and carbs (unix s, deleted ones left out) for the mute rules. */
    data class Doses(val rapid: List<Long> = emptyList(), val carbs: List<Long> = emptyList()) {
        fun of(treatment: String) = if (treatment == "rapid") rapid else carbs
    }

    /**
     * This device's notification actions on a side: muted until [until] (0 = not), repeats relaxed to [relaxMin]
     * (0 = not). [backSince]: when the side was first back by the margin without crossing out again (0 = it isn't).
     */
    data class Snooze(val until: Long, val relaxMin: Int, val backSince: Long = 0)

    /** What the UI shows per group: the matching kind, and why it's quiet if it is. */
    data class State(val kind: String, val muted: String?, val firedAt: Long, val n: Int)

    /**
     * An episode in a state the user can leave (SPEC §2 "States and exits"), for the app's sheet and Active now:
     * [kind] = the episode's current kind, [on] = it still matches. [quiet] why it's quiet ([QUIET_NOTIFICATION],
     * [QUIET_CARBS], [QUIET_INSULIN], [QUIET_FALLING]; null = not quiet) until [until] (epoch s; 0 = no fixed end),
     * [relaxMin] > 0 = relaxed. [canRelax] = a Relax would change anything (the kind repeats faster). A side muted
     * or relaxed after its episode ended shows with [on] false and the side's main kind (low, high, …).
     */
    data class Quiet(val kind: String, val on: Boolean, val quiet: String?, val until: Long, val relaxMin: Int, val canRelax: Boolean)

    private class Episode(val side: String, val start: Long, var kind: String) {
        /** The condition held at the last evaluation, and when it last did. */
        var on = true
        var lastIn = start
        /** The firing whose notification is up (cleared when the condition goes, mutes, or the kind changes). */
        var shown: Fire? = null
        var n = 0
        var firedAt: Long? = null
        var firedKind = kind
        var muted: String? = null
        var muteUntil = 0L
        /** When the next alert is due (Long.MAX_VALUE = never), from the last evaluation: the timer. */
        var next = start
        /** *Unmute* / *Alert me anyway* at this time (0 = never): treatments logged up to then and the falling mute don't quiet it. */
        var lifted = 0L
        /** Lifted since the last alert: the next match alerts at once (cleared when it does). */
        var liftPending = false
    }

    private val episodes = HashMap<String, Episode>()
    /** side → this device's Mute / Relax ([mute], [relax]); they outlive episodes (see the class comment). */
    private val snooze = HashMap<String, Snooze>()
    /** *Mute all* until then (0 = off, [NEVER] = until turned back on). */
    var allUntil = 0L
        private set
    var last: Reading? = null
        private set
    /**
     * The current sensor (core/SensorLife; set by [Alerts] before each check): no `no_data` while it has ended or is
     * warming up (no reading is expected then, SPEC §2); a running no-data alert clears.
     */
    var sensor: SensorInfo? = null

    /** No reading is expected: the sensor has ended or is warming up. */
    fun quiet(now: Long): Boolean = sensor?.let { SensorLife.ended(it.endEpoch, now) || SensorLife.warming(it, last?.t, now) } ?: false

    fun states(): Map<String, State> = episodes.filterValues { it.on }.mapValues { (_, e) -> State(e.kind, e.muted, e.firedAt ?: 0, e.n) }

    /** The newest stored reading after a restart: no data counts from it. No evaluation (no alert for old news). */
    fun seed(r: Reading?) { if (r != null && last.let { it == null || r.t > it.t }) last = r }

    /**
     * The episodes, saved after every evaluation so a restart resumes them. An app update kills the process and
     * removes its notifications; without this the next check took an ongoing alert for a new one and rang again at
     * once, at the same moment as LSPosed's "Module update complete" notification (system default sound): two
     * sounds (docs/15, "Two sounds at once").
     */
    fun snapshot(): JSONObject = JSONObject().apply {
        put(SNOOZES, JSONObject().also { j -> snooze.forEach { (side, sn) ->
            j.put(side, JSONObject().put("until", sn.until).put("relaxMin", sn.relaxMin).put("backSince", sn.backSince)) } })
        put(ALL, allUntil)
        for ((g, e) in episodes) put(g, JSONObject()
            .put("side", e.side).put("start", e.start).put("kind", e.kind).put("on", e.on).put("lastIn", e.lastIn)
            .put("n", e.n).put("firedAt", e.firedAt ?: JSONObject.NULL).put("firedKind", e.firedKind)
            .put("muted", e.muted ?: JSONObject.NULL).put("muteUntil", e.muteUntil).put("next", e.next)
            .put("lifted", e.lifted).put("liftPending", e.liftPending)
            .put("shown", e.shown?.let { f -> JSONObject().put("kind", f.kind).put("id", f.id).put("n", f.n)
                .put("th", f.th).put("at", f.at).put("treated", f.treated)
                .put("reading", f.reading?.let { r -> JSONObject().put("t", r.t).put("sgv", r.sgv).put("rate", r.rate ?: JSONObject.NULL) } ?: JSONObject.NULL)
            } ?: JSONObject.NULL))
    }

    /**
     * Back to the [snapshot] state (episodes over by [now] are dropped). Returns the firings whose notification was
     * up: the caller shows them again without sound. The next [evaluate] goes on from there: a repeat that came due
     * while the app was down alerts, anything else waits for its time.
     */
    fun restore(j: JSONObject, s: AlertSettings, now: Long): List<Fire> {
        episodes.clear()
        snooze.clear()
        j.optJSONObject(SNOOZES)?.let { o -> for (side in o.keys()) o.getJSONObject(side).let { x ->
            snooze[side] = Snooze(x.optLong("until"), x.optInt("relaxMin"), x.optLong("backSince")) } }
        allUntil = j.optLong(ALL).takeIf { it > now } ?: 0
        for (g in j.keys()) {
            if (g == SNOOZES || g == ALL) continue
            val o = j.optJSONObject(g) ?: continue
            val e = Episode(o.getString("side"), o.getLong("start"), o.getString("kind")).apply {
                on = o.getBoolean("on"); lastIn = o.getLong("lastIn"); n = o.getInt("n")
                firedAt = if (o.isNull("firedAt")) null else o.getLong("firedAt"); firedKind = o.getString("firedKind")
                muted = if (o.isNull("muted")) null else o.getString("muted"); muteUntil = o.getLong("muteUntil"); next = o.getLong("next")
                lifted = o.optLong("lifted"); liftPending = o.optBoolean("liftPending")
                shown = o.optJSONObject("shown")?.let { f ->
                    Fire(f.getString("kind"), f.getString("id"), f.getInt("n"),
                        f.optJSONObject("reading")?.let { r -> Reading(r.getLong("t"), r.getInt("sgv"), if (r.isNull("rate")) null else r.getDouble("rate")) },
                        f.getDouble("th"), f.getLong("at"), f.getInt("treated"))
                }
            }
            if (e.kind in Kind.ALL && now - e.lastIn < hold(e, s)) episodes[g] = e
        }
        return episodes.values.mapNotNull { it.shown }
    }

    /**
     * The alert notification's *Mute* for [min] minutes on [kind]'s side (every kind, very low and extremely high
     * included; with or without an episode), then [evaluate] (its alert clears). On a muted side: Change (the end
     * becomes now + [min]).
     */
    fun mute(kind: String, min: Int, s: AlertSettings, d: Doses, now: Long): List<Action> {
        if (min <= 0) return emptyList()
        val side = Kind.side(kind)
        snooze[side] = (snooze[side] ?: Snooze(0, 0)).copy(until = now + min * 60L, backSince = 0)
        return evaluate(s, d, now)
    }

    /** The alert notification's *Relax* on [kind]'s side: its repeats come every [AlertSettings.relaxMin] at least. */
    fun relax(kind: String, s: AlertSettings, d: Doses, now: Long): List<Action> {
        val side = Kind.side(kind)
        snooze[side] = (snooze[side] ?: Snooze(0, 0)).copy(relaxMin = s.relaxMin, backSince = 0)
        return evaluate(s, d, now)
    }

    /**
     * *Unmute* (a notification mute) / *Alert me anyway* (a treatment or falling-high mute) on [kind]'s side, this
     * device only: the side's Mute ends, and whatever quiets its episode now is lifted for the rest of the episode (a
     * treatment logged later mutes again), then [evaluate]: still matching → it alerts at once; back in range →
     * nothing (it alerts at once if it matches again within the episode). Nothing when nothing is quiet.
     */
    fun unmute(kind: String, s: AlertSettings, d: Doses, now: Long): List<Action> {
        val side = Kind.side(kind)
        val sn = snooze[side]
        val muted = sn != null && sn.until > now
        val e = episodes[Kind.group(kind)]?.takeIf { it.side == side }
        val quiet = e != null && (e.muted != null || cover(e, e.kind, s, d, now) != null)
        if (!muted && !quiet) return emptyList()
        if (sn != null) put(side, sn.copy(until = 0))
        if (e != null) {
            e.lifted = now
            e.liftPending = true
        }
        return evaluate(s, d, now)
    }

    /** *Stop relaxing* on [kind]'s side: the kind's own interval again (a repeat already due alerts at once). */
    fun unrelax(kind: String, s: AlertSettings, d: Doses, now: Long): List<Action> {
        val side = Kind.side(kind)
        val sn = snooze[side]?.takeIf { it.relaxMin > 0 } ?: return emptyList()
        put(side, sn.copy(relaxMin = 0))
        return evaluate(s, d, now)
    }

    /** *Mute all* until [until] ([NEVER] = until turned back on; a later call changes it), then [evaluate]. */
    fun muteAll(until: Long, s: AlertSettings, d: Doses, now: Long): List<Action> {
        if (until <= now) return emptyList()
        allUntil = until
        return evaluate(s, d, now)
    }

    /** *Turn alerts back on*: Mute all ends; whatever still matches (and isn't muted otherwise) alerts at once. */
    fun unmuteAll(s: AlertSettings, d: Doses, now: Long): List<Action> {
        if (allUntil <= now) return emptyList()
        allUntil = 0
        return evaluate(s, d, now)
    }

    /** Mute all's end at [now] (0 = off, [NEVER] = until turned back on). */
    fun allMuted(now: Long): Long = allUntil.takeIf { it > now } ?: 0

    private fun put(side: String, sn: Snooze) { if (sn.until == 0L && sn.relaxMin == 0) snooze.remove(side) else snooze[side] = sn }

    /** The side's Mute in force at [now], or 0. */
    private fun muteEnd(side: String, now: Long): Long = snooze[side]?.until?.takeIf { it > now } ?: 0

    /** The episodes and sides the user can act on now (quiet or relaxed): see [Quiet]. Mute all is [allMuted]. */
    fun quiets(s: AlertSettings, d: Doses, now: Long): List<Quiet> {
        val out = mutableListOf<Quiet>()
        val seen = HashSet<String>()
        for (g in listOf("level", "rate", Kind.NO_DATA)) {
            val e = episodes[g] ?: continue
            seen += e.side
            val tc = cover(e, e.kind, s, d, now)
            val by = s.mutedBy(e.kind)
            val note = muteEnd(e.side, now).takeIf { it > 0 }
            val relax = snooze[e.side]?.relaxMin ?: 0
            val (why, until) = when {
                tc != null && by != null -> (if (by == "rapid") QUIET_INSULIN else QUIET_CARBS) to maxOf(tc + s.muteS(by), note ?: 0)
                note != null -> QUIET_NOTIFICATION to note
                e.on && e.muted != null -> QUIET_FALLING to 0L
                else -> null to 0L
            }
            if (why == null && relax == 0) continue
            val rep = s.cfg(e.kind).repeatMin
            out += Quiet(e.kind, e.on, why, until, relax, relax == 0 && rep in 1 until s.relaxMin)
        }
        for ((side, sn) in snooze) {
            if (side in seen || (sn.until <= now && sn.relaxMin == 0)) continue
            val k = Kind.main(side)
            out += Quiet(k, false, if (sn.until > now) QUIET_NOTIFICATION else null, sn.until.takeIf { it > now } ?: 0, sn.relaxMin,
                sn.relaxMin == 0 && s.cfg(k).repeatMin in 1 until s.relaxMin)
        }
        return out
    }

    /** The newest treatment that quiets [k] in [e] at [now] (logged after an *Alert me anyway*), or null. */
    private fun cover(e: Episode, k: String, s: AlertSettings, d: Doses, now: Long): Long? {
        val by = s.mutedBy(k) ?: return null
        return d.of(by).filter { t -> t <= now && now - t < s.muteS(by) && t > e.lifted }.maxOrNull()
    }

    /** This device's notification actions in force, per side ("low", "high", "falling_fast", …): the badge, the sheet. */
    fun snoozes(now: Long): Map<String, Snooze> = snooze.filterValues { it.until > now || it.relaxMin > 0 }
        .mapValues { (_, sn) -> sn.copy(until = sn.until.takeIf { it > now } ?: 0) }

    /** Whether [kind]'s side is relaxed already (the notification offers *Stop relaxing* instead of *Relax*). */
    fun relaxed(kind: String): Boolean = (snooze[Kind.side(kind)]?.relaxMin ?: 0) > 0

    /** A new reading (any age; older than the newest one is ignored), then [evaluate]. */
    fun reading(r: Reading, s: AlertSettings, d: Doses, now: Long): List<Action> {
        if (last.let { it == null || r.t > it.t }) last = r
        return evaluate(s, d, now)
    }

    /** Re-check everything against the newest reading at [now] (timer: no data, re-alerts, mute windows ending). */
    fun evaluate(s: AlertSettings, d: Doses, now: Long): List<Action> {
        val out = mutableListOf<Action>()
        if (!s.on) {
            episodes.values.forEach { e -> e.shown?.let { out += Clear(it.kind, it.id, now) } }
            episodes.clear()
            return out
        }
        val r = last
        val fresh = r != null && now - r.t <= FRESH_S
        val level = AlertStatus.level(r, s, now)
        val rate = r?.rate
        val rateKind = if (!fresh || rate == null) null else when {
            s.cfg(Kind.FALLING_FAST).let { it.on && rate <= -it.value } -> Kind.FALLING_FAST
            s.cfg(Kind.RISING_FAST).let { it.on && rate >= it.value } -> Kind.RISING_FAST
            else -> null
        }
        val noData = s.cfg(Kind.NO_DATA).let { c -> r != null && c.on && now - r.t >= c.value * 60 } && !quiet(now)
        for (side in snooze.keys.toList()) {
            val sn = snooze.getValue(side)
            if (sn.until in 1..now) put(side, sn.copy(until = 0))   // the Mute's time is over
            val cur = snooze[side] ?: continue
            if (!fresh && side != Kind.NO_DATA) continue          // nothing new to judge the margin by
            if (!back(side, r, rateKind, noData, s)) { snooze[side] = cur.copy(backSince = 0); continue }
            val since = cur.backSince.takeIf { it > 0 } ?: now
            if (now - since >= s.backMin * 60L) { snooze.remove(side); out += Back(side, now) }
            else snooze[side] = cur.copy(backSince = since)
        }
        step("level", level, s, d, now, out)
        step("rate", rateKind, s, d, now, out)
        step(Kind.NO_DATA, if (noData) Kind.NO_DATA else null, s, d, now, out)
        return out
    }

    /** [side] is back by the margin ([backBy]); a rate side when its rate alert doesn't match; no data when readings come. */
    private fun back(side: String, r: Reading?, rateKind: String?, noData: Boolean, s: AlertSettings): Boolean = when (side) {
        "low", "high" -> r != null && backBy(side, r, s)
        Kind.NO_DATA -> !noData
        else -> rateKind != side
    }

    private fun hold(e: Episode, s: AlertSettings) = maxOf(s.cfg(e.kind).repeatMin, MIN_HOLD_MIN) * 60L

    private fun hide(e: Episode, now: Long, out: MutableList<Action>) {
        e.shown?.let { out += Clear(it.kind, it.id, now) }
        e.shown = null
    }

    private fun step(group: String, want: String?, s: AlertSettings, d: Doses, now: Long, out: MutableList<Action>) {
        var e = episodes[group]
        if (want == null) {
            if (e == null) return
            hide(e, now, out)
            e.on = false
            e.muted = null
            if (now - e.lastIn >= hold(e, s)) episodes.remove(group)
            return
        }
        if (e != null && (e.side != Kind.side(want) || (!e.on && now - e.lastIn >= hold(e, s)))) {
            hide(e, now, out)
            e = null
        }
        if (e == null) e = Episode(Kind.side(want), now, want).also { episodes[group] = it }
        e.on = true
        e.lastIn = now
        e.kind = want
        if (e.shown.let { it != null && it.kind != want }) hide(e, now, out)
        val by = s.mutedBy(want)
        val cover = cover(e, want, s, d, now)
        val mute = cover?.let { "${if (by == "rapid") "insulin" else by} ${Durations.text((now - it) / 60, Words())} ago" }
            ?: fallingMute(want, s, now).takeIf { e.lifted == 0L }
        if (mute != null) {
            hide(e, now, out)
            e.muted = mute
            e.muteUntil = if (cover != null) cover + s.muteS(by!!) else 0
            e.next = e.muteUntil
            return
        }
        val quietUntil = maxOf(muteEnd(e.side, now), allMuted(now))
        if (quietUntil > 0) {
            hide(e, now, out)
            e.muted = if (allMuted(now) == NEVER) "all alerts muted" else
                "muted ${if (quietUntil == allMuted(now)) "(all alerts)" else "from the notification"}, ${Durations.textS(quietUntil - now, Words())} left"
            e.muteUntil = quietUntil
            e.next = quietUntil
            return
        }
        e.muted = null
        e.muteUntil = 0
        e.next = due(e, want, s, d, now)
        if (now < e.next) return
        e.n++
        e.firedAt = now
        e.firedKind = want
        e.liftPending = false
        val fire = Fire(want, newId(), e.n, last, threshold(want, s), now, treated(e, want, s, d, now))
        e.shown = fire
        e.next = due(e, want, s, d, now)
        out += fire
    }

    /** When [e] alerts next for [k]: see the class comment. */
    private fun due(e: Episode, k: String, s: AlertSettings, d: Doses, now: Long): Long {
        val fired = e.firedAt ?: return e.start
        if (Kind.side(k) == Kind.side(e.firedKind) && Kind.severity(k) > Kind.severity(e.firedKind)) return now
        if (e.liftPending) return now   // an Unmute / Alert me anyway since the last alert: at once
        val by = s.mutedBy(k)
        val tc = by?.let { d.of(it).filter { t -> t in fired..now && t > e.lifted }.maxOrNull() }
        // a treatment, the side's Mute or Mute all after the last alert: at once when its window ends
        val ends = listOfNotNull(if (tc != null && by != null) tc + s.muteS(by) else null,
            snooze[e.side]?.until?.takeIf { it > fired }, allUntil.takeIf { it > fired && it != NEVER })
        if (ends.isNotEmpty()) return maxOf(ends.max(), fired + 1)
        val base = s.cfg(k).repeatMin
        val relax = snooze[e.side]?.relaxMin ?: 0
        val rep = (if (relax > 0 && base > 0) maxOf(base, relax) else base) * 60L
        return if (rep > 0) fired + rep else Long.MAX_VALUE
    }

    /**
     * Entries of the treatment that answers [k] (level kinds only) logged in the episode, or within the mute length
     * before it started (they're why it stayed quiet): 0, 1, or 2 for two or more.
     */
    private fun treated(e: Episode, k: String, s: AlertSettings, d: Doses, now: Long): Int {
        if (k !in Kind.LEVELS) return 0
        val tk = Kind.treatment(k) ?: return 0
        val from = e.start - s.muteS(tk)
        return d.of(tk).count { it in from..now }.coerceAtMost(2)
    }

    /** "back in range in ~N min" if the current fall brings it under the high threshold within the window. */
    private fun fallingMute(k: String, s: AlertSettings, now: Long): String? {
        val r = last ?: return null
        val rate = r.rate ?: return null
        if (!s.muteFalling || k !in Kind.HIGH_SIDE || rate >= 0) return null
        val high = s.cfg(Kind.HIGH).value
        if (r.sgv + rate * s.muteFallingMin >= high) return null
        return "falling, back in range in ~${Durations.text(Math.ceil((r.sgv - high) / -rate).toLong().coerceAtLeast(1), Words())}"
    }

    private fun threshold(k: String, s: AlertSettings) = s.cfg(k).value

    /** When the next timer check is useful (no-data deadline, a repeat, a mute window ending), or null. */
    fun nextCheckAt(s: AlertSettings, now: Long): Long? {
        if (!s.on) return null
        val c = mutableListOf<Long>()
        val r = last
        val nd = s.cfg(Kind.NO_DATA)
        if (r != null && nd.on && episodes[Kind.NO_DATA]?.on != true) c += r.t + (nd.value * 60).toLong()
        if (r != null) c += r.t + FRESH_S + 1   // level/rate alerts end when the reading goes stale
        // a warm-up that brings no reading: no_data is due again once it is over
        sensor?.let { si -> if (SensorLife.warming(si, r?.t, now)) c += si.warmupEndEpoch + SensorLife.WARMUP_GRACE_S }
        for (e in episodes.values) if (e.on && e.next != Long.MAX_VALUE) c += e.next
        // a Mute ending without an episode, the margin's time, Mute all ending
        for (sn in snooze.values) {
            if (sn.until > now) c += sn.until
            if (sn.backSince > 0) c += sn.backSince + s.backMin * 60L
        }
        if (allUntil in (now + 1) until NEVER) c += allUntil
        return c.filter { it > now }.minOrNull()
    }

    companion object {
        /** Readings older than this never alert (backfill, a late chunk). */
        const val FRESH_S = 15 * 60L
        /** An episode lasts until nothing of its side has matched for its repeat interval, but at least this long. */
        const val MIN_HOLD_MIN = 5
        /** [Quiet.quiet]: why an episode is quiet. */
        const val QUIET_NOTIFICATION = "notification"
        const val QUIET_CARBS = "carbs"
        const val QUIET_INSULIN = "insulin"
        const val QUIET_FALLING = "falling"
        /** [muteAll] without an end: until the user turns alerts back on. */
        const val NEVER = Long.MAX_VALUE
        private const val SNOOZES = "snoozes"
        private const val ALL = "all"
        private val rng = SecureRandom()
        fun doses(trs: List<Treatment>) = trs.filter { !it.del }.let { l ->
            Doses(l.filter { it.k == "rapid" }.map { it.t }, l.filter { it.k == "carbs" }.map { it.t })
        }
        /**
         * The reading [r] is back in range by the margin for [side]: lows at least [AlertSettings.backMgdl] above the
         * low threshold (the highest enabled low-side one), highs below the high threshold (the lowest enabled high-side
         * one) minus that; a rate side when [r]'s rate doesn't match it. The follower's [AlertMirror] uses it too.
         */
        fun backBy(side: String, r: Reading, s: AlertSettings): Boolean = when (side) {
            "low" -> Kind.LOW_SIDE.filter { s.cfg(it).on }.maxOfOrNull { s.cfg(it).value }.let { edge -> edge == null || r.sgv >= edge + s.backMgdl }
            "high" -> listOf(Kind.HIGH, Kind.VERY_HIGH, Kind.EXTREMELY_HIGH).filter { s.cfg(it).on }.minOfOrNull { s.cfg(it).value }
                .let { edge -> edge == null || r.sgv < edge - s.backMgdl }
            Kind.FALLING_FAST -> s.cfg(side).let { c -> !c.on || (r.rate ?: 0.0) > -c.value }
            Kind.RISING_FAST -> s.cfg(side).let { c -> !c.on || (r.rate ?: 0.0) < c.value }
            else -> true
        }
        fun newId(): String = ByteArray(8).also { rng.nextBytes(it) }.joinToString("") { "%02x".format(it) }
    }
}
