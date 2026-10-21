// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.widgets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The home screen's "add the widget" banner: when it shows, and which form. */
class WidgetBannerTest {
    private val none = WidgetPresence.State(placed = false, pinSupported = true, dismissed = false)

    @Test fun pinPillWhileNoGraphWidgetIsPlaced() {
        assertEquals(WidgetBanner.PIN, WidgetBanner.of("owner", none))
        assertEquals(WidgetBanner.PIN, WidgetBanner.of("follower", none))   // followers' widgets show the wearer
    }

    @Test fun hiddenOncePlacedOrDismissedOrBeforeSetup() {
        assertEquals(WidgetBanner.HIDDEN, WidgetBanner.of("owner", none.copy(placed = true)))
        assertEquals(WidgetBanner.HIDDEN, WidgetBanner.of("owner", none.copy(dismissed = true)))
        assertEquals(WidgetBanner.HIDDEN, WidgetBanner.of(null, none))
        // a dismissal is for good, even with the widget removed again
        assertEquals(WidgetBanner.HIDDEN, WidgetBanner.of("owner", none.copy(dismissed = true, pinSupported = false)))
    }

    @Test fun manualWhenTheLauncherCantPin() {
        assertEquals(WidgetBanner.MANUAL, WidgetBanner.of("owner", none.copy(pinSupported = false)))
        assertEquals(WidgetBanner.MANUAL, WidgetBanner.of("owner", none.copy(pinFailed = true)))
    }

    @Test fun startsHidden() = assertEquals(WidgetBanner.HIDDEN, WidgetBanner.of("owner", WidgetPresence.State()))

    @Test fun anyGraphProviderWithABoundIdCounts() {
        assertFalse(WidgetPresence.placed(listOf(IntArray(0), IntArray(0))))
        assertTrue(WidgetPresence.placed(listOf(IntArray(0), intArrayOf(12))))
        assertTrue(WidgetPresence.placed(listOf(intArrayOf(3), IntArray(0))))
        // only the graph widgets: the big one (pinned by the banner) first
        assertEquals(listOf(CombinedWidgetReceiver::class.java, GraphWidgetReceiver::class.java), WidgetPresence.BIG)
    }
}
