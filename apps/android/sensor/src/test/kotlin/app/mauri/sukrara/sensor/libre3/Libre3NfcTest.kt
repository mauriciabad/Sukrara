// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.sensor.libre3

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Vectors: the sensor answers quoted in Juggluco's libre3/nfc.cpp and the CRC checks in dp_activation.hpp (PoC test, unchanged). */
class Libre3NfcTest {
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private val running = hex("00A500010001000000C04E1E0D0101040C043036385A4D524631382F97")

    @Test
    fun patchInfo() {
        val p = Libre3Nfc.parsePatchInfo(running)!!
        assertEquals("068ZMRF18", p.serial)
        assertEquals(4, p.state)
        assertEquals(4, p.productType)
        assertEquals(60, p.warmupMin)
        assertEquals(20160, p.wearMin) // C04E: this Libre 3 vector says 14 days (a Libre 3 Plus says 21600)
        assertFalse(p.activate)
        assertNull(Libre3Nfc.parsePatchInfo(running.copyOf(20)))
    }

    @Test
    fun neverStartedSensorIsActivated() {
        val fresh = running.copyOf().also { it[17] = 1 }
        assertTrue(Libre3Nfc.parsePatchInfo(fresh)!!.activate)
    }

    @Test
    fun pairedAnswer() {
        val p = Libre3Nfc.parsePatchInfo(running)!!
        val a = Libre3Nfc.parseSecond(hex("00A500111DB71932189A948F6E9F0A7562A2FA"), p, now = 1L) as Libre3Nfc.Second.Ok
        assertEquals("18:32:19:B7:1D:11", a.pairing.address)
        assertArrayEquals(hex("9A948F6E"), a.pairing.pin)
        assertEquals(1651837599L, a.pairing.startEpoch)
        assertEquals("068ZMRF18", a.pairing.serial)
        assertNull(a.pairing.kAuth)
        assertEquals(20160, a.pairing.wearMin)
    }

    @Test
    fun wrongAccountAnswer() {
        val p = Libre3Nfc.parsePatchInfo(running)!!
        assertEquals(Libre3Nfc.Second.Refused(0xB1), Libre3Nfc.parseSecond(hex("00A501B1"), p, now = 1L))
        assertNull(Libre3Nfc.parseSecond(hex("00A5"), p, now = 1L))
    }

    @Test
    fun crc() {
        assertEquals(0x313E, Libre3Nfc.crc16(ByteArray(8), 8))
        assertEquals(0xCCBF, Libre3Nfc.crc16(byteArrayOf(1, 0, 0, 0, 0, 0, 0, 0), 8))
        assertEquals(0x2063, Libre3Nfc.crc16(byteArrayOf(8, 0, 0, 0, 12, 0, 0, 0), 8))
    }

    @Test
    fun takeoverCommand() {
        // now−1 = 8, receiver ID 12 → the dp_activation vector, CRC 0x2063 little-endian
        assertArrayEquals(hex("02A87A080000000C0000006320"), Libre3Nfc.secondCommand(false, 12, now = 9))
        assertEquals(0xA0.toByte(), Libre3Nfc.secondCommand(true, 12, now = 9)[1])
    }

    /** Only an Abbott chip (UID maker byte 0x7A) ever gets a Libre 3 command; a Libre 1/2/2 Plus (TI, 0x07) gets none. */
    @Test
    fun chipGate() {
        fun uid(s: String) = ByteArray(8) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        assertEquals(Libre3Nfc.Chip.LIBRE3, Libre3Nfc.chip(uid("0102030405067ae0")))
        assertEquals(Libre3Nfc.Chip.LIBRE2, Libre3Nfc.chip(uid("2fe7b10000a407e0"))) // a real Libre 2's UID (DiaBLE)
        assertEquals(Libre3Nfc.Chip.OTHER, Libre3Nfc.chip(uid("0102030405060ae0")))
        assertEquals(Libre3Nfc.Chip.OTHER, Libre3Nfc.chip(uid("0102030405067a00")))
        assertEquals(Libre3Nfc.Chip.OTHER, Libre3Nfc.chip(byteArrayOf(1, 2, 3, 4)))
        assertEquals(Libre3Nfc.Chip.OTHER, Libre3Nfc.chip(null))
    }

    /** Juggluco libre3/NFC.java (`secondWithID`): its logged A0 payload `8C 42 86 62 8D 6D 41 1F BC 93`. */
    @Test
    fun activationCommand() {
        assertArrayEquals(hex("02A07A" + "8C4286628D6D411FBC93"), Libre3Nfc.secondCommand(true, 0x1F416D8DL, now = 0x6286428DL))
    }
}
