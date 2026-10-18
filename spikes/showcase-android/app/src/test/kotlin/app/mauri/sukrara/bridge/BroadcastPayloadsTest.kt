// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.bridge

import app.mauri.sukrara.bridge.BroadcastPayloads.GLUCODATA
import app.mauri.sukrara.bridge.BroadcastPayloads.LIBRE
import app.mauri.sukrara.bridge.BroadcastPayloads.LIBRE_ACTIVATE
import app.mauri.sukrara.bridge.BroadcastPayloads.XDRIP
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Investigation 22: the broadcasts against what the receivers read (keys and **types** from their sources, docs/22 §2;
 * a wrong type is silent on Android), and who may get them (wearer only, allowed apps only, the actions each declares).
 */
class BroadcastPayloadsTest {
    private val r = BroadcastPayloads.Out(mgdl = 123, rate = -1.4, timeMs = 1_760_000_000_000L, serial = "3MH0012ABC",
        sensorStartMs = 1_759_700_000_000L)

    @Test fun xdripMatchesAapsAndGdh() {
        val x = BroadcastPayloads.xdrip(r)
        // AAPS XdripSourceWorker: getLong(Time), getDouble(BgEstimate), getString(BgSlopeName), getString(SourceInfo),
        // getLong(SensorStartedAt). GDH XDripBroadcastReceiver: getDouble(BgSlope) * 60000.
        assertEquals(1_760_000_000_000L, x[BroadcastPayloads.X_TIME] as Long)
        assertEquals(123.0, x[BroadcastPayloads.X_BG] as Double, 0.0)
        assertEquals("FortyFiveDown", x[BroadcastPayloads.X_SLOPE_NAME])
        assertEquals(-1.4, (x[BroadcastPayloads.X_SLOPE] as Double) * 60_000, 1e-9)
        assertEquals(1_759_700_000_000L, x[BroadcastPayloads.X_SENSOR_STARTED] as Long)
    }

    /** AAPS: SourceSensor.LIBRE_3.text == "Libre3" (exact match) → advanced filtering. Never a Dexcom label. */
    @Test fun sensorLabelIsLibre3NeverDexcom() {
        val info = BroadcastPayloads.xdrip(r)[BroadcastPayloads.X_SOURCE_INFO]
        assertEquals("Libre3", info)
        for (p in listOf(BroadcastPayloads.xdrip(r), BroadcastPayloads.libre(r), BroadcastPayloads.glucodata(r)))
            assertFalse(p.toString(), p.toString().contains("dexcom", ignoreCase = true))
        assertFalse(BroadcastPayloads.ACTIONS.any { it.contains("dexcom", ignoreCase = true) })
    }

    @Test fun unknownRateAndStartAreLeftOut() {
        val x = BroadcastPayloads.xdrip(r.copy(rate = null, sensorStartMs = 0))
        assertEquals("", x[BroadcastPayloads.X_SLOPE_NAME])
        assertFalse(BroadcastPayloads.X_SLOPE in x)
        assertFalse(BroadcastPayloads.X_SENSOR_STARTED in x)
        assertFalse("sas" in BroadcastPayloads.libre(r.copy(sensorStartMs = 0)))
    }

    @Test fun libreMatchesXdripAndGdh() {
        val l = BroadcastPayloads.libre(r)
        // xDrip+ LibreReceiver: getDoubleExtra(glucose), getLongExtra(timestamp), bleManager.getString(sensorSerial) non-null
        assertEquals(123.0, l["glucose"] as Double, 0.0)
        assertEquals(1_760_000_000_000L, l["timestamp"] as Long)
        assertEquals("3MH0012ABC", (l["bleManager"] as Map<*, *>)["sensorSerial"])
        assertEquals(1_759_700_000_000L, ((l["sas"] as Map<*, *>)["currentSensor"] as Map<*, *>)["sensorStartTime"] as Long)
        val a = BroadcastPayloads.libreActivate(r)
        assertEquals(1_759_700_000_000L, (a["sensor"] as Map<*, *>)["sensorStartTime"] as Long)
        assertEquals("3MH0012ABC", (a["bleManager"] as Map<*, *>)["sensorSerial"])
    }

