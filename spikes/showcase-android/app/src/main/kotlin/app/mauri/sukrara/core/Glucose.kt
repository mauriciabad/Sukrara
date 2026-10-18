// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.core

import android.graphics.Color
import app.mauri.sukrara.R
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * How a [Reading] looks, shared by every surface outside the app's own screens: status-bar notification, system
 * icons, SystemUI hook, home-screen widgets.
 */

/** A reading older than this is stale (struck through / grey / "123?"). */
const val STALE_AFTER_S = 5 * 60

fun Reading.ageS(nowS: Long) = nowS - t
fun Reading.stale(nowS: Long) = ageS(nowS) > STALE_AFTER_S

/** "now", "3 min ago", "2 h ago". */
fun ageText(ageS: Long): String = when {
    ageS < 60 -> "now"
    ageS < 3600 -> "${ageS / 60} min ago"
    ageS < 86_400 -> "${ageS / 3600} h ago"
    else -> "${ageS / 86_400} d ago"
}

/** Same mapping as the Mac (spikes/menubar TrendArrow.swift): 0 → flat, ±3 mg/dL/min → vertical. */
object Trend {
    const val FULL_SCALE = 3.0

    /** Screen-space angle in degrees: -90 = straight up (rising fast), 0 = flat, +90 = down. */
    fun angleDeg(rate: Double): Float = (-(rate / FULL_SCALE).coerceIn(-1.0, 1.0) * 90).toFloat()
    fun isDouble(rate: Double) = abs(rate) >= FULL_SCALE

    fun arrow(rate: Double?): String = when {
        rate == null -> "·"
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
    fun drawable(rate: Double?): Int = when {
        rate == null -> R.drawable.sb_t_none
        isDouble(rate) -> if (rate > 0) R.drawable.sb_t_up2 else R.drawable.sb_t_down2
        else -> ARROWS[((angleDeg(rate) + 90) / 5).roundToInt().coerceIn(0, 36)]
    }

    private val ARROWS = intArrayOf(
        R.drawable.sb_t00, R.drawable.sb_t01, R.drawable.sb_t02, R.drawable.sb_t03, R.drawable.sb_t04,
        R.drawable.sb_t05, R.drawable.sb_t06, R.drawable.sb_t07, R.drawable.sb_t08, R.drawable.sb_t09,
        R.drawable.sb_t10, R.drawable.sb_t11, R.drawable.sb_t12, R.drawable.sb_t13, R.drawable.sb_t14,
        R.drawable.sb_t15, R.drawable.sb_t16, R.drawable.sb_t17, R.drawable.sb_t18, R.drawable.sb_t19,
        R.drawable.sb_t20, R.drawable.sb_t21, R.drawable.sb_t22, R.drawable.sb_t23, R.drawable.sb_t24,
        R.drawable.sb_t25, R.drawable.sb_t26, R.drawable.sb_t27, R.drawable.sb_t28, R.drawable.sb_t29,
        R.drawable.sb_t30, R.drawable.sb_t31, R.drawable.sb_t32, R.drawable.sb_t33, R.drawable.sb_t34,
        R.drawable.sb_t35, R.drawable.sb_t36,
    )
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
