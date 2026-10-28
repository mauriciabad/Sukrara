// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.designsystem.motion

import android.provider.Settings
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext

/**
 * M3 Expressive motion springs (androidx material3 `ExpressiveMotionTokens` values): spatial springs (position, size,
 * rotation) have a hint of bounce, effects springs (alpha, colour) don't. All settle in well under 300 ms.
 * Every spec snaps when [reduced] is true, so callers pass `LocalReducedMotion.current` and need no branch.
 */
object Motion {
    fun <T> defaultSpatial(reduced: Boolean = false): FiniteAnimationSpec<T> = orSnap(reduced) { spring(0.8f, 380f) }

    fun <T> fastSpatial(reduced: Boolean = false): FiniteAnimationSpec<T> = orSnap(reduced) { spring(0.6f, 800f) }

    fun <T> defaultEffects(reduced: Boolean = false): FiniteAnimationSpec<T> = orSnap(reduced) { spring(1f, 1600f) }

    fun <T> fastEffects(reduced: Boolean = false): FiniteAnimationSpec<T> = orSnap(reduced) { spring(1f, 3800f) }

    private inline fun <T> orSnap(
        reduced: Boolean,
        spec: () -> FiniteAnimationSpec<T>,
    ): FiniteAnimationSpec<T> = if (reduced) snap() else spec()

    /** M3 fade through: for screens without a parent/child relation (role choice → setup → home). */
    fun fadeThrough(reduced: Boolean = false): ContentTransform {
        if (reduced) return fadeIn(snap()) togetherWith fadeOut(snap())
        val enter = tween<Float>(210, delayMillis = FADE_OUT_MS, easing = EmphasizedDecelerate)
        return (fadeIn(enter) + scaleIn(enter, initialScale = 0.92f)) togetherWith
            fadeOut(tween(FADE_OUT_MS, easing = LinearEasing))
    }

    private val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    private const val FADE_OUT_MS = 90
}

/**
 * True when the person turned animations off ("Remove animations" sets the animator duration scale to 0). Compose
 * already snaps its frame-clock animations then; this flag is for the things we choose not to do at all (counting
 * numbers, bouncing arrows) and is provided by `SukraraTheme`.
 */
val LocalReducedMotion = staticCompositionLocalOf { false }

/** Whether an animator duration scale means "no animations". */
fun isReducedMotion(animatorDurationScale: Float): Boolean = animatorDurationScale == 0f

/** Reads the system animator duration scale once per composition entry (it rarely changes while a screen is open). */
@Composable
fun rememberReducedMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return remember(resolver) {
        isReducedMotion(Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f))
    }
}
