// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.direct

import android.nfc.Tag
import android.nfc.TagLostException
import android.nfc.tech.NfcV
import android.util.Log

/**
 * The NFC half of starting or taking over a FreeStyle Libre 2 / 2 Plus (European, security generation 1). One tap:
 *  1. patch info (`02 A1 07`, Juggluco AlgNfcV.java:236-243) → type, generation;
 *  2. the 43 FRAM blocks (`02 23 <block> <n-1>`, 3 at a time, AlgNfcV.java:295-320), decrypted ([Libre2Crypto]) and
 *     checked by CRC → state, age, wear length, factory calibration;
 *  3. only for a new sensor, and only when [startSerial] says the user asked for it: activate (`A1 1B`);
 *  4. enable BLE streaming (`A1 1E`) with a fresh unlock code → the BLE address.
 * Nothing is sent to the sensor before steps 1–2 have succeeded, so a sensor is never started without a usable
 * calibration. Blocking: call off the main thread (the reader-mode callback already is).
 */
object Libre2Nfc {
    private const val TAG = "Sukrara.nfc2"
    /** Juggluco's base for times (2010-01-01): the unlock code is "seconds since base" at enable time, like Juggluco's. */
    private const val BASE_EPOCH = 1_262_304_000L

    sealed interface Result {
        data class Paired(val pairing: Libre2Pairing, val activated: Boolean) : Result
        /** A never-started sensor: ask the user, then call [scan] again with its serial as `startSerial`. */
        data class NeedsStart(val serial: String, val name: String) : Result
        /** Started, but streaming couldn't be enabled in the same tap: a second tap finishes it. */
        data class StartedOnly(val serial: String) : Result
        data class Ended(val serial: String) : Result
        data class Unsupported(val why: String) : Result
        data class Failed(val why: String) : Result
    }

    fun scan(tag: Tag, startSerial: String?): Result {
        val nfc = NfcV.get(tag) ?: return Result.Failed("not an NfcV tag")
        return try {
            nfc.connect()
            run(nfc, tag.id, startSerial)
        } catch (e: TagLostException) {
            Result.Failed("lost the sensor — hold the phone still until it vibrates")
        } catch (e: Exception) {
            Log.w(TAG, "scan failed", e)
            Result.Failed("${e.javaClass.simpleName}: ${e.message}")
        } finally {
            runCatching { nfc.close() }
        }
    }

    private fun run(nfc: NfcV, uid: ByteArray, startSerial: String?): Result {
        val r = command(nfc, byteArrayOf(0x02, 0xA1.toByte(), 0x07)) ?: return Result.Failed("no answer from the sensor")
        if (r.size < 7) return Result.Unsupported("not a Libre 2 (patch info ${r.size - 1} bytes)")
        val p = Libre2Sensor.PatchInfo(r.copyOfRange(1, 7))
        val serial = Libre2Crypto.serial(uid, p.family)
        Log.i(TAG, "patch info $p: $serial family=${p.family} gen=${p.securityGeneration} plus=${p.isPlus}")
        if (!p.supported) return Result.Unsupported(
            if (p.securityGeneration == 2) "${p.name} from the US / Canada (Gen2): only Abbott's library can read it"
            else "not a Libre 2 (patch info $p)")

        val framRaw = readFram(nfc) ?: return Result.Failed("couldn't read the sensor's memory")
        val fram = Libre2Sensor.Fram(Libre2Crypto.decryptFram(uid, p.raw, framRaw))
        val cal = fram.calibration
        Log.i(TAG, "FRAM: header=${fram.headerOk} body=${fram.bodyOk} footer=${fram.footerOk} state=${fram.state} " +
            "age=${fram.age}min maxLife=${fram.maxLife}min cal=$cal")
        if (!fram.headerOk || !fram.footerOk) return Result.Failed("the sensor's memory didn't decrypt (CRC)")
        if (!cal.usable) return Result.Failed("the sensor's calibration is unusable ($cal)")

        val now = System.currentTimeMillis() / 1000
        var activated = false
        val start: Long
        when (fram.state) {
            Libre2Sensor.STATE_NOT_ACTIVATED -> {
                if (startSerial != serial) return Result.NeedsStart(serial, p.name)
                val a = nfc.transceive(Libre2Crypto.activateCommand(uid))
                Log.i(TAG, "activate: ${a.size} bytes, flags ${"%02X".format(a.getOrElse(0) { 0 })}")
                if (a.isEmpty() || (a[0].toInt() and 1) != 0)
                    return Result.Failed("the sensor refused the start command (error ${a.getOrNull(1)?.let { "%02X".format(it) }})")
                activated = true
                start = now
            }
            Libre2Sensor.STATE_WARMING_UP, Libre2Sensor.STATE_ACTIVE -> start = now - 60L * fram.age
            Libre2Sensor.STATE_EXPIRED, Libre2Sensor.STATE_SHUTDOWN, Libre2Sensor.STATE_FAILURE -> return Result.Ended(serial)
            else -> return Result.Failed("unknown sensor state ${fram.state}")
        }
        // FRAM's own wear length when it's plausible (7–20 days), else the product's.
        val wear = fram.maxLife.takeIf { it in 10_080..28_800 } ?: p.defaultWearMin

        val code = (now - BASE_EPOCH) and 0xFFFFFFFFL
        val s = command(nfc, Libre2Crypto.enableStreamingCommand(uid, p.raw, code))
        if (s == null || s.size < 7) {
            Log.w(TAG, "enable streaming failed (${s?.size} bytes)")
            return if (activated) Result.StartedOnly(serial) else Result.Failed("the sensor didn't turn Bluetooth on")
        }
        val address = (6 downTo 1).joinToString(":") { "%02X".format(s[it]) }
        Log.i(TAG, "streaming enabled: $address, start=$start wear=${wear}min activated=$activated")
        return Result.Paired(Libre2Pairing(serial = serial, name = p.name, uid = uid, patchInfo = p.raw,
            address = address, unlockCode = code, unlockCount = 0, startEpoch = start, wearMin = wear,
            warmupMin = Libre2Sensor.WARMUP_MIN, calibration = cal), activated)
    }

    /** 344 bytes = 43 blocks, three per read-multiple-blocks command. */
    private fun readFram(nfc: NfcV): ByteArray? {
        val out = ByteArray(344)
        var block = 0
        while (block < 43) {
            val n = minOf(3, 43 - block)
            val r = command(nfc, byteArrayOf(0x02, 0x23, block.toByte(), (n - 1).toByte())) ?: return null
            if (r.size < 1 + n * 8) return null
            r.copyInto(out, block * 8, 1, 1 + n * 8)
            block += n
        }
        return out
    }

    /** Juggluco's issuenfc / wholenfccmd: retry transient errors and answers with the error flag (bit 0). */
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
}
