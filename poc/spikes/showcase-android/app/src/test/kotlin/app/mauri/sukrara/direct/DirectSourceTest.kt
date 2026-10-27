// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.direct

import app.mauri.sukrara.core.SensorProfiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How the direct source reaches the saved sensor (investigation 23, step 2). The Libre 3's path must not change:
 * it always connects by the address from its NFC tap and **never scans**.
 */
class DirectSourceTest {
    private fun libre3(address: String) = SensorPairing("3MH0001", address, 1_000, ByteArray(4), null, 0)

    @Test fun libre3NeverScans() {
        assertEquals(DirectSource.Transport.ADDRESS, DirectSource.transport(SensorProfiles.LIBRE_3, libre3("18:32:19:B7:1D:11")))
        // even a broken file without an address doesn't make it scan
        assertEquals(DirectSource.Transport.ADDRESS, DirectSource.transport(SensorProfiles.LIBRE_3, libre3("")))
        // the real security core is JNI: a stub that is never called (the driver isn't connected here)
        val noCore = java.lang.reflect.Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Libre3Security::class.java)) { _, _, _ ->
            error("not called") } as Libre3Security
        val driver: SensorDriver = Libre3Driver(libre3("18:32:19:B7:1D:11"), BrandEvents(), BrandPrefs(), noCore)
        assertTrue("the Libre 3 driver has nothing to scan for", driver.scan.isEmpty())
    }

    @Test fun otherFamiliesScanOnceThenGoByAddress() {
        for (p in SensorProfiles.all.filter { it.needsScan }) {
            val fresh = SensorPairing.forBrand(p, if (p == SensorProfiles.DEXCOM_G6) "8GABCD" else if (p == SensorProfiles.AIDEX_X) "22222A2WC8" else "")
            assertEquals(p.id, DirectSource.Transport.SCAN, DirectSource.transport(p, fresh))
            assertEquals(p.id, DirectSource.Transport.ADDRESS, DirectSource.transport(p, fresh.copy(address = "AA:BB:CC:DD:EE:FF")))
            val driver = Drivers.byId(p.id)!!.make(fresh, BrandEvents(), BrandPrefs())
            assertTrue("${p.id}: scans must be filtered", driver.scan.isNotEmpty() && driver.scan.all { it.service != null || it.name != null })
        }
    }

    @Test fun brandRecord() {
        val p = SensorPairing.forBrand(SensorProfiles.DEXCOM_G6, "8GABCD")
        assertEquals("dexcom-g6", p.profileId)
        assertEquals("", p.address)
        assertEquals(0L, p.startEpoch)
        assertEquals(SensorProfiles.DEXCOM_G6.wearMin, p.wearMin)
        assertEquals(SensorProfiles.DEXCOM_G6.warmupMin, p.warmupMin)
        assertEquals(null, Drivers.byId("dexcom-g6")!!.unsupported(p))
        assertEquals("Dexcom G6 / ONE: \"8GAB\" is not a valid id", Drivers.byId("dexcom-g6")!!.unsupported(p.copy(serial = "8GAB")))
    }
}
