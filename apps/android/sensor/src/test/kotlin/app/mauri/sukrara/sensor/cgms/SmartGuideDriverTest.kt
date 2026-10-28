// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.sensor.cgms

import app.mauri.sukrara.sensor.SensorProfile
import app.mauri.sukrara.sensor.ble.FailReason
import app.mauri.sukrara.sensor.ble.LinkStatus
import app.mauri.sukrara.sensor.ble.NoValueReason
import app.mauri.sukrara.sensor.brand.Advert
import app.mauri.sukrara.sensor.brand.BrandEvents
import app.mauri.sukrara.sensor.brand.BrandLink
import app.mauri.sukrara.sensor.brand.Trace
import app.mauri.sukrara.sensor.brand.epochSec
import app.mauri.sukrara.sensor.brand.hex
import app.mauri.sukrara.sensor.brand.unhex
import app.mauri.sukrara.sensor.pairing.Pairing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Accu-Chek SmartGuide over the standard CGM Service, against real SmartGuide frames quoted in Juggluco
 * (`accu/java.cpp`, GPL-3). Untested on a real sensor.
 */
class SmartGuideDriverTest {
    private val trace = Trace.resource("smartguide")
    private val nowMs = 1_800_000_000_000L
    private val nowS = nowMs / 1000
    private val chars = setOf(CgmService.MEASUREMENT, CgmService.STATUS, CgmService.RACP, CgmService.SOCP)

    private fun driver(events: BrandEvents) =
        SmartGuideDriver(
            Pairing.scanned(SensorProfile.SmartGuide, "", 1L),
            events,
            clockMs = { nowMs },
        )

    /** Bonded, subscribed, and the session start known from a status read: 4762 minutes ago. */
    private fun running(events: BrandEvents, link: BrandLink = BrandLink()): SmartGuideDriver =
        driver(events).also {
            it.onConnected(link, chars, 0)
            it.onStatusRead(trace["status"])
        }

    /** A measurement record with the E2E-CRC: size, [flags], glucose SFLOAT, offset, [rest]. */
    private fun record(flags: Int, glucose: Int, offset: Int, rest: String = ""): ByteArray {
        val body = unhex("%02x%04x%04x".format(flags, Integer.reverseBytes(glucose) ushr 16, Integer.reverseBytes(offset) ushr 16) + rest)
        val size = body.size + 1 + 2
        return CgmService.withCrc(byteArrayOf(size.toByte()) + body)
    }

    @Test
    fun sfloat() {
        assertEquals(123f, CgmService.sfloat(0x007B)!!, 1e-6f)
        assertEquals(12.3f, CgmService.sfloat(0xF07B)!!, 1e-4f)
        assertNull(CgmService.sfloat(0x07FF)) // NaN
        assertNull(CgmService.sfloat(0x0802)) // -INFINITY (below the sensor's range)
    }

    /** The annunciation octets follow the flags 0x80 / 0x40 / 0x20 (status, cal/temp, warning). */
    @Test
    fun realFramesDecode() {
        val error = CgmService.Measurement.parse(trace["error-1"])!!
        assertNull(error.mgdl)
        assertEquals(true, error.crcOk)
        assertTrue(error.annunciation.sensorMalfunction) // status 0x08
        val low = CgmService.Measurement.parse(trace["low-1"])!!
        assertNull(low.mgdl)
        assertTrue(low.annunciation.belowRange) // warning 0x40
        assertEquals(false, low.annunciation.sessionStopped)
        val status = CgmService.Status.parse(trace["status"])!!
        assertEquals(4762, status.offsetMinutes)
    }

