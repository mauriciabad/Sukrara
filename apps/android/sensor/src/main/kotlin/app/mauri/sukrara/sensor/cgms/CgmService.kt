// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.sensor.cgms

import app.mauri.sukrara.sensor.brand.Crc
import app.mauri.sukrara.sensor.brand.u16le
import app.mauri.sukrara.sensor.brand.u8
import java.util.UUID
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The Bluetooth SIG CGM Service (0x181F, CGMS 1.0.x): a public standard. The Accu-Chek SmartGuide speaks it. Layouts
 * from the specification; the frames in `SmartGuideDriverTest` are real SmartGuide traffic quoted in Juggluco
 * (`accu/java.cpp`), which pins the parser down.
 */
internal object CgmService {
    private fun sig(short: String) = UUID.fromString("0000$short-0000-1000-8000-00805f9b34fb")

    val SERVICE: UUID = sig("181F")
    val MEASUREMENT: UUID = sig("2AA7")
    val STATUS: UUID = sig("2AA9")

    /** Record Access Control Point: stored records. */
    val RACP: UUID = sig("2A52")

    /** CGM Specific Ops Control Point. */
    val SOCP: UUID = sig("2AAC")

    /** RACP: report the stored records whose time offset is >= [fromMinutes]. */
    fun reportRecordsFrom(fromMinutes: Int): ByteArray =
        byteArrayOf(0x01, 0x03, 0x01, (fromMinutes and 0xFF).toByte(), ((fromMinutes shr 8) and 0xFF).toByte())

    /** SOCP: get the communication interval, with the E2E-CRC the SmartGuide requires. */
    fun getCommunicationInterval(): ByteArray = withCrc(byteArrayOf(0x02))

    fun withCrc(body: ByteArray): ByteArray = body + Crc.le(Crc.mcrf4xx(body))

    /** True when the trailing two bytes are a correct E2E-CRC over everything before them. */
    fun crcOk(frame: ByteArray): Boolean = frame.size >= 3 && frame.u16le(frame.size - 2) == Crc.mcrf4xx(frame, 0, frame.size - 2)

    /** The three Sensor Status Annunciation octets (CGMS §3.1.1.4). */
    data class Annunciation(val status: Int, val calTemp: Int, val warning: Int) {
        /** Status bit 0: the session has stopped. */
        val sessionStopped get() = status and 0x01 != 0

        /** Status bit 3: sensor malfunction. */
        val sensorMalfunction get() = status and 0x08 != 0

        /** Warning bit 6 (annunciation bit 22): lower than the device can process ("LO"). */
        val belowRange get() = warning and 0x40 != 0

        /** Warning bit 7 (annunciation bit 23): higher than the device can process ("HI"). */
        val aboveRange get() = warning and 0x80 != 0
    }

    /**
     * A CGM Measurement record (2AA7, notified; also how RACP sends stored ones): size, flags, glucose SFLOAT (mg/dL),
     * time offset u16 (minutes since session start), the annunciation octets the flags announce (in the order status,
     * cal/temp, warning: flag 0x80, 0x40, 0x20), optional trend SFLOAT, optional quality SFLOAT, E2E-CRC.
     */
    data class Measurement(
        val offsetMinutes: Int,
        /** Null when the sensor sent a special SFLOAT (error, or out of its range) instead of a number. */
        val mgdl: Int?,
        val trendMgdlPerMin: Double?,
        val annunciation: Annunciation,
        /** Null when the record carries no E2E-CRC. */
        val crcOk: Boolean?,
    ) {
        companion object {
            /** One record starting at [from]; null when it is too short or its size is wrong. */
            fun parse(
                frame: ByteArray,
                from: Int = 0,
            ): Measurement? {
                if (frame.size < from + 6) return null
                val size = frame.u8(from)
                if (size < 6 || from + size > frame.size) return null
                val rec = frame.copyOfRange(from, from + size)
                val flags = rec.u8(1)
                val glucose = sfloat(rec.u16le(2))
                var at = 6

                fun octet(bit: Int): Int = if (flags and bit != 0 && at < size) rec.u8(at++) else 0
                val status = octet(0x80)
                val calTemp = octet(0x40)
                val warning = octet(0x20)

                fun optional(bit: Int): Float? = if (flags and bit != 0 && at + 1 < size) sfloat(rec.u16le(at)).also { at += 2 } else null
                val trend = optional(0x01)
                optional(0x02) // quality: not used
                val crc = if (size >= at + 2) crcOk(rec) else null
                return Measurement(
                    rec.u16le(4),
                    glucose?.roundToInt(),
                    trend?.toDouble(),
                    Annunciation(status, calTemp, warning),
                    crc,
                )
            }

            /** A notification may carry several records back to back, each starting with its size. */
            fun parseAll(frame: ByteArray): List<Measurement> {
                val out = ArrayList<Measurement>()
                var at = 0
                while (at < frame.size) {
                    val m = parse(frame, at) ?: break
                    out.add(m)
                    at += frame.u8(at)
                }
                return out
            }
        }
    }

    /** CGM Status (2AA9, read): time offset u16 (minutes the session has run), the three annunciation octets, E2E-CRC. */
    data class Status(val offsetMinutes: Int, val annunciation: Annunciation) {
        companion object {
            /** Null unless the read carries a correct E2E-CRC (the start of every reading's time comes from it). */
            fun parse(frame: ByteArray): Status? =
                if (frame.size < 7 || !crcOk(frame)) null else Status(frame.u16le(0), Annunciation(frame.u8(2), frame.u8(3), frame.u8(4)))
        }
    }

    /**
     * IEEE 11073-20601 16-bit SFLOAT. The reserved patterns (NaN, ±INFINITY, NRes, reserved) come back as null: they
     * mean "no number", e.g. 0x0802 = −INFINITY = below the sensor's range.
     */
    fun sfloat(raw: Int): Float? {
        if (raw in setOf(0x07FF, 0x0800, 0x0801, 0x0802, 0x07FE)) return null
        var mantissa = raw and 0x0FFF
        if (mantissa >= 0x0800) mantissa -= 0x1000
        var exponent = (raw shr 12) and 0x0F
        if (exponent >= 0x0008) exponent -= 0x0010
        return (mantissa * 10.0.pow(exponent)).toFloat()
    }
}
