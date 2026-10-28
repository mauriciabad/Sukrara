// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.spike.outputs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The payloads against what the receivers read (keys and types copied from their sources, see
 * docs/22-ecosystem-outputs.md). A wrong type is silent on Android: getDouble() on a Long extra returns the default.
 */
class PayloadsTest {
    private val r = Reading(mgdl = 123, rate = -1.4f, timeMs = 1_760_000_000_000L, serial = "SPIKE0000022",
        sensorStartMs = 1_759_700_000_000L)

    @Test fun xdripMatchesAapsAndGdh() {
        val x = Payloads.xdrip(r)
        // AAPS XdripSourceWorker: getLong(Time), getDouble(BgEstimate), getString(BgSlopeName), getString(SourceInfo),
        // getLong(SensorStartedAt). GDH XDripBroadcastReceiver: getDouble(BgSlope) * 60000.
        assertEquals(1_760_000_000_000L, x[Payloads.X_TIME] as Long)
        assertEquals(123.0, x[Payloads.X_BG] as Double, 0.0)
        assertEquals("FortyFiveDown", x[Payloads.X_SLOPE_NAME])
        assertEquals(-1.4, (x[Payloads.X_SLOPE] as Double) * 60_000, 1e-6)
        assertEquals(1_759_700_000_000L, x[Payloads.X_SENSOR_STARTED] as Long)
        // AAPS SourceSensor.LIBRE_3.text == "Libre3" → advanced filtering in XdripSourcePlugin.detectSource.
        assertEquals("Libre3", x[Payloads.X_SOURCE_INFO])
    }

    @Test fun libreMatchesXdripAndGdh() {
        val l = Payloads.libre(r)
        // xDrip+ LibreReceiver.processIntent: hasExtra glucose/timestamp/bleManager, getDoubleExtra, getLongExtra,
        // bleManager.getString("sensorSerial") must be non-null.
        assertEquals(123.0, l["glucose"] as Double, 0.0)
        assertEquals(1_760_000_000_000L, l["timestamp"] as Long)
        assertEquals("SPIKE0000022", (l["bleManager"] as Map<*, *>)["sensorSerial"])
        val start = ((l["sas"] as Map<*, *>)["currentSensor"] as Map<*, *>)["sensorStartTime"]
        assertEquals(1_759_700_000_000L, start as Long)
    }

    @Test fun glucodataMatchesGdh() {
        val g = Payloads.glucodata(r)
        assertEquals(123, g["glucodata.Minute.mgdl"] as Int)
        assertEquals(-1.4f, g["glucodata.Minute.Rate"] as Float, 0f)
        assertEquals(1_760_000_000_000L, g["glucodata.Minute.Time"] as Long)
        assertEquals("SPIKE0000022", g["glucodata.Minute.SerialNumber"])
    }

    @Test fun trendNamesAreTheXdripSet() {
        val names = listOf(4f, 2.5f, 1.2f, 0f, -1.2f, -2.5f, -4f).map(Payloads::trendName)
        assertEquals(listOf("DoubleUp", "SingleUp", "FortyFiveUp", "Flat", "FortyFiveDown", "SingleDown", "DoubleDown"), names)
        assertEquals("", Payloads.trendName(Float.NaN))
    }

    @Test fun syntheticStaysInRangeAndOnTheMinute() {
        for (m in 0 until 600) {
            val s = Synthetic.at(1_760_000_000_000L + m * 60_000L + 1234)
            assertTrue(s.mgdl in 70..190)
            assertEquals(0L, s.timeMs % 60_000)
        }
    }
}