    @Test
    fun bondThenTheConnectionSequence() {
        val events = BrandEvents()
        val link = BrandLink(isBonded = false)
        val d = driver(events)
        d.onConnected(link, chars, 0)
        assertEquals(listOf("bond"), link.ops)
        assertEquals(LinkStatus.EnterPin, events.last())
        d.onBondChanged(true)
        assertEquals(listOf("bond", "sub 2AA7", "sub 2A52 ind", "sub 2AAC ind", "write 2AAC 02952c", "read 2AA9"), link.ops)
        d.onStatusRead(trace["status"])
        assertEquals(listOf(nowS - 4762 * 60L), events.starts)
        assertEquals("write 2A52 ${CgmService.reportRecordsFrom(4762 - 24 * 60).hex()}", link.ops.last())
    }

    @Test
    fun aRefusedPinNeedsThePerson() {
        val events = BrandEvents()
        val link = BrandLink(isBonded = false)
        val d = driver(events)
        d.onConnected(link, chars, 0)
        d.onBondChanged(false)
        assertEquals(LinkStatus.Failed(FailReason.BondFailed), events.last())
        assertEquals(1, link.closed)
    }

    @Test
    fun liveAndStoredMeasurements() {
        val events = BrandEvents()
        val d = running(events)
        // 123 mg/dL now (offset 4762), trend +12 mg/dL/min ×0.1; and a stored one an hour earlier, in one notification
        val live = record(0x01, 0x007B, 4762, "78f0")
        val stored = record(0x00, 0x0064, 4702)
        d.onNotification(CgmService.MEASUREMENT, live + stored)
        assertEquals(listOf(123), events.readings.map { it.mgdl })
        assertEquals(nowS, events.readings.single().epochSec)
        assertEquals(12.0, events.readings.single().rateMgdlPerMin!!, 1e-6)
        assertEquals(listOf(nowS - 3_600), events.history.map { it.epochSec })
    }

    @Test
    fun belowRangeIsNoNumber() {
        val events = BrandEvents()
        val d = running(events)
        d.onNotification(CgmService.MEASUREMENT, trace["low-1"])
        assertTrue(events.readings.isEmpty() && events.history.isEmpty())
        assertEquals(LinkStatus.NoValue(NoValueReason.BelowRange), events.last())
        d.onNotification(CgmService.MEASUREMENT, trace["error-1"])
        assertEquals(LinkStatus.NoValue(NoValueReason.SensorError), events.last())
    }

    @Test
    fun aRecordWithoutTheCrcIsDropped() {
        val events = BrandEvents()
        val d = running(events)
        d.onNotification(CgmService.MEASUREMENT, unhex("06007b009a12"))
        val bad = record(0x00, 0x007B, 4762).also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        d.onNotification(CgmService.MEASUREMENT, bad)
        assertTrue(events.readings.isEmpty())
    }

    /** "Session stopped" on one frame only asks the status again; a confirmed one is "no session", never the end. */
    @Test
    fun aStoppedSessionIsConfirmedAndIsNotTheEnd() {
        val events = BrandEvents()
        val link = BrandLink()
        val d = running(events, link)
        d.onNotification(CgmService.MEASUREMENT, record(0x80, 0x007B, 4762, "01"))
        assertEquals("read 2AA9", link.ops.last())
        assertTrue(events.readings.isEmpty())
        d.onStatusRead(CgmService.withCrc(unhex("9a12010000")))
        assertEquals(LinkStatus.NoSession, events.last())
        assertTrue(events.ended.isEmpty())
        assertEquals(0, link.closed)
    }

    @Test
    fun controlPointBytes() {
        assertEquals("02952c", CgmService.getCommunicationInterval().hex())
        assertEquals("0103012c01", CgmService.reportRecordsFrom(300).hex())
    }

    @Test
    fun matchesAnAccuChekWithTheCgmService() {
        val d = driver(BrandEvents())
        assertTrue(d.matches(Advert("AA", "AC-12345678", listOf(CgmService.SERVICE))))
        assertTrue(!d.matches(Advert("AA", "AiDEX X-22222A2WC8", listOf(CgmService.SERVICE)))) // same service, not Roche
        assertTrue(!d.matches(Advert("AA", "AC-12345678")))
    }
}
