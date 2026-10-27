// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.designsystem.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontWeight

/**
 * M3's type scale on the device font (the person's font and size settings apply: sizes are `sp`, so the largest
 * font scale grows every text). Titles a step bolder so headers read at a glance.
 */
internal val SukraraTypography: Typography =
    Typography().run {
        copy(
            titleLarge = titleLarge.copy(fontWeight = FontWeight.SemiBold),
            titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold),
            labelLarge = labelLarge.copy(fontWeight = FontWeight.SemiBold),
        )
    }
