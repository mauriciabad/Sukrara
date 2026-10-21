// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.core

import android.graphics.Color
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * How a [Reading]'s trend and range look, shared by every surface outside the app's own screens: status-bar
 * notification, system icons, SystemUI hook, home-screen widgets.
 */

// How old a reading may be before it's struck through / hidden: graph/Freshness.kt (Freshness.look), for every surface.

/** Same mapping as the Mac (spikes/menubar TrendArrow.swift): 0 → flat, ±3 mg/dL/min → vertical. */
object Trend {
    const val FULL_SCALE = 3.0

    /** Screen-space angle in degrees: -90 = straight up (rising fast), 0 = flat, +90 = down. */
    fun angleDeg(rate: Double): Float = (-(rate / FULL_SCALE).coerceIn(-1.0, 1.0) * 90).toFloat()
    fun isDouble(rate: Double) = abs(rate) >= FULL_SCALE

    fun arrow(rate: Double?): String = when {
        rate == null -> app.mauri.sukrara.graph.TrendMark.RING
        rate >= FULL_SCALE -> "⇈"
        rate >= 2.0 -> "↑"
        rate >= 1.0 -> "↗"
        rate > -1.0 -> "→"
        rate > -2.0 -> "↘"
        rate > -FULL_SCALE -> "↓"
        else -> "⇊"
    }

    fun word(rate: Double?): String = when {
        rate == null -> Words.current.trendUnknown
        abs(rate) < 1 -> Words.current.trendSteady
        rate > 0 -> if (rate >= 2) Words.current.trendRisingFast else Words.current.trendRising
        else -> if (rate <= -2) Words.current.trendFallingFast else Words.current.trendFalling
    }

    /**
     * The continuous angle quantised to the 5° steps of the pre-rendered vector arrows (sb_t00 = up … sb_t18 = flat …
     * sb_t36 = down, from tools/gen-slot-drawables.swift), for surfaces that can't rotate a view: system icons and
     * widgets. Each is trimmed to its arrow's width, 15 dp tall.
     */
    fun drawableName(rate: Double): String = when {
        isDouble(rate) -> if (rate > 0) "sb_t_up2" else "sb_t_down2"
        else -> "sb_t%02d".format(java.util.Locale.ROOT, ((angleDeg(rate) + 90) / 5).roundToInt().coerceIn(0, 36))
    }
}

/**
 * The user's major ranges (investigation 16, [app.mauri.sukrara.graph.GraphSettings]): below range = red, above =
 * orange, in range = the surface's own colour. Set by [app.mauri.sukrara.graph.GraphSettings]; the SystemUI hook gets
 * them in its state broadcast.
 */
object Range {
    @Volatile var bands = app.mauri.sukrara.graph.Bands()
    val LOW get() = bands.low
    val HIGH get() = bands.high

    val RED = Color.rgb(0xFF, 0x45, 0x3A)
    val ORANGE = Color.rgb(0xFF, 0x9F, 0x0A)

    /** null = in range, draw with the surface's default colour. */
    fun color(mgdl: Int): Int? = when {
        mgdl < LOW -> RED
        mgdl > HIGH -> ORANGE
        else -> null
    }
}
