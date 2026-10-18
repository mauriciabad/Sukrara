// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.spike.sensors.dexcom

import dev.spike.sensors.BleLink
import dev.spike.sensors.DriverStatus
import dev.spike.sensors.Reading
import dev.spike.sensors.SensorEvents
import dev.spike.sensors.Session
import dev.spike.sensors.hex
import dev.spike.sensors.unhex
import dev.spike.sensors.harness.Trace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * The expected values come from LoopKit G7SensorKit's own unit tests (`G7GlucoseMessageTests.swift`, MIT) and from
 * DiaBLE's annotated frames — so this is a cross-implementation check of the port, not a restatement of it.
 */
class G7MessagesTest {
    private val trace = Trace.resource("g7-session")

    @Test fun `the EGV of a running sensor decodes like G7SensorKit`() {
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
    }

    @Test fun `G7SensorKit's two documented readings`() {
        val a = G7Messages.Egv.parse(trace["egv-138"])!!
        assertEquals(138, a.mgdl)
        assertEquals(87485L, a.readingSeconds)
        assertFalse(a.displayOnly)

        val b = G7Messages.Egv.parse(trace["egv-104"])!!
        assertEquals(104, b.mgdl)
        assertEquals(901390L, b.readingSeconds)
        assertTrue(b.displayOnly)
    }

    @Test fun `a whole sensor lifecycle gets the states and sequences G7SensorKit expects`() {
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
        // Stopped and warming up carry no number at all, or an unusable one.
        assertNull(G7Messages.Egv.parse(trace["life-0"])!!.mgdl)
        assertNull(G7Messages.Egv.parse(trace["life-1"])!!.mgdl)
        assertEquals(145, G7Messages.Egv.parse(trace["life-2"])!!.mgdl)
        assertFalse(G7Messages.Egv.parse(trace["life-2"])!!.usable)   // still warming up
        assertTrue(G7Messages.Egv.parse(trace["life-6"])!!.usable)
    }

    @Test fun `a backfill record decodes like G7SensorKit`() {
        val r = G7Messages.BackfillRecord.parse(trace["backfill-record"])!!
        assertEquals(41285, r.secondsSinceStart)
        assertEquals(150, r.mgdl)
        assertEquals(6, r.state)
        assertEquals(-0.4f, r.trend!!, 1e-6f)
        assertFalse(r.displayOnly)
        assertTrue(r.usable)
    }

    @Test fun `several backfill records arrive in one notification`() {
        val one = trace["backfill-record"]
        val records = G7Messages.BackfillRecord.parseAll(one + one + one)
        assertEquals(3, records.size)
        assertTrue(records.all { it.mgdl == 150 })
    }

    @Test fun `the backfill request is the sensor's own 0x59 layout`() {
        assertEquals("59f401000020030000", G7Messages.backfillRequest(500, 800).hex())
    }

    @Test fun `observer mode starts reading when it sees the vendor app's session go live, and never writes`() {
        val events = Recorder()
        val writes = ArrayList<String>()
        val driver = G7Driver(events, mode = G7Driver.Mode.OBSERVE, now = { 1_000_000_000_000 })
        driver.onConnected(RecordingLink(writes))
        assertEquals(G7Driver.Phase.WATCHING, driver.phase)

        driver.onNotification(Dexcom.AUTHENTICATION, byteArrayOf(0x05, 0x00, 0x00))   // not authenticated yet
        assertEquals(G7Driver.Phase.WATCHING, driver.phase)

        driver.onNotification(Dexcom.AUTHENTICATION, byteArrayOf(0x05, 0x01, 0x01))   // the vendor app is in
        assertEquals(G7Driver.Phase.STREAMING, driver.phase)

        driver.onNotification(Dexcom.CONTROL, trace["egv-97"])
        assertEquals(listOf(97), events.readings.map { it.mgdl })
        assertTrue("observer mode must not write to the sensor: $writes", writes.isEmpty())
    }

    @Test fun `a sensor that asks for an app certificate is refused with a reason`() {
        val events = Recorder()
        val driver = G7Driver(events, mode = G7Driver.Mode.PAIR, pairingCode = "1234")
        driver.onConnected(RecordingLink(ArrayList()))
        // The sensor's "send me certificate 0, 480 bytes".
        driver.onNotification(Dexcom.AUTHENTICATION, "0b0000e0010000".unhex())
        assertEquals(G7Driver.Phase.CERTIFICATE_BLOCKED, driver.phase)
        val failure = events.statuses.filterIsInstance<DriverStatus.Failed>().single()
        assertTrue(failure.reason, failure.reason.contains("certificate"))
    }

    class Recorder : SensorEvents {
        val readings = ArrayList<Reading>()
        val history = ArrayList<Reading>()
        val statuses = ArrayList<DriverStatus>()
        val sessions = ArrayList<Session>()
        var pairingState: ByteArray? = null
        override fun onReading(reading: Reading) { readings.add(reading) }
        override fun onHistory(readings: List<Reading>) { history.addAll(readings) }
        override fun onSession(session: Session) { sessions.add(session) }
        override fun onStatus(status: DriverStatus) { statuses.add(status) }
        override fun onPairingState(state: ByteArray) { pairingState = state }
    }

    class RecordingLink(val writes: MutableList<String>, override val isBonded: Boolean = true) : BleLink {
        val subscriptions = ArrayList<UUID>()
        var bonds = 0
        var disconnects = 0
        override fun subscribe(characteristic: UUID, indicate: Boolean) { subscriptions.add(characteristic) }
        override fun write(characteristic: UUID, value: ByteArray, withResponse: Boolean) { writes.add(value.hex()) }
        override fun bond() { bonds++ }
        override fun disconnect() { disconnects++ }
    }
}
