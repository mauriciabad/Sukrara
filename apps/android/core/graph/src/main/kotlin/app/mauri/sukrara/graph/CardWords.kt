// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.graph

import android.content.res.Resources
import app.mauri.sukrara.core.graph.R
import app.mauri.sukrara.core.model.DurationWords
import app.mauri.sukrara.core.model.Durations

/**
 * Every word the card draws, read once from the graph's strings (en + es) so the renderer itself needs no Context:
 * widgets, the notification and Compose pass the same [CardWords] for the phone's language.
 */
class CardWords private constructor(
    private val r: Resources,
) {
    val eatCarbs: String = r.getString(R.string.badge_eat_carbs)
    val carbsShort: String = r.getString(R.string.badge_carbs_short)
    val takeInsulin: String = r.getString(R.string.badge_take_insulin)
    val insulinShort: String = r.getString(R.string.badge_insulin_short)
    val muted: String = r.getString(R.string.badge_muted_short)
    val replaceSensor: String = r.getString(R.string.badge_replace_sensor)
    val replaceSensorShort: String = r.getString(R.string.badge_replace_sensor_short)
    val backToNow: String = r.getString(R.string.chart_back_to_now)
    val slotAverage: String = r.getString(R.string.slot_average_short)
    val slotGmi: String = r.getString(R.string.slot_gmi_short)
    val slotCv: String = r.getString(R.string.slot_cv_short)
    val tirInRange: String = r.getString(R.string.slot_tir_in_range)
    val tirVeryGood: String = r.getString(R.string.slot_tir_very_good)
    val noReadingsYet: String = r.getString(R.string.card_no_readings_yet)
    val noReadingSince: String = r.getString(R.string.card_no_reading_since)
    val sensorEnded: String = r.getString(R.string.card_sensor_ended)
    val warmingUp: String = r.getString(R.string.card_warming_up)
    val almostReady: String = r.getString(R.string.card_almost_ready)
    val waitingReading: String = r.getString(R.string.card_waiting_reading)
    val durationNow: String = r.getString(R.string.duration_now)

    /** The one duration formatter's words ([Durations]). */
    val durations =
        DurationWords(
            minutes = r.getString(R.string.duration_minutes),
            hours = r.getString(R.string.duration_hours),
            hoursMinutes = r.getString(R.string.duration_hours_minutes),
            days = r.getString(R.string.duration_days),
            textMinutes = r.getString(R.string.duration_text_minutes),
            textHours = r.getString(R.string.duration_text_hours),
            textHoursMinutes = r.getString(R.string.duration_text_hours_minutes),
        )

    /** "45m left" / "quedan 45 min": the badge's time left for a mute ending in [min] minutes. */
    fun timeLeft(min: Long): String =
        r.getQuantityString(R.plurals.badge_time_left, Durations.compactQuantity(min), Durations.compact(min, durations))

    /** "Muted · 1h 20m". */
    fun mutedFor(min: Long): String = r.getString(R.string.badge_muted_for, Durations.compact(min, durations))

    fun ago(duration: String): String = r.getString(R.string.duration_ago, duration)

    fun readyAt(time: String): String = r.getString(R.string.card_ready_at, time)

    /** "Sensor: 13d left" for [min] minutes left. */
    fun sensorLeft(min: Long): String =
        r.getQuantityString(R.plurals.card_sensor_left, Durations.wholeQuantity(min), Durations.whole(min, durations))

    /** A slot's window: "1d", "12h". */
    fun period(windowS: Long): String =
        if (windowS % 86_400 == 0L) {
            r.getString(R.string.period_days, (windowS / 86_400).toInt())
        } else {
            r.getString(R.string.period_hours, (windowS / 3_600).toInt())
        }

    /** The card's short duration: "now" under a minute, else [Durations.compact]; [up] rounds partial minutes up. */
    fun shortDuration(
        s: Long,
        up: Boolean = false,
    ): String {
        val m = if (up) (s.coerceAtLeast(0) + 59) / 60 else s.coerceAtLeast(0) / 60
        return if (m < 1) durationNow else Durations.compact(m, durations)
    }

    /** The trend in words for [rate] mg/dL/min (null = unknown). */
    fun trend(rate: Double?): String =
        r.getString(
            when {
                rate == null -> R.string.trend_unknown
                kotlin.math.abs(rate) < 1 -> R.string.trend_steady
                rate > 0 -> if (rate >= 2) R.string.trend_rising_fast else R.string.trend_rising
                else -> if (rate <= -2) R.string.trend_falling_fast else R.string.trend_falling
            },
        )

    internal fun string(
        id: Int,
        vararg args: Any,
    ): String = r.getString(id, *args)

    companion object {
        /** The words for [resources]' language (a widget / notification / Compose context's). */
        fun of(resources: Resources) = CardWords(resources)
    }
}
