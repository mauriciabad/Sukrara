// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.status

import app.mauri.sukrara.features.FeatureState
import app.mauri.sukrara.features.SystemAccess
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The restart card's action (the user, 2026-10-21): always the root restart of System UI, never a reboot and never a
 * "no root" text. The card shows whatever Sukrara's root state is (it was hidden behind a root check that home never
 * ran, so the phone showed "restart your phone" and no pill); a failed `su` comes back as the reason to show in red.
 */
class SystemUiRestartTest {
    @Test fun killedIsSuccess() = assertNull(RootStatusBar.restartError(0, ""))

    @Test fun suRefusedSaysWhy() {
        assertEquals("Permission denied", RootStatusBar.restartError(1, "\nPermission denied\n"))
        assertEquals("su exit 1", RootStatusBar.restartError(1, ""))
    }

    @Test fun noAnswerFromTheRootPrompt() = assertEquals(RootStatusBar.TIMED_OUT, RootStatusBar.restartError(null, ""))

    @Test fun theCardDoesntDependOnRootBeingKnown() {
        val f = FeatureState(hook = true, lockWidget = true)
        val s = SystemAccess.Status(SystemAccess.Module.UPDATE, loaded = 11037)
        for (root in listOf(null, false, true)) {
            RootStatusBar.root.value = root
            assertTrue(SystemAccess.homeBanner(s, f, null))
        }
    }
}
