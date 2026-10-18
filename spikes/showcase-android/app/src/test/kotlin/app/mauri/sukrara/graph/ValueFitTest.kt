// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.graph

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Font
import java.awt.font.FontRenderContext
import java.io.File

/**
 * [ValueFit] with the real face's digit widths (the bundled Google Sans Flex, proportional figures): at the user's
 * phone (347 dp inside the card) and the narrowest supported phone (296 dp) the widest value fits beside full-size
 * slots, and the slots that are kept never need more than the row. ValueRowTest checks the drawn slot size.
 */
class ValueFitTest {
    private val font = Font.createFont(Font.TRUETYPE_FONT, File("src/main/assets/fonts/GoogleSansFlexRounded-700.ttf"))
        .deriveFont(ValueFit.NUMBER_SP)
    private val frc = FontRenderContext(null, true, true)
    /** Each digit on its own, as RollingNumber draws them (font scale 1: sp = dp). */
    private fun numberDp(s: String, fontScale: Float = 1f) =
        s.sumOf { font.getStringBounds(it.toString(), frc).width } .toFloat() * fontScale

    @Test fun widestValueKeepsTheSlots() {
        for (fontScale in listOf(1f, 1.3f, 2f)) for (w in listOf(296f, 347f, 411f)) for (s in listOf("188", "388", "400", "68")) {
            val n = maxOf(numberDp(s, fontScale), numberDp(ValueFit.REFERENCE, fontScale))
            val f = ValueFit.of(w, n, 3)
            assertTrue("$w dp $s ×$fontScale: a slot", f.slots >= 1)
            assertTrue("$w dp $s ×$fontScale: fits", ValueFit.valueDp(n, f.scale) + f.slots * (ValueFit.SLOT_DP + ValueFit.SLOT_GAP_DP) <= w + 0.01f)
        }
        // the user's phone keeps two slots and the full-size value; the narrowest phone two slots, a slightly smaller value
        val n = numberDp("388")
        assertEquals(ValueFit.Fit(2, 1f), ValueFit.of(347f, n, 3))
        ValueFit.of(296f, n, 3).let { assertEquals(2, it.slots); assertTrue(it.scale >= ValueFit.MIN_SCALE) }
    }

    @Test fun proportionalFigures() {
        // "1" is narrower than "8": no tabular gaps (the user: the big numbers' letter spacing was too wide)
        assertTrue(numberDp("1") < numberDp("8") * 0.8f)
    }
}
