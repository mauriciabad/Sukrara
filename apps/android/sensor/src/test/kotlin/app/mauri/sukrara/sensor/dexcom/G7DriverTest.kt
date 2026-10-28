// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.sensor.dexcom

import app.mauri.sukrara.core.model.GlucoseRange
import app.mauri.sukrara.core.model.SourceId
import app.mauri.sukrara.sensor.SensorProfile
import app.mauri.sukrara.sensor.ble.LinkStatus
import app.mauri.sukrara.sensor.ble.NoValueReason
import app.mauri.sukrara.sensor.brand.Advert
import app.mauri.sukrara.sensor.brand.BrandEvents
import app.mauri.sukrara.sensor.brand.BrandLink
import app.mauri.sukrara.sensor.brand.Trace
import app.mauri.sukrara.sensor.brand.epochSec
import app.mauri.sukrara.sensor.pairing.Pairing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Dexcom G7 / ONE+ / Stelo, read along with the Dexcom app. The expected values are LoopKit G7SensorKit's own test
 * expectations (`G7GlucoseMessageTests.swift`, MIT) and DiaBLE's annotated frames: a cross-implementation check of the
 * port, not a restatement of it. Untested on a real sensor.
 */
class G7DriverTest {
    private val trace = Trace.resource("g7-session")
    private val nowMs = 1_800_000_000_000L
    private val nowS = nowMs / 1000
    private val chars = setOf(Dexcom.AUTHENTICATION, Dexcom.CONTROL, Dexcom.BACKFILL)

    private fun driver(
        events: BrandEvents = BrandEvents(),
        profile: SensorProfile = SensorProfile.DexcomG7,
    ) = G7Driver(Pairing.scanned(profile, "", 1L), events, clockMs = { nowMs })

    private fun streaming(events: BrandEvents, link: BrandLink = BrandLink()): G7Driver =
        driver(events).also {
            it.onConnected(link, chars, 0)
            it.onNotification(Dexcom.AUTHENTICATION, byteArrayOf(0x05, 0x01, 0x01))
        }

    @Test
    fun egvDecodesLikeG7SensorKit() {
        val egv = G7Messages.Egv.parse(trace["egv-97"])!!
        assertEquals(2005L, egv.secondsSinceStart)
        assertEquals(9, egv.sequence)
        assertEquals(5, egv.ageSeconds)
        assertEquals(97, egv.mgdl)
        assertEquals(2000L, egv.readingSeconds)
        assertEquals(Dexcom.State.Ok, Dexcom.state(egv.state))
        assertEquals(0.1, egv.trend!!, 1e-9)
        assertEquals(87485L, G7Messages.Egv.parse(trace["egv-138"])!!.readingSeconds)
        assertEquals(901390L, G7Messages.Egv.parse(trace["egv-104"])!!.readingSeconds)
    }

    @Test
    fun lifecycleStates() {
        val expected =
            listOf(
                "life-0" to Dexcom.State.Stopped,
                "life-2" to Dexcom.State.WarmingUp,
                "life-6" to Dexcom.State.Ok,
                "life-8" to Dexcom.State.Ended,
            )
        for ((label, state) in expected) assertEquals(label, state, Dexcom.state(G7Messages.Egv.parse(trace[label])!!.state))
        assertNull(G7Messages.Egv.parse(trace["life-0"])!!.mgdl)
    }

    @Test
    fun backfillRecords() {
        val r = G7Messages.BackfillRecord.parse(trace["backfill-record"])!!
        assertEquals(41285, r.secondsSinceStart)
        assertEquals(150, r.mgdl)
        assertEquals(-0.4, r.trend!!, 1e-9)
        val three = trace["backfill-record"] + trace["backfill-record"] + trace["backfill-record"]
        assertEquals(3, G7Messages.BackfillRecord.parseAll(three).size)
    }

    /** It reads only after the Dexcom app's session is live, and never writes to the sensor. */
    @Test
    fun readsAlongAndNeverWrites() {
        val events = BrandEvents()
        val link = BrandLink()
        val d = driver(events)
        d.onConnected(link, chars, 0)
        assertEquals(G7Driver.Phase.Watching, d.phase)
        assertEquals(LinkStatus.WaitingForVendorApp, events.last())
        d.onNotification(Dexcom.CONTROL, trace["egv-97"]) // before the Dexcom app's session: ignored
        d.onNotification(Dexcom.AUTHENTICATION, byteArrayOf(0x05, 0x00, 0x00))
        assertEquals(G7Driver.Phase.Watching, d.phase)
        assertTrue(events.readings.isEmpty())
        d.onNotification(Dexcom.AUTHENTICATION, byteArrayOf(0x05, 0x01, 0x01))
        assertEquals(G7Driver.Phase.Streaming, d.phase)

        d.onNotification(Dexcom.CONTROL, trace["egv-97"])
        val r = events.readings.single()
        assertEquals(97, r.mgdl)
        assertEquals(SourceId.DexcomG7, r.source)
        assertEquals(nowS - 2005 + 2000, r.epochSec) // measured 5 s before it was sent
        assertEquals(listOf(nowS - 2005), events.starts)
        assertEquals(LinkStatus.Live, events.last())
        d.onNotification(Dexcom.CONTROL, trace["egv-97"]) // another subscriber's copy: not a second reading
        assertEquals(1, events.readings.size)

        assertEquals(listOf("sub 3535 ind", "sub 3534", "sub 3536"), link.ops)
        assertTrue("must not write to the sensor: ${link.characteristicWrites}", link.characteristicWrites.isEmpty())
    }

