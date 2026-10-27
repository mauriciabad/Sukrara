// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.ui

import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A page must keep its composition while it starts or stops moving: composing it again at the start or end of a
 * transition draws its first frame from scratch (an empty chart, rows before their final layout), the flash seen in
 * 0.11.14. Pages are only composed when they appear (open / back to a parent that was left) and disposed when gone.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)   // PathEasing
@Config(sdk = [36])
class ScreenHostTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Test fun pagesAreComposedOncePerVisit() {
        val nav = Nav("home")
        val created = mutableListOf<String>()
        val disposed = mutableListOf<String>()
        compose.mainClock.autoAdvance = false
        compose.setContent {
            ScreenHost(nav) { s -> DisposableEffect(Unit) { created += s; onDispose { disposed += s } } }
        }
        fun settle() = repeat(60) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
        settle()
        assertEquals(listOf("home"), created)

        compose.runOnIdle { nav.open("settings") }
        repeat(6) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
        // mid-transition: both pages drawn, home not composed again
        assertEquals(listOf("home", "settings"), created)
        settle()
        // at rest on settings: settings kept, home gone
        assertEquals(listOf("home", "settings"), created)
        assertEquals(listOf("home"), disposed)

        compose.runOnIdle { nav.back() }
        settle()
        assertEquals(listOf("home", "settings", "home"), created)
        assertEquals(listOf("home", "settings"), disposed)
    }

    /** Back while a page is still coming in: that page animates back out (it doesn't vanish) and the host ends on home. */
    @Test fun backDuringOpen() {
        val nav = Nav("home")
        val alive = mutableListOf<String>()
        compose.mainClock.autoAdvance = false
        compose.setContent {
            ScreenHost(nav) { s -> DisposableEffect(Unit) { alive += s; onDispose { alive -= s } } }
        }
        fun frames(n: Int) = repeat(n) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
        frames(5)
        compose.runOnIdle { nav.open("settings") }
        frames(8)
        compose.runOnIdle { nav.back() }
        frames(3)
        assertEquals(setOf("home", "settings"), alive.toSet())
        frames(60)
        assertEquals(listOf("home"), alive)
    }
}
