// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.direct

import app.mauri.sukrara.core.SensorLife

import android.nfc.Tag
import android.nfc.TagLostException
import android.nfc.tech.NfcV
import android.util.Log

/**
 * The NFC half of starting or taking over a FreeStyle Libre 3 / 3 Plus (after Juggluco's Libre3.java,
 * libre3/NFC.java and nfc.cpp, GPLv3). Two ISO 15693 custom commands in one tap:
 *  1. patch info → serial, state, product type;
 *  2. activate (a new sensor) or switch receiver (a running one), carrying the receiver ID → BLE address, BLE PIN
 *     and activation time.
 * The Bluetooth pairing that follows needs only that answer ([SensorPairing] without an authorization yet).
 * Blocking: call off the main thread (the reader-mode callback already is).
 */
object Libre3Nfc {
    private const val TAG = "Sukrara.nfc"

    sealed interface Result {
        /** [activated]: a new sensor was started now (it warms up for an hour). */
        data class Paired(val pairing: SensorPairing, val activated: Boolean, val receiverId: Long) : Result
        /** The sensor is bound to a receiver ID we don't know (started by another app with another account). */
        data class WrongAccount(val serial: String) : Result
        data class Ended(val serial: String) : Result
        data class Unsupported(val why: String) : Result
        data class Failed(val why: String) : Result
    }

    /** Patch info: fixed offsets inside the 26-byte block after the 0xA5 marker(s). */
    internal class PatchInfo(raw: ByteArray, at: Int) {
        val productType = raw[at + 12].toInt() and 0xFF
        /** Wear length in minutes (LE16 at +6, Juggluco's `wearduration`; Libre 3 Plus 21600); 0 = unknown. */
        val wearMin = (raw[at + 6].toInt() and 0xFF) or ((raw[at + 7].toInt() and 0xFF) shl 8)
        val warmupMin = (raw[at + 13].toInt() and 0xFF) * 5
        val state = raw[at + 14].toInt() and 0xFF
        val serial = String(raw, at + 15, 9, Charsets.US_ASCII).trimEnd('\u0000')
        /** 1 = never started: activate; anything else: take over. */
        val activate get() = state == 1
    }

    /** First answer: 00, one or more A5, one more byte, then the 26-byte block. Null if it isn't a Libre 3. */
    internal fun parsePatchInfo(info: ByteArray): PatchInfo? {
        var i = 1
        while (i < info.size && info[i] == 0xA5.toByte()) i++
        val at = i + 1
        return if (info.size < at + 26) null else PatchInfo(info, at)
    }

    /** Second answer: [Paired] data, or the sensor's error code (0xB1 = wrong receiver ID), or null if unreadable. */
    internal sealed interface Second {
        data class Ok(val pairing: SensorPairing) : Second
        data class Refused(val error: Int) : Second
    }

    internal fun parseSecond(r: ByteArray, p: PatchInfo, now: Long): Second? = when {
        r.size == 19 -> Second.Ok(parsePaired(r, p, now))
        r.size == 4 && r[1] == 0xA5.toByte() && r[2] == 0x01.toByte() -> Second.Refused(r[3].toInt() and 0xFF)
        else -> null
    }

    fun scan(tag: Tag, candidates: List<Long>, forActivation: Long): Result {
        val nfc = NfcV.get(tag) ?: return Result.Failed("not a Libre 3 (no NfcV)")
        return try {
            nfc.connect()
            run(nfc, candidates, forActivation)
        } catch (e: TagLostException) {
            Result.Failed("lost the sensor — hold the phone still until it vibrates")
        } catch (e: Exception) {
            Log.w(TAG, "scan failed", e)
            Result.Failed("${e.javaClass.simpleName}: ${e.message}")
        } finally {
            runCatching { nfc.close() }
        }
    }

