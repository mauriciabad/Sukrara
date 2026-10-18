// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.direct

import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * What a Libre 2 / 2 Plus tells us: the 6-byte patch info, the decrypted FRAM (state, age, wear length, factory
 * calibration) and the decrypted BLE packets (raw values). Pure Kotlin, unit-tested (Libre2Test).
 *
 * The BLE values are RAW sensor counts, not mg/dL: the official apps turn them into glucose with Abbott's closed
 * algorithm (Juggluco: libcalibrate.so). [glucose] is the open "factory calibration" formula instead (DiaBLE
 * Glucose.swift:165-453, from xDripSwift LibreMeasurement.swift) — an approximation, see docs/02-direct-sensor.md.
 */
object Libre2Sensor {
    /** Wear lengths when the FRAM doesn't say: Libre 2 Plus 15 d, Libre 2 14 d (minutes). */
    const val WEAR_PLUS_MIN = 21_600
    const val WEAR_MIN = 20_160
    const val WARMUP_MIN = 60

    /** FRAM byte 4 (DiaBLE Sensor.swift SensorState). */
    const val STATE_NOT_ACTIVATED = 1
    const val STATE_WARMING_UP = 2
    const val STATE_ACTIVE = 3
    const val STATE_EXPIRED = 4
    const val STATE_SHUTDOWN = 5
    const val STATE_FAILURE = 6

    /** The NFC tag is a Libre 1/2 (TI chip, manufacturer code 0x07) rather than a Libre 3 (Juggluco ScanNfcV.java:254). */
    fun isLibre2Tag(uid: ByteArray) = uid.size == 8 && uid[6] == 0x07.toByte()

    /**
     * Patch info (`A1` answer without the flags byte): `[0]` type, `[2]` family (high nibble, 3 = Libre 2) and
     * security generation (low nibble), `[3]` region. Mapping from DiaBLE Libre.swift:9-81 and Juggluco
     * SensorGlucoseData.hpp:1125-1140 (`getgeneration`). Examples: Libre 2+ EU `C6 09 31 01` / `7F 0E 31 01`,
     * Libre 2 EU `9D 08 30 01` / `7F 0E 30 01`, Libre 2+ US `2C 0A 3A 02` (Gen2).
     */
    class PatchInfo(val raw: ByteArray) {
        val type = raw[0].toInt() and 0xFF
        val family = (raw[2].toInt() and 0xFF) shr 4
        val generation = raw[2].toInt() and 0x0F
        val region = raw[3].toInt() and 0xFF
        /** 1: the open crypto works; 2: the US/Canada "Gen2" sensors (closed, Abbott library only). */
        val securityGeneration = when (family) {
            3 -> if (generation < 9) 1 else 2
            7 -> if (generation < 4) 1 else 2
            else -> 0
        }
        val isPlus = type == 0xC6 || (type == 0x7F && generation > 0) || type == 0x2C || (type == 0x2B && generation > 9)
        val supported = family == 3 && securityGeneration == 1
        val name get() = if (isPlus) "Libre 2 Plus" else "Libre 2"
        val defaultWearMin get() = if (isPlus) WEAR_PLUS_MIN else WEAR_MIN
        override fun toString() = raw.joinToString("") { "%02X".format(it) }
    }

    /** Factory calibration constants (DiaBLE Libre.swift:186-194). */
    data class Calibration(val i1: Int, val i2: Int, val i3: Int, val i4: Int, val i5: Int, val i6: Int) {
        val usable get() = i2 in 1..1023 && i4 != i3 && i6 != 0
    }

