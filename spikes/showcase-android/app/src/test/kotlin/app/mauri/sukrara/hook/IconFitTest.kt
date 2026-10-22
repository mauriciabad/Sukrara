// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.hook

import app.mauri.sukrara.hook.IconFit.Fit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The status bar's icon row with glucose first in line ([IconFit]): SystemUI's own fit (Android 17
 * `EllipsizingRow.measureIcons`) and what the module makes of it. Widths in px, SystemUI's 6 dp spacing and 12 sp dot
 * space at 420 dpi (16 px, 32 px).
 */
class IconFitTest {
    private val sp = 16
    private val dot = 32

    // --- SystemUI's algorithm, as the module relies on it -----------------------------------------------------

    @Test fun everythingFits() =
        assertEquals(Fit(listOf(0, 1, 2), null), IconFit.fit(listOf(40, 40, 40), 152, sp, dot))

    @Test fun firstMisfitFromTheRightBecomesTheDotAndHidesTheRest() =
        // 40 + 16 + 40 = 96 fit; icon 1 needs 56 more, 4 left → dot; 4 < 32 + 16 → icon 2 becomes the dot instead
        assertEquals(Fit(listOf(3), 2), IconFit.fit(listOf(40, 40, 40, 40), 100, sp, dot))

    @Test fun fromStartFillsFromTheLeft() =
        assertEquals(Fit(listOf(0, 1), 2), IconFit.fit(listOf(40, 40, 40, 40), 152, sp, dot, fromEnd = false))

    @Test fun nothingFitsIsOneDot() =
        assertEquals(Fit(emptyList(), 0), IconFit.fit(listOf(80), 60, sp, dot))

    @Test fun widthMatchesSystemUi() {
        assertEquals(40 + 16 + 40, IconFit.width(listOf(40, 40), Fit(listOf(0, 1), null), sp, dot))
        assertEquals(32 + 16 + 40, IconFit.width(listOf(40, 40), Fit(listOf(1), 0), sp, dot))
        assertEquals(32, IconFit.width(listOf(40, 40), Fit(emptyList(), 0), sp, dot))
    }

    // --- glucose first in line, number + trend as one unit ------------------------------------------------------

    /** The user's row: [number, trend] leftmost (ours), then vibrate, hotspot, wifi, mobile. */
    private val row = listOf(60, 22, 40, 40, 40, 40)
    private val ours = setOf(0, 1)

    @Test fun withoutTheModuleTheNumberGoesFirstAndOnlyTheArrowIsLeft() =
        // SystemUI alone: 4 × 40 + 3 × 16 = 208, trend 16 + 22 → 246; the number (76) doesn't fit → dot, arrow alone
        assertEquals(Fit(listOf(1, 2, 3, 4, 5), 0), IconFit.fit(row, 320, sp, dot))

    @Test fun otherIconsFoldIntoTheDotBeforeGlucose() =
        // ours first: 22 + 16 + 60 = 98; + 3 × 56 = 266; vibrate (2) needs 56 more, 54 left → dot (48)
        assertEquals(Fit(listOf(0, 1, 3, 4, 5), 2), IconFit.prioritized(row, ours, 320, sp, dot))

    @Test fun glucoseKeepsItsPlaceInTheRow() {
        // ours after wifi (index 4 → between wifi and mobile)
        val r = listOf(40, 40, 40, 60, 22, 40)
        assertEquals(Fit(listOf(2, 3, 4, 5), 1), IconFit.prioritized(r, setOf(3, 4), 270, sp, dot))
    }

    @Test fun thePairBecomesOneDotWhenItCantFitAlone() =
        // only 90 px: the trend alone would fit (SystemUI would show just the arrow); the module folds both into the dot
        assertEquals(Fit(emptyList(), 0), IconFit.prioritized(row, ours, 90, sp, dot))

    @Test fun thePairNeverSplitsInTheDotPass() {
        // 100 px: the pair (98) fits, but then the dot needs 48 more → SystemUI drops the number; the module drops both
        val f = IconFit.prioritized(row, ours, 100, sp, dot)
        assertEquals(emptyList<Int>(), f.visible)
        assertEquals(true, f.overflow in ours)
    }

    @Test fun aSingleIconStateIsFirstInLineToo() =
        // "no readings" / "sensor ended": one icon of ours (index 0)
        assertEquals(Fit(listOf(0, 4, 5), 3), IconFit.prioritized(listOf(22, 40, 40, 40, 40, 40), setOf(0), 200, sp, dot))

    @Test fun fromStartPutsOursAtTheStartToo() =
        // the priority end is the left: ours (3, 4) first, then 0 and 1; 2 doesn't fit → dot
        assertEquals(Fit(listOf(0, 1, 3, 4), 2), IconFit.prioritized(listOf(40, 40, 40, 60, 22), setOf(3, 4), 260, sp, dot, fromEnd = false))

    @Test fun nothingToMoveWhenOursAreAlreadyLast() {
        assertNull(IconFit.permutation(3, setOf(1, 2)))
        assertNull(IconFit.permutation(3, emptySet()))
    }

    // --- "after X" when X is hidden ------------------------------------------------------------------------------

    private val order = listOf("zen", "mute", "vibrate", "bluetooth", "hotspot", "vpn", "wifi", "mobile", "battery")

    @Test fun afterAShownIcon() = assertEquals(2, IconFit.insertAfter(listOf("other_app", "bluetooth", "wifi"), "bluetooth", order))

    @Test fun afterAHiddenIconGoesWhereItWouldBe() {
        // bluetooth off: after vibrate (ranked before bluetooth), before wifi
        assertEquals(2, IconFit.insertAfter(listOf("other_app", "vibrate", "wifi", "mobile"), "bluetooth", order))
        // vpn off and nothing ranked before it shown but another app's icon: after that
        assertEquals(1, IconFit.insertAfter(listOf("other_app", "wifi"), "vpn", order))
        // nothing before it at all: first
        assertEquals(0, IconFit.insertAfter(listOf("wifi", "mobile"), "vpn", order))
    }

    @Test fun anUnknownTargetLeavesTheRowAlone() = assertNull(IconFit.insertAfter(listOf("wifi"), "nfc", order))
}
