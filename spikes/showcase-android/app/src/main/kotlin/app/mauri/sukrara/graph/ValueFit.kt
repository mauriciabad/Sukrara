// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.graph

/**
 * The app panel's header: the value (number + arrow) left, the slots (rings) right. The slots keep their designed
 * size ([SLOT_DP]); when the row is short the value shrinks first, down to [MIN_SCALE], and only then the
 * lowest-priority slot goes ([Slots.arrange] picks which). The widgets, the lock screen and the notification
 * ([GlucoseCard]) and the Mac do the same: their rings are sized from the card's height, the number fits the rest.
 */
object ValueFit {
    const val SLOT_DP = 68f
    const val SLOT_GAP_DP = 8f
    const val NUMBER_SP = 72f
    const val ARROW_GAP_DP = 8f
    const val ARROW_DP = 56f
    /** The smallest value before a slot is dropped (54 sp). */
    const val MIN_SCALE = 0.75f
    /** The value is sized for at least this wide a number, so it doesn't change size from 99 to 188. */
    const val REFERENCE = "388"

    data class Fit(val slots: Int, val scale: Float)

    /**
     * [widthDp] = the row's width, [numberDp] = the number's width at [NUMBER_SP] (the wider of the shown value and
     * [REFERENCE]), [wanted] = the slots there are data for. At least one slot stays (the main ring) when there's one;
     * then the value takes whatever scale fits.
     */
    fun of(widthDp: Float, numberDp: Float, wanted: Int): Fit {
        val value = numberDp + ARROW_GAP_DP + ARROW_DP
        val most = wanted.coerceIn(0, 3)
        for (n in most downTo 0) {
            val room = widthDp - n * (SLOT_DP + SLOT_GAP_DP)
            val scale = minOf(1f, room / value)
            if (scale >= MIN_SCALE || n <= 1) return Fit(n, scale.coerceAtLeast(0.2f))
        }
        return Fit(0, 1f)
    }

    /** The value's width at [scale] (dp). */
    fun valueDp(numberDp: Float, scale: Float) = (numberDp + ARROW_GAP_DP + ARROW_DP) * scale
}
