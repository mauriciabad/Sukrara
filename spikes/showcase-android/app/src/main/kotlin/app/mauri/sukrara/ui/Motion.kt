// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.NavigationEventTransitionState
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import kotlinx.coroutines.launch

/*
 * Motion for the whole app (Material 3 motion, Android 16+ conventions):
 *  - screens: shared axis X forward, a predictive-back "peek" (the screen shrinks off to reveal the one below) back,
 *    fade-through between unrelated screens (role / onboarding / follower status);
 *  - live data: rolling digits, smoothly rotating arrow, colour cross-fades, new chart points growing in;
 *  - lists and optional sections: fade + expand / shrink.
 * Every animation runs on Compose's frame clock, which scales durations by the system "Animator duration scale"
 * (Developer options → Remove animations = 0 → everything snaps) and pauses while the activity is stopped, so nothing
 * animates in the background and nothing loops: each animation runs once per state change.
 */

/**
 * M3 Expressive motion springs (the values of androidx material3 `ExpressiveMotionTokens`). Material3 1.4's
 * `MotionScheme` / `MaterialTheme.motionScheme` are still internal, so the app keeps its own copy: spatial springs
 * (position, size, rotation) have a hint of bounce, effects springs (alpha, colour) don't.
 */
object Motion {
    fun <T> defaultSpatialSpec(): FiniteAnimationSpec<T> = spring(0.8f, 380f)
    fun <T> fastSpatialSpec(): FiniteAnimationSpec<T> = spring(0.6f, 800f)
    fun <T> slowSpatialSpec(): FiniteAnimationSpec<T> = spring(0.8f, 200f)
    fun <T> defaultEffectsSpec(): FiniteAnimationSpec<T> = spring(1f, 1600f)
    fun <T> fastEffectsSpec(): FiniteAnimationSpec<T> = spring(1f, 3800f)
    fun <T> slowEffectsSpec(): FiniteAnimationSpec<T> = spring(1f, 800f)
}

// M3 emphasized easing (enter decelerates, exit accelerates); durations per the M3 transition patterns.
private val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
private val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
private const val NAV_MS = 300
private const val FADE_OUT_MS = 90

/** Shared axis X (M3): forward = new screen slides in from the end, back = the reverse. */
fun sharedAxisX(forward: Boolean, density: Float): ContentTransform {
    val shift = (30 * density).toInt() * if (forward) 1 else -1
    return (slideInHorizontally(tween(NAV_MS, easing = EmphasizedDecelerate)) { shift } +
        fadeIn(tween(NAV_MS - FADE_OUT_MS, delayMillis = FADE_OUT_MS, easing = LinearEasing))) togetherWith
        (slideOutHorizontally(tween(NAV_MS, easing = EmphasizedAccelerate)) { -shift } +
            fadeOut(tween(FADE_OUT_MS, easing = LinearEasing)))
}

/**
 * Back to the parent screen. The leaving screen stays on top and shrinks to 90 % with rounded corners (see [Page]),
 * revealing the parent sliding in behind it — the system predictive-back look. While the back gesture is being
 * dragged ([gesture]) everything is linear so the screen follows the finger exactly.
 */
fun popTransition(gesture: Boolean, density: Float): ContentTransform {
    val easeIn = if (gesture) LinearEasing else EmphasizedDecelerate
    val easeOut = if (gesture) LinearEasing else EmphasizedAccelerate
    val shift = (30 * density).toInt()
    return ContentTransform(
        targetContentEnter = slideInHorizontally(tween(NAV_MS, easing = easeIn)) { -shift } + fadeIn(tween(NAV_MS, easing = easeIn)),
        initialContentExit = scaleOut(tween(NAV_MS, easing = easeOut), targetScale = 0.9f) +
            slideOutHorizontally(tween(NAV_MS, easing = easeOut)) { shift } +
            fadeOut(tween(NAV_MS / 2, delayMillis = NAV_MS / 2, easing = LinearEasing)),
        targetContentZIndex = -1f,
    )
}

