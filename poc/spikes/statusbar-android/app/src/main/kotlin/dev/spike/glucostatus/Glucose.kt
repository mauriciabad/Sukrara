// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.spike.glucostatus

import android.graphics.Color
import kotlin.math.abs

/** One sensor reading. [rate] is mg/dL per minute, null when the source has no trend. */
data class Reading(val epochSec: Long, val mgdl: Int, val rate: Double?) {
    val ageSec get() = System.currentTimeMillis() / 1000 - epochSec
    val stale get() = ageSec > STALE_AFTER_SEC

    companion object {
        const val STALE_AFTER_SEC = 5 * 60
    }
}

/** Same mapping as the Mac (spikes/menubar TrendArrow.swift): 0 → flat, ±3 mg/dL/min → vertical. */
object Trend {
    const val FULL_SCALE = 3.0

    /** Screen-space angle in degrees: -90 = straight up (rising fast), 0 = flat, +90 = down. */
    fun angleDeg(rate: Double): Float = (-(rate / FULL_SCALE).coerceIn(-1.0, 1.0) * 90).toFloat()

    fun isDouble(rate: Double) = abs(rate) >= FULL_SCALE

    /** For text-only surfaces (chip, notification title): Libre's buckets plus double arrows. */
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
}

/** The user's personal thresholds (same as the Mac app). */
object Range {
    const val LOW = 65
    const val SLIGHTLY_HIGH = 145
    const val HIGH = 185

    /** null = in range, draw with the surface's default colour. */
    fun color(mgdl: Int): Int? = when {
        mgdl < LOW || mgdl > HIGH -> Color.rgb(0xFF, 0x45, 0x3A)
        mgdl > SLIGHTLY_HIGH -> Color.rgb(0xFF, 0x9F, 0x0A)
        else -> null
    }
}

fun Reading.text() = "$mgdl${Trend.arrow(rate)}"
