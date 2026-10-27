// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.designsystem.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test

/** WCAG contrast of Sukrara's own palettes: body text ≥ 4.5:1 on every surface it sits on. */
class ThemeContrastTest {
    private fun ratio(
        a: Color,
        b: Color,
    ): Double {
        val (hi, lo) = listOf(a.luminance() + 0.05, b.luminance() + 0.05).sortedDescending()
        return (hi / lo).toDouble()
    }

    private fun check(s: ColorScheme) {
        val pairs =
            listOf(
                s.onSurface to s.surface,
                s.onSurface to s.surfaceBright,
                s.onSurface to s.surfaceContainerHigh,
                s.onSurfaceVariant to s.surfaceBright,
                s.onSurfaceVariant to s.surface,
                s.primary to s.surface,
                s.primary to s.surfaceBright,
                s.error to s.surfaceBright,
                s.onPrimary to s.primary,
                s.onSecondaryContainer to s.secondaryContainer,
                // safety banners (BannerSeverity.Safety): words on the container, the filled action button
                s.onErrorContainer to s.errorContainer,
                s.onError to s.error,
            )
        for ((fg, bg) in pairs) assertTrue("$fg on $bg = ${ratio(fg, bg)}", ratio(fg, bg) >= 4.5)
    }

    @Test fun `dark palette text contrast`() = check(SukraraDark)

    @Test fun `light palette text contrast`() = check(SukraraLight)

    @Test fun `true dark background is near black`() {
        assertTrue(SukraraDark.background.luminance() < 0.01f)
    }
}
