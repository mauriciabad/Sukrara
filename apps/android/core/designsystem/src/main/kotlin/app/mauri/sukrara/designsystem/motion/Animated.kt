// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.designsystem.motion

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle

/** An optional part of a column: fades and expands in, fades and shrinks out (snaps with reduced motion). */
@Composable
fun Reveal(
    visible: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val r = LocalReducedMotion.current
    AnimatedVisibility(
        visible,
        modifier,
        enter = fadeIn(Motion.defaultEffects(r)) + expandVertically(Motion.defaultSpatial(r)),
        exit = fadeOut(Motion.fastEffects(r)) + shrinkVertically(Motion.defaultSpatial(r)),
    ) {
        Column { content() }
    }
}

/** Cross-fades between contents (a status label, a section that changes with a permission). */
@Composable
fun <S> Swap(
    state: S,
    modifier: Modifier = Modifier,
    content: @Composable AnimatedContentScope.(S) -> Unit,
) {
    val r = LocalReducedMotion.current
    AnimatedContent(
        state,
        modifier,
        transitionSpec = {
            fadeIn(Motion.defaultEffects(r)) togetherWith fadeOut(Motion.fastEffects(r)) using
                SizeTransform(clip = false) { _, _ -> Motion.defaultSpatial(r) }
        },
        label = "swap",
        content = content,
    )
}

/**
 * Rolling digits for a value the person changes (a stepper, a dose): each changed character slides out and its new
 * value in — up when the number rises, down when it falls; positions count from the right so 99 → 100 rolls right.
 * With reduced motion the text simply changes. Passive glucose updates don't use this (the card crossfades only).
 */
@Composable
fun RollingNumber(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
) {
    if (LocalReducedMotion.current) {
        Text(text, modifier, style = style)
        return
    }
    val value = text.toIntOrNull()
    val dir = remember { RollDirection(value) }
    val up = dir.directionTo(value)
    SideEffect { dir.last = value }
    Row(modifier.clipToBounds().clearAndSetSemantics { contentDescription = text }) {
        text.forEachIndexed { i, ch ->
            key(text.length - i) {
                AnimatedContent(ch, transitionSpec = { digitRoll(up) }, label = "digit") { c ->
                    Text(c.toString(), style = style)
                }
            }
        }
    }
}

/** The last committed value and direction, so recompositions without a new value keep rolling the same way. */
private class RollDirection(
    var last: Int?,
) {
    private var up = true

    /** The direction towards [value] (unchanged when there's nothing to compare). */
    fun directionTo(value: Int?): Boolean {
        val previous = last
        if (value != null && previous != null && value != previous) up = value > previous
        return up
    }
}

private fun AnimatedContentTransitionScope<Char>.digitRoll(up: Boolean): ContentTransform {
    val dir = if (up) 1 else -1
    return (slideInVertically(Motion.defaultSpatial()) { it * dir } + fadeIn(Motion.defaultEffects())) togetherWith
        (slideOutVertically(Motion.defaultSpatial()) { -it * dir } + fadeOut(Motion.fastEffects())) using
        SizeTransform(clip = false) { _, _ -> Motion.defaultSpatial() }
}
