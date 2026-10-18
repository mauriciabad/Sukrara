// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.direct

/**
 * FreeStyle Libre 2 / 2 Plus (security generation 1, i.e. the European sensors) crypto, pure Kotlin.
 *
 * Juggluco can't be the reference here: it hands every Libre 2 step (activation payload, streaming-enable payload,
 * BLE unlock, decryption, glucose) to Abbott's proprietary `libcalibrate.so` (Juggluco libre2.cpp:1231-1380,
 * `abbottcall(...)`), which isn't in its source. This is a port of the open, reverse-engineered implementation in
 * DiaBLE (MIT, third_party/DiaBLE, commit e6a909c), which LibreTools and LibreTransmitter (both MIT) share:
 *  - [prepareVariables], [processCrypto], [usefulFunction]: DiaBLE/Libre2.swift:56-123
 *  - [decryptFram]: Libre2.swift:133-171 (the `.libre2` branch)
 *  - [enableStreamingCommand]: Libre2.swift:190-198 + NFC.swift:112-122; LibreTransmitter SensorPairingService.swift:338-362
 *  - [activateCommand]: NFC.swift:39-45 (`.libre2` → `nfcCommand(.activate)`); LibreTools Libre2.swift:98-101
 *  - [streamingUnlockPayload]: Libre2.swift:238-292
 *  - [decryptBle]: Libre2.swift:300-332
 *  - [crc16]: Libre.swift:301-310; [readBits]: Libre.swift:315-329; [serial]: Libre.swift:278-298
 * The ISO 15693 framing (flags 0x02, manufacturer code 0x07 = TI) is Juggluco's: AlgNfcV.java:80-86, 236-243.
 *
 * Byte order: [uid] is Android's `Tag.getId()` (least significant byte first, `uid[7] == 0xE0`), which is what DiaBLE
 * calls the sensor UID (`tag.identifier.reversed()`). All 16-bit words are little-endian.
 */
object Libre2Crypto {
    private val KEY = intArrayOf(0xA0C5, 0x6860, 0x0000, 0x14C6)
    private const val SECRET = 0x1B6A

    /** NFC subcommands of the 0xA1 "universal" command (DiaBLE NFC.swift:82-89). */
    const val SUB_ACTIVATE = 0x1B
    const val SUB_ENABLE_STREAMING = 0x1E

    private fun w(hi: Int, lo: Int) = ((hi and 0xFF) shl 8) or (lo and 0xFF)
    private fun w(b: ByteArray, lo: Int) = w(b[lo + 1].toInt(), b[lo].toInt())

    internal fun prepareVariables(id: ByteArray, x: Int, y: Int) = intArrayOf(
        (w(id, 4) + x + y) and 0xFFFF,
        (w(id, 2) + KEY[2]) and 0xFFFF,
        (w(id, 0) + x * 2) and 0xFFFF,
        0x241A xor KEY[3],
    )

    private fun prepareVariables2(id: ByteArray, i1: Int, i2: Int, i3: Int, i4: Int) = intArrayOf(
        (w(id, 4) + i1) and 0xFFFF,
        (w(id, 2) + i2) and 0xFFFF,
        (w(id, 0) + i3 + KEY[2]) and 0xFFFF,
        (i4 + KEY[3]) and 0xFFFF,
    )

    internal fun processCrypto(input: IntArray): IntArray {
        fun op(v: Int): Int {
            var r = v ushr 2
            if (v and 1 != 0) r = r xor KEY[1]
            if (v and 2 != 0) r = r xor KEY[0]
            return r
        }
        val r0 = op(input[0]) xor input[3]
        val r1 = op(r0) xor input[2]
        val r2 = op(r1) xor input[1]
        val r3 = op(r2) xor input[0]
        val r4 = op(r3)
        val r5 = op(r4 xor r0)
        val r6 = op(r5 xor r1)
        val r7 = op(r6 xor r2)
        return intArrayOf(r3 xor r7, r2 xor r6, r1 xor r5, r0 xor r4)
    }

    internal fun usefulFunction(id: ByteArray, x: Int, y: Int): ByteArray {
        val k = processCrypto(prepareVariables(id, x, y))
        val r1 = k[0] xor 0x4163
        val r2 = k[1] xor 0x4344
        return byteArrayOf(r1.toByte(), (r1 shr 8).toByte(), r2.toByte(), (r2 shr 8).toByte())
    }

    private fun words(k: IntArray) = ByteArray(8) { (k[it / 2] shr (8 * (it % 2))).toByte() }

    /** Decrypts the 43 FRAM blocks (344 bytes) of a European Libre 2 / 2 Plus. */
    fun decryptFram(id: ByteArray, info: ByteArray, data: ByteArray): ByteArray {
        require(data.size >= 344) { "FRAM must be 344 bytes" }
        val y = w(info, 4) xor 0x44
        val out = ByteArray(344)
        for (block in 0 until 43) {
            val k = words(processCrypto(prepareVariables(id, block, y)))
            for (j in 0 until 8) out[block * 8 + j] = (data[block * 8 + j].toInt() xor k[j].toInt()).toByte()
        }
        return out
    }

    /** `02 A1 07 1B` + usefulFunction(uid, 0x1B, 0x1B6A): starts a new sensor (its warm-up begins). */
    fun activateCommand(uid: ByteArray): ByteArray =
        byteArrayOf(0x02, 0xA1.toByte(), 0x07, SUB_ACTIVATE.toByte()) + usefulFunction(uid, SUB_ACTIVATE, SECRET)

