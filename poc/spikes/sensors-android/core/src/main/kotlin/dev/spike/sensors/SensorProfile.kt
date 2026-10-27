// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.spike.sensors

import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * Everything Sukrara's UI needs to know about a sensor family without knowing its protocol: how to pair, warm-up,
 * lifetime, whether the user may calibrate. Sukrara hard-codes these for the Libre 3 today (core/SensorLife).
 */
data class SensorProfile(
    val id: String,
    val brand: String,
    val models: List<String>,
    val pairing: Pairing,
    val warmup: Duration,
    val lifetime: Duration,
    /** Readings still come after [lifetime] (Dexcom G7: 12 h grace). */
    val grace: Duration = Duration.ZERO,
    val calibration: Calibration,
    /** The phone must scan to find the sensor (power cost); false when NFC hands over the address. */
    val needsScan: Boolean,
    /** Never run against a real sensor (investigation 23: the user owns none of these). */
    val testedOnHardware: Boolean = false,
)

sealed interface Pairing {
    /** Libre 3: the NFC tap gives address, PIN and start time. */
    data object NfcTap : Pairing
    /** Dexcom G7 / ONE+ / Stelo: the 4-digit code printed on the applicator (6 digits on some). */
    data class PrintedCode(val digits: IntRange, val where: String) : Pairing
    /** Dexcom G6 / ONE: the 6-character transmitter serial. */
    data class TransmitterSerial(val length: Int, val where: String) : Pairing
    /** Standard BLE passkey pairing with a PIN printed on the sensor or its cap (Accu-Chek SmartGuide). */
    data class BlePasskey(val digits: Int, val where: String) : Pairing
}

enum class Calibration {
    /** Factory calibrated; the sensor sends mg/dL and accepts no calibration. */
    NONE,
    /** Factory calibrated; an optional finger-stick calibration can be sent to the sensor. */
    OPTIONAL_ON_SENSOR,
}

object SensorProfiles {
    /** Sukrara's existing driver (spikes/showcase-android direct/Libre3Client.kt), as it would be described here. */
    val LIBRE_3 = SensorProfile(
        id = "libre3", brand = "Abbott", models = listOf("FreeStyle Libre 3", "Libre 3 Plus"),
        pairing = Pairing.NfcTap, warmup = 60.minutes, lifetime = 15.days, grace = 12.hours,
        calibration = Calibration.NONE, needsScan = false, testedOnHardware = true,
    )

    /** 10-day G7 and ONE+; the 15-day G7 and Stelo use [DEXCOM_G7_15]. */
    val DEXCOM_G7 = SensorProfile(
        id = "dexcom-g7", brand = "Dexcom", models = listOf("G7 (10 day)", "ONE+"),
        pairing = Pairing.PrintedCode(4..4, "applicator label"), warmup = 30.minutes, lifetime = 10.days,
        grace = 12.hours, calibration = Calibration.OPTIONAL_ON_SENSOR, needsScan = true,
    )
    val DEXCOM_G7_15 = DEXCOM_G7.copy(id = "dexcom-g7-15", models = listOf("G7 15 Day", "Stelo"), lifetime = 15.days)

    val DEXCOM_G6 = SensorProfile(
        id = "dexcom-g6", brand = "Dexcom", models = listOf("G6", "ONE"),
        pairing = Pairing.TransmitterSerial(6, "transmitter back / box"), warmup = 2.hours, lifetime = 10.days,
        calibration = Calibration.OPTIONAL_ON_SENSOR, needsScan = true,
    )
}