    @Test
    fun stoppedIsAWaitWarmingUpHasNoValueAndExpiredEnds() {
        val events = BrandEvents()
        val link = BrandLink()
        val d = streaming(events, link)
        d.onNotification(Dexcom.CONTROL, trace["life-0"]) // "stopped" before the session starts: a wait, not the end
        assertEquals(LinkStatus.NoSession, events.last())
        assertTrue(events.ended.isEmpty())
        d.onNotification(Dexcom.CONTROL, trace["life-2"])
        assertTrue(events.last() is LinkStatus.WarmingUp)
        assertTrue(events.readings.isEmpty())
        d.onNotification(Dexcom.CONTROL, trace["life-8"])
        assertEquals(LinkStatus.SensorEnded, events.last())
        assertEquals(1, events.ended.size)
        assertEquals(1, link.closed)
    }

    @Test
    fun outOfRangeIsAFlaggedReadingNeverANumber() {
        val events = BrandEvents()
        val d = streaming(events)
        d.onEgv(G7Messages.Egv(2005, 10, 5, mgdl = 35, state = 6, trend = -2.0))
        assertTrue(events.readings.last().isLo)
        assertEquals(GlucoseRange.LO, events.readings.last().mgdl)
        assertEquals(null, events.readings.last().rateMgdlPerMin) // no rate from a value nobody knows
        d.onEgv(G7Messages.Egv(2305, 11, 5, mgdl = 420, state = 6, trend = null))
        assertTrue(events.readings.last().isHi)
        assertEquals(GlucoseRange.HI, events.readings.last().mgdl)
        assertEquals(2, events.readings.size)
        // a legacy special code (1–12) or the all-ones field is no value, never a LO / HI
        d.onEgv(G7Messages.Egv(2405, 12, 5, mgdl = 5, state = 6, trend = null))
        assertEquals(LinkStatus.NoValue(NoValueReason.Missing), events.last())
        d.onEgv(G7Messages.Egv(2505, 13, 5, mgdl = 0xFFF, state = 6, trend = null))
        assertEquals(LinkStatus.NoValue(NoValueReason.Missing), events.last())
        d.onEgv(G7Messages.Egv(2605, 14, 5, mgdl = 120, state = 7, trend = null)) // needs calibration: not trusted
        assertEquals(LinkStatus.NoValue(NoValueReason.Calibration), events.last())
        assertEquals(2, events.readings.size)
    }

    @Test
    fun anOldValueIsHistoryNotLive() {
        val events = BrandEvents()
        val d = streaming(events)
        d.onEgv(G7Messages.Egv(2005, 10, ageSeconds = 600, mgdl = 120, state = 6, trend = null))
        assertTrue(events.readings.isEmpty())
        assertEquals(listOf(120), events.history.map { it.mgdl })
    }

    @Test
    fun backfillIsCollectedAndFlushedAsHistory() {
        val events = BrandEvents()
        val d = streaming(events)
        d.onEgv(G7Messages.Egv(50_000, 20, 5, mgdl = 120, state = 6, trend = null)) // the session is 50 000 s old
        d.onNotification(Dexcom.BACKFILL, trace["backfill-record"])
        assertTrue(events.history.isEmpty())
        d.onNotification(Dexcom.CONTROL, trace["backfill-reply"]) // 0x59: done
        assertEquals(listOf(150), events.history.map { it.mgdl })
        assertEquals(nowS - 50_000 + 41285, events.history.single().epochSec)
    }

    /** A record "from the future" (a backfill timed against a different session) is dropped, never shown. */
    @Test
    fun aFutureRecordIsDropped() {
        val events = BrandEvents()
        val d = streaming(events)
        d.onNotification(Dexcom.CONTROL, trace["egv-97"]) // the session is 2005 s old; the record says 41 285 s
        d.onNotification(Dexcom.BACKFILL, trace["backfill-record"])
        d.onNotification(Dexcom.CONTROL, trace["backfill-reply"])
        assertTrue(events.history.isEmpty())
    }

    @Test
    fun matchesOnlyABondedDexcom() {
        val d = driver()
        assertTrue(d.matches(Advert("AA", "DXCM4A", bonded = true)))
        assertTrue(d.matches(Advert("AA", "DX02B1", bonded = true)))
        assertFalse(d.matches(Advert("AA", "DXCM4A", bonded = false)))
        assertFalse(d.matches(Advert("AA", "Dexcom4A", bonded = true)))
        assertFalse(d.matches(Advert("AA", null, bonded = true)))
        assertEquals(listOf(Dexcom.ADVERTISEMENT), d.scan.map { it.service })
    }

    @Test
    fun fifteenDayProfile() {
        assertEquals(15 * 24 * 60, driver(profile = SensorProfile.DexcomG7Long).profile.wearMin)
    }
}
