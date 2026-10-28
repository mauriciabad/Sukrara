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
    /** The badge's time left, "45m left" / "queda 1h" (a plural: the quantity is [Durations.compactQuantity]). */
    val timeLeft: Plural = Plural("%1\$s left", "%1\$s left"),
    /**
     * Mute alerts (StatusBadge kind "muted", rounds f30, f31): the pill "Muted · 1h 20m", "Muted" without an end (also
     * its short form). Screen readers say the badge with alerts/MuteCopy's lines (StatusBadge.describe).
     */
    val muted: String = "Muted",
    val mutedFor: String = "Muted · %1\$s",
    // short durations
    val durNow: String = "now",
    val durMinutes: String = "%1\$dm",
    val durHours: String = "%1\$dh",
    val durHoursMinutes: String = "%1\$dh %2\$dm",
    val durDays: String = "%1\$dd",
    // durations in words and settings (core/Durations.text): "45 min", "1 h", "1 h 30 min"
    val durTextMinutes: String = "%1\$d min",
    val durTextHours: String = "%1\$d h",
    val durTextHoursMinutes: String = "%1\$d h %2\$d min",
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
    val noReadingSince: String = "No reading since",
    // the sensor life badge and the ended card (round sensor-life)
    /** "Sensor: 13d left" / "Sensor: queda 1d" (a plural: the quantity is [Durations.wholeQuantity]). */
    val sensorLeft: Plural = Plural("Sensor: %1\$s left", "Sensor: %1\$s left"),
    val sensorEnded: String = "Sensor ended",
    /** The action pill on an ended sensor (StatusBadge kind "sensor"), full and short like "Eat carbs" / "Carbs". */
    val replaceSensor: String = "Replace sensor",
    val replaceSensorShort: String = "Sensor",
    /** The pill over a chart scrolled into the past (StatusBadge kind "now"): back to the latest reading. */
    val backToNow: String = "Now",
    val warmingUp: String = "Warming up",
    val readyAt: String = "Ready at %1\$s",
    /** Past the warm-up's end, before the first reading: the figure and the line under it (never "0m" / "-3m"). */
    val almostReady: String = "Almost ready",
    val waitingReading: String = "Waiting for the first reading",
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

    /**
     * A plural format (round f33: Spanish "queda 1 h", "quedan 2 h"): on the app [pick] is Android's plurals rules for
     * its language (`Resources.getQuantityText`); on the JVM (no resources) the English [one] / [other].
     */
    class Plural(val one: String, val other: String, private val pick: ((Int) -> String)? = null) {
        /** The format for [quantity]. */
        fun of(quantity: Int): String = pick?.invoke(quantity) ?: if (quantity == 1) one else other
        fun format(quantity: Int, vararg args: Any?): String = of(quantity).format(*args)
        override fun equals(o: Any?) = o is Plural && of(1) == o.of(1) && of(2) == o.of(2)
        override fun hashCode() = of(1).hashCode() * 31 + of(2).hashCode()
    }

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

        private fun plural(r: Resources, id: Int) = Plural(r.getQuantityText(id, 1).toString(), r.getQuantityText(id, 2).toString()) {
            r.getQuantityText(id, it).toString()
        }

        fun of(r: Resources) = Words(
            eatCarbs = r.getString(R.string.badge_eat_carbs), carbsShort = r.getString(R.string.badge_carbs_short),
            takeInsulin = r.getString(R.string.badge_take_insulin), insulinShort = r.getString(R.string.badge_insulin_short),
            timeLeft = plural(r, R.plurals.badge_time_left),
            muted = r.getString(R.string.badge_muted_short), mutedFor = r.getString(R.string.badge_muted_for),
            durNow = r.getString(R.string.duration_now), durMinutes = r.getString(R.string.duration_minutes),
            durHours = r.getString(R.string.duration_hours), durHoursMinutes = r.getString(R.string.duration_hours_minutes),
            durDays = r.getString(R.string.duration_days),
            durTextMinutes = r.getString(R.string.duration_text_minutes), durTextHours = r.getString(R.string.duration_text_hours),
            durTextHoursMinutes = r.getString(R.string.duration_text_hours_minutes), ago = r.getString(R.string.duration_ago),
            periodDays = r.getString(R.string.period_days), periodHours = r.getString(R.string.period_hours),
            slotAverage = r.getString(R.string.slot_average_short), slotGmi = r.getString(R.string.slot_gmi_short),
            slotCv = r.getString(R.string.slot_cv_short), tirInRange = r.getString(R.string.slot_tir_in_range),
            tirVeryGood = r.getString(R.string.slot_tir_very_good),
            noReadingsYet = r.getString(R.string.card_no_readings_yet),
            noReadingSince = r.getString(R.string.card_no_reading_since),
            sensorLeft = plural(r, R.plurals.card_sensor_left), sensorEnded = r.getString(R.string.card_sensor_ended),
            replaceSensor = r.getString(R.string.badge_replace_sensor), replaceSensorShort = r.getString(R.string.badge_replace_sensor_short),
            backToNow = r.getString(R.string.chart_back_to_now),
            warmingUp = r.getString(R.string.card_warming_up), readyAt = r.getString(R.string.card_ready_at),
            almostReady = r.getString(R.string.card_almost_ready), waitingReading = r.getString(R.string.card_waiting_reading),
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
