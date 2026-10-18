// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.direct

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Libre 2 crypto and decoding against vectors computed by DiaBLE's own Swift code (third_party/DiaBLE, commit
 * e6a909c: Libre2.swift, Libre.swift, Glucose.swift compiled unchanged with `swiftc`; harness described in
 * docs/02-direct-sensor.md). UID + patch info `9D 08 30 01 71 2B` + serial `3MH001DG75W` are a real Libre 2 from
 * DiaBLE App.swift:238; `C6 09 31 01 …` is the Libre 2 Plus EU patch-info prefix with made-up last two bytes.
 */
class Libre2Test {
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    private val uid = hex("2fe7b10000a407e0")
    private val infoPlus = hex("c60931013f52")
    private val code = 527_000_123L

    @Test fun serialFromUid() = assertEquals("3MH001DG75W", Libre2Crypto.serial(uid, 3))

    @Test fun tagRouting() {
        assertTrue(Libre2Sensor.isLibre2Tag(uid))                       // uid[6] = 0x07: TI → Libre 1/2
        assertFalse(Libre2Sensor.isLibre2Tag(hex("0102030405067ae0")))  // 0x7A: Libre 3
    }

    @Test fun activateCommand() =
        assertEquals("02a1071b" + "18df7c40", Libre2Crypto.activateCommand(uid).hex())

    @Test fun enableStreamingCommand() =
        assertEquals("02a107" + "1e3b62691ff0f18691", Libre2Crypto.enableStreamingCommand(uid, infoPlus, code).hex())

    @Test fun unlockPayload() =
        assertEquals("4262691f22fc236b2cd25226", Libre2Crypto.streamingUnlockPayload(uid, infoPlus, code, 7).hex())

    @Test fun crc16() {
        assertEquals(0xA29B, Libre2Crypto.crc16(hex("0123456789abcdef55aa")))
        val b = ByteArray(30) { (it * 31 + 7).toByte() }
        assertEquals(Libre3Nfc.crc16(b, b.size), Libre2Crypto.crc16(b))
    }

    @Test fun decryptFram() {
        val input = ByteArray(344) { (it * 7 + 3).toByte() }
        val out = Libre2Crypto.decryptFram(uid, infoPlus, input).hex()
        assertTrue(out.startsWith("9a7b274b71d7cea8f7649e3b8a7e015cc140e93423d0d4c4"))
        assertTrue(out.endsWith("4fd43496897cf68e66fa618e"))
        assertEquals(688, out.length)
    }

    private val bleCipher = hex("5ac39df2a5e83e49e3d6a1eb5f4fd9391693b11e8389166c07cb39737d3ca684f1c396afcc9e32837f59c2db4bfb")
    private val blePlain = hex("010e1b2835424f5c697683909daab7c4d1deebf805121f2c394653606d7a8794a1aebbc8d5e2effc9a05d984")

    @Test fun decryptBle() {
        assertArrayEquals(blePlain, Libre2Crypto.decryptBle(uid, bleCipher))
        val broken = bleCipher.copyOf().also { it[10] = (it[10] + 1).toByte() }
        assertNull(Libre2Crypto.decryptBle(uid, broken))      // CRC catches a wrong key or a damaged packet
        assertNull(Libre2Crypto.decryptBle(uid, bleCipher.copyOf(45)))
    }

    @Test fun parsePacket() {
        val p = Libre2Sensor.parsePacket(blePlain)
        assertEquals(1434, p.wearMin)
        val all = p.trend + p.history
        // raw, raw temperature, temperature adjustment as DiaBLE's parseBLEData reads them
        val expected = listOf(
            Triple(3585, 432, 40), Triple(565, 1268, 92), Triple(13929, 2100, -16), Triple(10909, 2936, -68),
            Triple(7889, 3772, -120), Triple(4613, 496, 44), Triple(1593, 1332, 96), Triple(14957, 2164, -20),
            Triple(11937, 3000, -72), Triple(8917, 3836, -124))
        assertEquals(expected, all.map { Triple(it.raw, it.rawTemperature, it.temperatureAdjustment) })
        assertEquals(listOf(1434, 1432, 1430, 1428, 1427, 1422, 1419), p.trend.map { it.ageMin })
        assertEquals(listOf(1425, 1410, 1395), p.history.map { it.ageMin })   // ((1434 − 2) / 15) · 15 − 15 i
    }