    @Test fun glucodataMatchesGdh() {
        val g = BroadcastPayloads.glucodata(r)
        assertEquals(123, g["glucodata.Minute.mgdl"] as Int)
        assertEquals(123f, g["glucodata.Minute.glucose"] as Float, 0f)
        assertEquals(-1.4f, g["glucodata.Minute.Rate"] as Float, 0f)
        assertEquals(1_760_000_000_000L, g["glucodata.Minute.Time"] as Long)
        assertEquals("3MH0012ABC", g["glucodata.Minute.SerialNumber"])
        assertEquals(0, g["glucodata.Minute.Alarm"] as Int)
    }

    @Test fun trendNamesAreTheXdripSet() {
        val names = listOf(4.0, 2.5, 1.2, 0.0, -1.2, -2.5, -4.0).map(BroadcastPayloads::trendName)
        assertEquals(listOf("DoubleUp", "SingleUp", "FortyFiveUp", "Flat", "FortyFiveDown", "SingleDown", "DoubleDown"), names)
        assertEquals("", BroadcastPayloads.trendName(null))
    }

    private val declared = mapOf(
        "info.nightscout.androidaps" to setOf(XDRIP),
        "de.michelinside.glucodatahandler" to setOf(XDRIP, LIBRE, LIBRE_ACTIVATE, GLUCODATA),
        "com.eveningoutpost.dexdrip" to setOf(LIBRE, LIBRE_ACTIVATE),
    )

    @Test fun nothingIsAllowedByDefault() {
        assertEquals(emptyMap<String, List<String>>(), BroadcastPayloads.plan(true, true, declared, emptySet(), newSensor = true))
    }

    @Test fun onlyAllowedAppsGetOnlyWhatTheyDeclare() {
        val p = BroadcastPayloads.plan(true, true, declared, setOf("info.nightscout.androidaps", "com.eveningoutpost.dexdrip", "not.installed"), newSensor = false)
        assertEquals(mapOf("info.nightscout.androidaps" to listOf(XDRIP), "com.eveningoutpost.dexdrip" to listOf(LIBRE)), p)
        val all = BroadcastPayloads.plan(true, true, declared, declared.keys, newSensor = true)
        assertEquals(listOf(XDRIP, LIBRE, LIBRE_ACTIVATE, GLUCODATA), all["de.michelinside.glucodatahandler"])
    }

    /** A follower never broadcasts (AAPS must not take someone else's readings as a local sensor); nor Juggluco mode. */
    @Test fun wearerReadingTheSensorOnly() {
        assertEquals(emptyMap<String, List<String>>(), BroadcastPayloads.plan(wearer = false, direct = true, declared, declared.keys, true))
        assertEquals(emptyMap<String, List<String>>(), BroadcastPayloads.plan(wearer = true, direct = false, declared, declared.keys, true))
    }

    @Test fun throttleLikeJuggluco() {
        assertTrue(BroadcastPayloads.due(0, 1_760_000_000_000L))   // the first one
        assertFalse(BroadcastPayloads.due(60_000, 60_000))          // same reading twice
        assertFalse(BroadcastPayloads.due(60_000, 30_000))          // older
        assertFalse(BroadcastPayloads.due(60_000, 110_000))         // < 55 s
        assertTrue(BroadcastPayloads.due(60_000, 120_000))
    }

    /** Health Connect dedupes on clientRecordId: one id per minute, the same for any second in it. */
    @Test fun healthConnectIdIsPerMinute() {
        assertEquals(HealthConnect.clientId(1_760_000_040L), HealthConnect.clientId(1_760_000_059L))
        assertFalse(HealthConnect.clientId(1_760_000_040L) == HealthConnect.clientId(1_760_000_100L))
    }
}
