// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.sensor

import kotlinx.serialization.Serializable

/**
 * The sensor families Sukrara reads (Dexcom, Aidex, SmartGuide are out: no hardware to test them).
 * [warmupMin] / [wearMin] are the nominal values used when the sensor doesn't say; a Libre 3's NFC patch info carries
 * both and wins.
 */
@Serializable
enum class SensorProfile(val id: String, val warmupMin: Int, val wearMin: Int) {
    /**
     * FreeStyle Libre 3 / 3 Plus, read directly over NFC + Bluetooth. 15 days (the 3 Plus; LibreView `wearDuration` 21600)
     * and a 60 min warm-up when the patch info doesn't say (a Libre 3 says 14 days).
     */
    Libre3("libre3", warmupMin = 60, wearMin = 15 * 24 * 60),

    /** Juggluco's local web server on this phone: Juggluco owns the sensor. It doesn't publish the wear length. */
    Juggluco("juggluco", warmupMin = 60, wearMin = 15 * 24 * 60),
}
