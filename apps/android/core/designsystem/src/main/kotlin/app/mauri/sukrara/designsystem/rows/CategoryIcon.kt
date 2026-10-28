// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.designsystem.rows

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp

/**
 * The hue of a settings overview row's icon (Android Settings' homepage categories): the glyph in the hue's tone 30
 * on a circle in tone 90 (tone 80 in the dark theme), from the Material reference palettes.
 */
enum class Category(
    internal val fg: Long,
    internal val light: Long,
    internal val dark: Long,
) {
    Red(0xFF8C1D18, 0xFFF9DEDC, 0xFFF2B8B5),
    Orange(0xFF7A3000, 0xFFFFDBCA, 0xFFFFB68F),
    Yellow(0xFF574500, 0xFFFFE088, 0xFFE9C349),
    Green(0xFF0F5223, 0xFFB7F1C0, 0xFF9CD4A6),
    Teal(0xFF004F58, 0xFFA6EEFF, 0xFF7DD3E5),
    Blue(0xFF004A77, 0xFFD1E4FF, 0xFF9FCAFF),
    Indigo(0xFF353E86, 0xFFDFE0FF, 0xFFBAC3FF),
    Purple(0xFF5A3489, 0xFFEEDCFF, 0xFFD8B9FF),
    Grey(0xFF474747, 0xFFE3E3E3, 0xFFC7C7C7),
}

/** A row's leading category icon: [icon] (24 dp) on a 40 dp circle in [category]'s hue. Decorative (the title says it). */
@Composable
fun CategoryIcon(
    @DrawableRes icon: Int,
    category: Category,
    modifier: Modifier = Modifier,
) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    Box(
        modifier.size(40.dp).clip(CircleShape).background(Color(if (dark) category.dark else category.light)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = Color(category.fg), modifier = Modifier.size(24.dp))
    }
}