    /**
     * `02 A1 07 1E` + code (LE32) + usefulFunction(uid, 0x1E, info[4..5] ^ code[0..1]): turns BLE streaming on,
     * bound to [code]; the answer is the sensor's BLE address. The same [code] (and patch info) build the unlock
     * payload on every Bluetooth connection ([streamingUnlockPayload]).
     */
    fun enableStreamingCommand(uid: ByteArray, info: ByteArray, code: Long): ByteArray {
        val c = ByteArray(4) { (code shr (8 * it)).toByte() }
        val y = w(info, 4) xor w(c, 0)
        return byteArrayOf(0x02, 0xA1.toByte(), 0x07, SUB_ENABLE_STREAMING.toByte()) + c +
            usefulFunction(uid, SUB_ENABLE_STREAMING, y)
    }

    /** The 12 bytes written to the login characteristic (F001) on each connection; [unlockCount] goes up by one each time. */
    fun streamingUnlockPayload(id: ByteArray, info: ByteArray, enableTime: Long, unlockCount: Int): ByteArray {
        val time = (enableTime + unlockCount) and 0xFFFFFFFFL
        val b = ByteArray(4) { (time shr (8 * it)).toByte() }
        val ad = usefulFunction(id, SUB_ACTIVATE, SECRET)
        val ed = usefulFunction(id, SUB_ENABLE_STREAMING, (enableTime.toInt() and 0xFFFF) xor w(info, 4))
        val t2 = processCrypto(prepareVariables2(id,
            w(ed, 0) xor w(b, 2), w(ad, 0), w(ed, 2) xor w(b, 0), w(ad, 2)))
        val t31 = crc16(byteArrayOf(0xC1.toByte(), 0xC4.toByte(), 0xC3.toByte(), 0xC0.toByte(), 0xD4.toByte(),
            0xE1.toByte(), 0xE7.toByte(), 0xBA.toByte(), t2[0].toByte(), (t2[0] shr 8).toByte()))
        val t32 = crc16(byteArrayOf(t2[1].toByte(), (t2[1] shr 8).toByte(), t2[2].toByte(), (t2[2] shr 8).toByte(),
            t2[3].toByte(), (t2[3] shr 8).toByte()))
        val t33 = crc16(byteArrayOf(ad[0], ad[1], ad[2], ad[3], ed[0], ed[1]))
        val t34 = crc16(byteArrayOf(ed[2], ed[3], b[0], b[1], b[2], b[3]))
        return b + words(processCrypto(prepareVariables2(id, t31, t32, t33, t34)))
    }

    /** Decrypts one 46-byte BLE packet (20 + 18 + 8 notifications) into 44 bytes; null if its CRC doesn't match. */
    fun decryptBle(id: ByteArray, data: ByteArray): ByteArray? {
        if (data.size != 46) return null
        val d = usefulFunction(id, SUB_ACTIVATE, SECRET)
        val x = (w(d, 0) xor w(d, 2)) or 0x63
        val y = w(data, 0) xor 0x63
        val key = ByteArray(64)
        var k = processCrypto(prepareVariables(id, x, y))
        for (round in 0 until 8) {
            words(k).copyInto(key, round * 8)
            k = processCrypto(k)
        }
        val out = ByteArray(44) { (data[it + 2].toInt() xor key[it].toInt()).toByte() }
        return if (crc16(out, 0, 42) == w(out, 42)) out else null
    }

    /** Libre CRC-16: poly 0x1021, init 0xFFFF, input reflected, output not (= [Libre3Nfc.crc16]). */
    fun crc16(b: ByteArray, from: Int = 0, to: Int = b.size): Int {
        var crc = 0xFFFF
        for (i in from until to) {
            crc = crc xor (b[i].toInt() and 0xFF)
            repeat(8) { crc = if (crc and 1 != 0) (crc ushr 1) xor 0x8408 else crc ushr 1 }
        }
        return Integer.reverse(crc) ushr 16
    }

    /** [bitCount] bits starting at bit [bitOffset] of byte [byteOffset], least significant first. */
    fun readBits(b: ByteArray, byteOffset: Int, bitOffset: Int, bitCount: Int): Int {
        var res = 0
        for (i in 0 until bitCount) {
            val t = byteOffset * 8 + bitOffset + i
            if ((b[t / 8].toInt() shr (t % 8)) and 1 == 1) res = res or (1 shl i)
        }
        return res
    }

    /** The printed serial ("3MH001DG75W"): the family digit + ten 5-bit characters of the UID. */
    fun serial(uid: ByteArray, family: Int): String {
        val table = "0123456789ACDEFGHJKLMNPQRTUVWXYZ"
        val b = IntArray(6) { uid[5 - it].toInt() and 0xFF }
        val five = intArrayOf(
            b[0] shr 3, (b[0] shl 2) + (b[1] shr 6), b[1] shr 1, (b[1] shl 4) + (b[2] shr 4),
            (b[2] shl 1) + (b[3] shr 7), b[3] shr 2, (b[3] shl 3) + (b[4] shr 5), b[4], b[5] shr 3, b[5] shl 2)
        return family.toString() + five.joinToString("") { table[it and 0x1F].toString() }
    }
}
