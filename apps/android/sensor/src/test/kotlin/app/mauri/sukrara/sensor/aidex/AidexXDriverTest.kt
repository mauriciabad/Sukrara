// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.sensor.aidex

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
import app.mauri.sukrara.sensor.pairing.Pairing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Aidex X against Juggluco's recorded trace (`aidexx/decrypt.cpp`, GPL-3): every key, command and reply the driver makes
 * must match the recorded bytes, and the decrypted replies the recorded plaintexts. Untested on a real sensor.
 */
class AidexXDriverTest {
    private val trace = Trace.resource("aidex-x")
    private val serial = String(trace["serial"], Charsets.US_ASCII)
    private val iv = AidexX.iv(serial)
    private var nowMs = 1_800_000_000_000L
    private val nowS get() = nowMs / 1000
    private val chars = setOf(AidexX.KEY_UNBONDED, AidexX.DATA, AidexX.KEY_BONDED)

    private fun driver(
        events: BrandEvents,
        s: String = serial,
        start: Long = 0,
    ) = AidexXDriver(Pairing.scanned(SensorProfile.AidexX, s, 1L).copy(startEpoch = start), events, clockMs = { nowMs })

    @Test
    fun keysComeFromTheSerialAlone() {
        assertEquals("72b718f89470e73422d8c6ad2c228db8", iv.hex())
        assertEquals(trace["ask-key"].hex(), AidexX.askKey(serial).hex())
        assertEquals("afc443924ea220e6b8e98eb505f0f77e", AidexX.iv("22222A532X").hex())
    }

    @Test
    fun sessionKeyAndItsCrc() {
        assertEquals("1c152cd42daa9e503300fe6baf908046", AidexX.sessionKey(trace["notified-key"], iv, trace["session-read"])!!.hex())
        val broken = trace["session-read"].copyOf().also { it[0] = (it[0] + 1).toByte() }
        assertNull(AidexX.sessionKey(trace["notified-key"], iv, broken))
    }

    @Test
    fun recordedCommandsAndReplies() {
        val session = AidexX.sessionKey(trace["notified-key"], iv, trace["session-read"])!!
        assertEquals(trace["cmd-start-time"].hex(), AidexX.command(session, iv, AidexX.OP_START_TIME).hex())
        assertEquals(trace["cmd-glucose"].hex(), AidexX.command(session, iv, AidexX.OP_GLUCOSE).hex())
        val info = AidexX.reply(session, iv, trace["rx-device-info"])!!
        assertEquals("GX-01S", String(info, 10, 6, Charsets.US_ASCII))
        val g = AidexX.Glucose.parseReply(AidexX.reply(session, iv, trace["rx-glucose"])!!)!!
        assertEquals(21600, g.minutesSinceStart)
        assertEquals(92, g.mgdl)
    }

    @Test
    fun glucoseReplyIsWhatTheVendorAppLogged() {
        val plain = trace["plain-glucose"].let { it.copyOf(it.size - 2) }
        val g = AidexX.Glucose.parseReply(plain)!!
        assertEquals(19348, g.minutesSinceStart)
        assertEquals(119, g.mgdl)
        assertEquals(-0.5f, g.trend, 1e-6f)
        assertTrue(g.valid)
        assertFalse(g.warmingUp)
    }

    /** The whole recorded session through the driver: ask-key, key, read, start time, glucose, then the minute poll. */
    @Test
    fun recordedSessionThroughTheDriver() {
        val events = BrandEvents()
        val link = BrandLink(isBonded = false)
        val d = driver(events)
        d.onConnected(link, chars, 0)
        assertEquals(listOf("sub F001", "sub F002", "write F001 ${trace["ask-key"].hex()}"), link.ops)
        d.onNotification(AidexX.KEY_UNBONDED, trace["notified-key"])
        assertEquals("read F002", link.ops.last())
        d.onRead(AidexX.DATA, trace["session-read"])
        assertEquals("write F002 ${trace["cmd-start-time"].hex()}", link.ops.last())
        d.onNotification(AidexX.DATA, trace["rx-start-time"])
        assertEquals(listOf("write F002 ${trace["cmd-glucose"].hex()}", "schedule ${AidexXDriver.POLL_MS}"), link.ops.takeLast(2))
        d.onNotification(AidexX.DATA, trace["rx-glucose"])
        assertEquals(listOf(92), events.readings.map { it.mgdl })
        assertEquals(nowS, events.readings.single().epochSec)
        assertEquals(listOf(nowS - 21600 * 60L), events.starts)
        assertEquals("schedule ${AidexXDriver.POLL_MS}", link.ops.last())
        link.pending!!.run()
        assertEquals(listOf("write F002 ${trace["cmd-glucose"].hex()}", "schedule ${AidexXDriver.POLL_MS}"), link.ops.takeLast(2))
    }

