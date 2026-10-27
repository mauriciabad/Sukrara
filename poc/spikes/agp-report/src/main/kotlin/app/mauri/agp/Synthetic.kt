// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.agp

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.SplittableRandom
import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * Made-up glucose for the fixtures (never the user's data). Glucose follows the local wall clock (meals, dawn rise,
 * some night lows), so DST moves it in UTC like a real person's day. Readings come every 5 min (history) with
 * 1-minute stretches (live) in the evenings, off the 5-minute grid; plus signal gaps and a sensor change (warm-up gap,
 * the new sensor reading high for its first day).
 */
data class Scenario(
    val name: String,
    val zone: ZoneId,
    val first: LocalDate,
    val days: Int,
    val seed: Long,
    /** Local times where the old sensor stops and the new one starts reading (warm-up included). */
    val sensorChange: Pair<LocalDateTime, LocalDateTime>? = null,
    /** Days (0-based) without any data, e.g. the sensor fell off. */
    val missingDays: Set<Int> = emptySet(),
    /** Number of signal-loss gaps (20–90 min) sprinkled over the period. */
    val gaps: Int = 4,
    /** Nights (0-based day indices) with a low around 03:00. */
    val lowNights: Set<Int> = emptySet(),
    val note: String,
)

object Synthetic {
    val MADRID: ZoneId = ZoneId.of("Europe/Madrid")

    val SCENARIOS = listOf(
        Scenario("spring-dst", MADRID, LocalDate.of(2026, 3, 22), 14, 26,
            sensorChange = LocalDateTime.of(2026, 3, 31, 10, 15) to LocalDateTime.of(2026, 3, 31, 11, 15),
            lowNights = setOf(2, 6, 11), note = "14 days over the spring-forward night (29 Mar: a 23 h day), 1 h sensor warm-up on day 10, 4 signal gaps"),
        Scenario("autumn-dst", MADRID, LocalDate.of(2026, 10, 19), 14, 27,
            sensorChange = LocalDateTime.of(2026, 10, 26, 22, 0) to LocalDateTime.of(2026, 10, 27, 8, 0),
            lowNights = setOf(1, 4, 8, 12), gaps = 6, note = "14 days over the fall-back night (25 Oct: a 25 h day), sensor ended overnight with a 10 h gap before the next one, 6 signal gaps"),
        Scenario("sparse", MADRID, LocalDate.of(2026, 5, 4), 14, 28, missingDays = (6 until 14).toSet() + setOf(2),
            lowNights = setOf(1), gaps = 2, note = "14-day period with data on 5 days only (sensor lost): metrics and daily profiles, no percentile profile"),
    )

    private fun bump(h: Double, at: Double, height: Double, width: Double): Double {
        val x = h - at
        return if (x < 0) 0.0 else height * (x / width) * exp(1 - x / width)
    }

    fun readings(s: Scenario): List<Reading> {
        val rnd = SplittableRandom(s.seed)
        val start = s.first.atStartOfDay(s.zone).toEpochSecond()
        val end = s.first.plusDays(s.days.toLong()).atStartOfDay(s.zone).toEpochSecond()
        // Per-day meal times and sizes, picked up front so the series doesn't depend on the sampling.
        data class Meal(val at: Double, val size: Double)
        val meals = (0..s.days).map {
            listOf(Meal(7.5 + rnd.nextDouble(-0.7, 1.2), rnd.nextDouble(50.0, 140.0)),
                Meal(13.8 + rnd.nextDouble(-0.8, 1.0), rnd.nextDouble(60.0, 150.0)),
                Meal(20.5 + rnd.nextDouble(-0.8, 1.0), rnd.nextDouble(60.0, 170.0)))
        }
        val baseline = (0..s.days).map { rnd.nextDouble(105.0, 150.0) }
        val gapStarts = (0 until s.gaps).map { start + rnd.nextLong(end - start - 7200) to rnd.nextLong(20, 91) * 60 }
        val change = s.sensorChange?.let { (a, b) -> a.atZone(s.zone).toEpochSecond() to b.atZone(s.zone).toEpochSecond() }
        val offset = 37L // readings fall between grid instants
        var noise = 0.0
        val out = ArrayList<Reading>()
        var t = start + offset
        while (t < end) {
            val z = Instant.ofEpochSecond(t).atZone(s.zone)
            val day = (z.toLocalDate().toEpochDay() - s.first.toEpochDay()).toInt()
            val h = z.hour + z.minute / 60.0 + z.second / 3600.0
            val live = h in 18.0..23.0 && day % 3 != 0 // 1-minute stretches in some evenings
            val step = if (live) 60L else 300L
            noise = 0.95 * noise + rnd.nextDouble(-5.0, 5.0)
            var g = baseline[day] + noise +
                bump(h, 4.0, 25.0, 2.0) + // dawn rise
                meals[day].sumOf { bump(h, it.at, it.size, 0.9) } +
                (if (day > 0) meals[day - 1].sumOf { bump(h + 24, it.at, it.size, 0.9) } else 0.0)
            if (day in s.lowNights) g -= bump(h, 1.5, 85.0, 1.4)
            change?.let { (_, b) -> if (t in b until b + 86400) g += 15.0 * (1 - (t - b) / 86400.0) }
            val skip = day in s.missingDays ||
                gapStarts.any { (a, len) -> t in a until a + len } ||
                (change != null && t in change.first until change.second)
            if (!skip) out += Reading(t, g.roundToInt().coerceIn(40, 400))
            t += step
        }
        return out
    }
}