    @Test fun factoryGlucose() {
        val cal = Libre2Sensor.Calibration(i1 = 2, i2 = 300, i3 = 0, i4 = 12000, i5 = 0, i6 = 6900)
        fun g(raw: Int, t: Int, a: Int) = Libre2Sensor.glucose(Libre2Sensor.Raw(100, raw, t, a), cal)
        assertEquals(126, g(1500, 7000, 0))
        assertEquals(234, g(2500, 7100, -40))
        assertEquals(62, g(900, 6800, 12))
        assertEquals(314, g(3200, 7300, 0))
        assertNull(g(0, 7000, 0))                                               // error value
        assertNull(Libre2Sensor.glucose(Libre2Sensor.Raw(100, 1500, 7000, 0), cal.copy(i2 = 0)))  // no calibration
    }

    @Test fun patchInfo() {
        val plusEu = Libre2Sensor.PatchInfo(hex("c60931013f52"))
        assertTrue(plusEu.supported); assertTrue(plusEu.isPlus); assertEquals(21_600, plusEu.defaultWearMin)
        val plusEu2025 = Libre2Sensor.PatchInfo(hex("7f0e31010000"))
        assertTrue(plusEu2025.supported); assertTrue(plusEu2025.isPlus)
        val libre2Eu = Libre2Sensor.PatchInfo(hex("9d083001712b"))
        assertTrue(libre2Eu.supported); assertFalse(libre2Eu.isPlus); assertEquals(20_160, libre2Eu.defaultWearMin)
        val plusUs = Libre2Sensor.PatchInfo(hex("2c0a3a020000"))
        assertFalse(plusUs.supported); assertEquals(2, plusUs.securityGeneration)
    }

    /** A synthetic decrypted FRAM: CRCs, state, age, wear length and calibration bits where DiaBLE reads them. */
    @Test fun framFields() {
        val f = ByteArray(344)
        f[4] = 3                                        // active
        f[316] = (1500 and 0xFF).toByte(); f[317] = (1500 shr 8).toByte()
        f[326] = (21600 and 0xFF).toByte(); f[327] = (21600 shr 8).toByte()
        // i1 = 2 (3 bits) and i2 = 300 (10 bits) from byte 2; i3 = 25, i4 = 9000, i6 = 6900 (>> 2) at 0x150
        val head = 2 or (300 shl 3)
        f[2] = head.toByte(); f[3] = (head shr 8).toByte()
        val foot = 25L or (9000L shl 8) or ((6900L shr 2) shl 0x34)
        for (k in 0 until 8) f[0x150 + k] = (foot shr (8 * k)).toByte()
        fun crc(at: Int, from: Int, to: Int) = Libre2Crypto.crc16(f, from, to).let { f[at] = it.toByte(); f[at + 1] = (it shr 8).toByte() }
        crc(0, 2, 24); crc(24, 26, 320); crc(320, 322, 344)
        val fram = Libre2Sensor.Fram(f)
        assertTrue(fram.headerOk && fram.bodyOk && fram.footerOk)
        assertEquals(3, fram.state); assertEquals(1500, fram.age); assertEquals(21600, fram.maxLife)
        assertEquals(Libre2Sensor.Calibration(2, 300, 25, 9000, 0, 6900), fram.calibration)
        // decryptFram is its own inverse (XOR): encrypting and decrypting gives the FRAM back, CRCs intact
        val enc = Libre2Crypto.decryptFram(uid, infoPlus, f)
        assertFalse(Libre2Sensor.Fram(enc).headerOk)
        assertArrayEquals(f, Libre2Crypto.decryptFram(uid, infoPlus, enc))
    }

    @Test fun rate() {
        assertNotNull(Libre2Sensor.rate(listOf(0 to 120, -2 to 116, -4 to 112)))
        assertEquals(2.0, Libre2Sensor.rate(listOf(0 to 120, -2 to 116, -4 to 112))!!, 1e-9)
        assertNull(Libre2Sensor.rate(listOf(0 to 120)))
    }
}
