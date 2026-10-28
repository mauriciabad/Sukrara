// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.monitor.systemui

import app.mauri.sukrara.graph.CardSurface
import app.mauri.sukrara.graph.Freshness
import app.mauri.sukrara.graph.GlucoseCard
import app.mauri.sukrara.graph.GlucoseLook
import app.mauri.sukrara.graph.GraphPalette
import app.mauri.sukrara.graph.SensorBadge
import app.mauri.sukrara.graph.TrendMark
import app.mauri.sukrara.graph.stats.Band
import app.mauri.sukrara.graph.stats.Bands

/**
 * What the System UI module shows for the state the app sent ([HookState]): the card's decision
 * ([GlucoseCard.face] for [CardSurface.STATUS_HOOK]) turned into the text beside the clock and the range colour of
 * our system icons. Plain Kotlin, so the JVM tests run it without the Xposed API. English: it runs in System UI.
 */
object HookText {
    /** The mark after the value: the arrow, the "no trend" ring, the sensor-ended flag, the "no readings" icon. */
    enum class Mark { ARROW, RING, FLAG, NO_READINGS, NONE }

    /** The value ([text], struck through, at [alpha]) and the [mark] after it, in [color]; [desc] for TalkBack. */
    data class Render(
        val text: String,
        val strike: Boolean,
        val alpha: Float,
        val color: Int,
        val mark: Mark,
        val desc: String,
        val rate: Double? = null,
    )

    /** The card's decision for [s] at [nowS]: the sensor's end or warm-up first, then the freshness steps. */
    fun face(
        s: HookState,
        nowS: Long,
    ): GlucoseLook = GlucoseCard.face(s.latest(), nowS, sensorLook(s, nowS), CardSurface.STATUS_HOOK)

    /** The sensor states the face decides on (ended; warming up with its ready time where the number goes). */
    fun sensorLook(
        s: HookState,
        nowS: Long,
    ): SensorBadge.Look? =
        when {
            s.sensorEnded -> {
                SensorBadge.Look(label = "", life = 0f, ended = true)
            }

            s.readyAtS > 0 -> {
                SensorBadge.Look(
                    label = "",
                    life = 0f,
                    ended = false,
                    warmup = Freshness.timeText(s.readyAtS, nowS),
                    readyAt = s.readyAtS,
                )
            }

            else -> {
                null
            }
        }

    /**
     * The text for [look]: the value in its range colour (when [colorOn] and out of range) or [base] with its arrow /
     * the "no trend" ring; struck through at half strength from 5 min; from 20 min no value, only the "no readings"
     * icon; the flag once the sensor has ended; a warming-up sensor's time left where the number goes.
     */
    fun of(
        look: GlucoseLook,
        colorOn: Boolean,
        rangeColor: Int?,
        base: Int,
    ): Render =
        when (look.state) {
            GlucoseLook.State.WARMING_UP -> {
                Render(look.warmup.orEmpty(), false, 1f, base, Mark.NONE, "Sensor warming up, ${look.warmup.orEmpty()}")
            }

            GlucoseLook.State.ENDED -> {
                Render("", false, 1f, base, Mark.FLAG, "Sensor ended")
            }

            GlucoseLook.State.NO_READINGS, GlucoseLook.State.NONE -> {
                Render("", false, 1f, base, Mark.NO_READINGS, "No glucose readings")
            }

            GlucoseLook.State.VALUE -> {
                val text = checkNotNull(look.text)
                Render(
                    text,
                    look.strike,
                    if (look.strike) 0.5f else 1f,
                    if (look.strike || !colorOn) base else rangeColor ?: base,
                    if (look.trend == TrendMark.ARROW) Mark.ARROW else Mark.RING,
                    "Glucose $text${if (look.strike) " (old)" else ""}",
                    look.rate,
                )
            }
        }

    /**
     * The range colour of [mgdl] on a status bar whose own ink is [base]: null in range (the clock's colour, like the
     * other icons), else the band's colour from the palette that reads on that bar (light ink = a dark bar).
     */
    fun rangeColor(
        mgdl: Int,
        bands: Bands,
        base: Int,
    ): Int? {
        val band = bands.of(mgdl)
        if (band == Band.TIGHT || band == Band.TARGET) return null
        return GraphPalette.of(dark = luminance(base) > 0.5f).at(bands, mgdl)
    }

    /** The colour for our system icons now, or null to keep System UI's tint (off, in range, struck, no value). */
    fun iconColor(
        s: HookState,
        nowS: Long,
        base: Int,
    ): Int? {
        if (!s.enabled || !s.color || !s.systemIcons || s.mgdl <= 0) return null
        val look = face(s, nowS)
        if (look.state != GlucoseLook.State.VALUE || look.strike) return null
        return rangeColor(s.mgdl, s.bands, base)
    }

    private fun luminance(c: Int): Float {
        val r = (c shr 16 and 0xFF) / 255f
        val g = (c shr 8 and 0xFF) / 255f
        val b = (c and 0xFF) / 255f
        return 0.2126f * r + 0.7152f * g + 0.0722f * b
    }
}
