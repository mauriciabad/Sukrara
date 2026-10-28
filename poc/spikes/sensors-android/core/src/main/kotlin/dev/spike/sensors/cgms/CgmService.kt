// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.spike.sensors.cgms

import dev.spike.sensors.Crc
import dev.spike.sensors.u16le
import dev.spike.sensors.u8
import java.util.UUID

/**
 * The Bluetooth SIG **CGM Service** (0x181F, CGMS 1.0.2) — a public standard, no reverse engineering involved.
 * The Accu-Chek SmartGuide speaks it; the Aidex X exposes the same service UUID with private characteristics.
 *
 * Layouts from the specification; the frames in `CgmsTest` are real SmartGuide traffic quoted in Juggluco
 * (`Common/src/main/cpp/accu/java.cpp:130-182, :292`), which is how this parser was pinned down.
 */
object CgmService {
    private fun sig(short: String) = UUID.fromString("0000$short-0000-1000-8000-00805f9b34fb")

    val SERVICE: UUID = sig("181F")
    val MEASUREMENT: UUID = sig("2AA7")
    val FEATURE: UUID = sig("2AA8")
    val STATUS: UUID = sig("2AA9")
    val SESSION_START_TIME: UUID = sig("2AAA")
    val SESSION_RUN_TIME: UUID = sig("2AAB")
    /** Record Access Control Point: stored records. */
    val RACP: UUID = sig("2A52")
    /** CGM Specific Ops Control Point. */
    val SOCP: UUID = sig("2AAC")

    /** RACP: report the stored records whose time offset is >= [fromMinutes]. */
    fun reportRecordsFrom(fromMinutes: Int): ByteArray =
        byteArrayOf(0x01, 0x03, 0x01, (fromMinutes and 0xFF).toByte(), ((fromMinutes shr 8) and 0xFF).toByte())

    /** SOCP: get the communication interval, with the E2E-CRC devices like the SmartGuide require. */
    fun getCommunicationInterval(): ByteArray = withCrc(byteArrayOf(0x02))

    /** SOCP: send readings every [minutes] minutes (0 = stop). */
    fun setCommunicationInterval(minutes: Int): ByteArray = withCrc(byteArrayOf(0x01, minutes.toByte()))

    fun withCrc(body: ByteArray): ByteArray = body + Crc.le(Crc.mcrf4xx(body))

    /** True when the trailing two bytes are a correct E2E-CRC over everything before them. */
    fun crcOk(frame: ByteArray): Boolean =
        frame.size >= 3 && frame.u16le(frame.size - 2) == Crc.mcrf4xx(frame, 0, frame.size - 2)

    /**
     * A CGM Measurement record (2AA7, notified) or one record of a RACP reply:
     * size, flags, glucose SFLOAT (mg/dL), time offset u16 (minutes since session start), 0-3 annunciation bytes,
     * optional trend SFLOAT (mg/dL/min), optional quality SFLOAT (%), optional E2E-CRC.
     */
    data class Measurement(
        val offsetMinutes: Int,
        /** Null when the sensor reported a special SFLOAT (error, or out of its range) instead of a number. */
        val mgdl: Int?,
        val trendMgdlPerMin: Float?,
        val qualityPercent: Float?,
        val status: Int,
        val calTemp: Int,
        val warning: Int,
        val crcOk: Boolean?,
    ) {
        val sessionStopped get() = status and 0x01 != 0
        val sensorMalfunction get() = status and 0x08 != 0
        val calibrationRequired get() = calTemp and 0x08 != 0
        /** The reading is below what the sensor can process: a CGM shows "LO" (Juggluco posts 39 mg/dL). */
        val belowRange get() = warning and 0x40 != 0
        val aboveRange get() = warning and 0x80 != 0

        companion object {
            fun parse(frame: ByteArray): Measurement? {
                if (frame.size < 6) return null
                val size = frame.u8(0)
                if (size < 6 || size > frame.size) return null
                val flags = frame.u8(1)
                val glucose = sfloat(frame.u16le(2))
                val offset = frame.u16le(4)
                var at = 6
                fun annunciation(bit: Int): Int = if (flags and bit != 0 && at < size) frame.u8(at++) else 0
                val status = annunciation(0x20)
                val calTemp = annunciation(0x40)
                val warning = annunciation(0x80)
                fun optional(bit: Int): Float? =
                    if (flags and bit != 0 && at + 1 < size) sfloat(frame.u16le(at)).also { at += 2 } else null
                val trend = optional(0x01)
                val quality = optional(0x02)
                val crc = if (size >= at + 2) crcOk(frame.copyOf(size)) else null
                return Measurement(offset, glucose?.let { Math.round(it) }, trend, quality, status, calTemp, warning, crc)
            }
        }
    }

    /**
     * CGM Status (2AA9, read): time offset u16 then the three annunciation bytes, plus the E2E-CRC.
     * The offset is how many minutes the session has been running, which is how the session start is recovered.
     */
    data class Status(val offsetMinutes: Int, val status: Int, val calTemp: Int, val warning: Int) {
        companion object {
            fun parse(frame: ByteArray): Status? =
                if (frame.size < 5) null else Status(frame.u16le(0), frame.u8(2), frame.u8(3), frame.u8(4))
        }
    }

    /**
     * IEEE 11073-20601 16-bit SFLOAT. The reserved patterns (NaN, ±INFINITY, NRes, reserved) come back as null:
     * they mean "no number", e.g. 0x0802 = −INFINITY = below the sensor's range.
     */
    fun sfloat(raw: Int): Float? {
        if (raw in setOf(0x07FF, 0x0800, 0x0801, 0x0802, 0x07FE)) return null
        var mantissa = raw and 0x0FFF
        if (mantissa >= 0x0800) mantissa -= 0x1000
        var exponent = (raw shr 12) and 0x0F
        if (exponent >= 0x0008) exponent -= 0x0010
        return (mantissa * Math.pow(10.0, exponent.toDouble())).toFloat()
    }
}
