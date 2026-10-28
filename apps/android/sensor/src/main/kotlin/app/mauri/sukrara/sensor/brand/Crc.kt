// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.sensor.brand

/**
 * The checksums the scanned families need. Each one is pinned by real frames in `CrcTest`, so a wrong variant can't
 * slip past. The Libre 3 needs none of them (its packets are AES-CCM).
 */
internal object Crc {
    /** CRC-16/XMODEM. Dexcom G5/G6 messages append it little-endian. */
    fun xmodem(data: ByteArray, from: Int = 0, to: Int = data.size): Int {
        var crc = 0
        for (i in from until to) {
            crc = ((crc shl 8) and 0xFFFF) xor TAB_1021[((crc shr 8) xor (data[i].toInt() and 0xFF)) and 0xFF]
        }
        return crc
    }

    /** CRC-16/MCRF4XX: the Bluetooth CGM Service's E2E-CRC, appended little-endian. */
    fun mcrf4xx(data: ByteArray, from: Int = 0, to: Int = data.size): Int {
        var crc = 0xFFFF
        for (i in from until to) {
            crc = crc xor (data[i].toInt() and 0xFF)
            repeat(8) { crc = if (crc and 1 != 0) (crc shr 1) xor 0x8408 else crc shr 1 }
        }
        return crc
    }

    /** CRC-16/CCITT-FALSE. Aidex X commands end with it, little-endian. */
    fun ccittFalse(data: ByteArray, from: Int = 0, to: Int = data.size): Int {
        var crc = 0xFFFF
        for (i in from until to) {
            crc = crc xor ((data[i].toInt() and 0xFF) shl 8)
            repeat(8) { crc = if (crc and 0x8000 != 0) ((crc shl 1) xor 0x1021) and 0xFFFF else (crc shl 1) and 0xFFFF }
        }
        return crc
    }

    /** CRC-8/MAXIM, checking Aidex X's session key. */
    fun crc8Maxim(data: ByteArray, from: Int = 0, to: Int = data.size): Int {
        var crc = 0
        for (i in from until to) {
            crc = crc xor (data[i].toInt() and 0xFF)
            repeat(8) { crc = if (crc and 1 != 0) ((crc shr 1) xor 0x8C) and 0xFF else (crc shr 1) and 0xFF }
        }
        return crc
    }

    /** CRC-32 with the raw polynomial and a caller-chosen seed (Aidex X advertisements). */
    fun crc32Seeded(data: ByteArray, from: Int, length: Int, seed: Long): Long {
        var crc = seed and 0xFFFFFFFFL
        for (i in from until from + length) {
            crc = crc xor ((data[i].toLong() and 0xFF) shl 24)
            repeat(8) {
                crc = if (crc and 0x80000000L != 0L) ((crc shl 1) xor 0x04C11DB7L) and 0xFFFFFFFFL else (crc shl 1) and 0xFFFFFFFFL
            }
        }
        return crc
    }

    fun le(crc: Int) = byteArrayOf((crc and 0xFF).toByte(), ((crc shr 8) and 0xFF).toByte())

    private val TAB_1021 =
        IntArray(256) { i ->
            var c = i shl 8
            repeat(8) { c = if (c and 0x8000 != 0) ((c shl 1) xor 0x1021) and 0xFFFF else (c shl 1) and 0xFFFF }
            c
        }
}

internal fun ByteArray.u8(at: Int): Int = this[at].toInt() and 0xFF

internal fun ByteArray.u16le(at: Int): Int = u8(at) or (u8(at + 1) shl 8)

internal fun ByteArray.u32le(at: Int): Long = (u16le(at).toLong()) or (u16le(at + 2).toLong() shl 16)

internal fun ByteArray.i32le(at: Int): Int = u32le(at).toInt()
