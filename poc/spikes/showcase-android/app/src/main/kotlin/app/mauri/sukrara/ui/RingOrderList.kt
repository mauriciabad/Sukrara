// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.ui

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import app.mauri.sukrara.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Where a reorder drag is (the flow's states): nothing held, a row lifted under the finger, or a row settling into place. */
internal enum class DragPhase { IDLE, DRAGGING, SETTLING }

/** [list] with the item at [from] moved to [to] (the others shift by one). */
internal fun <T> moved(list: List<T>, from: Int, to: Int): List<T> =
    if (from == to || from !in list.indices || to !in list.indices) list else list.toMutableList().also { it.add(to, it.removeAt(from)) }

/**
 * A short list reordered by drag and drop inside a settings page (which is a plain Column in a verticalScroll), the
 * Compose pattern of the foundation's LazyColumn drag-and-drop demo: a LazyColumn of fixed height (it never scrolls by
 * itself) whose rows are keyed, so while a row is held the list is reordered live and every other row slides to its
 * new place with `Modifier.animateItem()` ("animate item placement", developer.android.com/develop/ui/compose/lists).
 *
 * The flow, every state handled:
 * - **Idle.** Rows at rest; the page scrolls normally anywhere, rows included.
 * - **Pick up**, two ways: touch-down on the trailing drag handle starts at once (the handle consumes the touch, so the
 *   page's verticalScroll never sees it: Compose dispatches the Main pass to the child first, and scrollable ignores
 *   consumed changes); or a long press anywhere on the row (`detectDragGesturesAfterLongPress`: a swipe before the
 *   timeout is the page's scroll, never a reorder). Haptic DRAG_START.
 * - **Dragging.** The row lifts like Material's dragged state (m3.material.io/foundations/interaction/states: a
 *   dragged element is raised, elevation 8 dp, 16 % state layer) and grows 2 %; it follows the finger; crossing half a
 *   row swaps it with its neighbour (haptic SEGMENT_FREQUENT_TICK per slot), the neighbour animating out of the way.
 *   Near the top or bottom of the page's viewport the page auto-scrolls, and the row stays under the finger.
 * - **Dropped.** The order is saved and the row settles into its slot with a spring (haptic GESTURE_END).
 * - **Cancelled** (the gesture is taken away): the list goes back to the order before the drag and the row settles
 *   back; nothing is saved.
 * Dragging is hard for people with motor impairments, so each row also has the accessibility actions Move up / Move
 * down / Move to top / Move to bottom (customActions "can be used for more complex touchscreen gestures, like swipe to
 * dismiss or drag and drop", developer.android.com/develop/ui/compose/accessibility/semantics), as AOSP Settings ›
 * Languages offers (LocaleDragAndDropAdapter: trailing handle, the same four actions).
 */
@Composable
internal fun <T : Any> ReorderList(items: List<T>, onOrder: (List<T>) -> Unit, rowContent: @Composable (T) -> Unit) {
    val density = LocalDensity.current
    val view = LocalView.current
    val page = LocalPageScroll.current
    val scope = rememberCoroutineScope()
    val rowH = 56.dp
    val rowPx = with(density) { rowH.toPx() }
    val edgePx = with(density) { 56.dp.toPx() }
    val maxSpeed = with(density) { 14.dp.toPx() }
    val save by rememberUpdatedState(onOrder)

    var order by remember { mutableStateOf(items) }
    var held by remember { mutableStateOf<T?>(null) }
    var phase by remember { mutableStateOf(DragPhase.IDLE) }
    var dy by remember { mutableFloatStateOf(0f) }
    var listTop by remember { mutableFloatStateOf(0f) }
    var before by remember { mutableStateOf(items) }
    val auto = remember { arrayOfNulls<Job>(1) }
    // the saved order changed elsewhere (reset, another device): show it unless a row is held
    androidx.compose.runtime.LaunchedEffect(items) { if (phase == DragPhase.IDLE) order = items }

    /** Swaps the held row with its neighbours while it is more than half a row away from its slot. */
    fun follow() {
        val h = held ?: return
        val i = order.indexOf(h)
        val to = (i + (dy / rowPx).roundToInt()).coerceIn(0, order.lastIndex)
        if (to != i) {
            order = moved(order, i, to)
            dy -= (to - i) * rowPx
            view.performHapticFeedback(HapticFeedbackConstants.SEGMENT_FREQUENT_TICK)
        }
    }

    fun settle(then: () -> Unit) {
        phase = DragPhase.SETTLING
        scope.launch {
            animate(dy, 0f, animationSpec = spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMediumLow)) { v, _ -> dy = v }
            if (phase == DragPhase.SETTLING) { held = null; phase = DragPhase.IDLE }
            then()
        }
    }

    fun start(item: T) {
        if (phase == DragPhase.DRAGGING) return
        before = order
        held = item
        dy = 0f
        phase = DragPhase.DRAGGING
        view.performHapticFeedback(HapticFeedbackConstants.DRAG_START)
        // auto-scroll: while the held row is within [edgePx] of the viewport's edge, scroll the page that way
        auto[0] = page?.let { p ->
            scope.launch {
                while (isActive && phase == DragPhase.DRAGGING) {
                    withFrameNanos { }
                    val h = held ?: break
                    val top = listTop + order.indexOf(h) * rowPx + dy
                    val vpTop = p.viewport.top + with(density) { p.topInset.toPx() }
                    val vpBottom = p.viewport.bottom - with(density) { p.bottomInset.toPx() }
                    val speed = when {
                        top < vpTop + edgePx && p.state.canScrollBackward -> -maxSpeed * ((vpTop + edgePx - top) / edgePx).coerceIn(0.2f, 1f)
                        top + rowPx > vpBottom - edgePx && p.state.canScrollForward -> maxSpeed * ((top + rowPx - vpBottom + edgePx) / edgePx).coerceIn(0.2f, 1f)
                        else -> 0f
                    }
                    if (speed != 0f) {
                        val used = p.state.scrollBy(speed)
                        dy += used
                        follow()
                    }
                }
            }
        }
    }

    fun drop() {
        if (phase != DragPhase.DRAGGING) return
        auto[0]?.cancel()
        view.performHapticFeedback(HapticFeedbackConstants.GESTURE_END)
        val result = order
        settle { if (result != before) save(result) }
    }

    fun cancel() {
        if (phase != DragPhase.DRAGGING) return
        auto[0]?.cancel()
        val h = held ?: return
        // back to the order before the drag, the held row keeping its place on screen, then settling into its slot
        dy += (order.indexOf(h) - before.indexOf(h)) * rowPx
        order = before
        settle {}
    }

    fun moveBy(item: T, to: Int) {
        val i = order.indexOf(item)
        val next = moved(order, i, to)
        if (next != order) { order = next; save(next) }
    }

    val up = stringResource(R.string.graph_ring_move_up)
    val down = stringResource(R.string.graph_ring_move_down)
    val top = stringResource(R.string.graph_ring_move_top)
    val bottom = stringResource(R.string.graph_ring_move_bottom)
    val handleLabel = stringResource(R.string.graph_ring_drag)

    LazyColumn(Modifier.fillMaxWidth().height(rowH * items.size).onGloballyPositioned { listTop = it.positionInWindow().y },
        userScrollEnabled = false) {
        items(order, key = { it }) { item ->
            val lifted = held == item && phase != DragPhase.IDLE
            val raised = held == item && phase == DragPhase.DRAGGING
            val scale by animateFloatAsState(if (raised) 1.02f else 1f, label = "lift")
            val elevation by animateDpAsState(if (raised) 8.dp else 0.dp, label = "elevation")
            val i = order.indexOf(item)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier
                .then(if (lifted) Modifier.zIndex(1f) else Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null))
                .fillMaxWidth().height(rowH)
                .graphicsLayer { translationY = if (lifted) dy else 0f; scaleX = scale; scaleY = scale }
                .shadow(elevation, MaterialTheme.shapes.medium, clip = false)
                .clip(MaterialTheme.shapes.medium)
                .background(if (elevation > 0.dp) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent)
                .background(if (raised) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f * (elevation / 8.dp)) else Color.Transparent)
                .testTag("reorder-row")
                .pointerInput(item) {
                    detectDragGesturesAfterLongPress(onDragStart = { start(item) }, onDragEnd = { drop() }, onDragCancel = { cancel() }) { change, d ->
                        change.consume(); dy += d.y; follow()
                    }
                }
                .semantics(mergeDescendants = true) {
                    customActions = listOfNotNull(
                        CustomAccessibilityAction(up) { moveBy(item, i - 1); true }.takeIf { i > 0 },
                        CustomAccessibilityAction(down) { moveBy(item, i + 1); true }.takeIf { i < order.lastIndex },
                        CustomAccessibilityAction(top) { moveBy(item, 0); true }.takeIf { i > 1 },
                        CustomAccessibilityAction(bottom) { moveBy(item, order.lastIndex); true }.takeIf { i < order.lastIndex - 1 },
                    )
                }) {
                androidx.compose.foundation.layout.Box(Modifier.weight(1f).padding(start = 4.dp)) { rowContent(item) }
                Icon(painterResource(R.drawable.ic_sym_drag_indicator), contentDescription = handleLabel,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(48.dp).testTag("reorder-handle").pointerInput(item) {
                        // immediately on touch-down, and the touch is ours: the page never scrolls from the handle
                        awaitEachGesture {
                            val first = awaitFirstDown(requireUnconsumed = false)
                            first.consume()
                            start(item)
                            val ended = drag(first.id) { c -> dy += c.positionChange().y; c.consume(); follow() }
                            if (ended) drop() else cancel()
                        }
                    }.padding(12.dp))
            }
        }
    }
}

/** One ring-order row: the segment's colour and its range's name (no position number: the order is the list). */
@Composable
internal fun RingRow(color: Int, name: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.foundation.Canvas(Modifier.padding(end = 12.dp).size(14.dp)) { drawCircle(Color(color)) }
        Text(name, style = MaterialTheme.typography.bodyLarge)
    }
}
