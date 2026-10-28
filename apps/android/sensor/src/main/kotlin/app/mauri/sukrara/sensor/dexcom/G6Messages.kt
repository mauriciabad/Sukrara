// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.sensor.dexcom

import app.mauri.sukrara.sensor.brand.Crc
import app.mauri.sukrara.sensor.brand.i32le
import app.mauri.sukrara.sensor.brand.u16le
import app.mauri.sukrara.sensor.brand.u8
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Dexcom G5 / G6 / ONE transmitter messages. Layouts from xDrip+'s `g5model` message classes (GPL-3), cross-checked
 * against DiaBLE `Dexcom.swift` (MIT). The transmitter's own algorithm result (mg/dL) is what these carry, so no vendor
 * algorithm is needed. Untested on a real transmitter.
 */
internal object G6Messages {
    const val AUTH_REQUEST = 0x01
    const val AUTH_CHALLENGE = 0x03
    const val AUTH_CHALLENGE_REPLY = 0x04
    const val AUTH_STATUS = 0x05
    const val BOND_REQUEST = 0x07
    const val TIME = 0x24
    const val TIME_REPLY = 0x25
    const val GLUCOSE_REPLY = 0x31
    const val EGLUCOSE = 0x4E
    const val EGLUCOSE_REPLY = 0x4F
    const val BACKFILL = 0x50
    const val BACKFILL_REPLY = 0x51

    /**
     * The 16-byte key is the transmitter's own id, nothing else: ASCII "00" + id + "00" + id (xDrip+ getCryptKey). The
     * person reads the id off the transmitter: no vendor secret.
     */
    fun key(transmitterId: String): ByteArray {
        require(transmitterId.length == 6) { "a G5/G6/ONE transmitter id is 6 characters" }
        return "00$transmitterId".repeat(2).toByteArray(Charsets.US_ASCII)
    }

    /** 0x01 token(8) slot: start authentication. Slot 2 is the one phone apps use (xDrip+ "alt channel" off). */
    fun authRequest(
        token: ByteArray,
        slot: Int = 2,
    ) = byteArrayOf(AUTH_REQUEST.toByte()) + token + byteArrayOf(slot.toByte())

    /** 0x04 hash(8): our answer to the transmitter's challenge. */
    fun challengeReply(hash: ByteArray) = byteArrayOf(AUTH_CHALLENGE_REPLY.toByte()) + hash

    fun bondRequest() = byteArrayOf(BOND_REQUEST.toByte())

    /** 0x24 + CRC: the transmitter's clock and its session start. */
    fun timeRequest() = withCrc(byteArrayOf(TIME.toByte()))

    /** 0x4E + CRC: the current value (G6 firmware). */
    fun eGlucoseRequest() = withCrc(byteArrayOf(EGLUCOSE.toByte()))

