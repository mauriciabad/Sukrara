// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.graph

import androidx.core.graphics.ColorUtils
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * WCAG 1.4.3: the words on the card's pills ("Eat carbs", "9m left", "Replace sensor") reach 4.5:1 in both themes, on
 * the card and on the app's light background (Mac: `-graphselftest` pill contrast). The person reads them during a low.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PillContrastTest {
    private val kinds = listOf("rapid", "long", "carbs", "note")

    /** What the pill sits on: the card, and the app's light background `#F7F8FC` behind a transparent card. */
    private fun grounds(dark: Boolean) = if (dark) listOf(GraphPalette.of(true).bg, 0xFF0B0C0E.toInt()) else listOf(GraphPalette.of(false).bg, 0xFFF7F8FC.toInt())

    private fun check(dark: Boolean) {
        val p = TreatmentMarks.palette(dark)
        for (k in kinds) {
            val c = TreatmentMarks.color(p, k)
            val filled = ColorUtils.calculateContrast(TreatmentMarks.onColor(dark), c)
            assertTrue("$k filled pill (dark=$dark): $filled", filled >= 4.5)
            for (ground in grounds(dark)) {
                val tint = ColorUtils.compositeColors(TreatmentMarks.tonalTint(c, dark), ground)
                val tonal = ColorUtils.calculateContrast(TreatmentMarks.tonalInk(k, dark), tint)
                assertTrue("$k tonal pill on ${Integer.toHexString(ground)} (dark=$dark): $tonal", tonal >= 4.5)
            }
        }
    }

    @Test fun `light pills reach 4_5 to 1`() = check(dark = false)

    @Test fun `dark pills reach 4_5 to 1`() = check(dark = true)
}
