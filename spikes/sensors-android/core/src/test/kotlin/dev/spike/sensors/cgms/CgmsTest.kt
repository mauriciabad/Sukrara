// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.spike.sensors.cgms

import dev.spike.sensors.DriverStatus
import dev.spike.sensors.dexcom.G7MessagesTest
import dev.spike.sensors.harness.Trace
import dev.spike.sensors.hex
import dev.spike.sensors.unhex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The standard CGM Service, as the Accu-Chek SmartGuide uses it. Fixtures: real frames quoted in Juggluco's
 * `accu/java.cpp`, whose struct names say what each field is.
 */
class CgmsTest {
    private val trace = Trace.resource("smartguide")

    @Test fun `the SFLOAT of the specification, including its no-number patterns`() {
        assertEquals(123f, CgmService.sfloat(0x007B)!!, 1e-6f)          // 123 × 10^0
        assertEquals(12.3f, CgmService.sfloat(0xF07B)!!, 1e-4f)         // 123 × 10^-1
        assertEquals(-5f, CgmService.sfloat(0x0FFB)!!, 1e-6f)           // negative mantissa
        assertNull(CgmService.sfloat(0x07FF))                            // NaN
        assertNull(CgmService.sfloat(0x0802))                            // -INFINITY (below the sensor's range)
    }

    @Test fun `a sensor-error measurement has no number and keeps its CRC`() {
        val m = CgmService.Measurement.parse(trace["error-1"])!!
        assertNull(m.mgdl)
        assertNull(m.trendMgdlPerMin)
        assertNull(m.qualityPercent)
        assertEquals(0x46AF, m.offsetMinutes)
        assertEquals(true, m.crcOk)
    }

    @Test fun `a below-range measurement is flagged, and the driver posts the floor value`() {
        val m = CgmService.Measurement.parse(trace["low-1"])!!
        assertNull(m.mgdl)                        // the glucose field is the -INFINITY pattern
        assertTrue(m.belowRange)
        assertEquals(true, m.crcOk)

        val events = G7MessagesTest.Recorder()
        val driver = SmartGuideDriver(events, now = { 1_000_000_000_000 })
        val reading = driver.measurement(trace["low-1"])!!
        assertEquals(39, reading.mgdl)            // what Juggluco posts for this frame too
    }

    @Test fun `the status read says how long the session has been running`() {
        val status = CgmService.Status.parse(trace["status"])!!
        assertEquals(4762, status.offsetMinutes)

        val events = G7MessagesTest.Recorder()
        val driver = SmartGuideDriver(events, now = { 1_000_000_000_000 })
        driver.onStatusRead(trace["status"])
        val session = events.sessions.single()
        assertEquals(1_000_000_000_000 - 4762 * 60_000L, session.startMs)
        assertEquals(session.startMs!! + 60 * 60_000L, session.warmupEndMs)
        assertEquals(session.startMs!! + 14 * 24 * 60 * 60_000L, session.endMs)
    }

    @Test fun `a normal measurement becomes a reading on the session's clock`() {
        // Flags 0x43: trend and quality present, plus the second annunciation byte. 13 bytes with the E2E-CRC.
        val frame = CgmService.withCrc("0d43 7b00 1e00 00 0c00 5a00".replace(" ", "").unhex())
        val m = CgmService.Measurement.parse(frame)!!
        assertEquals(123, m.mgdl)
        assertEquals(30, m.offsetMinutes)
        assertEquals(12f, m.trendMgdlPerMin!!, 1e-6f)
        assertEquals(90f, m.qualityPercent!!, 1e-6f)
        assertEquals(true, m.crcOk)

        val events = G7MessagesTest.Recorder()
        val driver = SmartGuideDriver(events, now = { 1_000_000_000_000 })
        driver.onStatusRead(trace["status"])
        val reading = driver.measurement(frame)!!
        assertEquals(123, reading.mgdl)
        assertEquals(events.sessions.first().startMs!! + 30 * 60_000L, reading.timeMs)
    }

    @Test fun `a measurement whose CRC is wrong is dropped`() {
        val frame = CgmService.withCrc("0d437b001e00000c005a00".unhex()).also { it[2] = 0x7C }
        assertFalse(CgmService.crcOk(frame))
        val events = G7MessagesTest.Recorder()
        assertNull(SmartGuideDriver(events).measurement(frame))
    }

    @Test fun `a stopped session ends the driver instead of posting a value`() {
        // Annunciation field 1, bit 0 = session stopped. Flags 0x20 = only that byte.
        val frame = CgmService.withCrc("0920 7b00 1e00 01".replace(" ", "").unhex())
        val events = G7MessagesTest.Recorder()
        assertNull(SmartGuideDriver(events).measurement(frame))
        assertTrue(events.statuses.any { it is DriverStatus.Ended })
    }

    @Test fun `the control-point writes carry the E2E-CRC the sensor expects`() {
        // Juggluco writes exactly these three bytes to 2AAC on an unbonded SmartGuide.
        assertEquals("02952c", CgmService.getCommunicationInterval().hex())
        assertEquals("0105cdbe", CgmService.setCommunicationInterval(5).hex())
        assertNotNull(CgmService.reportRecordsFrom(300))
        assertEquals("0103012c01", CgmService.reportRecordsFrom(300).hex())
    }
}
