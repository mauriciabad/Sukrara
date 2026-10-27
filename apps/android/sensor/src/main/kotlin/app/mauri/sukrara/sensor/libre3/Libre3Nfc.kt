// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.sensor.libre3

import android.nfc.Tag
import android.nfc.TagLostException
import android.nfc.tech.NfcV
import app.mauri.sukrara.sensor.SensorLife
import app.mauri.sukrara.sensor.pairing.Pairing
import app.mauri.sukrara.sensor.pairing.ReceiverIds

/**
 * The NFC half of starting or taking over a FreeStyle Libre 3 / 3 Plus (after Juggluco's Libre3.java, libre3/NFC.java
 * and nfc.cpp, GPLv3; ported from the PoC unchanged). Two ISO 15693 custom commands in one tap:
 *  1. patch info → serial, state, product type;
 *  2. activate (a new sensor, 0xA0) or switch receiver (a running one, 0xA8), carrying the receiver ID → BLE address,
 *     BLE PIN and activation time.
 * The Bluetooth pairing that follows needs only that answer (a [Pairing] without an authorization yet).
 * Blocking: call off the main thread (the reader-mode callback already is).
 */
internal object Libre3Nfc {
    sealed interface Result {
        /** [activated]: a new sensor was started now (it warms up for an hour). */
        data class Paired(val pairing: Pairing, val activated: Boolean, val receiverId: Long) : Result

        /** Bound to a receiver ID we don't have (started by another app or account). Nothing changed on the sensor. */
        data class OwnedByAnotherReceiver(val serial: String) : Result

        /** No LibreView receiver ID is known: nothing was sent (see [ReceiverIds]). */
        data class NeedsAccount(val serial: String, val neverStarted: Boolean) : Result

        data class Ended(val serial: String) : Result

        /** A Libre 1 / 2 / 2 Plus (TI chip): not supported, nothing was sent to it. */
        data object Libre2 : Result

        data class Unsupported(val why: String) : Result

        data class Failed(val why: String) : Result
    }

    /** Patch info: fixed offsets inside the 26-byte block after the 0xA5 marker(s). */
    class PatchInfo(raw: ByteArray, at: Int) {
        val productType = raw[at + 12].toInt() and 0xFF

        /** Wear length in minutes (LE16 at +6, Juggluco's `wearduration`; Libre 3 Plus 21600); 0 = unknown. */
        val wearMin = (raw[at + 6].toInt() and 0xFF) or ((raw[at + 7].toInt() and 0xFF) shl 8)
        val warmupMin = (raw[at + 13].toInt() and 0xFF) * 5
        val state = raw[at + 14].toInt() and 0xFF
        val serial = String(raw, at + 15, 9, Charsets.US_ASCII).trimEnd('\u0000')

        /** Never started: activate; anything else: take over. */
        val activate get() = state == PatchState.NEVER_STARTED
    }

    /** First answer: 00, one or more A5, one more byte, then the 26-byte block. Null if it isn't a Libre 3. */
    fun parsePatchInfo(info: ByteArray): PatchInfo? {
        var i = 1
        while (i < info.size && info[i] == 0xA5.toByte()) i++
        val at = i + 1
        return if (info.size < at + 26) null else PatchInfo(info, at)
    }

    /** Second answer: pairing data, or the sensor's error code (0xB1 = wrong receiver ID), or null if unreadable. */
    sealed interface Second {
        data class Ok(val pairing: Pairing) : Second

        data class Refused(val error: Int) : Second
    }

    fun parseSecond(r: ByteArray, p: PatchInfo, now: Long): Second? = when {
        r.size == 19 -> Second.Ok(parsePaired(r, p, now))
        r.size == 4 && r[1] == 0xA5.toByte() && r[2] == 0x01.toByte() -> Second.Refused(r[3].toInt() and 0xFF)
        else -> null
    }

    /** The tag's chip, from the UID's IC manufacturer byte (`Tag.getId()` is LSB first: `[7]` = 0xE0, `[6]` = maker). */
    enum class Chip { LIBRE3, LIBRE2, OTHER }

    /** 0x7A (Abbott) = Libre 3 / 3 Plus, the only chip our commands are for; 0x07 (TI) = Libre 1 / 2 / 2 Plus. */
    fun chip(uid: ByteArray?): Chip = when {
        uid == null || uid.size != 8 || uid[7] != 0xE0.toByte() -> Chip.OTHER
        uid[6] == 0x7A.toByte() -> Chip.LIBRE3
        uid[6] == 0x07.toByte() -> Chip.LIBRE2
        else -> Chip.OTHER
    }