    private fun run(nfc: NfcV, candidates: List<Long>, forActivation: Long): Result {
        val info = command(nfc, byteArrayOf(0x02, 0xA1.toByte(), 0x7A)) ?: return Result.Failed("no answer from the sensor")
        val p = parsePatchInfo(info) ?: return Result.Unsupported("not a Libre 3 (patch info ${info.size} bytes)")
        Log.i(TAG, "patch info: serial=${p.serial} state=${p.state} product=${p.productType} warmup=${p.warmupMin}min wear=${p.wearMin}min")
        if (p.productType == 9) return Result.Unsupported("Lingo sensors aren't supported")

        val ids = if (p.activate) listOf(forActivation) else candidates
        for (id in ids) {
            val now = System.currentTimeMillis() / 1000
            val r = command(nfc, secondCommand(p.activate, id, now))
                ?: return Result.Failed("the sensor didn't answer the ${if (p.activate) "start" else "takeover"} command")
            when (val a = parseSecond(r, p, now)) {
                is Second.Ok -> return Result.Paired(a.pairing, p.activate, id)
                is Second.Refused -> {
                    Log.i(TAG, "sensor refused: error ${"%02X".format(a.error)} state ${p.state}")
                    if (a.error != 0xB1) return when (p.state) {
                        6, 8 -> Result.Ended(p.serial)
                        else -> Result.Failed("the sensor refused (error ${"%02X".format(a.error)}, state ${p.state})")
                    }
                    // 0xB1: wrong receiver ID — try the next candidate in the same tap
                }
                null -> return Result.Failed("unexpected answer (${r.size} bytes)")
            }
        }
        return Result.WrongAccount(p.serial)
    }

    /** Activate (0xA0) or switch receiver (0xA8): now−1 (LE32), receiver ID (LE32), CRC-16 (LE). */
    internal fun secondCommand(activate: Boolean, receiverId: Long, now: Long): ByteArray {
        val d = ByteArray(10)
        le32(d, 0, now - 1)
        le32(d, 4, receiverId)
        val crc = crc16(d, 8)
        d[8] = (crc and 0xFF).toByte(); d[9] = (crc shr 8).toByte()
        return byteArrayOf(0x02, if (activate) 0xA0.toByte() else 0xA8.toByte(), 0x7A) + d
    }

    /** 00 A5 00 | BD address (6, reversed) | PIN (4) | activation time (LE32) | CRC (2). */
    private fun parsePaired(r: ByteArray, p: PatchInfo, now: Long): SensorPairing {
        val address = (8 downTo 3).joinToString(":") { "%02X".format(r[it]) }
        val pin = r.copyOfRange(9, 13)
        var start = (r[13].toLong() and 0xFF) or ((r[14].toLong() and 0xFF) shl 8) or
            ((r[15].toLong() and 0xFF) shl 16) or ((r[16].toLong() and 0xFF) shl 24)
        if (start <= 0) start = now
        return SensorPairing(serial = p.serial, address = address, startEpoch = start, pin = pin, kAuth = null,
            securityVersion = 0, wearMin = p.wearMin.takeIf { it > 0 } ?: SensorLife.DEFAULT_WEAR_MIN,
            warmupMin = p.warmupMin.takeIf { it > 0 } ?: SensorLife.DEFAULT_WARMUP_MIN)
    }

    /** Juggluco's wholenfccmd: retry transient errors; an answer with the error flag (bit 0) is retried too. */
    private fun command(nfc: NfcV, cmd: ByteArray): ByteArray? {
        val deadline = System.currentTimeMillis() + 5_000
        repeat(10) {
            try {
                val r = nfc.transceive(cmd)
                if (r != null && r.isNotEmpty() && (r[0].toInt() and 1) == 0) return r
            } catch (e: TagLostException) {
                throw e
            } catch (e: Exception) {
                if (System.currentTimeMillis() > deadline) return null
            }
        }
        return null
    }

    private fun le32(b: ByteArray, o: Int, v: Long) {
        for (k in 0 until 4) b[o + k] = ((v shr (8 * k)) and 0xFF).toByte()
    }

    /** poly 0x1021, init 0xFFFF, input bytes reflected, output not (Juggluco's dp_activation.hpp). */
    internal fun crc16(b: ByteArray, n: Int): Int {
        var crc = 0xFFFF
        for (k in 0 until n) {
            crc = crc xor (Integer.reverse(b[k].toInt() and 0xFF) ushr 24 shl 8)
            repeat(8) { crc = if (crc and 0x8000 != 0) ((crc shl 1) xor 0x1021) and 0xFFFF else (crc shl 1) and 0xFFFF }
        }
        return crc
    }
}
