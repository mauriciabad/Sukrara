// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.widgets

import android.content.Intent
import app.mauri.sukrara.core.Reading
import app.mauri.sukrara.graph.Clock
import app.mauri.sukrara.status.GlucoseNotification
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Switching the phone's 24-hour setting while Sukrara runs redraws every surface at once: Settings broadcasts
 * ACTION_TIME_CHANGED, the role's receiver re-renders the notification and refreshes the widgets
 * ([SukraraHooks.onClockChange]), and the clock's look is in every redraw key, so those redraws aren't skipped as
 * "same content" (before, the keys had no clock: the widgets and the notification's graph kept the old format until
 * the next reading or the 5-minute tick).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ClockRefreshTest {
    @After fun reset() { Clock.hour24Source = null }

    @Test fun theRedrawKeysFollowTheSetting() {
        val ctx = RuntimeEnvironment.getApplication()
        val r = Reading(1_800_000_000L, 120, 0.5)
        fun keys(h24: Boolean): Pair<Any, Any> {
            Clock.hour24Source = { h24 }
            return GlucoseNotification.contentKey(ctx, r, chip = false, icon = true, detail = "") to
                WidgetUpdater.drawKey(r.t, null, r.t + 60, 0, 1, 0)
        }
        val a = keys(true); val b = keys(false)
        assertNotEquals("the notification's key ignores the 24-hour setting", a.first, b.first)
        assertNotEquals("the widgets' key ignores the 24-hour setting", a.second, b.second)
        assertEquals("the widgets' key is stable", a.second, keys(true).second)
    }

    @Test fun aClockBroadcastRedraws() {
        val ctx = RuntimeEnvironment.getApplication()
        var renders = 0
        for (action in listOf(Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED))
            assertTrue(action, SukraraHooks.onClockChange(ctx, Intent(action)) { renders++ })
        assertEquals(2, renders)
        assertFalse(SukraraHooks.onClockChange(ctx, Intent(Intent.ACTION_SCREEN_ON)) { renders++ })
        assertEquals(2, renders)
    }
}
