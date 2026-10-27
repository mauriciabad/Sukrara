// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.spike.glucobridge.direct

import android.content.Context
import java.io.File
import org.json.JSONObject

/**
 * Per-sensor pairing state needed to (re)connect to a Libre 3 without NFC.
 *
 * In this spike the file is imported from an app that already activated the sensor
 * (see tools/import-pairing.sh). The real app will produce it itself during NFC activation.
 * It is a secret: never log [pin] or [kAuth], never copy it off the device.
 */
data class SensorPairing(
    val serial: String,
    val address: String,        // sensor BLE MAC, e.g. "AA:BB:CC:DD:EE:FF"
    val startEpoch: Long,       // sensor activation time; reading time = start + 60*(lifeCount+1)
    val pin: ByteArray,         // 4 bytes
    val kAuth: ByteArray,       // 149 bytes, "L3S9…"
    val securityVersion: Int,   // 0 = Libre 3 / 3 Plus; non-zero = Lingo (unsupported here)
) {
    companion object {
        const val FILE = "sensor.json"

        fun load(context: Context): SensorPairing? {
            val f = File(context.filesDir, FILE)
            if (!f.isFile) return null
            val o = JSONObject(f.readText())
            val pin = o.getString("pinHex").hex()
            val kAuth = o.getString("kAuthHex").hex()
            require(pin.size == 4) { "pin must be 4 bytes" }
            require(kAuth.size == 149) { "kAuth must be 149 bytes" }
            return SensorPairing(
                serial = o.getString("serial"),
                address = o.getString("address").uppercase(),
                startEpoch = o.getLong("startEpoch"),
                pin = pin,
                kAuth = kAuth,
                securityVersion = o.optInt("securityVersion", 0),
            )
        }

        private fun String.hex() = ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    /** Safe for logs. */
    override fun toString() = "SensorPairing(serial=$serial, address=$address, start=$startEpoch)"
}