    /**
     * The record's status byte: 0 and 1 come with good values in the recorded frames, anything else gives no value.
     * The sensor's "ended" frame (type 3 on the key characteristic) stops it for good only when two arrive intact.
     */
    @Test
    fun unseenStatusGivesNoValueAndTheEndTakesTwoIntactFrames() {
        val events = BrandEvents()
        val link = BrandLink(isBonded = true)
        val d = driver(events)
        d.onConnected(link, chars, 0)
        d.onNotification(AidexX.KEY_BONDED, trace["notified-key"])
        d.onRead(AidexX.DATA, trace["session-read"])
        val g = AidexX.Glucose.parse(trace["advertisement"], 11)!!
        assertEquals(1, g.status)
        d.onGlucose(g.copy(status = 2))
        assertEquals(LinkStatus.NoValue(NoValueReason.SensorError), events.last())
        assertTrue(events.readings.isEmpty())

        val session = AidexX.sessionKey(trace["notified-key"], iv, trace["session-read"])!!
        val endedFrame = AidexX.command(session, iv, AidexX.CURRENT_ENDED, ByteArray(14))
        d.onNotification(AidexX.KEY_BONDED, endedFrame)
        assertTrue(events.ended.isEmpty())
        d.onNotification(AidexX.KEY_BONDED, endedFrame.copyOf().also { it[3] = (it[3] + 1).toByte() }) // CRC fails
        assertTrue(events.ended.isEmpty())
        d.onNotification(AidexX.KEY_BONDED, endedFrame)
        assertEquals(1, events.ended.size)
        assertEquals(LinkStatus.SensorEnded, events.last())
    }

    /** Juggluco's range: the 10-bit field all ones (1023) or above 800 is no value, never a HI. */
    @Test
    fun implausibleValueIsNoValueAndOutOfRangeIsFlagged() {
        val start = nowS - 21600 * 60L
        val events = BrandEvents()
        val d = driver(events, start = start)
        val g = AidexX.Glucose.parse(trace["advertisement"], 11)!!
        d.onGlucose(g.copy(mgdl = 1023))
        assertEquals(LinkStatus.NoValue(NoValueReason.Missing), events.last())
        d.onGlucose(g.copy(minutesSinceStart = g.minutesSinceStart + 1, mgdl = 30))
        assertTrue(events.readings.single().isLo)
    }

    /**
     * The start is kept once known: the same record repeated 5 minutes later keeps its time (history, never the
     * current value).
     */
    @Test
    fun aRepeatedRecordIsNeverLiveAgain() {
        val start = nowS - 21600 * 60L
        val events = BrandEvents()
        val d = driver(events, start = start)
        val g = AidexX.Glucose.parse(trace["advertisement"], 11)!!
        d.onGlucose(g)
        assertEquals(1, events.readings.size)
        nowMs += 5 * 60_000L
        val restarted = BrandEvents()
        driver(restarted, start = start).onGlucose(g) // a new driver after a restart: the same minute
        assertTrue(restarted.readings.isEmpty())
        assertEquals(listOf(start + 21600 * 60L), restarted.history.map { it.epochSec })
        assertTrue(restarted.starts.isEmpty())
    }

    @Test
    fun advertisementIsUsedOnlyOnceTheStartIsKnown() {
        val a = AidexX.Advertisement.parse(trace["advertisement"])!!
        assertTrue(a.crcOk)
        assertEquals(92, a.latest.mgdl)
        assertEquals("AiDEX X-22222A2WC8", a.name)
        val tampered = trace["advertisement"].copyOf().also { it[15] = (it[15] + 1).toByte() }
        assertFalse(AidexX.Advertisement.parse(tampered)!!.crcOk)

        val unknown = BrandEvents()
        driver(unknown).onAdvertisement(Advert("AA", "AiDEX X-22222A2WC8"), trace["advertisement"])
        assertTrue(unknown.readings.isEmpty() && unknown.history.isEmpty())

        val events = BrandEvents()
        val d = driver(events, start = nowS - 21600 * 60L)
        d.onAdvertisement(Advert("AA", "AiDEX X-22222A2WC8"), tampered) // bad CRC: ignored
        d.onAdvertisement(Advert("AA", "AiDEX X-22222A2WC8"), trace["advertisement"])
        d.onAdvertisement(Advert("AA", "AiDEX X-22222A2WC8"), trace["advertisement"]) // same minute: once
        assertEquals(listOf(92), events.readings.map { it.mgdl })
        // someone else's sensor nearby: not ours
        val other = BrandEvents()
        driver(other, "22222A532X", start = nowS).onAdvertisement(Advert("AA", "AiDEX X-22222A2WC8"), trace["advertisement"])
        assertTrue(other.readings.isEmpty())
    }

    @Test
    fun wrongSerialIsAFailure() {
        val events = BrandEvents()
        val link = BrandLink(isBonded = false)
        val d = driver(events, "22222A532X")
        d.onConnected(link, chars, 0)
        d.onNotification(AidexX.KEY_UNBONDED, trace["notified-key"])
        d.onRead(AidexX.DATA, trace["session-read"]) // encrypted for the other sensor's IV
        assertEquals(LinkStatus.Failed(FailReason.WrongId), events.last())
        assertEquals(1, link.closed)
    }

    @Test
    fun matchesItsOwnName() {
        val d = driver(BrandEvents())
        assertTrue(d.matches(Advert("AA", "AiDEX X-22222A2WC8")))
        assertFalse(d.matches(Advert("AA", "AiDEX X-22222A532X")))
        assertEquals("AiDEX X-22222A2WC8", d.scan.single().name)
    }
}