    /** Decrypted FRAM, checked by its header and footer CRCs (DiaBLE Libre.swift:89-200, 263-270). */
    class Fram(val data: ByteArray) {
        val headerOk = Libre2Crypto.crc16(data, 2, 24) == u16(0)
        val bodyOk = Libre2Crypto.crc16(data, 26, 320) == u16(24)
        val footerOk = Libre2Crypto.crc16(data, 322, 344) == u16(320)
        val state = data[4].toInt() and 0xFF
        /** Minutes since activation. */
        val age = u16(316)
        /** Wear length in minutes (0 = unknown). */
        val maxLife = u16(326)
        val calibration: Calibration
            get() {
                val i3 = Libre2Crypto.readBits(data, 0x150, 0, 8)
                return Calibration(
                    i1 = Libre2Crypto.readBits(data, 2, 0, 3),
                    i2 = Libre2Crypto.readBits(data, 2, 3, 0xA),
                    i3 = if (Libre2Crypto.readBits(data, 0x150, 0x21, 1) != 0) -i3 else i3,
                    i4 = Libre2Crypto.readBits(data, 0x150, 8, 0xE),
                    i5 = Libre2Crypto.readBits(data, 0x150, 0x28, 0xC) shl 2,
                    i6 = Libre2Crypto.readBits(data, 0x150, 0x34, 0xC) shl 2,
                )
            }

        private fun u16(o: Int) = (data[o].toInt() and 0xFF) or ((data[o + 1].toInt() and 0xFF) shl 8)
    }

    /** One raw BLE value; [ageMin] = minutes since activation. [raw] 0 = the sensor flagged an error. */
    data class Raw(val ageMin: Int, val raw: Int, val rawTemperature: Int, val temperatureAdjustment: Int)

    /** A decrypted 44-byte BLE packet: 7 trend values (0–15 min old), 3 quarter-hour history values, the age. */
    class Packet(val wearMin: Int, val trend: List<Raw>, val history: List<Raw>)

    private val TREND_OFFSETS = intArrayOf(0, 2, 4, 6, 7, 12, 15)

    /** DiaBLE Libre2.swift:343-394 (`parseBLEData`). */
    fun parsePacket(d: ByteArray): Packet {
        val wear = (d[40].toInt() and 0xFF) or ((d[41].toInt() and 0xFF) shl 8)
        val all = (0 until 10).map { i ->
            val raw = Libre2Crypto.readBits(d, i * 4, 0, 0xE)
            val temp = Libre2Crypto.readBits(d, i * 4, 0xE, 0xC) shl 2
            var adj = Libre2Crypto.readBits(d, i * 4, 0x1A, 0x5) shl 2
            if (Libre2Crypto.readBits(d, i * 4, 0x1F, 0x1) != 0) adj = -adj
            val age = if (i < 7) wear - TREND_OFFSETS[i] else ((wear - 2) / 15) * 15 - 15 * (i - 7)
            Raw(age, raw, if (raw != 0) temp else 0, adj)
        }
        return Packet(wear, all.take(7), all.drop(7))
    }

    /**
     * mg/dL from a raw value with the FRAM's factory calibration (DiaBLE Glucose.swift:165-453), or null when it
     * can't be computed (error value, bad calibration, impossible temperature) or falls outside 39–501.
     */
    fun glucose(r: Raw, c: Calibration): Int? {
        if (r.raw <= 0 || r.ageMin < 0 || !c.usable) return null
        val denominator = (r.temperatureAdjustment + c.i6).toDouble()
        if (denominator == 0.0) return null
        val rr = r.rawTemperature * (1000.0 + 71_500.0) / denominator - 1000.0
        if (rr <= 0) return null
        val logR = ln(rr)
        val d = logR.pow(3) * 0.00000005283566 + logR.pow(2) * 0.0000007061775 + logR * 0.0001964561 + 0.0009180023
        val temperature = 1 / d - 273.15
        if (!temperature.isFinite()) return null
        val g1 = 65.0 * (r.raw - c.i3) / (c.i4 - c.i3)
        val g3 = g1 * 1.045.pow(32.5 - temperature)
        val v = ((g3 - Libre2Tables.T1[c.i2 - 1]) / Libre2Tables.T2[c.i2 - 1])
        if (!v.isFinite()) return null
        return v.roundToInt().takeIf { it in 39..501 }
    }

    /** mg/dL per minute from the trend values (least squares over the last 15 minutes), null with < 3 points. */
    fun rate(points: List<Pair<Int, Int>>): Double? {
        if (points.size < 3) return null
        val n = points.size.toDouble()
        val mx = points.sumOf { it.first } / n
        val my = points.sumOf { it.second } / n
        val sxx = points.sumOf { (it.first - mx) * (it.first - mx) }
        if (sxx == 0.0) return null
        return points.sumOf { (it.first - mx) * (it.second - my) } / sxx
    }
}
