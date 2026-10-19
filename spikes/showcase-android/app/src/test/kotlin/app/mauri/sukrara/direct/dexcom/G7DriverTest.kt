// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.direct.dexcom

import app.mauri.sukrara.core.SensorProfiles
import app.mauri.sukrara.direct.Advert
import app.mauri.sukrara.direct.BrandEvents
import app.mauri.sukrara.direct.BrandLink
import app.mauri.sukrara.direct.BrandPrefs
import app.mauri.sukrara.direct.DriverPrefs
import app.mauri.sukrara.direct.QUIET
import app.mauri.sukrara.direct.SensorPairing
import app.mauri.sukrara.direct.Trace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Dexcom G7 / ONE+ / Stelo observer mode (investigation 23, step 2). The expected values are LoopKit G7SensorKit's own
 * test expectations (`G7GlucoseMessageTests.swift`, MIT) and DiaBLE's annotated frames: a cross-implementation check
 * of the port, not a restatement of it. Untested on hardware.
 */
class G7DriverTest {
    private val trace = Trace.resource("g7-session")
    private val nowMs = 1_800_000_000_000L
    private val nowS = nowMs / 1000

    private fun driver(events: BrandEvents = BrandEvents(), prefs: BrandPrefs = BrandPrefs()) =
        G7Driver(SensorPairing.forBrand(SensorProfiles.DEXCOM_G7, ""), events, prefs, clock = { nowMs }, log = QUIET)

    private val chars = setOf(Dexcom.AUTHENTICATION, Dexcom.CONTROL, Dexcom.BACKFILL)

    @Test fun egvDecodesLikeG7SensorKit() {
        val egv = G7Messages.Egv.parse(trace["egv-97"])!!
        assertEquals(2005L, egv.secondsSinceStart)
        assertEquals(9, egv.sequence)
        assertEquals(5, egv.ageSeconds)
        assertEquals(97, egv.mgdl)
        assertEquals(2000L, egv.readingSeconds)
        assertEquals(6, egv.state)
        assertEquals(0.1f, egv.trend!!, 1e-6f)
        assertNull(egv.predictedMgdl)
        assertFalse(egv.displayOnly)
        assertTrue(egv.usable)

        val a = G7Messages.Egv.parse(trace["egv-138"])!!
        assertEquals(138, a.mgdl); assertEquals(87485L, a.readingSeconds); assertFalse(a.displayOnly)
        val b = G7Messages.Egv.parse(trace["egv-104"])!!
        assertEquals(104, b.mgdl); assertEquals(901390L, b.readingSeconds); assertTrue(b.displayOnly)
    }

    @Test fun lifecycleStatesAndSequences() {
        val expected = listOf(
            Triple("life-0", "stopped", 1), Triple("life-1", "warming up", 3), Triple("life-2", "warming up", 4),
            Triple("life-3", "warming up", 5), Triple("life-4", "warming up", 6), Triple("life-5", "warming up", 7),
            Triple("life-6", "ok", 8), Triple("life-7", "ok", 9), Triple("life-8", "expired", 3028),
        )
        for ((label, state, sequence) in expected) {
            val egv = G7Messages.Egv.parse(trace[label])!!
            assertEquals(label, state, Dexcom.stateName(egv.state))
            assertEquals(label, sequence, egv.sequence)
        }
        assertNull(G7Messages.Egv.parse(trace["life-0"])!!.mgdl)
        assertEquals(145, G7Messages.Egv.parse(trace["life-2"])!!.mgdl)
        assertFalse(G7Messages.Egv.parse(trace["life-2"])!!.usable)   // still warming up
        assertTrue(G7Messages.Egv.parse(trace["life-6"])!!.usable)
    }

    @Test fun backfillRecords() {
        val r = G7Messages.BackfillRecord.parse(trace["backfill-record"])!!
        assertEquals(41285, r.secondsSinceStart); assertEquals(150, r.mgdl); assertEquals(6, r.state)
        assertEquals(-0.4f, r.trend!!, 1e-6f); assertTrue(r.usable)
        assertEquals(3, G7Messages.BackfillRecord.parseAll(trace["backfill-record"] + trace["backfill-record"] + trace["backfill-record"]).size)
    }