    /** One tap: [account] = the LibreView receiver ID (null = not signed in), [own] = V1's own. */
    fun scan(tag: Tag, account: Long?, own: Long, log: (String) -> Unit): Result {
        when (chip(tag.id)) {
            Chip.LIBRE3 -> Unit
            Chip.LIBRE2 -> return Result.Libre2
            Chip.OTHER -> return Result.Unsupported("not a FreeStyle Libre 3")
        }
        val nfc = NfcV.get(tag) ?: return Result.Failed("not a Libre 3 (no NfcV)")
        return try {
            nfc.connect()
            run({ cmd -> command(nfc, cmd) }, account, own, System::currentTimeMillis, log)
        } catch (_: TagLostException) {
            Result.Failed("lost the sensor")
        } catch (e: Exception) {
            Result.Failed("${e.javaClass.simpleName}: ${e.message}")
        } finally {
            runCatching { nfc.close() }
        }
    }

    /** The tap's logic over a [transceive] function (null = no answer), so it is tested without a tag. */
    fun run(transceive: (ByteArray) -> ByteArray?, account: Long?, own: Long, clockMs: () -> Long, log: (String) -> Unit): Result {
        val info = transceive(byteArrayOf(0x02, 0xA1.toByte(), 0x7A)) ?: return Result.Failed("no answer from the sensor")
        val p = parsePatchInfo(info) ?: return Result.Unsupported("not a Libre 3 (patch info ${info.size} bytes)")
        log("patch info: state=${p.state} product=${p.productType} warmup=${p.warmupMin}min wear=${p.wearMin}min")
        if (p.productType == 9) return Result.Unsupported("Lingo sensors aren't supported")

        val ids = (if (p.activate) ReceiverIds.forStart(account) else ReceiverIds.forTakeover(account, own))
            ?: return Result.NeedsAccount(p.serial, neverStarted = p.activate)
        for (id in ids) {
            val now = clockMs() / 1000
            val r = transceive(secondCommand(p.activate, id, now))
                ?: return Result.Failed("the sensor didn't answer the ${if (p.activate) "start" else "takeover"} command")
            when (val a = parseSecond(r, p, now)) {
                is Second.Ok -> return Result.Paired(a.pairing, p.activate, id)
                is Second.Refused -> {
                    log("sensor refused: error ${"%02X".format(a.error)} state ${p.state}")
                    if (a.error != 0xB1) {
                        return when (p.state) {
                            6, 8 -> Result.Ended(p.serial)
                            else -> Result.Failed("the sensor refused (error ${"%02X".format(a.error)}, state ${p.state})")
                        }
                    }
                    // 0xB1: wrong receiver ID — try the next candidate in the same tap
                }
                null -> return Result.Failed("unexpected answer (${r.size} bytes)")
            }
        }
        return Result.OwnedByAnotherReceiver(p.serial)
    }

    /** Activate (0xA0) or switch receiver (0xA8): now−1 (LE32), receiver ID (LE32), CRC-16 (LE). */
    fun secondCommand(activate: Boolean, receiverId: Long, now: Long): ByteArray {
        val d = ByteArray(10)
        le32(d, 0, now - 1)
        le32(d, 4, receiverId)
        val crc = crc16(d, 8)
        d[8] = (crc and 0xFF).toByte()
        d[9] = (crc shr 8).toByte()
        return byteArrayOf(0x02, if (activate) 0xA0.toByte() else 0xA8.toByte(), 0x7A) + d
    }

    /** 00 A5 00 | BD address (6, reversed) | PIN (4) | activation time (LE32) | CRC (2). A new pairing: no kAuth yet. */
    private fun parsePaired(r: ByteArray, p: PatchInfo, now: Long): Pairing {
        val address = (8 downTo 3).joinToString(":") { "%02X".format(r[it]) }
        val pin = r.copyOfRange(9, 13)
        var start = (r[13].toLong() and 0xFF) or ((r[14].toLong() and 0xFF) shl 8) or
            ((r[15].toLong() and 0xFF) shl 16) or ((r[16].toLong() and 0xFF) shl 24)
        if (start <= 0) start = now
        return Pairing(
            serial = p.serial, address = address, startEpoch = start, securityVersion = 0,
            wearMin = p.wearMin.takeIf { it > 0 } ?: SensorLife.DEFAULT_WEAR_MIN,
            warmupMin = p.warmupMin.takeIf { it > 0 } ?: SensorLife.DEFAULT_WARMUP_MIN,
            pin = pin, kAuth = null,
        )
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
            } catch (_: Exception) {
                if (System.currentTimeMillis() > deadline) return null
            }
        }
        return null
    }

    private fun le32(b: ByteArray, o: Int, v: Long) {
        for (k in 0 until 4) b[o + k] = ((v shr (8 * k)) and 0xFF).toByte()
    }

    /** poly 0x1021, init 0xFFFF, input bytes reflected, output not (Juggluco's dp_activation.hpp). */
    fun crc16(b: ByteArray, n: Int): Int {
        var crc = 0xFFFF
        for (k in 0 until n) {
            crc = crc xor (Integer.reverse(b[k].toInt() and 0xFF) ushr 24 shl 8)
            repeat(8) { crc = if (crc and 0x8000 != 0) ((crc shl 1) xor 0x1021) and 0xFFFF else (crc shl 1) and 0xFFFF }
        }
        return crc
    }
}
