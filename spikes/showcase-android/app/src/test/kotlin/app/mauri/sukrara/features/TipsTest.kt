// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.features

import android.content.Context
import app.mauri.sukrara.features.Tips.Tip
import app.mauri.sukrara.widgets.WidgetPresence
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Closed tips come back (round f33): Undo right after closing, Settings → Show closed tips for all of them. The guard:
 * every [Tip] closes and comes back through the app's real stores, and only itself; a closable card that isn't in the
 * registry can't be restored, so the close buttons go through [Tips.close] (checked in `closeButtonsGoThroughTheRegistry`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TipsTest {
    private val ctx: Context = RuntimeEnvironment.getApplication()

    @Before fun setup() {
        Features.init(ctx)
        Tips.restoreAll(ctx)
    }

    @After fun tearDown() { Tips.restoreAll(ctx) }

    @Test fun everyTipClosesAndComesBackAloneThroughTheRealStores() {
        for (t in Tip.entries) {
            assertEquals("nothing closed before $t", emptyList<Tip>(), Tips.closed(ctx))
            Tips.close(ctx, t)
            assertEquals("only $t closed", listOf(t), Tips.closed(ctx))
            Tips.undo(ctx, t)
            assertEquals("$t back after Undo", emptyList<Tip>(), Tips.closed(ctx))
        }
    }

    @Test fun closeThenUndo() {
        Tips.close(ctx, Tip.CLINIC_UPLOAD)
        assertTrue(Features.value.uploadBannerDismissed)
        assertEquals(Tip.CLINIC_UPLOAD, Tips.justClosed.value)   // the snackbar shows
        Tips.undo(ctx, Tip.CLINIC_UPLOAD)
        assertFalse(Features.value.uploadBannerDismissed)
        assertNull(Tips.justClosed.value)
    }

    @Test fun closeThenShowAgainBringsBackEveryClosedTip() {
        Tips.close(ctx, Tip.ADD_WIDGET)
        Tips.consumed(Tip.ADD_WIDGET)                          // the snackbar timed out
        Tips.close(ctx, Tip.CLINIC_UPLOAD)
        Tips.close(ctx, Tip.RESTART_SYSTEM_UI)
        assertTrue(WidgetPresence.dismissed(ctx))
        assertEquals(3, Tips.restoreAll(ctx))
        assertEquals(emptyList<Tip>(), Tips.closed(ctx))
        assertFalse(WidgetPresence.dismissed(ctx))
        assertFalse(Features.value.uploadBannerDismissed)
        assertFalse(SystemAccess.closedNow())
        assertNull(Tips.justClosed.value)
    }

    @Test fun showAgainWithNothingClosedDoesNothing() = assertEquals(0, Tips.restoreAll(ctx))

    @Test fun aTimedOutSnackbarLeavesTheTipClosed() {
        Tips.close(ctx, Tip.CLINIC_UPLOAD)
        Tips.consumed(Tip.CLINIC_UPLOAD)
        assertNull(Tips.justClosed.value)
        assertEquals(listOf(Tip.CLINIC_UPLOAD), Tips.closed(ctx))
    }

    @Test fun closeButtonsGoThroughTheRegistry() {
        // every home card with a close button names its Tip; none writes its store directly
        val ui = java.io.File("src/main/kotlin/app/mauri/sukrara/ui")
        val closers = ui.walk().filter { it.isFile && it.extension == "kt" }
            .filter { f -> f.readText().let { it.contains("ic_sym_close") && it.contains("IconButton") } }.toList()
        assertTrue("close buttons found", closers.isNotEmpty())
        val named = closers.flatMap { f -> Regex("""Tips\.close\(ctx, [\w.]*Tip\.(\w+)\)""").findAll(f.readText()).map { it.groupValues[1] }.toList() }
        assertEquals(Tip.entries.map { it.name }.sorted(), named.sorted())
        for (f in closers) {
            val t = f.readText()
            assertFalse("${f.name} writes a closed state itself", t.contains("uploadBannerDismissed = true") ||
                t.contains("WidgetPresence.dismiss(") || t.contains("SystemAccess::dismiss") || t.contains("SystemAccess.dismiss("))
        }
    }
}
