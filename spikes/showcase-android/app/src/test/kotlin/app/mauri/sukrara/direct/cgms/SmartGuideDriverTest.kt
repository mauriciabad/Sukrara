// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.direct.cgms

import app.mauri.sukrara.core.SensorProfiles
import app.mauri.sukrara.direct.Advert
import app.mauri.sukrara.direct.BrandEvents
import app.mauri.sukrara.direct.BrandLink
import app.mauri.sukrara.direct.BrandPrefs
import app.mauri.sukrara.direct.QUIET
import app.mauri.sukrara.direct.SensorPairing
import app.mauri.sukrara.direct.Trace
import app.mauri.sukrara.direct.hex
import app.mauri.sukrara.direct.unhex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Accu-Chek SmartGuide / the standard CGM Service (investigation 23, step 3). Fixtures: real frames quoted in
 * Juggluco's `accu/java.cpp`, whose struct names say what each field is. Untested on hardware.
 */
class SmartGuideDriverTest {
    private val trace = Trace.resource("smartguide")
    private val nowMs = 1_800_000_000_000L
    private val nowS = nowMs / 1000
    private val chars = setOf(CgmService.MEASUREMENT, CgmService.STATUS, CgmService.RACP, CgmService.SOCP)

    private fun driver(events: BrandEvents) =
        SmartGuideDriver(SensorPairing.forBrand(SensorProfiles.SMARTGUIDE, ""), events, BrandPrefs(), clock = { nowMs }, log = QUIET)

    @Test fun sfloat() {
        assertEquals(123f, CgmService.sfloat(0x007B)!!, 1e-6f)
        assertEquals(12.3f, CgmService.sfloat(0xF07B)!!, 1e-4f)
        assertEquals(-5f, CgmService.sfloat(0x0FFB)!!, 1e-6f)
        assertNull(CgmService.sfloat(0x07FF))   // NaN
        assertNull(CgmService.sfloat(0x0802))   // -INFINITY (below the sensor's range)
    }

    @Test fun sensorErrorHasNoNumber() {
        val m = CgmService.Measurement.parse(trace["error-1"])!!
        assertNull(m.mgdl); assertNull(m.trendMgdlPerMin); assertEquals(0x46AF, m.offsetMinutes); assertEquals(true, m.crcOk)
        val events = BrandEvents()
        assertNull(driver(events).measurement(trace["error-1"]))
        assertTrue(events.readings.isEmpty())
    }

    @Test fun belowRangePostsTheFloorValue() {
        val m = CgmService.Measurement.parse(trace["low-1"])!!
        assertNull(m.mgdl); assertTrue(m.belowRange); assertEquals(true, m.crcOk)
        assertEquals(39, driver(BrandEvents()).measurement(trace["low-1"])!!.mgdl)   // what Juggluco posts too
    }

    /** Unbonded: Android's PIN dialog first; bonded: subscribe, the E2E-CRC'd SOCP write, the status read, RACP. */
    @Test fun bondThenTheConnectionSequence() {
        val events = BrandEvents()
        val link = BrandLink(isBonded = false)
        val d = driver(events)
        d.onConnected(link, chars, 0)
        assertEquals(listOf("bond"), link.ops)
        assertEquals(SmartGuideDriver.PAIRING, events.last().first)
        d.onBondChanged(true)
        assertEquals(listOf("bond", "sub 2AA7", "sub 2A52 ind", "sub 2AAC ind", "write 2AAC 02952c", "read 2AA9"), link.ops)

        d.onRead(CgmService.STATUS, trace["status"])   // 4762 minutes into the session
        assertEquals(listOf(nowS - 4762 * 60L), events.starts)
        assertEquals("write 2A52 ${CgmService.reportRecordsFrom(0).hex()}", link.ops.last())
    }

    @Test fun aRefusedPinNeedsTheUser() {
        val events = BrandEvents()
        val link = BrandLink(isBonded = false)
        val d = driver(events)
        d.onConnected(link, chars, 0)
        d.onBondChanged(false)
        assertEquals("failed", events.last().first)
        assertEquals(1, link.closed)
    }

    @Test fun liveAndStoredMeasurements() {
        val events = BrandEvents()
        val d = driver(events)
        d.onConnected(BrandLink(), chars, 0)
        d.onRead(CgmService.STATUS, trace["status"])
        // the current minute: a live reading; flags 0x43 = trend + quality + second annunciation byte
        d.onNotification(CgmService.MEASUREMENT, CgmService.withCrc(unhex("0d437b009a1200" + "0c005a00")))
        assertEquals(listOf(123), events.readings.map { it.mgdl })
        assertEquals(nowS, events.readings.single().epochSec)
        assertEquals(12.0, events.readings.single().rate!!, 1e-6)
        // an hour back (a RACP record): history, not the current value
        d.onNotification(CgmService.MEASUREMENT, CgmService.withCrc(unhex("0d437b005e1200" + "0c005a00")))
        assertEquals(1, events.readings.size)
        assertEquals(listOf(nowS - 3_600), events.history.map { it.epochSec })
    }

    @Test fun badCrcIsDroppedAndAStoppedSessionEnds() {
        val bad = CgmService.withCrc(unhex("0d437b001e00000c005a00")).also { it[2] = 0x7C }
        assertFalse(CgmService.crcOk(bad))
        assertNull(driver(BrandEvents()).measurement(bad))
        val events = BrandEvents()
        val d = driver(events)
        d.onConnected(BrandLink(), chars, 0)
        assertNull(d.measurement(CgmService.withCrc(unhex("09207b001e0001"))))
        assertEquals("sensor ended", events.last().first)
    }

    @Test fun controlPointBytes() {
        assertEquals("02952c", CgmService.getCommunicationInterval().hex())
        assertEquals("0105cdbe", CgmService.setCommunicationInterval(5).hex())
        assertEquals("0103012c01", CgmService.reportRecordsFrom(300).hex())
    }

    @Test fun matchesAnAccuChekWithTheCgmService() {
        val d = driver(BrandEvents())
        assertTrue(d.matches(Advert("AA", "AC-12345678", listOf(CgmService.SERVICE))))
        assertFalse(d.matches(Advert("AA", "AiDEX X-22222A2WC8", listOf(CgmService.SERVICE))))   // same service, not Roche
        assertFalse(d.matches(Advert("AA", "AC-12345678")))
    }
}
