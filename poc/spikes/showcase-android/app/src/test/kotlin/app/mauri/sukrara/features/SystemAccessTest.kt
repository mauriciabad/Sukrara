// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.features

import app.mauri.sukrara.features.SystemAccess.Module
import app.mauri.sukrara.features.SystemAccess.Target
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When System UI needs a restart (features/SystemAccess.decide). LSPosed reports STALE after every Sukrara install
 * (the module is the app): only a change of the code System UI runs (the HOOK_CODE fingerprint) is worth a restart.
 */
class SystemAccessTest {
    private val v = 11038L
    private val code = "aaaa"
    private val known = mapOf(11037L to "aaaa", 11036L to "bbbb")
    private fun decide(inScope: Boolean = true, target: Target?) = SystemAccess.decide(inScope, target, v, code) { known[it] }

    @Test fun notInScope() = assertEquals(Module.NO_SCOPE, decide(inScope = false, target = Target("UP_TO_DATE", v)))

    @Test fun notRunningOrFailedNeedsARestart() {
        assertEquals(Module.RESTART, decide(target = null))
        assertEquals(Module.RESTART, decide(target = Target("FAILED", v)))
    }

    @Test fun upToDateOrReloadingIsActive() {
        assertEquals(Module.ACTIVE, decide(target = Target("UP_TO_DATE", v)))
        // LSPosed hot-reloading the module: no restart to ask for (was UPDATE: a false positive)
        assertEquals(Module.ACTIVE, decide(target = Target("RELOADING", 11037)))
    }

    /** The false positive: an update that didn't touch System UI's code. */
    @Test fun staleWithTheSameSystemUiCodeIsActive() = assertEquals(Module.ACTIVE, decide(target = Target("STALE", 11037)))

    @Test fun staleWithOtherOrUnknownCodeNeedsARestart() {
        assertEquals(Module.UPDATE, decide(target = Target("STALE", 11036)))   // the hook or the card changed
        assertEquals(Module.UPDATE, decide(target = Target("STALE", 11030)))   // installed before the record existed
        // a rebuild with the same versionCode: its code can't be told apart, so ask
        assertEquals(Module.UPDATE, decide(target = Target("STALE", v)))
    }

    @Test fun homeCardOnlyWhenAModuleFeatureIsOn() {
        val off = FeatureState(hook = false, lockWidget = false)
        val restart = SystemAccess.Status(Module.UPDATE, loaded = 11036)
        assertFalse(SystemAccess.homeBanner(restart, off, null))
        assertTrue(SystemAccess.homeBanner(restart, off.copy(lockWidget = true), null))
        assertTrue(SystemAccess.homeBanner(restart, off.copy(hook = true), null))
        // the value with the system icons, no colour, Sukrara's icon kept: root alone, no module code
        assertFalse(SystemAccess.homeBanner(restart, off.copy(hook = true, systemIcons = true, hookColor = false, hideNotifIcon = false), null))
        assertFalse(SystemAccess.homeBanner(SystemAccess.Status(Module.ACTIVE), off.copy(lockWidget = true), null))
        assertFalse(SystemAccess.homeBanner(SystemAccess.Status(Module.MISSING), off.copy(lockWidget = true), null))
    }

    @Test fun dismissedUntilTheStateChanges() {
        val f = FeatureState(lockWidget = true)
        val s = SystemAccess.Status(Module.UPDATE, loaded = 11036)
        assertFalse(SystemAccess.homeBanner(s, f, SystemAccess.key(s)))
        assertTrue(SystemAccess.homeBanner(s.copy(loaded = 11037), f, SystemAccess.key(s)))
        assertTrue(SystemAccess.homeBanner(s.copy(module = Module.RESTART), f, SystemAccess.key(s)))
    }
}
