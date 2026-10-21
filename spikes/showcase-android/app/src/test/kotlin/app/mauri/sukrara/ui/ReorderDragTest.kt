// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import com.android.settingslib.spa.framework.theme.SettingsTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The ring order's drag and drop inside a scrolling settings page (ReorderList, the user 2026-10-21: "it messes with
 * the scroll"): a drag from the handle reorders and never scrolls the page; a vertical swipe on a row scrolls the page
 * and never reorders; a long press then drag reorders; a drag that ends where it started saves nothing.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h800dp-xhdpi")
class ReorderDragTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private var saved: List<String>? = null
    private var page: PageScroll? = null
    private val rowPx get() = 56 * compose.activity.resources.displayMetrics.density

    private fun show() = compose.setContent {
        SettingsTheme {
            SettingsPage("Test", {}) {
                page = LocalPageScroll.current
                Spacer(Modifier.height(300.dp))
                SettingsGroup { card { ReorderList(listOf("A", "B", "C", "D", "E", "F"), { saved = it }) { Text(it) } } }
                Spacer(Modifier.height(900.dp))
            }
        }
    }

    @Test fun dragFromTheHandleReordersAndDoesNotScroll() {
        show()
        val scroll0 = page!!.state.value
        compose.onAllNodesWithTag("reorder-handle", useUnmergedTree = true)[0].performTouchInput {
            down(center)
            repeat(22) { moveBy(Offset(0f, rowPx * 2.2f / 22)) }
            up()
        }
        compose.waitForIdle()
        assertEquals(listOf("B", "C", "A", "D", "E", "F"), saved)
        assertEquals("the page must not scroll while a row is dragged", scroll0, page!!.state.value)
    }

    @Test fun aSwipeOnARowScrollsAndDoesNotReorder() {
        show()
        val scroll0 = page!!.state.value
        compose.onAllNodesWithTag("reorder-row")[2].performTouchInput { swipeUp(startY = centerY, endY = centerY - 400f, durationMillis = 200) }
        compose.waitForIdle()
        assertNull(saved)
        assertTrue("the page scrolls (${page!!.state.value} > $scroll0)", page!!.state.value > scroll0)
    }

    @Test fun longPressThenDragReorders() {
        show()
        compose.onAllNodesWithTag("reorder-row")[3].performTouchInput {
            down(center)
            advanceEventTime(1000)
            repeat(20) { moveBy(Offset(0f, -rowPx * 1.2f / 20)) }
            up()
        }
        compose.waitForIdle()
        assertEquals(listOf("A", "B", "D", "C", "E", "F"), saved)
    }

    @Test fun droppedWhereItStartedSavesNothing() {
        show()
        compose.onAllNodesWithTag("reorder-handle", useUnmergedTree = true)[1].performTouchInput {
            down(center); moveBy(Offset(0f, rowPx * 0.3f)); moveBy(Offset(0f, -rowPx * 0.3f)); up()
        }
        compose.waitForIdle()
        assertNull(saved)
    }

    @Test fun moveLogic() {
        assertEquals(listOf(2, 1, 3), moved(listOf(1, 2, 3), 0, 1))
        assertEquals(listOf(3, 1, 2), moved(listOf(1, 2, 3), 2, 0))
        assertEquals(listOf(1, 2, 3), moved(listOf(1, 2, 3), 1, 5))
    }
}
