// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.direct

import app.mauri.sukrara.core.SensorInfo
import app.mauri.sukrara.core.SensorProfiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** The driver registry (investigation 23, step 1) and the pairing file's family field. */
class DriversTest {
    private fun pairing(securityVersion: Int = 0) =
        SensorPairing("3MH0001", "18:32:19:B7:1D:11", 1_000, ByteArray(4), null, securityVersion)

    @Test fun oneDriverTheLibre3() {
        assertEquals(listOf(SensorProfiles.LIBRE_3), Drivers.all.map { it.profile })
        assertSame(SensorProfiles.LIBRE_3, Drivers.byId("libre3")!!.profile)
        assertNull(Drivers.byId("dexcom-g7"))
    }

    /** DirectSource's old `securityVersion != 0` check, now the Libre 3 entry's. */
    @Test fun lingoIsStillUnsupported() {
        val libre3 = Drivers.byId("libre3")!!
        assertNull(libre3.unsupported(pairing()))
        assertEquals("security version 2 (Lingo) is not implemented", libre3.unsupported(pairing(securityVersion = 2)))
    }

    /** A pairing written by the NFC scan is the Libre 3's; its SensorInfo is what it was before the profile. */
    @Test fun pairingDefaults() {
        val p = pairing()
        assertEquals("libre3", p.profileId)
        assertEquals(21_600, p.wearMin)
        assertEquals(60, p.warmupMin)
        assertEquals(1_000 + 60L * 21_600, p.endEpoch)
        assertEquals(SensorInfo("3MH0001", 1_000, 1_000 + 60L * 21_600, 1_000 + 3_600), p.info)
        assertEquals("SensorPairing(libre3 serial=3MH0001, address=18:32:19:B7:1D:11, start=1000, paired=false)", p.toString())
    }
}
