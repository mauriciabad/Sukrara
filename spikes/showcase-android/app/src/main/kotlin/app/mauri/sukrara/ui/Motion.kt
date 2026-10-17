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
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.PathEasing
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
import androidx.compose.ui.graphics.graphicsLayer
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
 *  - screens: the system's own activity open / close and predictive back motion ([ScreenHost]), fade-through
 *    between unrelated screens (role / onboarding / follower status);
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

// M3 emphasized easing (enter decelerates, exit accelerates), for fade through.
private val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
private const val FADE_OUT_MS = 90

/** Fade through (M3): for screens without a parent/child relation (role choice → onboarding → home). */
fun fadeThrough(): ContentTransform =
    (fadeIn(tween(210, delayMillis = FADE_OUT_MS, easing = EmphasizedDecelerate)) +
        scaleIn(tween(210, delayMillis = FADE_OUT_MS, easing = EmphasizedDecelerate), initialScale = 0.92f)) togetherWith
        fadeOut(tween(FADE_OUT_MS, easing = LinearEasing))

/**
 * One full screen: opaque background (so the screen moving over another covers it cleanly), edge-to-edge safe area,
 * its own scroll position.
 */
@Composable
fun Page(scroll: ScrollState = rememberScrollState(), content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
        .safeDrawingPadding().verticalScroll(scroll).padding(16.dp), content = content)
}

/**
 * In-app back stack: the first screen is home, [open] pushes a child (or goes back to it if it's already below),
 * [back] pops one level. Each screen's back arrow and the system back both call [back], so Back from Alerts returns
 * to Settings, not home.
 */
class Nav(home: String) {
    val stack = androidx.compose.runtime.mutableStateListOf(home)
    val screen get() = stack.last()
    fun open(s: String) {
        highlight = null
        val i = stack.indexOf(s)
        if (i >= 0) while (stack.size > i + 1) stack.removeAt(stack.lastIndex) else stack.add(s)
    }
    fun back() { highlight = null; if (stack.size > 1) stack.removeAt(stack.lastIndex) }
    /** A setting search opened: the row with this title flashes and scrolls into view on the page, then this clears. */
    var highlight by mutableStateOf<String?>(null)
    /** A notification asked for [s]: home, then [s] (so Back leads home). */
    fun reset(vararg s: String) { while (stack.size > 1) stack.removeAt(stack.lastIndex); s.forEach { if (it != stack[0]) stack.add(it) } }
}

@Composable
fun rememberNav(home: String) = remember { Nav(home) }

/*
 * Screen changes copy what the Settings app shows on Android 16/17. Settings pages are separate activities there, so
 * the motion is the system's (AOSP core/res/res/anim/activity_{open,close}_*.xml and WM Shell
 * back/CrossActivityBackAnimation.kt; SettingsLib's own transition helper is empty):
 *  - open: both pages slide 96 dp to the start, 450 ms fast-out-extra-slow-in; the new page fades in over 83 ms after
 *    50 ms;
 *  - back: the reverse; the closing page fades out over 83 ms after 35 ms;
 *  - back gesture: the page shrinks to 90 % with the window's corner radius, its end edge 8 dp from the screen's, and
 *    follows the finger up and down; the page below waits 96 dp to the start at the same scale, a black scrim (20 %
 *    light, 80 % dark) between them. Releasing finishes in 450 ms (the page leaves 96 dp to the end, gone by 20 %),
 *    cancelling springs back.
 */
private const val SYSTEM_MS = 450
private val FastOutExtraSlowIn = PathEasing(androidx.compose.ui.graphics.Path().apply {
    moveTo(0f, 0f); cubicTo(0.05f, 0f, 0.133333f, 0.06f, 0.166666f, 0.4f); cubicTo(0.208333f, 0.82f, 0.25f, 1f, 1f, 1f)
})
private val BackGesture = CubicBezierEasing(0.1f, 0.1f, 0f, 1f)

private enum class Move { OPEN, BACK, GESTURE, COMMIT }

