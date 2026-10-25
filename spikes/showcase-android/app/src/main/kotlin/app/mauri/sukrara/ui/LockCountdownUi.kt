// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.mauri.sukrara.R
import app.mauri.sukrara.treatments.LockCountdown

/**
 * Lock mode of the quick treatment dialog (round 52), shared from [QuickTreatmentActivity] with the dialog and its
 * sub-dialogs: [countdown] is null when the dialog isn't over the lock screen (or was unlocked). The activity ticks
 * [now] every frame, calls [onUnlock] for *Unlock*, and reads [hasInput] (written by the dialog) for the warning tick
 * and the *Treatment not saved* toast.
 */
class LockSession {
    var countdown by mutableStateOf<LockCountdown?>(null)
    var now by mutableLongStateOf(0L)
    /** Bumps at the 5 s warning: the ring pulses once. */
    var warnings by mutableIntStateOf(0)
    /** Bumps when an unlock ended lock mode: the dialog gives the focused field its keyboard back. */
    var unlocks by mutableIntStateOf(0)
    /** TalkBack's one *Closes in 10 seconds*. */
    var announce by mutableStateOf(false)
    /** Timed out: the dialog plays its exit, then the activity finishes. */
    var closing by mutableStateOf(false)
    @Volatile var hasInput = false
    var onUnlock: () -> Unit = {}
}

val LocalLockSession = staticCompositionLocalOf<LockSession?> { null }

/**
 * *Cancel* of the treatment dialog and its sub-dialogs. In lock mode the countdown ring is its leading icon (it does
 * what the timeout does: closes without saving), and TalkBack hears *Closes in N seconds* as its state. After an
 * unlock the ring shrinks and fades away and the button narrows.
 */
@Composable
fun CountdownCancel(text: String, onClick: () -> Unit) {
    val s = LocalLockSession.current
    val cd = s?.countdown
    // 5 s steps: recomposes four times in 20 s, not every frame
    val seconds by remember(cd) { androidx.compose.runtime.derivedStateOf { if (cd != null && s != null) cd.spokenSeconds(s.now) else null } }
    val state = seconds?.let { n -> pluralStringResource(R.plurals.lock_closes_in, n, n) }
    TextButton(onClick = onClick, modifier = Modifier.animateContentSize().semantics { if (state != null) stateDescription = state }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AnimatedVisibility(cd != null, enter = fadeIn(tween(0)),
                exit = fadeOut(tween(200)) + scaleOut(tween(200)) + shrinkHorizontally(tween(200))) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    s?.let { CountdownRing(it) }
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                }
            }
            Text(text)
        }
    }
}

/**
 * The 24 dp ring with the seconds left inside: full at the start, emptying smoothly every frame, primary on its track
 * (red is for errors only). It fills in with the dialog's enter (300 ms) and pulses once (1 → 1.2 → 1) at 5 s.
 * *Remove animations*: it steps once per second, no fill-in, no pulse. The digits were the user's call (2026-10-25:
 * "I would like to see the seconds count"; the spec had no digits); they are hidden from TalkBack, which hears the
 * calmer 5 s steps on Cancel instead.
 */
@Composable
private fun CountdownRing(s: LockSession) {
    val ctx = LocalContext.current
    val reduced = remember { app.mauri.sukrara.pen.PenHaptics.reducedMotion(ctx) }
    // keeps drawing the last countdown while the ring leaves after an unlock
    val last = remember { arrayOfNulls<LockCountdown>(1) }
    s.countdown?.let { last[0] = it }
    val cd = last[0] ?: return
    val fill = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(Unit) { if (!reduced) fill.animateTo(1f, tween(300)) }
    val pulse = remember { Animatable(1f) }
    LaunchedEffect(s.warnings) {
        if (s.warnings > 0 && !reduced) {
            pulse.animateTo(1.2f, spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMedium))
            pulse.animateTo(1f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium))
        }
    }
    // read in the draw phase: every frame redraws the ring without recomposing the dialog
    // a fixed size (dp, not sp): the number has to fit inside the ring; TalkBack and the ring carry the meaning
    val digits = with(androidx.compose.ui.platform.LocalDensity.current) { 10.dp.toSp() }
    val secs by remember(cd) { androidx.compose.runtime.derivedStateOf { cd.wholeSeconds(s.now) } }
    Box(Modifier.size(24.dp).graphicsLayer { scaleX = pulse.value; scaleY = pulse.value }.clearAndSetSemantics { },
        contentAlignment = Alignment.Center) {
        CircularProgressIndicator(progress = { (if (cd.stopped) 1f else cd.fraction(s.now, stepped = reduced)) * fill.value },
            modifier = Modifier.size(24.dp),
            color = MaterialTheme.colorScheme.primary, trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.24f),
            strokeWidth = 2.dp, strokeCap = StrokeCap.Round, gapSize = 0.dp)
        // the number that counts down; tabular figures so 10 → 9 doesn't jump sideways
        Text("$secs", style = MaterialTheme.typography.labelSmall.copy(fontSize = digits, lineHeight = digits,
            fontFeatureSettings = "tnum", fontWeight = FontWeight.Medium), color = MaterialTheme.colorScheme.primary, maxLines = 1)
    }
}

/**
 * The dialog's action row: [start] (*Open Sukrara* / *Unlock*) at the start, [end] (*Cancel*, *Save*) at the end. When
 * they don't fit on one line, [start] wraps to its own start-aligned line above (M3: dialog buttons that don't fit
 * stack; a button is never ellipsized).
 */
@Composable
fun DialogActionRow(start: (@Composable () -> Unit)?, end: @Composable () -> Unit) {
    Layout(contents = listOf(start ?: {}, { Row(verticalAlignment = Alignment.CenterVertically) { end() } })) { (sm, em), c ->
        val loose = c.copy(minWidth = 0, minHeight = 0)
        val e = em.first().measure(loose)
        val st = sm.map { it.measure(loose) }
        val sw = st.maxOfOrNull { it.width } ?: 0
        val sh = st.maxOfOrNull { it.height } ?: 0
        val gap = 8.dp.roundToPx()
        val w = if (c.hasBoundedWidth) c.maxWidth else sw + gap + e.width
        if (st.isEmpty() || sw + gap + e.width <= w) {
            val h = maxOf(sh, e.height)
            layout(w, h) {
                st.forEach { it.place(0, (h - it.height) / 2) }
                e.place(w - e.width, (h - e.height) / 2)
            }
        } else layout(w, sh + e.height) {
            st.forEach { it.place(0, 0) }
            e.place(w - e.width, sh)
        }
    }
}