    /** Observer mode: it reads only after the Dexcom app's session goes live, and **never writes to the sensor**. */
    @Test fun observerReadsAlongAndNeverWrites() {
        val events = BrandEvents()
        val link = BrandLink()
        val d = driver(events)
        d.onConnected(link, chars, 0)
        assertEquals(G7Driver.Phase.WATCHING, d.phase)
        assertEquals(G7Driver.WAITING_FOR_APP, events.last().first)

        d.onNotification(Dexcom.AUTHENTICATION, byteArrayOf(0x05, 0x00, 0x00))   // not authenticated yet
        assertEquals(G7Driver.Phase.WATCHING, d.phase)
        d.onNotification(Dexcom.AUTHENTICATION, byteArrayOf(0x05, 0x01, 0x01))   // the Dexcom app is in
        assertEquals(G7Driver.Phase.STREAMING, d.phase)

        d.onNotification(Dexcom.CONTROL, trace["egv-97"])
        assertEquals(listOf(97), events.readings.map { it.mgdl })
        assertEquals(nowS - 2005 + 2000, events.readings.single().epochSec)   // measured 5 s ago
        assertEquals(listOf(nowS - 2005), events.starts)                      // the session start, for the warm-up and the end
        assertEquals("live", events.last().first)
        // the same message again (another subscriber's copy) is not a second reading
        d.onNotification(Dexcom.CONTROL, trace["egv-97"])
        assertEquals(1, events.readings.size)

        assertEquals(listOf("sub 3535 ind", "sub 3534", "sub 3536"), link.ops)
        assertTrue("observer mode must not write to the sensor: ${link.characteristicWrites}", link.characteristicWrites.isEmpty())
    }

    @Test fun warmingUpThenStoppedThenExpired() {
        val events = BrandEvents()
        val prefs = BrandPrefs()
        val link = BrandLink()
        val d = driver(events, prefs)
        d.onConnected(link, chars, 0)
        d.onNotification(Dexcom.AUTHENTICATION, byteArrayOf(0x05, 0x01, 0x01))
        d.onNotification(Dexcom.CONTROL, trace["life-0"])   // "stopped" before the session starts: a wait, not the end
        assertTrue(events.ended.isEmpty())
        d.onNotification(Dexcom.CONTROL, trace["life-2"])
        assertEquals("warming up", events.last().first)
        assertTrue(events.readings.isEmpty())
        d.onNotification(Dexcom.CONTROL, trace["life-8"])   // expired: the end, no more connections
        assertEquals("sensor ended", events.last().first)
        assertEquals(listOf(""), events.ended)
        assertEquals(true, prefs.map[DriverPrefs.endedKey("")])
        assertEquals(1, link.closed)
    }

    @Test fun backfillIsCollectedPassivelyAndFlushed() {
        val events = BrandEvents()
        val d = driver(events)
        d.onConnected(BrandLink(), chars, 0)
        d.onNotification(Dexcom.AUTHENTICATION, byteArrayOf(0x05, 0x01, 0x01))
        d.onNotification(Dexcom.CONTROL, trace["egv-97"])
        d.onNotification(Dexcom.BACKFILL, trace["backfill-record"])
        assertTrue(events.history.isEmpty())
        d.onNotification(Dexcom.CONTROL, trace["backfill-reply"])   // 0x59: done
        assertEquals(listOf(150), events.history.map { it.mgdl })
        assertEquals(nowS - 2005 + 41285, events.history.single().epochSec)
    }

    /** Only a G7 this phone is bonded with (the Dexcom app paired it): a stranger's sensor nearby is never read. */
    @Test fun matchesOnlyABondedDexcom() {
        val d = driver()
        assertTrue(d.matches(Advert("AA", "DXCM4A", bonded = true)))
        assertTrue(d.matches(Advert("AA", "DX02B1", bonded = true)))
        assertFalse(d.matches(Advert("AA", "DXCM4A", bonded = false)))
        assertFalse(d.matches(Advert("AA", "Dexcom4A", bonded = true)))
        assertFalse(d.matches(Advert("AA", null, bonded = true)))
        assertEquals(listOf(Dexcom.ADVERTISEMENT), d.scan.map { it.service })
    }

    @Test fun fifteenDayProfile() {
        val d = G7Driver(SensorPairing.forBrand(SensorProfiles.DEXCOM_G7_15, ""), BrandEvents(), BrandPrefs(), SensorProfiles.DEXCOM_G7_15, log = QUIET)
        assertEquals(15 * 24 * 60, d.profile.wearMin)
    }
}