/** The screens of a [Nav]: one at rest, two while moving between them (see above). */
@Composable
fun ScreenHost(nav: Nav, content: @Composable (String) -> Unit) {
    val holder = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    val scope = rememberCoroutineScope()
    val density = androidx.compose.ui.platform.LocalDensity.current
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    // the device's own window corner radius, what the system rounds a page to during the back gesture
    val cornerPx = remember {
        ctx.display?.getRoundedCorner(android.view.RoundedCorner.POSITION_TOP_LEFT)?.radius?.toFloat() ?: with(density) { 28.dp.toPx() }
    }
    // last known depth of every screen: a change to a shallower one is a back
    val depth = remember { HashMap<String, Int>() }
    nav.stack.forEachIndexed { i, s -> depth[s] = i }

    var shown by remember { mutableStateOf(nav.screen) }        // the page at rest / the one being left
    var other by remember { mutableStateOf<String?>(null) }     // the page coming in, or the parent during a gesture
    var move by remember { mutableStateOf(Move.OPEN) }
    val t = remember { Animatable(0f) }                         // linear time 0..1 of OPEN / BACK / COMMIT
    val g = remember { Animatable(0f) }                         // gesture progress (interpolated)
    var dy by remember { mutableStateOf(0f) }                   // finger travel up/down since the gesture started
    var startY by remember { mutableStateOf<Float?>(null) }

    val parent = nav.stack.getOrNull(nav.stack.size - 2)
    val navState = rememberNavigationEventState(NavigationEventInfo.None)
    NavigationBackHandler(navState, isBackEnabled = parent != null,
        onBackCancelled = {
            scope.launch {
                g.animateTo(0f, spring(1f, 1500f))
                if (move == Move.GESTURE) { other = null; startY = null; dy = 0f; move = Move.OPEN }
            }
        },
        onBackCompleted = { nav.back() })
    val ts = navState.transitionState
    LaunchedEffect(ts) {
        if (ts is NavigationEventTransitionState.InProgress && parent != null) {
            if (move != Move.GESTURE || other == null) { move = Move.GESTURE; other = parent; t.snapTo(0f) }
            val e = ts.latestEvent
            if (startY == null) startY = e.touchY
            dy = e.touchY - (startY ?: e.touchY)
            g.snapTo(BackGesture.transform(e.progress))
        }
    }
    LaunchedEffect(nav.screen) {
        val target = nav.screen
        if (target == shown && other == null) return@LaunchedEffect
        val back = (depth[target] ?: 0) < (depth[shown] ?: 0)
        move = if (move == Move.GESTURE && back) Move.COMMIT else if (back) Move.BACK else Move.OPEN
        other = target
        t.snapTo(0f)
        t.animateTo(1f, tween(SYSTEM_MS, easing = LinearEasing))   // the frame clock applies the system animation scale
        shown = target; other = null; g.snapTo(0f); startY = null; dy = 0f
    }

    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val d96 = with(density) { 96.dp.toPx() }
        val d8 = with(density) { 8.dp.toPx() }
        val scrimMax = if (dark) 0.8f else 0.2f
        @Composable
        fun page(key: String, layer: androidx.compose.ui.graphics.GraphicsLayerScope.() -> Unit) =
            androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().graphicsLayer(layer)) { holder.SaveableStateProvider(key) { content(key) } }
        val o = other
        if (o == null) page(shown) {}
        else when (move) {
            Move.OPEN -> {
                // shown slides out to the start, the new page comes in from the end on top of it
                page(shown) { translationX = -d96 * FastOutExtraSlowIn.transform(t.value) }
                page(o) {
                    translationX = d96 * (1 - FastOutExtraSlowIn.transform(t.value))
                    alpha = ((t.value * SYSTEM_MS - 50f) / 83f).coerceIn(0f, 1f)
                }
            }
            Move.BACK -> {
                page(o) { translationX = -d96 * (1 - FastOutExtraSlowIn.transform(t.value)) }
                page(shown) {
                    translationX = d96 * FastOutExtraSlowIn.transform(t.value)
                    alpha = 1f - ((t.value * SYSTEM_MS - 35f) / 83f).coerceIn(0f, 1f)
                }
            }
            Move.GESTURE, Move.COMMIT -> {
                val p = g.value
                val c = if (move == Move.COMMIT) FastOutExtraSlowIn.transform(t.value) else 0f
                val scale = 1f - 0.1f * p
                // at full progress the page's end edge sits 8 dp from the screen's
                val toEnd = p * (w * 0.05f - d8).coerceAtLeast(0f)
                val maxY = (h * (1f - scale) / 2f - d8).coerceAtLeast(0f)
                val yn = (kotlin.math.abs(dy) / (h / 2f)).coerceIn(0f, 1f)
                val y = kotlin.math.sign(dy) * (1f - (1f - yn) * (1f - yn)) * maxY
                val sBelow = scale + (1f - scale) * c
                page(o) {
                    scaleX = sBelow; scaleY = sBelow
                    translationX = -d96 * (1f - c); translationY = y * (1f - c)
                    shape = RoundedCornerShape(cornerPx * (1f - c)); clip = p > 0f
                }
                val scrim = if (p > 0f) scrimMax * (1f - if (move == Move.COMMIT) t.value else 0f) else 0f
                androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) { drawRect(Color.Black, alpha = scrim) }
                page(shown) {
                    scaleX = scale; scaleY = scale
                    translationX = toEnd + d96 * c; translationY = y
                    alpha = (1f - c * 5f).coerceAtLeast(0f)
                    shape = RoundedCornerShape(cornerPx); clip = p > 0f
                }
            }
        }
    }
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
