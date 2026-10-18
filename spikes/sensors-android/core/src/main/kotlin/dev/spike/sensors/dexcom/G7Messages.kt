// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.spike.sensors.dexcom

import dev.spike.sensors.i32le
import dev.spike.sensors.u16le
import dev.spike.sensors.u8
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * G7 / ONE+ / Stelo messages.
 *
 * Field layout and the "no value" rules follow **LoopKit's G7SensorKit** (MIT:
 * `G7SensorKit/Messages/G7GlucoseMessage.swift`, `G7CGMManager/G7BackfillMessage.swift`), which has unit tests with
 * recorded frames — those frames are the fixtures in `G7MessagesTest`. Juggluco's `cpp/dexcom/java.cpp` and xDrip+'s
 * `EGlucoseRxMessage2` parse the same bytes but read the display-only flag out of the glucose field's top nibble and
 * mask the prediction with 0x3FF; G7SensorKit's reading (flag in the last byte, 12-bit prediction, 0xFFFF = none) is
 * the one its tests pin down, so it is what this port does.
 */
object G7Messages {
    const val EGV = 0x4E
    const val BACKFILL_REQUEST = 0x59
    const val BACKFILL_FINISHED = 0x59

    /** 0x4E, 19 bytes, little-endian. [secondsSinceStart] counts from the sensor's pairing. */
    data class Egv(
        val secondsSinceStart: Long, val sequence: Int, val ageSeconds: Int,
        val mgdl: Int?, val displayOnly: Boolean, val state: Int, val trend: Float?, val predictedMgdl: Int?,
        val calibration: Int,
    ) {
        /** When this value was measured, in sensor time. */
        val readingSeconds get() = secondsSinceStart - ageSeconds
        val usable get() = mgdl != null && Dexcom.stateUsable(state)

        companion object {
            fun parse(b: ByteArray): Egv? {
                if (b.size < 19 || b.u8(0) != EGV || b.u8(1) != 0) return null
                val raw = b.u16le(12)
                val predicted = b.u16le(16)
                return Egv(
                    secondsSinceStart = b.i32le(2).toLong() and 0xFFFFFFFFL, sequence = b.u16le(6), ageSeconds = b.u16le(10),
                    mgdl = if (raw != 0xFFFF) raw and 0xFFF else null, displayOnly = (b.u8(18) and 0x10) != 0,
                    state = b.u8(14), trend = Dexcom.trend(b[15]),
                    predictedMgdl = if (predicted != 0xFFFF) predicted and 0xFFF else null, calibration = b.u8(18),
                )
            }
        }
    }

    /** One 9-byte backfill record: seconds since start (3 bytes), glucose u16, state, flags, trend. */
    data class BackfillRecord(val secondsSinceStart: Int, val mgdl: Int?, val displayOnly: Boolean, val state: Int, val trend: Float?) {
        val usable get() = mgdl != null && Dexcom.stateUsable(state)

        companion object {
            const val SIZE = 9
            fun parse(b: ByteArray, at: Int = 0): BackfillRecord? {
                if (b.size < at + SIZE) return null
                val raw = b.u16le(at + 4)
                return BackfillRecord(
                    secondsSinceStart = b.u8(at) or (b.u8(at + 1) shl 8) or (b.u8(at + 2) shl 16),
                    mgdl = if (raw != 0xFFFF) raw and 0xFFF else null, displayOnly = (b.u8(at + 7) and 0x10) != 0,
                    state = b.u8(at + 6), trend = Dexcom.trend(b[at + 8]),
                )
            }

            /** A notification can carry several records back to back. */
            fun parseAll(b: ByteArray): List<BackfillRecord> =
                if (b.isEmpty() || b.size % SIZE != 0) emptyList()
                else (b.indices step SIZE).mapNotNull { parse(b, it) }
        }
    }

    /** 0x59 start end: ask for the records between two sensor times (seconds since start). */
    fun backfillRequest(fromSeconds: Int, toSeconds: Int): ByteArray =
        ByteBuffer.allocate(9).order(ByteOrder.LITTLE_ENDIAN).put(BACKFILL_REQUEST.toByte()).putInt(fromSeconds).putInt(toSeconds).array()

    /** 0x59 on the control characteristic, 19 bytes: the sensor has finished sending backfill records. */
    fun isBackfillFinished(b: ByteArray) = b.size >= 3 && b.u8(0) == BACKFILL_FINISHED

    // ---- authentication characteristic ----

    /** 0x0A n: "send me your J-PAKE round n". */
    fun jpakeRound(n: Int) = byteArrayOf(0x0A, n.toByte())
    /** 0x02 token(8) 0x02: authenticate with the J-PAKE key. */
    fun authRequest(token: ByteArray) = byteArrayOf(0x02) + token + byteArrayOf(0x02)
    fun challengeReply(hash: ByteArray) = byteArrayOf(0x04) + hash
    /** 0x0B which len(4, LE): "here comes my certificate [which]" (Juggluco lencode). */
    fun certificateOffer(which: Int, length: Int) =
        ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN).put(0x0B).put(which.toByte()).putInt(length).array()
    /** 0x0C + 16 random bytes: our challenge for the sensor's key. */
    fun keyChallenge(random16: ByteArray) = byteArrayOf(0x0C) + random16
    val SIGNATURE_DONE = byteArrayOf(0x0D, 0x00, 0x02)
    val KEEP_ALIVE_BOND = byteArrayOf(0x06, 0x19)

    /** Sensor answers that mean "now create the BLE bond" (Juggluco bondBytes). */
    val BOND_TRIGGERS = listOf(byteArrayOf(0x06, 0x19), byteArrayOf(0xFF.toByte(), 0x06, 0x01), byteArrayOf(0x06, 0x00))

    /** 0x03 tokenHash(8) challenge(8). */
    class AuthChallenge(val tokenHash: ByteArray, val challenge: ByteArray) {
        companion object {
            fun parse(b: ByteArray) = if (b.size >= 17 && b.u8(0) == 0x03) AuthChallenge(b.copyOfRange(1, 9), b.copyOfRange(9, 17)) else null
        }
    }

    /**
     * 0x05 authenticated bonded. The sensor indicates this to **every** subscriber, which is what makes
     * [G7Driver]'s observer mode possible (G7SensorKit `AuthChallengeRxMessage`): 1/1 means some app on this phone
     * has a live authenticated session, so the control characteristic will start sending glucose.
     * bonded 3 = refused, another device holds the sensor.
     */
    data class AuthStatus(val authenticated: Int, val bonded: Int) {
        val live get() = authenticated == 1 && bonded == 1
        companion object {
            fun parse(b: ByteArray) = if (b.size >= 3 && b.u8(0) == 0x05) AuthStatus(b.u8(1), b.u8(2)) else null
        }
    }

    /** 0x0B 0x00 which size(u16): the sensor expects our app certificate [which] (Juggluco getDexCertSize). */
    data class CertificateSize(val which: Int, val size: Int) {
        companion object {
            fun parse(b: ByteArray) =
                if (b.size == 7 && b.u8(0) == 0x0B && b.u8(1) == 0 && b[2] >= 0) CertificateSize(b.u8(2), b.u16le(3)) else null
        }
    }
}
