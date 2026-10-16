// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.alerts

import app.mauri.sukrara.core.Reading
import java.security.SecureRandom

/**
 * The alert rules of spikes/alerts/SPEC.md §1–§2, without Android: readings, rapid-insulin times and a clock in,
 * [Fire] / [Clear] actions out. One instance per role; not thread-safe (callers serialise).
 *
 * Three independent groups (level, rate, no data), each with at most one active kind. Within a group the active
 * kind fires once, re-fires every `repeatMin` while it stays, and is cleared when it ends. A kind that comes back
 * within `repeatMin` of its last firing stays quiet until that time is up (no flapping at a threshold).
 */
class AlertEngine {
    sealed interface Action { val kind: String; val id: String; val at: Long }
    data class Fire(override val kind: String, override val id: String, val n: Int, val reading: Reading?, val th: Double,
                    override val at: Long) : Action
    data class Clear(override val kind: String, override val id: String, override val at: Long) : Action

    /** What the UI shows per group: the matching kind, and why it's quiet if it is. */
    data class State(val kind: String, val muted: String?, val firedAt: Long, val n: Int)

    private class Active(val kind: String, var id: String? = null, var n: Int = 0, var firedAt: Long = 0, var muted: String? = null,
                         var insulinUntil: Long = 0)

    private val active = HashMap<String, Active>()
    private val lastFired = HashMap<String, Long>()
    var last: Reading? = null
        private set

    fun states(): Map<String, State> = active.mapValues { (_, a) -> State(a.kind, a.muted, a.firedAt, a.n) }

    /** The newest stored reading after a restart: no data counts from it. No evaluation (no alert for old news). */
    fun seed(r: Reading?) { if (r != null && last.let { it == null || r.t > it.t }) last = r }

    /** A new reading (any age; older than the newest one is ignored), then [evaluate]. */
    fun reading(r: Reading, s: AlertSettings, rapid: List<Long>, now: Long): List<Action> {
        if (last.let { it == null || r.t > it.t }) last = r
        return evaluate(s, rapid, now)
    }

    /** Re-check everything against the newest reading at [now] (timer: no data, re-alerts, mute windows ending). */
    fun evaluate(s: AlertSettings, rapid: List<Long>, now: Long): List<Action> {
        val out = mutableListOf<Action>()
        if (!s.on) {
            active.values.forEach { a -> a.id?.let { out += Clear(a.kind, it, now) } }
            active.clear()
            return out
        }
        val r = last
        val fresh = r != null && now - r.t <= FRESH_S
        val level = if (!fresh) null else Kind.LEVELS.firstOrNull { k ->
            val c = s.cfg(k)
            c.on && if (k == Kind.URGENT_LOW || k == Kind.LOW) r!!.sgv <= c.value else r!!.sgv >= c.value
        }
        val rate = r?.rate
        val rateKind = if (!fresh || rate == null) null else when {
            s.cfg(Kind.FALLING_FAST).let { it.on && rate <= -it.value } -> Kind.FALLING_FAST
            s.cfg(Kind.RISING_FAST).let { it.on && rate >= it.value } -> Kind.RISING_FAST
            else -> null
        }
        val noData = s.cfg(Kind.NO_DATA).let { c -> r != null && c.on && now - r.t >= c.value * 60 }
        step("level", level, s, rapid, now, out)
        step("rate", rateKind, s, rapid, now, out)
        step(Kind.NO_DATA, if (noData) Kind.NO_DATA else null, s, rapid, now, out)
        return out
    }

    private fun step(group: String, want: String?, s: AlertSettings, rapid: List<Long>, now: Long, out: MutableList<Action>) {
        val cur = active[group]
        if (cur != null && cur.kind != want) {
            cur.id?.let { out += Clear(cur.kind, it, now) }
            active.remove(group)
        }
        if (want == null) return
        val a = active.getOrPut(group) { Active(want) }
        val insulinUntil = insulinMuteUntil(want, s, rapid, now)
        val mute = when {
            insulinUntil != null -> "insulin ${(now - (insulinUntil - s.muteInsulinMin * 60L)) / 60} min ago"
            else -> fallingMute(want, s, now)
        }
        if (mute != null) {
            a.id?.let { out += Clear(want, it, now); a.id = null }
            // after an insulin mute it fires as soon as the window ends (the insulin had its time and it's still high)
            if (insulinUntil != null) { lastFired.remove(want); a.insulinUntil = insulinUntil }
            a.muted = mute
            return
        }
        a.muted = null
        val repeat = s.cfg(want).repeatMin * 60L
        val lf = lastFired[want]
        val due = if (a.id == null) lf == null || now - lf >= repeat else repeat > 0 && now - a.firedAt >= repeat
        if (!due) return
        val id = newId()
        a.n = if (a.id == null) 1 else a.n + 1
        a.id = id
        a.firedAt = now
        lastFired[want] = now
        out += Fire(want, id, a.n, last, threshold(want, s), now)
    }

    /** End of the insulin mute window if a rapid dose mutes [k] now. */
    private fun insulinMuteUntil(k: String, s: AlertSettings, rapid: List<Long>, now: Long): Long? {
        if (!s.muteInsulin || (k !in Kind.HIGH_SIDE && k != Kind.RISING_FAST)) return null
        val w = s.muteInsulinMin * 60L
        return rapid.filter { it <= now && now - it < w }.maxOrNull()?.let { it + w }
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

    /** When the next timer check is useful (no-data deadline, re-alert, a mute window ending), or null. */
    fun nextCheckAt(s: AlertSettings, now: Long): Long? {
        if (!s.on) return null
        val c = mutableListOf<Long>()
        val r = last
        val nd = s.cfg(Kind.NO_DATA)
        if (r != null && nd.on && active[Kind.NO_DATA] == null) c += r.t + (nd.value * 60).toLong()
        if (r != null) c += r.t + FRESH_S + 1   // level/rate alerts end when the reading goes stale
        for (a in active.values) {
            val rep = s.cfg(a.kind).repeatMin * 60L
            if (a.muted == null && rep > 0) c += (if (a.id != null) a.firedAt else lastFired[a.kind] ?: now) + rep
            if (a.muted != null && a.insulinUntil > now) c += a.insulinUntil
        }
        return c.filter { it > now }.minOrNull()
    }

    companion object {
        /** Readings older than this never alert (backfill, a late chunk). */
        const val FRESH_S = 15 * 60L
        private val rng = SecureRandom()
        fun newId(): String = ByteArray(8).also { rng.nextBytes(it) }.joinToString("") { "%02x".format(it) }
    }
}
