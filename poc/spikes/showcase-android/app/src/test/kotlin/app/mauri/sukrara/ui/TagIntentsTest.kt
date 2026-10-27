// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The home banner names what Android's tag-intent block stops; nothing to say while allowed or with no tap feature on. */
class TagIntentsTest {
    @Test fun whatTheBannerNames() {
        assertNull(NfcLaunch.blocked(allowed = true, pensOn = true, sensorOn = true))
        assertNull(NfcLaunch.blocked(allowed = false, pensOn = false, sensorOn = false))
        assertEquals(NfcLaunch.Blocked.PEN, NfcLaunch.blocked(allowed = false, pensOn = true, sensorOn = false))
        assertEquals(NfcLaunch.Blocked.SENSOR, NfcLaunch.blocked(allowed = false, pensOn = false, sensorOn = true))
        assertEquals(NfcLaunch.Blocked.BOTH, NfcLaunch.blocked(allowed = false, pensOn = true, sensorOn = true))
    }
}
