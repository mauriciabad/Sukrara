// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.core

import android.content.res.Resources
import app.mauri.sukrara.R
import java.util.Locale

/**
 * The words the plain-Kotlin drawing and formatting code uses (the glucose card, the graph, the status badge, the
 * notification's slots, durations, trend words, alert names) without a Context. The defaults are the English of
 * `res/values/strings.xml`, so the JVM tests and galleries (no Android resources) render English; the app installs
 * [loader] at start, and [current] re-reads the resources whenever the app's locale changes (per-app language).
 * Format strings use Android's positional arguments (`%1$s`), which [String.format] understands too.
 */
data class Words(
    // status badge under the value
    val eatCarbs: String = "Eat carbs",
    val carbsShort: String = "Carbs",
    val takeInsulin: String = "Take insulin",
    val insulinShort: String = "Insulin",
    val timeLeft: String = "%1\$s left",
    val alertsPaused: String = "%1\$s: alerts paused, %2\$s left",
    // short durations
    val durNow: String = "now",
    val durMinutes: String = "%1\$dm",
    val durHours: String = "%1\$dh",
    val durHoursMinutes: String = "%1\$dh %2\$dm",
    val durDays: String = "%1\$dd",
    val ago: String = "%1\$s ago",
    // slot windows ("1d", "12h")
    val periodDays: String = "%1\$dd",
    val periodHours: String = "%1\$dh",
    // slots beside the value
    val slotAverage: String = "avg",
    val slotGmi: String = "GMI",
    val slotCv: String = "CV",
    val tirInRange: String = "in range",
    val tirVeryGood: String = "very good",
    // the card without a current reading
    val noReadingsYet: String = "No readings yet",
    val noReadingsSince: String = "No readings since %1\$s",
    val noReadingSince: String = "No reading since",
    // the sensor life badge and the ended card (round sensor-life)
    val sensorLeft: String = "Sensor: %1\$s left",
    val sensorEnded: String = "Sensor ended",
    val sensorReplace: String = "Replace the sensor",
    /** When it ended, after [sensorEnded]: today / yesterday / this week (a weekday + time) / older (a date). */
    val endedAt: String = "at %1\$s",
    val endedYesterdayAt: String = "yesterday at %1\$s",
    val endedOn: String = "%1\$s",
    /** [sensorEnded] + one of the above: "Sensor ended at 18:03". */
    val sensorEndedWhen: String = "%1\$s %2\$s",
    val warmingUp: String = "Warming up",
    val readyAt: String = "ready at %1\$s",
    // trend in words (screen readers, the notification)
    val trendUnknown: String = "trend unknown",
    val trendSteady: String = "steady",
    val trendRising: String = "rising",
    val trendRisingFast: String = "rising fast",
    val trendFalling: String = "falling",
    val trendFallingFast: String = "falling fast",
    /** Alert kind → its name ("Very low", "No data"). */
    val kinds: Map<String, String> = mapOf(
        "very_low" to "Very low", "low" to "Low", "high" to "High", "very_high" to "Very high",
        "extremely_high" to "Extremely high", "falling_fast" to "Falling fast", "rising_fast" to "Rising fast",
        "no_data" to "No data",
    ),
) {
    fun kind(k: String) = kinds[k] ?: k

    companion object {
        /** Builds the words from the app's resources (installed by the app; null on the JVM = English). */
        @Volatile var loader: (() -> Words)? = null
        @Volatile private var cached: Pair<Locale, Words>? = null

        /** The words for the current locale. */
        val current: Words get() {
            val l = Locale.getDefault()
            cached?.let { (cl, w) -> if (cl == l) return w }
            return (loader?.invoke() ?: Words()).also { cached = l to it }
        }

        fun of(r: Resources) = Words(
            eatCarbs = r.getString(R.string.badge_eat_carbs), carbsShort = r.getString(R.string.badge_carbs_short),
            takeInsulin = r.getString(R.string.badge_take_insulin), insulinShort = r.getString(R.string.badge_insulin_short),
            timeLeft = r.getString(R.string.badge_time_left), alertsPaused = r.getString(R.string.badge_alerts_paused_description),
            durNow = r.getString(R.string.duration_now), durMinutes = r.getString(R.string.duration_minutes),
            durHours = r.getString(R.string.duration_hours), durHoursMinutes = r.getString(R.string.duration_hours_minutes),
            durDays = r.getString(R.string.duration_days), ago = r.getString(R.string.duration_ago),
            periodDays = r.getString(R.string.period_days), periodHours = r.getString(R.string.period_hours),
            slotAverage = r.getString(R.string.slot_average_short), slotGmi = r.getString(R.string.slot_gmi_short),
            slotCv = r.getString(R.string.slot_cv_short), tirInRange = r.getString(R.string.slot_tir_in_range),
            tirVeryGood = r.getString(R.string.slot_tir_very_good),
            noReadingsYet = r.getString(R.string.card_no_readings_yet), noReadingsSince = r.getString(R.string.card_no_readings_since),
            noReadingSince = r.getString(R.string.card_no_reading_since),
            sensorLeft = r.getString(R.string.card_sensor_left), sensorEnded = r.getString(R.string.card_sensor_ended),
            sensorReplace = r.getString(R.string.card_sensor_replace),
            endedAt = r.getString(R.string.card_sensor_ended_at), endedYesterdayAt = r.getString(R.string.card_sensor_ended_yesterday_at),
            endedOn = r.getString(R.string.card_sensor_ended_on), sensorEndedWhen = r.getString(R.string.card_sensor_ended_when),
            warmingUp = r.getString(R.string.card_warming_up), readyAt = r.getString(R.string.card_ready_at),
            trendUnknown = r.getString(R.string.trend_unknown), trendSteady = r.getString(R.string.trend_steady),
            trendRising = r.getString(R.string.trend_rising), trendRisingFast = r.getString(R.string.trend_rising_fast),
            trendFalling = r.getString(R.string.trend_falling), trendFallingFast = r.getString(R.string.trend_falling_fast),
            kinds = mapOf(
                "very_low" to r.getString(R.string.alert_kind_very_low), "low" to r.getString(R.string.alert_kind_low),
                "high" to r.getString(R.string.alert_kind_high), "very_high" to r.getString(R.string.alert_kind_very_high),
                "extremely_high" to r.getString(R.string.alert_kind_extremely_high),
                "falling_fast" to r.getString(R.string.alert_kind_falling_fast),
                "rising_fast" to r.getString(R.string.alert_kind_rising_fast), "no_data" to r.getString(R.string.alert_kind_no_data),
            ),
        )
    }
}
