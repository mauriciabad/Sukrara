// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.graph

import app.mauri.sukrara.core.graph.R
import app.mauri.sukrara.core.model.Durations
import app.mauri.sukrara.graph.stats.Band
import app.mauri.sukrara.graph.stats.Bands
import app.mauri.sukrara.graph.stats.GlucoseStats

/**
 * What a screen reader says for a card (TalkBack; the home panel, widgets and the lock screen use it as their content
 * description): the value with its unit, the trend in words, the range in words (never by colour alone), how old it
 * is, the sensor and the status pill when there are any, then a one-line chart summary. Built from the same decisions
 * the card draws ([GlucoseCard.face], [StatusBadge.look], [SensorBadge.look]).
 */
object A11y {
    /** The value sentence: "112 milligrams per decilitre, falling, in range, 2 min ago". */
    fun value(
        model: CardModel,
        bands: Bands,
        surface: CardSurface,
    ): String {
        val w = model.words
        val now = model.nowS
        val r = model.latest
        val sensor = SensorBadge.look(model.sensor, now, r?.t, w)
        val face = GlucoseCard.face(r, now, sensor, surface)
        val parts =
            when (face.state) {
                GlucoseLook.State.WARMING_UP, GlucoseLook.State.ENDED -> listOf(SensorBadge.describe(checkNotNull(sensor), now, w))
                GlucoseLook.State.NONE -> listOf(w.string(R.string.a11y_no_readings))
                GlucoseLook.State.NO_READINGS ->
                    listOf(w.string(R.string.a11y_no_reading_since, Freshness.timeText(checkNotNull(r).t, now)))
                GlucoseLook.State.VALUE -> {
                    val p = checkNotNull(r)
                    listOfNotNull(
                        when {
                            p.sgv < 40 -> w.string(R.string.a11y_low_reading)
                            p.sgv > 400 -> w.string(R.string.a11y_high_reading)
                            else -> w.string(R.string.a11y_value, p.sgv.toString())
                        },
                        w.string(R.string.a11y_value_old).takeIf { face.strike },
                        w.trend(p.rate.takeIf { face.trend == TrendMark.ARROW }),
                        band(p.sgv, bands, w),
                        age(now - p.t, w),
                    )
                }
            }
        val extras =
            listOfNotNull(
                StatusBadge.describe(model.status, now, w),
                sensor?.takeIf { face.state == GlucoseLook.State.VALUE }?.let { SensorBadge.describe(it, now, w) },
            )
        return (listOf(parts.joinToString(", ")) + extras).joinToString(". ")
    }

    /** The chart summary: "Last 12 hours: 82% in range, 3% below, 15% above" (or "no data"). */
    fun chart(
        model: CardModel,
        style: GraphStyle,
    ): String {
        val hours = (style.windowS / 3600).toInt()
        val from = model.nowS - style.windowS
        val tir = GlucoseStats.timeInRange(model.readings, from, model.nowS, style.bands.sane())
        if (tir.empty) return model.words.string(R.string.a11y_chart_empty, hours)
        val (lo, inr, hi) = GlucoseGraph.pct3(tir.below, tir.inRange, tir.above)
        return model.words.string(R.string.a11y_chart, hours, inr, lo, hi)
    }

    /** The whole card: the value sentence, then the chart summary when [style] has a chart. */
    fun card(
        model: CardModel,
        style: CardStyle,
        surface: CardSurface,
    ): String {
        val bands = style.bands.sane()
        val value = if (style.value) value(model, bands, surface) else null
        val chart = style.graph?.let { chart(model, it) }
        return listOfNotNull(value, chart).joinToString(". ")
    }

    private fun band(
        sgv: Int,
        b: Bands,
        w: CardWords,
    ): String =
        w.string(
            when {
                sgv >= b.extremelyHigh -> R.string.a11y_band_extremely_high
                else ->
                    when (b.of(sgv)) {
                        Band.VERY_LOW -> R.string.a11y_band_very_low
                        Band.LOW -> R.string.a11y_band_low
                        Band.TIGHT, Band.TARGET -> R.string.a11y_band_in_range
                        Band.HIGH -> R.string.a11y_band_high
                        Band.VERY_HIGH -> R.string.a11y_band_very_high
                    }
            },
        )

    private fun age(
        s: Long,
        w: CardWords,
    ): String = if (s < 60) w.string(R.string.a11y_just_now) else w.string(R.string.a11y_ago, Durations.text(s / 60, w.durations))
}