    /** 0x50 05 02 00 start end 000000000000 + CRC: stream the stored values between two transmitter times. */
    fun backfillRequest(
        startSeconds: Int,
        endSeconds: Int,
    ): ByteArray {
        val b = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN)
        b
            .put(BACKFILL.toByte())
            .put(0x05)
            .put(0x02)
            .put(0x00)
            .putInt(startSeconds)
            .putInt(endSeconds)
            .put(ByteArray(6))
        val a = b.array()
        System.arraycopy(Crc.le(Crc.xmodem(a, 0, 18)), 0, a, 18, 2)
        return a
    }

    fun withCrc(body: ByteArray) = body + Crc.le(Crc.xmodem(body))

    fun crcOk(frame: ByteArray) = frame.size >= 3 && frame.u16le(frame.size - 2) == Crc.xmodem(frame, 0, frame.size - 2)

    /** 0x03 tokenHash(8) challenge(8). */
    class AuthChallenge(val tokenHash: ByteArray, val challenge: ByteArray) {
        companion object {
            fun parse(b: ByteArray) =
                if (b.size >= 17 && b.u8(0) == AUTH_CHALLENGE) AuthChallenge(b.copyOfRange(1, 9), b.copyOfRange(9, 17)) else null
        }
    }

    /** 0x05 authenticated bonded. */
    data class AuthStatus(val authenticated: Int, val bonded: Int) {
        val ok get() = authenticated == 1

        companion object {
            fun parse(b: ByteArray) = if (b.size >= 3 && b.u8(0) == AUTH_STATUS) AuthStatus(b.u8(1), b.u8(2)) else null
        }
    }

    /**
     * 0x25 status currentTime(u32) sessionStart(u32) … CRC, in the transmitter's seconds. No session: sessionStart is
     * 0xFFFFFFFF or equal to the current time (xDrip+ `sessionInProgress`).
     */
    data class Time(val currentSeconds: Int, val sessionStartSeconds: Int) {
        val sessionInProgress get() = sessionStartSeconds != -1 && currentSeconds != sessionStartSeconds

        companion object {
            fun parse(b: ByteArray) = if (b.size >= 12 && b.u8(0) == TIME_REPLY && crcOk(b)) Time(b.i32le(2), b.i32le(6)) else null
        }
    }

    /**
     * 0x31 (14 bytes) and 0x4F (18 bytes): status, sequence u32, transmitter time u32, glucose u16 (12 bits mg/dL +
     * display-only flags), state, trend (0.1 mg/dL/min), [predicted u16], CRC.
     */
    data class Glucose(val sequence: Int, val timeSeconds: Int, val mgdl: Int, val state: Int, val trend: Double?) {
        companion object {
            fun parse(b: ByteArray): Glucose? {
                val op = b.firstOrNull()?.toInt()?.and(0xFF) ?: return null
                if (op != GLUCOSE_REPLY && op != EGLUCOSE_REPLY) return null
                if (b.size < 14 || !crcOk(b)) return null
                return Glucose(b.i32le(2), b.i32le(6), b.u16le(10) and 0xFFF, b.u8(12), Dexcom.trend(b[13]))
            }
        }
    }

    /** 0x51: status, backfill status, identifier, start u32, end u32, buffer length u32, buffer CRC u16, CRC. */
    data class BackfillReply(val startSeconds: Int, val endSeconds: Int, val length: Int) {
        companion object {
            fun parse(b: ByteArray): BackfillReply? =
                if (b.size < 20 || b.u8(0) != BACKFILL_REPLY || !crcOk(b)) null else BackfillReply(b.i32le(4), b.i32le(8), b.i32le(12))
        }
    }

    /**
     * The backfill characteristic sends numbered 20-byte pieces: sequence, (unused), then the stream, which is a u32
     * length followed by 8-byte records (transmitter time u32, glucose u16 (12 bits), state, trend). Bounded: a stream
     * longer than [MAX_BYTES] (more than a day of records) is cut off.
     */
    class BackfillStream {
        private val buf = ArrayList<Byte>(2800)
        private var expected = 1

        /** False when a piece arrived out of order (the stream is then unusable until [clear]). */
        fun push(piece: ByteArray): Boolean {
            if (piece.isEmpty() || piece.u8(0) != expected) return false
            expected++
            for (i in 2 until piece.size) if (buf.size < MAX_BYTES) buf.add(piece[i])
            return true
        }

        fun clear() {
            buf.clear()
            expected = 1
        }

        fun records(): List<Record> {
            val b = buf.toByteArray()
            if (b.size < 4) return emptyList()
            val length = b.i32le(0)
            val end = minOf(b.size, 4 + maxOf(0, length))
            val out = ArrayList<Record>()
            var at = 4
            while (at + 8 <= end) {
                out.add(Record(b.i32le(at), b.u16le(at + 4) and 0xFFF, b.u8(at + 6), Dexcom.trend(b[at + 7])))
                at += 8
            }
            return out
        }

        data class Record(val timeSeconds: Int, val mgdl: Int, val state: Int, val trend: Double?)

        private companion object {
            const val MAX_BYTES = 4 + 288 * 8
        }
    }
}
