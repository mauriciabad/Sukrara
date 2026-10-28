// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.sensor.aidex

import app.mauri.sukrara.sensor.brand.Crc
import app.mauri.sukrara.sensor.brand.u16le
import app.mauri.sukrara.sensor.brand.u32le
import app.mauri.sukrara.sensor.brand.u8
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * **Microtech Aidex X** (sold as LinX, LumiFlex, GlucoRx and Wellion): everything the phone needs comes from the
 * sensor's **serial number**, printed on the box — no vendor key, no vendor algorithm, and the values arrive as
 * finished mg/dL.
 *
 * Ported from Juggluco's `Common/src/main/cpp/aidexx/` (GPL-3): `mkiv.cpp` (the two key
 * derivations), `decrypt.cpp` (the session-key exchange and its recorded trace), `glucose.h` (frames and
 * advertisements) and `crc.cpp`. Every step is checked against that trace in `AidexXDriverTest`. Untested on hardware.
 */
internal object AidexX {
    private fun sig(short: String) = UUID.fromString("0000$short-0000-1000-8000-00805f9b34fb")

    /** The sensor advertises the standard CGM service UUID but speaks its own protocol on private characteristics. */
    val SERVICE: UUID = sig("181F")

    /** Key exchange before bonding. */
    val KEY_UNBONDED: UUID = sig("F001")

    /** Commands and replies, AES-CFB encrypted. */
    val DATA: UUID = sig("F002")

    /** Key exchange once bonded. */
    val KEY_BONDED: UUID = sig("F003")

    /** "AiDEX X-<serial>" is the advertised name. */
    fun deviceName(serial: String) = "AiDEX X-$serial"

    /** Serial characters as numbers: '0'-'9' → 0-9, letters → 10-35 (case-insensitive). */
    private fun serialBytes(serial: String) =
        ByteArray(serial.length) { i ->
            val c = serial[i]
            when (c) {
                in '0'..'9' -> c - '0'
                in 'A'..'Z' -> c - 'A' + 10
                in 'a'..'z' -> c - 'a' + 10
                else -> c.code
            }.toByte()
        }

    private fun md5(data: ByteArray): ByteArray = MessageDigest.getInstance("MD5").digest(data)

    /** The AES IV for this sensor: MD5 over each serial value mapped through v·17 + 0x13. */
    fun iv(serial: String): ByteArray = md5(serialBytes(serial).map { ((it.toInt() and 0xFF) * 17 + 0x13).toByte() }.toByteArray())

    /** The key written to [KEY_UNBONDED] to ask for the session key: MD5 over v·13 + 61. */
    fun askKey(serial: String): ByteArray = md5(serialBytes(serial).map { ((it.toInt() and 0xFF) * 13 + 61).toByte() }.toByteArray())

    /** AES-128-CFB (full-block feedback), restarted from the sensor's IV for every message, as the sensor does. */
    fun cfb(key: ByteArray, iv: ByteArray, data: ByteArray, encrypt: Boolean): ByteArray =
        Cipher.getInstance("AES/CFB/NoPadding").run {
            init(if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            doFinal(data)
        }

    /**
     * The 17 bytes read from [DATA] right after the ask-key: decrypted with the key the sensor notified, they are
     * the 16-byte session key plus its CRC-8/MAXIM. Null when the CRC does not match (wrong serial).
     */
    fun sessionKey(notifiedKey: ByteArray, iv: ByteArray, read17: ByteArray): ByteArray? {
        if (read17.size != 17) return null
        val plain = cfb(notifiedKey, iv, read17, encrypt = false)
        val session = plain.copyOf(16)
        return if (Crc.crc8Maxim(session) == plain.u8(16)) session else null
    }

    /** A command: opcode, payload, CRC-16/CCITT-FALSE (little-endian), all AES-CFB encrypted. */
    fun command(sessionKey: ByteArray, iv: ByteArray, opcode: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        val body = byteArrayOf(opcode.toByte()) + payload
        return cfb(sessionKey, iv, body + Crc.le(Crc.ccittFalse(body)), encrypt = true)
    }

    /** Decrypt a reply and check its CRC-16. Null = not ours / corrupt. */
    fun reply(sessionKey: ByteArray, iv: ByteArray, encrypted: ByteArray): ByteArray? {
        if (encrypted.size < 3) return null
        val plain = cfb(sessionKey, iv, encrypted, encrypt = false)
        return if (plain.u16le(plain.size - 2) == Crc.ccittFalse(plain, 0, plain.size - 2)) plain.copyOf(plain.size - 2) else null
    }

    const val OP_DEVICE_INFO = 0x10
    const val OP_START_TIME = 0x21
    const val OP_GLUCOSE = 0x11

    /** 8 bytes: minutes since start u16, status, cal/temp, trend (0.1 mg/dL/min), glucose u16 (10 bits + valid), quality. */
    data class Glucose(
        val minutesSinceStart: Int,
        val status: Int,
        val calTemp: Int,
        val trend: Float,
        val mgdl: Int,
        val valid: Boolean,
        val quality: Int,
        /** Bit 10 of the glucose word; Juggluco also ignores anything in the sensor's first hour. */
        val warmupFlag: Boolean,
    ) {
        val warmingUp get() = warmupFlag || minutesSinceStart < 60
        val usable get() = valid && mgdl in 18..800

        companion object {
            /** From a decrypted [OP_GLUCOSE] reply (opcode, 0x01, then the 8-byte record). */
            fun parseReply(plain: ByteArray): Glucose? =
                if (plain.size < 2 + SIZE || plain.u8(0) != OP_GLUCOSE) null else parse(plain, 2)

            const val SIZE = 8

            fun parse(b: ByteArray, at: Int): Glucose? {
                if (b.size < at + SIZE) return null
                val g = b.u16le(at + 5)
                return Glucose(
                    b.u16le(at),
                    b.u8(at + 2),
                    b.u8(at + 3),
                    b[at + 4] / 10f,
                    g and 0x3FF,
                    (g and 0x8000) != 0,
                    b.u8(at + 7),
                    (g and 0x400) != 0,
                )
            }
        }
    }

    /**
     * The sensor also broadcasts its last three values in a 62-byte advertisement, so a reading costs no
     * connection at all. The manufacturer data (company 0x0059) holds the newest 8-byte record, two 3-byte older
     * ones and two spare bytes — 16 bytes covered by a CRC-32 whose seed is the sum of that block's four
     * little-endian words — and then the complete local name.
     */
    data class Advertisement(val latest: Glucose, val previous: List<Int>, val crcOk: Boolean, val name: String) {
        companion object {
            fun parse(bytes: ByteArray): Advertisement? {
                if (bytes.size < 32 || bytes.u8(8) != 0xFF || bytes.u16le(9) != 0x0059) return null
                val latest = Glucose.parse(bytes, 11) ?: return null
                val previous = (0..1).map { bytes.u16le(19 + it * 3) and 0x3FF }
                val seed = (0..3).sumOf { bytes.u32le(11 + it * 4) } % 0x7FA777L
                val crcOk = Crc.crc32Seeded(bytes, 11, 0x10, seed) == bytes.u32le(27)
                val name =
                    if (bytes.size > 33 && bytes.u8(32) == 0x09) {
                        String(bytes, 33, minOf(bytes.u8(31) - 1, bytes.size - 33), Charsets.US_ASCII)
                    } else {
                        ""
                    }
                return Advertisement(latest, previous, crcOk, name)
            }
        }
    }
}
