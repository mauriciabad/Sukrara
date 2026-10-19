// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.direct.aidex

import app.mauri.sukrara.core.SensorProfiles
import app.mauri.sukrara.direct.Advert
import app.mauri.sukrara.direct.BrandEvents
import app.mauri.sukrara.direct.BrandLink
import app.mauri.sukrara.direct.BrandPrefs
import app.mauri.sukrara.direct.QUIET
import app.mauri.sukrara.direct.SensorPairing
import app.mauri.sukrara.direct.Trace
import app.mauri.sukrara.direct.hex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Aidex X (investigation 23, step 4) against the GATT trace recorded in Juggluco's `aidexx/decrypt.cpp` (serial
 * 22222A2WC8): the derived keys must equal the bytes that went over the air, and the recorded ciphertexts must decrypt
 * to the recorded plaintexts. Untested on hardware.
 */
class AidexXDriverTest {
    private val trace = Trace.resource("aidex-x")
    private val serial = String(trace["serial"], Charsets.US_ASCII)
    private val iv = AidexX.iv(serial)
    private val nowMs = 1_800_000_000_000L
    private val nowS = nowMs / 1000
    private val chars = setOf(AidexX.KEY_UNBONDED, AidexX.DATA, AidexX.KEY_BONDED)

    private fun driver(events: BrandEvents, s: String = serial) =
        AidexXDriver(SensorPairing.forBrand(SensorProfiles.AIDEX_X, s), events, BrandPrefs(), clock = { nowMs }, log = QUIET)

    @Test fun keysComeFromTheSerialAlone() {
        assertEquals("72b718f89470e73422d8c6ad2c228db8", iv.hex())
        assertEquals(trace["ask-key"].hex(), AidexX.askKey(serial).hex())
        assertEquals("afc443924ea220e6b8e98eb505f0f77e", AidexX.iv("22222A532X").hex())
        assertEquals("7fdb4d90ffabb142ccf8a15b58402c91", AidexX.askKey("22222A532X").hex())
    }

    @Test fun sessionKeyAndItsCrc() {
        assertEquals("1c152cd42daa9e503300fe6baf908046", AidexX.sessionKey(trace["notified-key"], iv, trace["session-read"])!!.hex())
        val broken = trace["session-read"].copyOf().also { it[0] = (it[0] + 1).toByte() }
        assertNull(AidexX.sessionKey(trace["notified-key"], iv, broken))
    }

    @Test fun recordedCommandsAndReplies() {
        val session = AidexX.sessionKey(trace["notified-key"], iv, trace["session-read"])!!
        assertEquals(trace["cmd-device-info"].hex(), AidexX.command(session, iv, AidexX.OP_DEVICE_INFO).hex())
        assertEquals(trace["cmd-start-time"].hex(), AidexX.command(session, iv, AidexX.OP_START_TIME).hex())
        assertEquals(trace["cmd-glucose"].hex(), AidexX.command(session, iv, AidexX.OP_GLUCOSE).hex())
        val info = AidexX.reply(session, iv, trace["rx-device-info"])!!
        assertEquals("GX-01S", String(info, 10, 6, Charsets.US_ASCII))
        assertEquals("2101ea07020f0c21360400", AidexX.reply(session, iv, trace["rx-start-time"])!!.hex())
        val g = AidexX.Glucose.parseReply(AidexX.reply(session, iv, trace["rx-glucose"])!!)!!
        assertEquals(21600, g.minutesSinceStart); assertEquals(92, g.mgdl)
    }

    @Test fun glucoseReplyIsWhatTheVendorAppLogged() {
        val plain = trace["plain-glucose"].let { it.copyOf(it.size - 2) }
        val g = AidexX.Glucose.parseReply(plain)!!
        assertEquals(19348, g.minutesSinceStart); assertEquals(119, g.mgdl); assertEquals(-0.5f, g.trend, 1e-6f)
        assertEquals(77, g.quality); assertTrue(g.usable); assertFalse(g.warmingUp)
    }

    /** The whole recorded session through the driver: ask-key, key, read, start time, glucose, then the minute poll. */
    @Test fun recordedSessionThroughTheDriver() {
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
        assertEquals("write F002 ${trace["cmd-glucose"].hex()}", link.ops.last())
        d.onNotification(AidexX.DATA, trace["rx-glucose"])
        assertEquals(listOf(92), events.readings.map { it.mgdl })
        assertEquals(nowS, events.readings.single().epochSec)
        assertEquals(listOf(nowS - 21600 * 60L), events.starts)
        assertEquals("schedule ${AidexXDriver.POLL_MS}", link.ops.last())
        link.pending!!.run()
        assertEquals("write F002 ${trace["cmd-glucose"].hex()}", link.ops.last())
    }

    @Test fun advertisementGivesAReadingWithoutConnecting() {
        val a = AidexX.Advertisement.parse(trace["advertisement"])!!
        assertTrue(a.crcOk); assertEquals(92, a.latest.mgdl); assertEquals(listOf(84, 78), a.previous)
        assertEquals("AiDEX X-22222A2WC8", a.name)
        val tampered = trace["advertisement"].copyOf().also { it[15] = (it[15] + 1).toByte() }
        assertFalse(AidexX.Advertisement.parse(tampered)!!.crcOk)

        val events = BrandEvents()
        val d = driver(events)
        d.onAdvertisement(Advert("AA", "AiDEX X-22222A2WC8"), trace["advertisement"])
        assertEquals(listOf(92), events.readings.map { it.mgdl })
        assertEquals(listOf(nowS - 21600 * 60L), events.starts)
        d.onAdvertisement(Advert("AA", "AiDEX X-22222A2WC8"), tampered)   // bad CRC: ignored
        d.onAdvertisement(Advert("AA", "AiDEX X-22222A2WC8"), trace["advertisement"])   // same minute: no second reading
        assertEquals(1, events.readings.size)
        // someone else's sensor nearby: not ours
        val other = BrandEvents()
        driver(other, "22222A532X").onAdvertisement(Advert("AA", "AiDEX X-22222A2WC8"), trace["advertisement"])
        assertTrue(other.readings.isEmpty())
    }

    @Test fun wrongSerialIsAFailure() {
        val events = BrandEvents()
        val link = BrandLink(isBonded = false)
        val d = driver(events, "22222A532X")
        d.onConnected(link, chars, 0)
        d.onNotification(AidexX.KEY_UNBONDED, trace["notified-key"])
        d.onRead(AidexX.DATA, trace["session-read"])   // encrypted for the other sensor's IV
        assertEquals("failed", events.last().first)
        assertEquals(1, link.closed)
        assertNotNull(events.last().second)
    }

    @Test fun matchesItsOwnName() {
        val d = driver(BrandEvents())
        assertTrue(d.matches(Advert("AA", "AiDEX X-22222A2WC8")))
        assertFalse(d.matches(Advert("AA", "AiDEX X-22222A532X")))
        assertEquals("AiDEX X-22222A2WC8", d.scan.single().name)
    }
}
