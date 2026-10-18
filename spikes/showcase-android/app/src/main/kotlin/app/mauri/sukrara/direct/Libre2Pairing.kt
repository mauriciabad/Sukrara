// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.direct

import android.content.Context
import app.mauri.sukrara.core.SensorLife
import java.io.File
import org.json.JSONObject

/**
 * What Sukrara needs to stream from a Libre 2 / 2 Plus, written by the NFC scan ([Libre2Nfc]): the UID and the patch
 * info at the time streaming was enabled, the unlock code we chose then, the connection counter, the BLE address and
 * the factory calibration from the FRAM. Exactly one of this and [SensorPairing] (Libre 3) exists: saving one deletes
 * the other ([save]). A secret like the Libre 3 pairing: never copy it off the device.
 */
data class Libre2Pairing(
    val serial: String,
    val name: String,              // "Libre 2 Plus" / "Libre 2"
    val uid: ByteArray,            // 8 bytes, Tag.getId() order
    val patchInfo: ByteArray,      // 6 bytes, as sent with the streaming-enable command
    val address: String,
    val unlockCode: Long,          // 32-bit, our choice at enable time (seconds since 2010, like Juggluco)
    val unlockCount: Int,          // goes up by one before each Bluetooth login
    val startEpoch: Long,
    val wearMin: Int,
    val warmupMin: Int,
    val calibration: Libre2Sensor.Calibration,
) {
    val endEpoch get() = startEpoch + 60L * wearMin
    val info get() = SensorLife.fromStart(serial, startEpoch, wearMin, warmupMin)

    /** Atomically replaces the file and removes a Libre 3 pairing. */
    fun save(context: Context) {
        val c = calibration
        val o = JSONObject().put("serial", serial).put("name", name).put("uidHex", uid.hex())
            .put("patchInfoHex", patchInfo.hex()).put("address", address).put("unlockCode", unlockCode)
            .put("unlockCount", unlockCount).put("startEpoch", startEpoch).put("wearMin", wearMin)
            .put("warmupMin", warmupMin)
            .put("cal", JSONObject().put("i1", c.i1).put("i2", c.i2).put("i3", c.i3).put("i4", c.i4).put("i5", c.i5).put("i6", c.i6))
        val tmp = File(context.filesDir, "$FILE.tmp")
        tmp.writeText(o.toString())
        tmp.renameTo(File(context.filesDir, FILE))
        File(context.filesDir, SensorPairing.FILE).delete()
    }

    override fun toString() = "Libre2Pairing(serial=$serial, $name, address=$address, start=$startEpoch, wear=$wearMin)"

    companion object {
        const val FILE = "sensor-libre2.json"

        fun load(context: Context): Libre2Pairing? {
            val f = File(context.filesDir, FILE)
            if (!f.isFile) return null
            val o = JSONObject(f.readText())
            val c = o.getJSONObject("cal")
            return Libre2Pairing(
                serial = o.getString("serial"), name = o.optString("name", "Libre 2"),
                uid = o.getString("uidHex").unhex(), patchInfo = o.getString("patchInfoHex").unhex(),
                address = o.getString("address").uppercase(), unlockCode = o.getLong("unlockCode"),
                unlockCount = o.getInt("unlockCount"), startEpoch = o.getLong("startEpoch"),
                wearMin = o.getInt("wearMin"), warmupMin = o.optInt("warmupMin", Libre2Sensor.WARMUP_MIN),
                calibration = Libre2Sensor.Calibration(c.getInt("i1"), c.getInt("i2"), c.getInt("i3"), c.getInt("i4"),
                    c.getInt("i5"), c.getInt("i6")),
            )
        }

        /** Removes the file only (the caller decides about SensorLife). */
        fun delete(context: Context) {
            File(context.filesDir, FILE).delete()
        }

        private fun String.unhex() = ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
    }
}
