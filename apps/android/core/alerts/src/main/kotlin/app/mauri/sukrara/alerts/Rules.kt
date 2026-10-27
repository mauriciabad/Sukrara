// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.alerts

import kotlinx.serialization.Serializable

/**
 * A glucose reading as the rules see it: [t] epoch seconds, [mgdl], [rate] mg/dL/min (null = unknown: no rate alert).
 * Double on purpose: the falling-high prediction `mgdl + rate × min` must match the Mac and the scenarios exactly.
 */
@Serializable
data class AlertReading(val t: Long, val mgdl: Int, val rate: Double? = null)

/** Times (epoch s) of the logged, not deleted, carbs and rapid insulin: the treatment quiet rules (SPEC §2). */
data class Doses(val carbs: List<Long> = emptyList(), val rapid: List<Long> = emptyList()) {
    fun of(kind: TreatmentKind): List<Long> = if (kind == TreatmentKind.CARBS) carbs else rapid
}

/**
 * The current sensor's life (epoch s), so no `no_data` alerts while no reading is expected: the sensor has ended or
 * is warming up (SPEC §1 `no_data`). A warm-up counts until [WARMUP_GRACE_S] past its end, or until a reading of this
 * sensor arrives. Same numbers as the Mac's SensorLife (parity: contracts/alerts scenarios `sensor ended|started`).
 */
data class SensorWindow(val startEpoch: Long, val endEpoch: Long, val warmupEndEpoch: Long) {
    fun ended(now: Long): Boolean = now >= endEpoch

    fun warming(latestT: Long?, now: Long): Boolean =
        now >= startEpoch && now < warmupEndEpoch + WARMUP_GRACE_S && (latestT == null || latestT < startEpoch)

    /** No reading is expected at [now]. */
    fun silent(latestT: Long?, now: Long): Boolean = ended(now) || warming(latestT, now)

    companion object {
        const val WARMUP_GRACE_S = 15 * 60L
        const val DEFAULT_WARMUP_MIN = 60

        /** A sensor started at [startEpoch] lasting [wearMin] minutes with a [warmupMin] warm-up. */
        fun started(startEpoch: Long, wearMin: Int, warmupMin: Int = DEFAULT_WARMUP_MIN) =
            SensorWindow(startEpoch, startEpoch + wearMin * 60L, startEpoch + warmupMin * 60L)
    }
}

/** The pure predicates shared by [AlertEngine], [WearerMirror] and [AlertStatus]. */
object Rules {
    /** Readings older than this never alert (backfill, a late chunk). */
    const val FRESH_S = 15 * 60L

    /** An episode lasts until nothing of its side matched for its repeat interval, but at least this long. */
    const val MIN_HOLD_MIN = 5

    /** A *Mute all* without an end: until the user turns alerts back on. */
    const val NEVER = Long.MAX_VALUE

    fun fresh(r: AlertReading?, now: Long): Boolean = r != null && now - r.t <= FRESH_S

    /** The level kind [r] is in at [now] (the most severe that matches), or null: stale, in range, alerts off. */
    fun levelOf(r: AlertReading?, s: AlertSettings, now: Long): AlertKind? {
        if (r == null || !s.on || !fresh(r, now)) return null
        return AlertKind.LEVELS_BY_SEVERITY.firstOrNull { k ->
            val rule = s.rule(k)
            rule.on && if (k.isBelowThreshold) r.mgdl < rule.threshold else r.mgdl >= rule.threshold
        }
    }

    /** The rate kind [r] matches at [now], or null (stale, no rate, rate alerts off). */
    fun rateOf(r: AlertReading?, s: AlertSettings, now: Long): AlertKind? {
        val rate = r?.rate
        if (!fresh(r, now) || rate == null) return null
        val falling = s.rule(AlertKind.FALLING_FAST)
        val rising = s.rule(AlertKind.RISING_FAST)
        return when {
            falling.on && rate <= -falling.threshold -> AlertKind.FALLING_FAST
            rising.on && rate >= rising.threshold -> AlertKind.RISING_FAST
            else -> null
        }
    }

    /**
     * [kind] is a high that the current fall brings below the high threshold within the falling-high window
     * (`rate < 0` and `mgdl + rate × min < high`, SPEC §2 `muteFallingHigh`).
     */
    fun fallingBackIntoRange(kind: AlertKind, r: AlertReading?, s: AlertSettings): Boolean {
        val rate = r?.rate ?: return false
        if (!s.fallingHigh.on || kind !in AlertKind.FALLING_MUTABLE || rate >= 0) return false
        return r.mgdl + rate * s.fallingHigh.min < s.rule(AlertKind.HIGH).threshold
    }

    /**
     * [r] is back in range by the margin for [side] (SPEC §2 "Scope and end"): lows at least `backMgdl` above the highest
     * enabled low threshold, highs below the lowest enabled high threshold minus it, a rate side when [r]'s rate
     * doesn't match it. No data is judged by the caller (readings coming).
     */
    fun backBy(side: Side, r: AlertReading, s: AlertSettings): Boolean {
        val margin = s.actions.backMgdl
        return when (side) {
            Side.LOW -> AlertKind.entries.filter { it.side == Side.LOW && s.rule(it).on }
                .maxOfOrNull { s.rule(it).threshold }.let { edge -> edge == null || r.mgdl >= edge + margin }
            Side.HIGH -> AlertKind.entries.filter { it.side == Side.HIGH && s.rule(it).on }
                .minOfOrNull { s.rule(it).threshold }.let { edge -> edge == null || r.mgdl < edge - margin }
            Side.FALLING_FAST -> s.rule(AlertKind.FALLING_FAST).let { !it.on || (r.rate ?: 0.0) > -it.threshold }
            Side.RISING_FAST -> s.rule(AlertKind.RISING_FAST).let { !it.on || (r.rate ?: 0.0) < it.threshold }
            Side.NO_DATA -> true
        }
    }

    /** The doses of [kind] that quiet it at [now] (`now − min < t ≤ now`), logged after [after]: the newest, or null. */
    fun coveringDose(kind: AlertKind, s: AlertSettings, d: Doses, now: Long, after: Long = 0): Long? {
        val by = s.mutedBy(kind) ?: return null
        return d.of(by).filter { t -> t <= now && now - t < s.quietSeconds(by) && t > after }.maxOrNull()
    }
}
