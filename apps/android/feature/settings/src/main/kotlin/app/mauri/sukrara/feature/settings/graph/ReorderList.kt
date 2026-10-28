// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.settings.graph

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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
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
import app.mauri.sukrara.designsystem.motion.LocalReducedMotion
import app.mauri.sukrara.feature.settings.R
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** The ring-order edits, pure (tested on the JVM). */
object Reorder {
    /** [list] with the item at [from] moved to [to] (the others shift by one); out-of-range indices change nothing. */
    fun <T> moved(
        list: List<T>,
        from: Int,
        to: Int,
    ): List<T> =
        if (from == to || from !in list.indices || to !in list.indices) {
            list
        } else {
            list.toMutableList().also { it.add(to, it.removeAt(from)) }
        }

    /** Where a row held [offsetRows] rows away from slot [from] lands (half a row or more crosses a neighbour). */
    fun target(
        from: Int,
        offsetRows: Float,
        size: Int,
    ): Int = (from + offsetRows.roundToInt()).coerceIn(0, (size - 1).coerceAtLeast(0))
}

private enum class Phase { Idle, Dragging, Settling }

/**
 * A short list reordered by drag and drop inside a scrolling settings page. Every state has its way in and out:
 * - **Idle**: the page scrolls normally, rows included.
 * - **Pick up**: touch-down on the trailing handle starts at once (the handle takes the touch, so the page never
 *   scrolls from it), or a long press anywhere on the row (a swipe before the long press is the page's scroll).
 * - **Dragging**: the row lifts (8 dp, grows 2 %), follows the finger and swaps with a neighbour every half row
 *   (a tick per slot).
 * - **Dropped**: the order is saved and the row settles with a spring; **cancelled**: the order before the drag comes
 *   back and nothing is saved.
 * TalkBack (and anyone who can't drag): each row has Move up / Move down / Move to top / Move to bottom, saved at once.
 */
@Composable
fun <T : Any> ReorderList(
    items: List<T>,
    onOrder: (List<T>) -> Unit,
    modifier: Modifier = Modifier,
    rowContent: @Composable (T) -> Unit,
) {
    val density = LocalDensity.current
    val view = LocalView.current
    val reduced = LocalReducedMotion.current
    val scope = rememberCoroutineScope()
    val rowH = 56.dp
    val rowPx = with(density) { rowH.toPx() }
    val save by rememberUpdatedState(onOrder)

    var order by remember { mutableStateOf(items) }
    var held by remember { mutableStateOf<T?>(null) }
    var phase by remember { mutableStateOf(Phase.Idle) }
    var dy by remember { mutableFloatStateOf(0f) }
    var before by remember { mutableStateOf(items) }
    // the saved order changed elsewhere (Reset, another device): show it unless a row is held
    LaunchedEffect(items) { if (phase == Phase.Idle) order = items }

    fun follow() {
        val h = held ?: return
        val i = order.indexOf(h)
        val to = Reorder.target(i, dy / rowPx, order.size)
        if (to != i) {
            order = Reorder.moved(order, i, to)
            dy -= (to - i) * rowPx
            view.performHapticFeedback(HapticFeedbackConstants.SEGMENT_FREQUENT_TICK)
        }
    }

    fun settle(then: () -> Unit) {
        phase = Phase.Settling
        scope.launch {
            if (reduced) {
                dy = 0f
            } else {
                animate(dy, 0f, animationSpec = spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMediumLow)) { v, _ -> dy = v }
            }
            if (phase == Phase.Settling) {
                held = null
                phase = Phase.Idle
            }
            then()
        }
    }

    fun start(item: T) {
        if (phase == Phase.Dragging) return
        before = order
        held = item
        dy = 0f
        phase = Phase.Dragging
        view.performHapticFeedback(HapticFeedbackConstants.DRAG_START)
    }

    fun drop() {
        if (phase != Phase.Dragging) return
        view.performHapticFeedback(HapticFeedbackConstants.GESTURE_END)
        val result = order
        settle { if (result != before) save(result) }
    }

    fun cancel() {
        if (phase != Phase.Dragging) return
        val h = held ?: return
        dy += (order.indexOf(h) - before.indexOf(h)) * rowPx
        order = before
        settle {}
    }

    fun moveTo(
        item: T,
        to: Int,
    ) {
        val next = Reorder.moved(order, order.indexOf(item), to)
        if (next != order) {
            order = next
            save(next)
        }
    }

    val up = stringResource(R.string.st_move_up)
    val down = stringResource(R.string.st_move_down)
    val top = stringResource(R.string.st_move_top)
    val bottom = stringResource(R.string.st_move_bottom)
    val handle = stringResource(R.string.st_drag)

    Column(modifier.fillMaxWidth()) {
        for (item in order) {
            androidx.compose.runtime.key(item) {
                val lifted = held == item && phase != Phase.Idle
                val raised = held == item && phase == Phase.Dragging
                val scale by animateFloatAsState(if (raised) 1.02f else 1f, label = "lift")
                val elevation by animateDpAsState(if (raised) 8.dp else 0.dp, label = "elevation")
                val i = order.indexOf(item)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier =
                        Modifier
                            .zIndex(if (lifted) 1f else 0f)
                            .fillMaxWidth()
                            .height(rowH)
                            .graphicsLayer {
                                translationY = if (lifted) dy else 0f
                                scaleX = scale
                                scaleY = scale
                            }.shadow(elevation, MaterialTheme.shapes.medium, clip = false)
                            .background(
                                if (lifted) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent,
                                MaterialTheme.shapes.medium,
                            ).testTag("reorder-row")
                            .pointerInput(item) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = { start(item) },
                                    onDragEnd = { drop() },
                                    onDragCancel = { cancel() },
                                ) { change, d ->
                                    change.consume()
                                    dy += d.y
                                    follow()
                                }
                            }.semantics(mergeDescendants = true) {
                                customActions =
                                    listOfNotNull(
                                        CustomAccessibilityAction(up) { moveTo(item, i - 1).let { true } }.takeIf { i > 0 },
                                        CustomAccessibilityAction(down) { moveTo(item, i + 1).let { true } }.takeIf { i < order.lastIndex },
                                        CustomAccessibilityAction(top) { moveTo(item, 0).let { true } }.takeIf { i > 1 },
                                        CustomAccessibilityAction(bottom) {
                                            moveTo(item, order.lastIndex).let { true }
                                        }.takeIf { i < order.lastIndex - 1 },
                                    )
                            },
                ) {
                    Box(Modifier.weight(1f).padding(start = 4.dp)) { rowContent(item) }
                    Icon(
                        painterResource(R.drawable.st_ic_drag_indicator),
                        contentDescription = handle,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier =
                            Modifier
                                .size(48.dp)
                                .testTag("reorder-handle")
                                .pointerInput(item) {
                                    // at once on touch-down, and the touch is ours: the page never scrolls from the handle
                                    awaitEachGesture {
                                        val first = awaitFirstDown(requireUnconsumed = false)
                                        first.consume()
                                        start(item)
                                        val ended =
                                            drag(first.id) { c ->
                                                dy += c.positionChange().y
                                                c.consume()
                                                follow()
                                            }
                                        if (ended) drop() else cancel()
                                    }
                                }.padding(12.dp),
                    )
                }
            }
        }
    }
}
