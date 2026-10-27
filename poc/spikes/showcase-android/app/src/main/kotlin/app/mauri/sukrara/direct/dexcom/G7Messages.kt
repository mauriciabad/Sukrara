// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.direct.dexcom

import app.mauri.sukrara.direct.i32le
import app.mauri.sukrara.direct.u16le
import app.mauri.sukrara.direct.u8

/**
 * The G7 / ONE+ / Stelo messages observer mode reads. Field layout and the "no value" rules follow **LoopKit's
 * G7SensorKit** (MIT: `G7GlucoseMessage.swift`, `G7BackfillMessage.swift`, `AuthChallengeRxMessage`), whose own test
 * frames are the fixtures in `G7DriverTest`. Juggluco and xDrip+ read the display-only flag out of the glucose field's
 * top nibble; G7SensorKit's reading (flag in the last byte, 0xFFFF = none) is the one its tests pin down.
 *
 * Only what a reader needs: the sensor's own pairing (J-PAKE + a Dexcom app certificate) is not here, by decision
 * (docs/23 "Why the Dexcom G7's own pairing is refused"). Ported from `spikes/sensors-android`. Untested on hardware.
 */
internal object G7Messages {
    const val EGV = 0x4E
    const val BACKFILL_FINISHED = 0x59

    /** 0x4E, 19 bytes, little-endian. [secondsSinceStart] counts from the sensor's session start. */
    data class Egv(
        val secondsSinceStart: Long, val sequence: Int, val ageSeconds: Int,
        val mgdl: Int?, val displayOnly: Boolean, val state: Int, val trend: Float?, val predictedMgdl: Int?,
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
                    predictedMgdl = if (predicted != 0xFFFF) predicted and 0xFFF else null,
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

    /** 0x59 on the control characteristic: the sensor has finished sending the backfill the vendor app asked for. */
    fun isBackfillFinished(b: ByteArray) = b.size >= 3 && b.u8(0) == BACKFILL_FINISHED

    /**
     * 0x05 authenticated bonded. The sensor indicates this to **every** subscriber, which is what makes observer mode
     * possible (G7SensorKit): 1/1 means an app on this phone has a live authenticated session, so the control
     * characteristic will start sending glucose.
     */
    data class AuthStatus(val authenticated: Int, val bonded: Int) {
        val live get() = authenticated == 1 && bonded == 1
        companion object {
            fun parse(b: ByteArray) = if (b.size >= 3 && b.u8(0) == 0x05) AuthStatus(b.u8(1), b.u8(2)) else null
        }
    }
}