/** Fade through (M3): for screens without a parent/child relation (role choice → onboarding → home). */
fun fadeThrough(): ContentTransform =
    (fadeIn(tween(210, delayMillis = FADE_OUT_MS, easing = EmphasizedDecelerate)) +
        scaleIn(tween(210, delayMillis = FADE_OUT_MS, easing = EmphasizedDecelerate), initialScale = 0.92f)) togetherWith
        fadeOut(tween(FADE_OUT_MS, easing = LinearEasing))

private val PageCorner = 28.dp

/**
 * One full screen: opaque background (so a screen shrinking during back covers the one below cleanly), edge-to-edge
 * safe area, its own scroll position. Inside an [AnimatedContentScope] the corners round while the page leaves.
 */
@Composable
fun AnimatedContentScope.Page(scroll: ScrollState = rememberScrollState(), content: @Composable ColumnScope.() -> Unit) {
    val corner by transition.animateDp(label = "corner") { if (it == EnterExitState.PostExit) PageCorner else 0.dp }
    PageColumn(scroll, corner, content)
}

@Composable
private fun PageColumn(scroll: ScrollState, corner: Dp, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().clip(RoundedCornerShape(corner)).background(MaterialTheme.colorScheme.background)
        .safeDrawingPadding().verticalScroll(scroll).padding(16.dp), content = content)
}

/**
 * Parent [home] + child screens with predictive back: the system back gesture seeks the transition to [home]
 * (NavigationEvent API, what Navigation 3 builds on); releasing completes it, cancelling springs back.
 */
@Composable
fun ScreenHost(screen: String, home: String, onBack: () -> Unit, content: @Composable AnimatedContentScope.(String) -> Unit) {
    val seekable = remember { SeekableTransitionState(screen) }
    val transition = rememberTransition(seekable, label = "screen")
    val scope = rememberCoroutineScope()
    var gesture by remember { mutableStateOf(false) }
    val nav = rememberNavigationEventState(NavigationEventInfo.None)
    NavigationBackHandler(nav, isBackEnabled = screen != home,
        onBackCancelled = { scope.launch { seekable.animateTo(screen); gesture = false } },
        onBackCompleted = onBack)
    val ts = nav.transitionState
    LaunchedEffect(ts) {
        if (ts is NavigationEventTransitionState.InProgress && screen != home) {
            gesture = true
            seekable.seekTo(ts.latestEvent.progress, targetState = home)
        }
    }
    LaunchedEffect(screen) {
        if (seekable.currentState != screen || seekable.targetState != screen) seekable.animateTo(screen)
        gesture = false
    }
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    transition.AnimatedContent(
        transitionSpec = { if (targetState == home) popTransition(gesture, density) else sharedAxisX(true, density) },
        contentAlignment = Alignment.TopStart,
    ) { content(it) }
}

/** Optional part of a column: fades and expands in, fades and shrinks out. */
@Composable
fun Reveal(visible: Boolean, content: @Composable ColumnScope.() -> Unit) {
    val m = Motion
    AnimatedVisibility(visible, enter = fadeIn(m.defaultEffectsSpec()) + expandVertically(m.defaultSpatialSpec()),
        exit = fadeOut(m.fastEffectsSpec()) + shrinkVertically(m.defaultSpatialSpec())) {
        Column { content() }
    }
}

/** [Reveal] for an optional value (busy / error text): keeps showing the last value while it leaves; changes cross-fade. */
@Composable
fun <T : Any> RevealValue(value: T?, content: @Composable (T) -> Unit) {
    val last = remember { arrayOfNulls<Any>(1) }
    if (value != null) last[0] = value
    Reveal(value != null) {
        @Suppress("UNCHECKED_CAST")
        (last[0] as T?)?.let { Swap(it) { v -> content(v) } }
    }
}

