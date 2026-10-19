// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.core

import app.mauri.sukrara.bridge.Bridge
import app.mauri.sukrara.bridge.NightscoutApi
import app.mauri.sukrara.export.GlucoseCsv
import app.mauri.sukrara.nightscout.NsApi
import app.mauri.sukrara.nightscout.NsDocs
import app.mauri.sukrara.nightscout.NightscoutQueue
import app.mauri.sukrara.tidepool.TidepoolData
import java.time.ZoneId
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every output names the real sensor (investigation 23): never Abbott / FreeStyle Libre 3 / "Libre 3" for another
 * family, "(untested)" where the format has free text, and the Libre 3's outputs exactly as before.
 */
class SensorOutputsTest {
    private val untested = SensorProfiles.all.filter { !it.verified }
    private val utc = TimeZone.getTimeZone("UTC")

    private fun claimsLibre(s: String) = listOf("Abbott", "Libre").any { s.contains(it, ignoreCase = true) }

    @Test fun readingsAreTaggedWithTheirFamily() {
        for (p in SensorProfiles.all) assertEquals(p, SensorOutputs.profileOf(p.id))
        assertEquals(SensorProfiles.LIBRE_3, SensorOutputs.profileOf("juggluco"))   // Juggluco's Libre, as before
        assertNull(SensorOutputs.profileOf(""))
        assertNull(SensorOutputs.profileOf("lingo"))
    }

    @Test fun healthConnectDevice() {
        assertEquals("Abbott" to "FreeStyle Libre 3", SensorOutputs.healthConnectDevice("libre3"))   // unchanged
        assertEquals("Dexcom" to "Dexcom G7 / ONE+ (untested)", SensorOutputs.healthConnectDevice("dexcom-g7"))
        assertEquals("Roche" to "Accu-Chek SmartGuide (untested)", SensorOutputs.healthConnectDevice("accu-chek-smartguide"))
        assertEquals("Microtech" to "Aidex X / LinX / GlucoRx (untested)", SensorOutputs.healthConnectDevice("aidex-x"))
        assertNull(SensorOutputs.healthConnectDevice(""))
        for (p in untested) {
            val (maker, model) = SensorOutputs.healthConnectDevice(p.id)!!
            assertFalse(p.id, claimsLibre(maker) || claimsLibre(model))
            assertTrue(p.id, model.endsWith("(untested)"))
        }
    }

    @Test fun tidepoolNamesTheSensorInThePayload() {
        val libre = TidepoolData.cbg(TidepoolData.Glucose(1_760_000_000, 120, null, "FreeStyle Libre 3"), ZoneId.of("UTC"), "dev", "1")
        assertEquals("{\"sensor\":\"FreeStyle Libre 3\"}", libre["payload"].toString())
        for (p in untested) {
            val d = TidepoolData.cbg(TidepoolData.Glucose(1_760_000_000, 120, null, SensorOutputs.model(p)), ZoneId.of("UTC"), "dev", "1")
            assertEquals("\"${SensorOutputs.model(p)}\"", (d["payload"] as kotlinx.serialization.json.JsonObject)["sensor"].toString())
            assertFalse(p.id, claimsLibre(d.toString()))
        }
        // unknown sensor (a follower's copy): no payload, and the data set names only Sukrara
        assertNull(TidepoolData.cbg(TidepoolData.Glucose(1_760_000_000, 120), ZoneId.of("UTC"), "dev", "1")["payload"])
        assertFalse(claimsLibre(TidepoolData.dataSet("dev", "Sukrara", "1", ZoneId.of("UTC"), 1_760_000_000).toString()))
    }

    @Test fun nightscoutUploadDevice() {
        assertEquals("Sukrara", SensorOutputs.uploader("Sukrara", SensorProfiles.LIBRE_3))   // unchanged: Nightscout dedupes on it
        assertEquals("Sukrara", SensorOutputs.uploader("Sukrara", null))
        for (p in untested) {
            val device = SensorOutputs.uploader("Sukrara", p)
            assertEquals("Sukrara · ${SensorOutputs.model(p)}", device)
            val e = NsDocs.entry(Reading(1_760_000_000, 120, null, p.id), null, device, utc, NsApi.V1)
            assertEquals(device, e.getString("device"))
            assertFalse(claimsLibre(e.toString()))
            // a family change is still this uploader, not "another device" that pauses the upload
            assertTrue(NsDocs.ours(device, "Sukrara"))
            val ev = NsDocs.sensorEvents(SensorInfo("8GABCD", 1_760_000_000, 1_761_000_000), "Sukrara", utc, p)
            assertTrue(ev.all { it.getString("device") == device && it.getString("notes") == "${SensorOutputs.model(p)} 8GABCD" })
        }
        assertFalse(NsDocs.ours("xDrip-DexcomG7", "Sukrara"))
        val libre = NsDocs.sensorEvents(SensorInfo("3MH0001", 1_760_000_000, 1_761_000_000), "Sukrara", utc, SensorProfiles.LIBRE_3)
        assertTrue(libre.all { it.getString("device") == "Sukrara" && it.getString("notes") == "Libre 3 3MH0001" })
        // the conflict check ignores our own entries under any family
        val st = NightscoutQueue.reconciled(NightscoutQueue.State(), mapOf(1_760_000_000_000L to "Sukrara · Dexcom G7 / ONE+ (untested)"),
            1_759_999_000, 1_760_000_060, "Sukrara", 1_760_000_060_000)
        assertNull(st.conflict)
    }

    @Test fun phoneNightscoutApiDevice() {
        val r = Bridge.Reading(1_760_000_000, 120, null)
        assertEquals("Sukrara", NightscoutApi.entry(r, listOf(r)).getString("device"))
        assertEquals("Sukrara", NightscoutApi.device(SensorProfiles.LIBRE_3))
        assertEquals("Sukrara · Accu-Chek SmartGuide (untested)", NightscoutApi.device(SensorProfiles.SMARTGUIDE))
        assertEquals("Sukrara · Accu-Chek SmartGuide (untested)",
            NightscoutApi.entry(r, listOf(r), NightscoutApi.device(SensorProfiles.SMARTGUIDE)).getString("device"))
    }

    @Test fun csvDeviceColumn() {
        val rows = listOf(GlucoseCsv.Row(1_760_000_000, 120, null))
        val libre = GlucoseCsv.write(rows, emptyList(), 1_759_000_000, 1_761_000_000, ZoneId.of("UTC"), 1_760_000_100,
            GlucoseCsv.Sensor("3MH0001", 1_759_000_000, 1_761_000_000))
        assertTrue(libre.lines()[2].startsWith("Sukrara,3MH0001,"))   // unchanged
        val g6 = GlucoseCsv.write(rows, emptyList(), 1_759_000_000, 1_761_000_000, ZoneId.of("UTC"), 1_760_000_100,
            GlucoseCsv.Sensor("8GABCD", 1_759_000_000, 1_761_000_000, SensorOutputs.model(SensorProfiles.DEXCOM_G6)))
        assertTrue(g6.lines()[2], g6.lines()[2].startsWith("Sukrara · Dexcom G6 / ONE (untested),8GABCD,"))
        assertFalse(claimsLibre(g6.lines().drop(2).joinToString()))
    }

    @Test fun libreViewOnlyForTheLibre3() {
        assertTrue(SensorOutputs.libreView(SensorProfiles.LIBRE_3))
        assertFalse(SensorOutputs.libreView(null))
        for (p in SensorProfiles.all.filter { it.id != "libre3" }) assertFalse(p.id, SensorOutputs.libreView(p))
    }
}
