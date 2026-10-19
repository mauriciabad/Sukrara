// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.core

import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * Everything the app needs to know about a sensor family without knowing its protocol (investigation 23): how it is
 * paired, its warm-up and lifetime, whether the user may calibrate it, whether the phone has to scan for it. The
 * single source for what used to be hard-coded for the Libre 3: [SensorLife]'s defaults, the pairing flow's warm-up,
 * the direct source's connection kind. One instance per family in [SensorProfiles]; a driver (`direct/SensorDriver`)
 * names its profile.
 *
 * [lifetime] and [warmup] are the family's nominal values, used when the sensor itself doesn't say; a sensor that does
 * (the Libre 3's NFC patch info carries both) wins.
 */
data class SensorProfile(
    val id: String,
    val brand: String,
    val models: List<String>,
    val pairing: Pairing,
    val warmup: Duration,
    val lifetime: Duration,
    /** Readings may still come this long after [lifetime] (the vendor's grace period); zero when there is none. */
    val grace: Duration = Duration.ZERO,
    val calibration: Calibration,
    /** The phone must scan to find the sensor (a power cost); false when pairing hands over the address (NFC). */
    val needsScan: Boolean,
) {
    val warmupMin: Int get() = warmup.inWholeMinutes.toInt()
    val wearMin: Int get() = lifetime.inWholeMinutes.toInt()
}

/** What the user has to do or type to pair a sensor of this family; the New sensor screen shows one step per kind. */
sealed interface Pairing {
    /** Libre 3: one NFC tap gives the BLE address, the PIN and the start time. */
    data object NfcTap : Pairing
    /** Dexcom G7 / ONE+ / Stelo: the code printed on the applicator (4 digits; 6 on some). */
    data class PrintedCode(val digits: IntRange, val where: String) : Pairing
    /** Dexcom G6 / ONE: the transmitter's serial. */
    data class TransmitterSerial(val length: Int, val where: String) : Pairing
    /** Standard BLE passkey pairing with a PIN printed on the sensor or its cap (Accu-Chek SmartGuide); Android's dialog. */
    data class BlePasskey(val digits: Int, val where: String) : Pairing
    /** The vendor's own app pairs the sensor and Sukrara reads along, writing nothing (Dexcom G7 observer mode). */
    data object VendorApp : Pairing
}

enum class Calibration {
    /** Factory calibrated; the sensor sends mg/dL and accepts no calibration. */
    NONE,
    /** Factory calibrated; an optional finger-stick calibration can be sent to the sensor. */
    OPTIONAL_ON_SENSOR,
}

object SensorProfiles {
    /**
     * FreeStyle Libre 3 / 3 Plus (`direct/Libre3Driver`). Lifetime is the 3 Plus's 15 days (LibreView `wearDuration`
     * 21600 min) and the warm-up 60 min: the defaults when a sensor's patch info doesn't say (a Libre 3 says 14 days).
     * The 12 h grace is Abbott's figure for the sensor after its end; the app doesn't use it yet (an ended sensor gets
     * one glucose wait, `direct/PatchState`).
     */
    val LIBRE_3 = SensorProfile(
        id = "libre3", brand = "Abbott", models = listOf("FreeStyle Libre 3", "FreeStyle Libre 3 Plus"),
        pairing = Pairing.NfcTap, warmup = 60.minutes, lifetime = 15.days, grace = 12.hours,
        calibration = Calibration.NONE, needsScan = false,
    )

    /** Every family the app knows, in the order a brand picker would list them. */
    val all: List<SensorProfile> = listOf(LIBRE_3)

    fun byId(id: String): SensorProfile? = all.firstOrNull { it.id == id }
}
