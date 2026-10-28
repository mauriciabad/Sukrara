// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.spike.sensors.dexcom

import dev.spike.sensors.Crc
import dev.spike.sensors.i32le
import dev.spike.sensors.u16le
import dev.spike.sensors.u8
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Dexcom G5 / G6 / ONE transmitter messages. Layouts from xDrip+'s `g5model` message classes (GPL-3) and cross-checked
 * against DiaBLE `Dexcom.swift` (MIT), which parses the same bytes.
 *
 * The transmitter's own algorithm result (mg/dL) is what these messages carry, so no vendor algorithm is needed.
 */
object G6Messages {
    const val AUTH_REQUEST = 0x01
    const val AUTH_CHALLENGE = 0x03
    const val AUTH_CHALLENGE_REPLY = 0x04
    const val AUTH_STATUS = 0x05
    const val KEEP_ALIVE = 0x06
    const val BOND_REQUEST = 0x07
    const val GLUCOSE = 0x30
    const val GLUCOSE_REPLY = 0x31
    const val EGLUCOSE = 0x4E
    const val EGLUCOSE_REPLY = 0x4F
    const val BACKFILL = 0x50
    const val BACKFILL_REPLY = 0x51
    const val DISCONNECT = 0x09

    /**
     * The 16-byte key is the transmitter's own serial, nothing else: ASCII "00" + id + "00" + id
     * (xDrip+ Ob1G5StateMachine.getCryptKey). The user reads the id off the transmitter, so no vendor secret is
     * involved — unlike the G7 (see docs/23).
     */
    fun key(transmitterId: String): ByteArray {
        require(transmitterId.length == 6) { "a G5/G6/ONE transmitter id is 6 characters" }
        return "00$transmitterId".repeat(2).toByteArray(Charsets.US_ASCII)
    }

    /** 0x01 token(8) slot: start authentication. Slot 2 is the normal one, 1 the "alternative" one. */
    fun authRequest(token: ByteArray, slot: Int = 2) = byteArrayOf(AUTH_REQUEST.toByte()) + token + byteArrayOf(slot.toByte())

    /** 0x04 hash(8): our answer to the transmitter's challenge. */
    fun challengeReply(hash: ByteArray) = byteArrayOf(AUTH_CHALLENGE_REPLY.toByte()) + hash

    fun keepAlive(seconds: Int) = byteArrayOf(KEEP_ALIVE.toByte(), seconds.toByte())
    fun bondRequest() = byteArrayOf(BOND_REQUEST.toByte())
    /** 0x30 + CRC: give me the current value from your own algorithm. */
    fun glucoseRequest() = withCrc(byteArrayOf(GLUCOSE.toByte()))
    /** 0x4E + CRC: the extended value (G6 firmware; adds the 30-minute prediction). */
    fun eGlucoseRequest() = withCrc(byteArrayOf(EGLUCOSE.toByte()))
    fun disconnect() = byteArrayOf(DISCONNECT.toByte())

    /** 0x50 05 02 00 start end 000000000000 + CRC: stream the stored values between two transmitter times. */
    fun backfillRequest(startSeconds: Int, endSeconds: Int): ByteArray {
        val b = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN)
        b.put(BACKFILL.toByte()).put(0x05).put(0x02).put(0x00).putInt(startSeconds).putInt(endSeconds).put(ByteArray(6))
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
     * 0x31 (14 bytes) and 0x4F (18 bytes, with the prediction): status, sequence u32, transmitter time u32,
     * glucose u16 (12 bits mg/dL + display-only flags), state, trend (0.1 mg/dL/min), [predicted u16], CRC.
     */
    data class Glucose(
        val status: Int, val sequence: Int, val timeSeconds: Int, val mgdl: Int, val displayOnly: Boolean,
        val state: Int, val trend: Float?, val predictedMgdl: Int?,
    ) {
        val usable get() = Dexcom.stateUsable(state) && mgdl in 39..501
        val lowBattery get() = status and 0x81 == 0x81

        companion object {
            fun parse(b: ByteArray): Glucose? {
                val op = b.firstOrNull()?.toInt()?.and(0xFF) ?: return null
                if (op != GLUCOSE_REPLY && op != EGLUCOSE_REPLY) return null
                if (b.size < 14 || !crcOk(b)) return null
                val g = b.u16le(10)
                val predicted = if (op == EGLUCOSE_REPLY && b.size >= 18) (b.u16le(14) and 0x0FFF).takeIf { it != 0x0FFF } else null
                return Glucose(b.u8(1), b.i32le(2), b.i32le(6), g and 0xFFF, (g and 0xF000) != 0,
                    b.u8(12), Dexcom.trend(b[13]), predicted)
            }
        }
    }

    /**
     * 0x51: status, backfill status, identifier, start u32, end u32, buffer length u32, buffer CRC u16, CRC.
     * The records themselves arrive on the backfill characteristic as [BackfillStream].
     */
    data class BackfillReply(val startSeconds: Int, val endSeconds: Int, val length: Int, val bufferCrc: Int) {
        companion object {
            fun parse(b: ByteArray): BackfillReply? =
                if (b.size < 20 || b.u8(0) != BACKFILL_REPLY || !crcOk(b)) null
                else BackfillReply(b.i32le(4), b.i32le(8), b.i32le(12), b.u16le(16))
        }
    }

    /**
     * The backfill characteristic sends numbered 20-byte pieces: sequence, (unused), then the stream, which is a
     * u32 length followed by 8-byte records (transmitter time u32, glucose u16 (12 bits), state, trend).
     */
    class BackfillStream {
        private val buf = ArrayList<Byte>(2800)
        private var expected = 1

        /** Returns false when a piece arrived out of order: the caller should ask for the backfill again. */
        fun push(piece: ByteArray): Boolean {
            if (piece.isEmpty()) return false
            if (piece.u8(0) != expected) return false
            expected++
            for (i in 2 until piece.size) buf.add(piece[i])
            return true
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

        data class Record(val timeSeconds: Int, val mgdl: Int, val state: Int, val trend: Float?) {
            val usable get() = Dexcom.stateUsable(state) && mgdl in 39..501
        }
    }
}
