// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.designsystem.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import app.mauri.sukrara.designsystem.motion.LocalReducedMotion
import app.mauri.sukrara.designsystem.motion.rememberReducedMotion

/**
 * The app's theme: dark-first (the person checks glucose at night; nothing white lights up the room), the device's
 * dynamic colours when [dynamic], else Sukrara's own true-dark palette. Dynamic dark keeps its tonal surfaces but
 * the background goes near black. Also provides [LocalReducedMotion] for every animation below it.
 */
@Composable
fun SukraraTheme(
    dark: Boolean = isSystemInDarkTheme(),
    dynamic: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val scheme =
        when {
            dynamic && dark -> dynamicDarkColorScheme(context).trueDark()
            dynamic -> dynamicLightColorScheme(context)
            dark -> SukraraDark
            else -> SukraraLight
        }
    CompositionLocalProvider(LocalReducedMotion provides rememberReducedMotion()) {
        MaterialTheme(colorScheme = scheme, typography = SukraraTypography, content = content)
    }
}

/** Near-black background and lowest surface so an OLED screen stays dark; the tonal surfaces above it stay. */
internal fun ColorScheme.trueDark(): ColorScheme =
    copy(background = NearBlack, surface = NearBlack, surfaceContainerLowest = Color.Black)

private val NearBlack = Color(0xFF0B0C0E)

/** Sukrara's own dark palette (no dynamic colours): text on its surfaces ≥ 4.5:1 (ThemeContrastTest). */
internal val SukraraDark =
    darkColorScheme(
        primary = Color(0xFF8FCDFF),
        onPrimary = Color(0xFF00344F),
        primaryContainer = Color(0xFF004B70),
        onPrimaryContainer = Color(0xFFCAE6FF),
        secondary = Color(0xFFB7C9D9),
        onSecondary = Color(0xFF22323F),
        secondaryContainer = Color(0xFF384956),
        onSecondaryContainer = Color(0xFFD3E5F5),
        error = Color(0xFFFFB4AB),
        onError = Color(0xFF690005),
        errorContainer = Color(0xFF93000A),
        onErrorContainer = Color(0xFFFFDAD6),
        background = NearBlack,
        onBackground = Color(0xFFE2E2E5),
        surface = NearBlack,
        onSurface = Color(0xFFE2E2E5),
        surfaceVariant = Color(0xFF41474D),
        onSurfaceVariant = Color(0xFFC1C7CE),
        outline = Color(0xFF8B9198),
        outlineVariant = Color(0xFF41474D),
        surfaceContainerLowest = Color.Black,
        surfaceContainerLow = Color(0xFF15171A),
        surfaceContainer = Color(0xFF1A1C1F),
        surfaceContainerHigh = Color(0xFF24272A),
        surfaceContainerHighest = Color(0xFF2F3235),
        surfaceBright = Color(0xFF2C2F33),
    )

/** Sukrara's own light palette (used when the person picks light and dynamic colours are off). */
internal val SukraraLight =
    lightColorScheme(
        primary = Color(0xFF00629B),
        onPrimary = Color.White,
        primaryContainer = Color(0xFFCAE6FF),
        onPrimaryContainer = Color(0xFF001E31),
        secondary = Color(0xFF50606E),
        onSecondary = Color.White,
        secondaryContainer = Color(0xFFD3E5F5),
        onSecondaryContainer = Color(0xFF0C1D29),
        error = Color(0xFFBA1A1A),
        onError = Color.White,
        background = Color(0xFFF8F9FC),
        onBackground = Color(0xFF191C1E),
        surface = Color(0xFFF8F9FC),
        onSurface = Color(0xFF191C1E),
        surfaceVariant = Color(0xFFDDE3EA),
        onSurfaceVariant = Color(0xFF41474D),
        outline = Color(0xFF71787E),
        surfaceBright = Color.White,
        surfaceContainerLow = Color(0xFFF2F3F6),
        surfaceContainer = Color(0xFFECEEF1),
        surfaceContainerHigh = Color(0xFFE6E8EB),
    )