/** Cross-fades between two contents (e.g. a status label or a section that changes with a permission). */
@Composable
fun <S> Swap(state: S, modifier: Modifier = Modifier, content: @Composable AnimatedContentScope.(S) -> Unit) {
    val m = Motion
    AnimatedContent(state, modifier, transitionSpec = {
        fadeIn(m.defaultEffectsSpec()) togetherWith fadeOut(m.fastEffectsSpec()) using SizeTransform(clip = false) { _, _ -> m.defaultSpatialSpec() }
    }, label = "swap", content = content)
}

private class ListMemory<T> {
    var initial = true
    var last: List<Pair<Any, T>> = emptyList()
    val exiting = LinkedHashMap<Any, Pair<Int, T>>()   // key → (old index, last value)
    val tick = mutableIntStateOf(0)
}

/**
 * A short keyed list inside a scrolling column (not a LazyColumn, so `Modifier.animateItem` doesn't apply): rows that
 * appear fade + expand in, removed rows stay until they've faded + shrunk out. Rows present on first show don't animate.
 */
@Composable
fun <T> AnimatedItems(items: List<T>, key: (T) -> Any, content: @Composable (T) -> Unit) {
    val mem = remember { ListMemory<T>() }
    mem.tick.intValue
    val now = items.map { key(it) to it }
    val keys = now.mapTo(HashSet()) { it.first }
    mem.last.forEachIndexed { i, (k, v) -> if (k !in keys && k !in mem.exiting) mem.exiting[k] = i to v }
    mem.last = now
    val rows = now.toMutableList()
    for ((k, p) in mem.exiting.entries.toList().sortedBy { it.value.first }) {
        if (k in keys) mem.exiting.remove(k) else rows.add(p.first.coerceAtMost(rows.size), k to p.second)
    }
    val m = Motion
    val initial = mem.initial
    for ((k, v) in rows) key(k) {
        val st = remember { MutableTransitionState(initial) }
        st.targetState = k in keys
        AnimatedVisibility(st, enter = fadeIn(m.defaultEffectsSpec()) + expandVertically(m.defaultSpatialSpec()),
            exit = fadeOut(m.fastEffectsSpec()) + shrinkVertically(m.defaultSpatialSpec())) { content(v) }
        if (!st.targetState && !st.currentState && st.isIdle) LaunchedEffect(Unit) { mem.exiting.remove(k); mem.tick.intValue++ }
    }
    SideEffect { mem.initial = false }
}

/**
 * Rolling digits: each changed character slides out and its new value slides in — upwards when the number rises,
 * downwards when it falls; unchanged digits stay still. Positions count from the right so 99 → 100 rolls correctly.
 */
@Composable
fun RollingNumber(text: String, color: Color, fontSize: TextUnit, fontWeight: FontWeight) {
    val value = text.toIntOrNull()
    // direction of the last change; a plain holder so recompositions without a new value (the "ago" tick) keep it
    val dir = remember { RollDirection(value) }
    if (value != dir.last) {
        val last = dir.last
        if (value != null && last != null) dir.up = value > last
        dir.last = value
    }
    val up = dir.up
    val m = Motion
    Row(Modifier.clipToBounds()) {
        text.forEachIndexed { i, ch ->
            key(text.length - i) {
                AnimatedContent(ch, transitionSpec = { digitRoll(up, m) }, label = "digit") { c ->
                    Text(c.toString(), color = color, fontSize = fontSize, fontWeight = fontWeight)
                }
            }
        }
    }
}

private class RollDirection(var last: Int?) { var up = true }

private fun AnimatedContentTransitionScope<Char>.digitRoll(up: Boolean, m: Motion): ContentTransform {
    val spatial = m.defaultSpatialSpec<IntOffset>()
    val dir = if (up) 1 else -1
    return (slideInVertically(spatial) { it * dir } + fadeIn(m.defaultEffectsSpec())) togetherWith
        (slideOutVertically(spatial) { -it * dir } + fadeOut(m.fastEffectsSpec())) using
        SizeTransform(clip = false) { _, _ -> m.defaultSpatialSpec() }
}
