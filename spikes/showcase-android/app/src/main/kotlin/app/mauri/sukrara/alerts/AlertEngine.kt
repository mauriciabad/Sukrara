// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.alerts

import app.mauri.sukrara.core.Reading
import app.mauri.sukrara.core.SensorInfo
import app.mauri.sukrara.core.SensorLife
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
 * - the falling-high mute only postpones: when it ends, whatever was due alerts.
 */
class AlertEngine {
    sealed interface Action { val kind: String; val id: String; val at: Long }

    /** [treated]: carbs (lows) or rapid-insulin (highs) entries in this episode, for the message; 0 for the other kinds. */
    data class Fire(override val kind: String, override val id: String, val n: Int, val reading: Reading?, val th: Double,
                    override val at: Long, val treated: Int = 0) : Action
    data class Clear(override val kind: String, override val id: String, override val at: Long) : Action

    /** Times of the logged rapid insulin and carbs (unix s, deleted ones left out) for the mute rules. */
    data class Doses(val rapid: List<Long> = emptyList(), val carbs: List<Long> = emptyList()) {
        fun of(treatment: String) = if (treatment == "rapid") rapid else carbs
    }

    /** What the UI shows per group: the matching kind, and why it's quiet if it is. */
    data class State(val kind: String, val muted: String?, val firedAt: Long, val n: Int)

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
    }

    private val episodes = HashMap<String, Episode>()
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
        for ((g, e) in episodes) put(g, JSONObject()
            .put("side", e.side).put("start", e.start).put("kind", e.kind).put("on", e.on).put("lastIn", e.lastIn)
            .put("n", e.n).put("firedAt", e.firedAt ?: JSONObject.NULL).put("firedKind", e.firedKind)
            .put("muted", e.muted ?: JSONObject.NULL).put("muteUntil", e.muteUntil).put("next", e.next)
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
        for (g in j.keys()) {
            val o = j.optJSONObject(g) ?: continue
            val e = Episode(o.getString("side"), o.getLong("start"), o.getString("kind")).apply {
                on = o.getBoolean("on"); lastIn = o.getLong("lastIn"); n = o.getInt("n")
                firedAt = if (o.isNull("firedAt")) null else o.getLong("firedAt"); firedKind = o.getString("firedKind")
                muted = if (o.isNull("muted")) null else o.getString("muted"); muteUntil = o.getLong("muteUntil"); next = o.getLong("next")
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
        step("level", level, s, d, now, out)
        step("rate", rateKind, s, d, now, out)
        step(Kind.NO_DATA, if (noData) Kind.NO_DATA else null, s, d, now, out)
        return out
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
        val cover = by?.let { d.of(it).filter { t -> t <= now && now - t < s.muteS(it) }.maxOrNull() }
        val mute = cover?.let { "${if (by == "rapid") "insulin" else by} ${(now - it) / 60} min ago" } ?: fallingMute(want, s, now)
        if (mute != null) {
            hide(e, now, out)
            e.muted = mute
            e.muteUntil = if (cover != null) cover + s.muteS(by!!) else 0
            e.next = e.muteUntil
            return
        }
        e.muted = null
        e.muteUntil = 0
        e.next = due(e, want, s, d, now)
        if (now < e.next) return
        e.n++
        e.firedAt = now
        e.firedKind = want
        val fire = Fire(want, newId(), e.n, last, threshold(want, s), now, treated(e, want, s, d, now))
        e.shown = fire
        e.next = due(e, want, s, d, now)
        out += fire
    }

    /** When [e] alerts next for [k]: see the class comment. */
    private fun due(e: Episode, k: String, s: AlertSettings, d: Doses, now: Long): Long {
        val fired = e.firedAt ?: return e.start
        if (Kind.side(k) == Kind.side(e.firedKind) && Kind.severity(k) > Kind.severity(e.firedKind)) return now
        val by = s.mutedBy(k)
        val tc = by?.let { d.of(it).filter { t -> t in fired..now }.maxOrNull() }
        if (tc != null && by != null) return maxOf(tc + s.muteS(by), fired + 1)
        val rep = s.cfg(k).repeatMin * 60L
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
        return "falling, back in range in ~${Math.ceil((r.sgv - high) / -rate).toInt().coerceAtLeast(1)} min"
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
        return c.filter { it > now }.minOrNull()
    }

    companion object {
        /** Readings older than this never alert (backfill, a late chunk). */
        const val FRESH_S = 15 * 60L
        /** An episode lasts until nothing of its side has matched for its repeat interval, but at least this long. */
        const val MIN_HOLD_MIN = 5
        private val rng = SecureRandom()
        fun doses(trs: List<Treatment>) = trs.filter { !it.del }.let { l ->
            Doses(l.filter { it.k == "rapid" }.map { it.t }, l.filter { it.k == "carbs" }.map { it.t })
        }
        fun newId(): String = ByteArray(8).also { rng.nextBytes(it) }.joinToString("") { "%02x".format(it) }
    }
}
