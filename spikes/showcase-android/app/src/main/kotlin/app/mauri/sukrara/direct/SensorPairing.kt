// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.direct

import android.content.Context
import app.mauri.sukrara.core.SensorLife
import app.mauri.sukrara.core.SensorProfile
import app.mauri.sukrara.core.SensorProfiles
import java.io.File
import org.json.JSONObject

/**
 * The saved sensor (`sensor.json`), one generic record keyed by [profileId]: what the direct source needs to
 * (re)connect to it. For a Libre 3 it is the NFC tap's address, PIN and start time plus [kAuth].
 *
 * Written by the app itself: the NFC scan ([Libre3Nfc]) gives the address, PIN and start time; the first Bluetooth
 * connection after it runs a fresh pairing and adds [kAuth]. (The older spikes imported this file from Juggluco.)
 * It is a secret: never log [pin] or [kAuth], never copy it off the device.
 *
 * [profileId] names the sensor family ([SensorProfiles]) and so the driver ([Drivers]); a file written before the
 * field exists is the Libre 3's. The other families ([forBrand], investigation 23 steps 2–5) keep [serial] = what the
 * user typed (G6 transmitter id, Aidex X serial; empty for the Dexcom G7 observer and the SmartGuide), [address]
 * empty until a scan found the sensor, [startEpoch] 0 until the sensor said, and no [pin] / [kAuth].
 */
data class SensorPairing(
    val serial: String,
    val address: String,        // sensor BLE MAC, e.g. "AA:BB:CC:DD:EE:FF"
    val startEpoch: Long,       // sensor activation time; reading time = start + 60*(lifeCount+1)
    val pin: ByteArray,         // 4 bytes
    val kAuth: ByteArray?,      // 149 bytes, "L3S9…"; null until the first pairing after a scan completes
    val securityVersion: Int,   // 0 = Libre 3 / 3 Plus; non-zero = Lingo (unsupported here)
    val wearMin: Int = SensorLife.DEFAULT_WEAR_MIN,   // wear length from the patch info (Libre 3 Plus: 21600 = 15 d)
    val warmupMin: Int = SensorLife.DEFAULT_WARMUP_MIN, // warm-up from the patch info (60)
    val profileId: String = SensorProfiles.LIBRE_3.id,
) {
    /** When the sensor stops (unix s): start + wear length. */
    val endEpoch get() = startEpoch + 60L * wearMin
    /** What [SensorLife] knows from this pairing. */
    val info get() = SensorLife.fromStart(serial, startEpoch, wearMin, warmupMin)

    companion object {
        const val FILE = "sensor.json"

        fun load(context: Context): SensorPairing? {
            val f = File(context.filesDir, FILE)
            if (!f.isFile) return null
            val o = JSONObject(f.readText())
            val pin = o.getString("pinHex").hex()
            val kAuth = o.optString("kAuthHex", "").takeIf { it.isNotEmpty() }?.hex()
            val libre3 = o.optString("profile", SensorProfiles.LIBRE_3.id) == SensorProfiles.LIBRE_3.id
            require(!libre3 || pin.size == 4) { "pin must be 4 bytes" }
            require(!libre3 || kAuth == null || kAuth.size == 149) { "kAuth must be 149 bytes" }
            return SensorPairing(
                serial = o.getString("serial"),
                address = o.getString("address").uppercase(),
                startEpoch = o.getLong("startEpoch"),
                pin = pin,
                kAuth = kAuth,
                securityVersion = o.optInt("securityVersion", 0),
                wearMin = o.optInt("wearMin", 0).takeIf { it > 0 } ?: SensorLife.DEFAULT_WEAR_MIN,
                warmupMin = o.optInt("warmupMin", 0).takeIf { it > 0 } ?: SensorLife.DEFAULT_WARMUP_MIN,
                profileId = o.optString("profile", SensorProfiles.LIBRE_3.id),
            )
        }

        /** A new sensor of a family that isn't paired by NFC: the brand step's typed [id] (or none) and its profile. */
        fun forBrand(profile: SensorProfile, id: String) = SensorPairing(
            serial = id, address = "", startEpoch = 0, pin = ByteArray(0), kAuth = null, securityVersion = 0,
            wearMin = profile.wearMin, warmupMin = profile.warmupMin, profileId = profile.id,
        )

        fun forget(context: Context) {
            File(context.filesDir, FILE).delete()
            SensorLife.update(context, null)
        }

        private fun String.hex() = ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
    }

    /** Atomically replaces the file (private app storage, 0600 by default). */
    fun save(context: Context) {
        val o = JSONObject()
            .put("serial", serial).put("address", address).put("startEpoch", startEpoch)
            .put("securityVersion", securityVersion).put("wearMin", wearMin).put("warmupMin", warmupMin).put("pinHex", pin.hex())
            .put("profile", profileId)
        kAuth?.let { o.put("kAuthHex", it.hex()) }
        val tmp = File(context.filesDir, "$FILE.tmp")
        tmp.writeText(o.toString())
        tmp.renameTo(File(context.filesDir, FILE))
    }

    /** Safe for logs. */
    override fun toString() = "SensorPairing($profileId serial=$serial, address=$address, start=$startEpoch, paired=${kAuth != null})"
}
