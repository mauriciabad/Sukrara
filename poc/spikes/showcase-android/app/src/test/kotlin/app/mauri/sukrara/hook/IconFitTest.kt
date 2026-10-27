// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.hook

import app.mauri.sukrara.graph.ValueGlyphs
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

    // --- number → trend at the card's gap ---------------------------------------------------------------------

    @Test fun pullBringsTheTrendToTheCardsGap() {
        // 13 sp icons at 420 dpi = 34 px; digits 28 px; the card's gap 0.27 of them = 8 px instead of SystemUI's 16
        assertEquals(8, IconFit.pull(16, 34))
        assertEquals(0, IconFit.pull(4, 34))   // never apart
    }

    @Test fun onlyWhatFollowsTheNumberMoves() {
        // [vibrate 40][number 60][trend 22][wifi 40], all shown; pull 8 after the number (index 1)
        val w = listOf(40, 60, 22, 40)
        val all = Fit(listOf(0, 1, 2, 3), null)
        assertEquals(listOf(0, 56, 132, 170) to 210, IconFit.place(w, all, sp, dot))
        // the trend 8 px closer to the number, wifi keeps its 16 px from the trend, the row 8 px narrower
        assertEquals(listOf(0, 56, 124, 162) to 202, IconFit.place(w, all, sp, dot, pullAfter = 1, pull = 8))
    }

    @Test fun theDotComesFirstAndAPairAtTheEndStillPulls() {
        val w = listOf(40, 60, 22)
        val f = Fit(listOf(1, 2), 0)
        assertEquals(listOf(48, 124) to 32 + 16 + 60 + 16 + 22, IconFit.place(w, f, sp, dot))
        assertEquals(listOf(48, 116) to 32 + 16 + 60 + 16 + 22 - 8, IconFit.place(w, f, sp, dot, pullAfter = 1, pull = 8))
    }

    @Test fun nothingToPullWhenTheNumberIsLastOrHidden() {
        val w = listOf(40, 60)
        assertEquals(listOf(0, 56) to 116, IconFit.place(w, Fit(listOf(0, 1), null), sp, dot, pullAfter = 1, pull = 8))
        assertEquals(listOf(48) to 88, IconFit.place(w, Fit(listOf(0), 1), sp, dot, pullAfter = 1, pull = 8))
    }

    @Test fun thePulledPxGoToAnotherIconInsteadOfTheDot() {
        // the user's row: [vibrate][number][trend][signal][wifi], 260 px. SystemUI alone (6 dp between number and trend):
        // 22 + 76 + 56 + 56 = 210, vibrate needs 56 of the 50 left → the dot
        val w = listOf(40, 60, 22, 40, 40)
        assertEquals(Fit(listOf(1, 2, 3, 4), 0), IconFit.prioritized(w, setOf(1, 2), 260, sp, dot))
        // offered the 8 px the pull gives back: vibrate fits, no dot, and the pulled row is 258 ≤ 260
        assertEquals(Fit(listOf(0, 1, 2, 3, 4), null) to 258, IconFit.pulledRow(w, 1, 2, 260, sp, dot, 8))
    }

    @Test fun thePulledRowNeverOutgrowsTheRealWidth() {
        val rnd = java.util.Random(7)
        repeat(20_000) {
            val others = rnd.nextInt(6)
            val at = rnd.nextInt(others + 1)
            val w = (0 until others).map { 10 + rnd.nextInt(70) }.toMutableList()
            w.addAll(at, listOf(20 + rnd.nextInt(60), 8 + rnd.nextInt(20)))   // number, trend
            val max = dot + rnd.nextInt(400)
            val pull = rnd.nextInt(sp + 1)
            for (fromEnd in listOf(true, false)) {
                val (f, width) = IconFit.pulledRow(w, at, at + 1, max, sp, dot, pull, fromEnd)
                val label = "widths $w max $max pull $pull fromEnd $fromEnd → $f, $width"
                assertEquals(label, true, width <= max)
                assertEquals("pair split: $label", (at in f.visible), (at + 1 in f.visible))
                // with the pair shown nothing of ours is in the dot; without it nothing else shows
                if (at !in f.visible) assertEquals(label, emptyList<Int>(), f.visible)
            }
        }
    }

    /**
     * [IconFit.CARD_GAP_OF_DIGIT] is the card's own gap: the renderer's geometry ([ValueGlyphs]: the mark's box
     * [ValueGlyphs.MARK_GAP] after the number's advance, the arrow drawn [ValueGlyphs.ARROW_IN_BOX] × its
     * [ValueGlyphs.MARK_BOX] box, centred) with the card's font (the digits' right side bearing), ink to ink, averaged
     * over the digits and the 5° arrow angles, per height of the digit "0".
     */
    @Test fun theGapIsTheCardsGap() {
        val font = generateSequence(java.io.File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .map { java.io.File(it, "spikes/showcase-android/app/src/main/assets/fonts/GoogleSansFlexRounded-700.ttf") }.first { it.isFile }
        val f = java.awt.Font.createFont(java.awt.Font.TRUETYPE_FONT, font).deriveFont(1000f)
        val frc = java.awt.font.FontRenderContext(null, true, true)
        fun glyph(c: Char) = f.createGlyphVector(frc, c.toString()).let { it.getGlyphOutline(0).bounds2D to it.getGlyphMetrics(0).advance }
        val t = 1000.0
        val bearing = "0123456789".map { glyph(it).let { (b, adv) -> adv - b.maxX } }.average()
        val box = ValueGlyphs.MARK_BOX * t
        val scale = box * ValueGlyphs.ARROW_IN_BOX
        val s = ValueGlyphs.ARROW_SHAFT
        val outline = listOf(s[0] to s[1], s[2] to s[1], s[2] to s[3], s[0] to s[3]) + ValueGlyphs.ARROW_HEAD
        val inset = (-90..90 step 5).map { deg ->
            val r = Math.toRadians(deg.toDouble())
            box / 2 + outline.minOf { (x, y) -> (x * Math.cos(r) - y * Math.sin(r)) * scale }
        }.average()
        val gap = (ValueGlyphs.MARK_GAP * t + bearing + inset) / glyph('0').first.height
        assertEquals("the card's number → arrow gap per digit height", gap, IconFit.CARD_GAP_OF_DIGIT.toDouble(), 0.005)
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

    // --- number, then trend, from System UI's first frame -----------------------------------------------------------

    private val ourSlots = listOf("glucose", "glucose_trend")
    private fun arranged(slots: List<String>, target: String?) = IconFit.arrange(slots, ourSlots, target, order)?.map { slots[it] } ?: slots

    @Test fun afterARestartTheNumberComesBackFirst() {
        // system_server hands them back trend first; plain "slot:" placement (no target): same place, fixed order
        assertEquals(listOf("glucose", "glucose_trend", "vibrate", "wifi"), arranged(listOf("glucose_trend", "glucose", "vibrate", "wifi"), null))
        assertEquals(listOf("other_app", "glucose", "glucose_trend", "wifi"), arranged(listOf("other_app", "glucose_trend", "glucose", "wifi"), ""))
    }

    @Test fun afterXInTheFixedOrderToo() {
        assertEquals(listOf("vibrate", "wifi", "glucose", "glucose_trend", "mobile"),
            arranged(listOf("glucose_trend", "glucose", "vibrate", "wifi", "mobile"), "wifi"))
        // the target hidden: where it would be
        assertEquals(listOf("vibrate", "glucose", "glucose_trend", "wifi"), arranged(listOf("glucose_trend", "glucose", "vibrate", "wifi"), "bluetooth"))
    }

    // --- "before the mobile signal" ---------------------------------------------------------------------------------

    /** The user's Pixel 10 Pro, Android 17 (`cmd overlay lookup android android:array/config_statusBarIcons`, 2026-10-23). */
    private val pixel = listOf("no_calling", "call_strength", "alarm_clock", "rotate", "headset", "data_saver", "ime",
        "sync_failing", "sync_active", "nfc", "tty", "speakerphone", "cdma_eri", "data_connection", "phone_evdo_signal",
        "phone_signal", "secure", "managed_profile", "connected_display", "vpn", "bluetooth", "camera", "microphone",
        "location", "mute", "volume", "zen", "screen_record", "cast", "ethernet", "satellite", "hotspot", "stacked_mobile",
        "mobile", "wifi", "airplane", "battery", "sensors_off")
    private fun before(slots: List<String>) = IconFit.arrange(slots, ourSlots, "<mobile", pixel)?.map { slots[it] } ?: slots

    @Test fun beforeTheMobileSignalOnTheUsersRow() =
        // today (after VPN): speakerphone, VPN, glucose, vibrate ("volume"), mobile, Wi-Fi → vibrate, glucose, mobile
        assertEquals(listOf("speakerphone", "vpn", "volume", "glucose", "glucose_trend", "mobile", "wifi"),
            before(listOf("speakerphone", "vpn", "glucose", "glucose_trend", "volume", "mobile", "wifi")))

    @Test fun beforeAHiddenMobileSignalIsWhereItWouldBe() {
        // no SIM / mobile hidden: after the last icon ranked before mobile, still left of Wi-Fi (ranked after mobile)
        assertEquals(listOf("vpn", "volume", "glucose", "glucose_trend", "wifi"),
            before(listOf("glucose_trend", "glucose", "vpn", "volume", "wifi")))
        // nothing ranked before it shown: first
        assertEquals(listOf("glucose", "glucose_trend", "wifi"), before(listOf("wifi", "glucose_trend", "glucose")))
        // another app's icon (no rank) counts as before, like SystemUI puts it
        assertEquals(1, IconFit.insertBefore(listOf("other_app", "wifi"), "mobile", pixel))
    }

    @Test fun beforeAnUnknownIconLeavesOursInPlace() {
        assertNull(IconFit.insertBefore(listOf("wifi"), "nfc_reader", pixel))
        assertEquals(listOf("wifi", "glucose", "glucose_trend"), IconFit.arrange(listOf("wifi", "glucose_trend", "glucose"), ourSlots, "<nfc_reader", pixel)!!
            .map { listOf("wifi", "glucose_trend", "glucose")[it] })
    }

    @Test fun nothingToDoWhenAlreadyInOrderOrNotThere() {
        assertNull(IconFit.arrange(listOf("glucose", "glucose_trend", "wifi"), ourSlots, null, order))
        assertNull(IconFit.arrange(listOf("vibrate", "wifi"), ourSlots, "wifi", order))
        // a single icon (no readings / ended: the trend slot only) stays where it is
        assertNull(IconFit.arrange(listOf("glucose_trend", "wifi"), ourSlots, null, order))
        // an unknown target: ourSlots keep their place, fixed order
        assertEquals(listOf("wifi", "glucose", "glucose_trend"), arranged(listOf("wifi", "glucose_trend", "glucose"), "nfc"))
    }
}
