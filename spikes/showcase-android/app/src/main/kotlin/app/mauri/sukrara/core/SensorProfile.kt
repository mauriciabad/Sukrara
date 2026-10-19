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
    /** What the brand step and the feedback banner call the family (brand names: not translated). */
    val name: String,
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
    /**
     * Confirmed on a real sensor. False = Sukrara's support is **untested** (investigation 23): the brand step tags it,
     * pairing asks for a confirmation, and the wearer's home shows the feedback banner under the graph
     * (`ui/SensorFeedback`). This flag is the single switch that removes the banner once enough feedback came in.
     */
    val verified: Boolean,
) {
    val warmupMin: Int get() = warmup.inWholeMinutes.toInt()
    val wearMin: Int get() = lifetime.inWholeMinutes.toInt()
}

/** What the user has to do or type to pair a sensor of this family; the New sensor screen shows one step per kind. */
sealed interface Pairing {
    /** Libre 3: one NFC tap gives the BLE address, the PIN and the start time. */
    data object NfcTap : Pairing
    /**
     * The vendor's own app pairs the sensor and Sukrara reads along, writing nothing (Dexcom G7 observer mode): no
     * secret, but the vendor app must stay installed and running. [app] names it.
     */
    data class VendorApp(val app: String) : Pairing
    /** A code the user types: the transmitter id (Dexcom G6 / ONE) or the sensor serial from the box (Aidex X). */
    data class TypedId(val kind: IdKind, val length: Int) : Pairing
    /** Standard BLE passkey pairing with the PIN printed on the sensor's cap (Accu-Chek SmartGuide): Android's dialog. */
    data class BlePasskey(val digits: Int) : Pairing
}

enum class IdKind { TRANSMITTER_ID, SENSOR_SERIAL }

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
     * one glucose wait, `direct/PatchState`). The only family tested on hardware.
     */
    val LIBRE_3 = SensorProfile(
        id = "libre3", name = "FreeStyle Libre 3 / 3 Plus", brand = "Abbott",
        models = listOf("FreeStyle Libre 3", "FreeStyle Libre 3 Plus"),
        pairing = Pairing.NfcTap, warmup = 60.minutes, lifetime = 15.days, grace = 12.hours,
        calibration = Calibration.NONE, needsScan = false, verified = true,
    )

    /**
     * Dexcom G7 (10 day) and ONE+, observer mode (`direct/dexcom/G7Driver`): the Dexcom app pairs the sensor and
     * Sukrara reads the glucose it is sent, writing nothing. Warm-up 30 min, 12 h grace (LoopKit G7SensorKit).
     */
    val DEXCOM_G7 = SensorProfile(
        id = "dexcom-g7", name = "Dexcom G7 / ONE+", brand = "Dexcom", models = listOf("G7 (10 day)", "ONE+"),
        pairing = Pairing.VendorApp("Dexcom"), warmup = 30.minutes, lifetime = 10.days, grace = 12.hours,
        calibration = Calibration.OPTIONAL_ON_SENSOR, needsScan = true, verified = false,
    )

    /** The 15-day G7 and Stelo: the same sensor and driver, a longer life. */
    val DEXCOM_G7_15 = DEXCOM_G7.copy(id = "dexcom-g7-15", name = "Dexcom G7 15 Day / Stelo",
        models = listOf("G7 15 Day", "Stelo"), lifetime = 15.days)

    /**
     * Accu-Chek SmartGuide (`direct/cgms/SmartGuideDriver`), the standard Bluetooth CGM Service: bonded with the PIN on
     * the cap in Android's own dialog. Warm-up 60 min, 14 days (Juggluco `SensorGlucoseData.hpp:1387`).
     */
    val SMARTGUIDE = SensorProfile(
        id = "accu-chek-smartguide", name = "Accu-Chek SmartGuide", brand = "Roche", models = listOf("Accu-Chek SmartGuide"),
        pairing = Pairing.BlePasskey(6), warmup = 60.minutes, lifetime = 14.days,
        calibration = Calibration.OPTIONAL_ON_SENSOR, needsScan = true, verified = false,
    )

    /**
     * Microtech Aidex X and its relabels (`direct/aidex/AidexXDriver`): every key comes from the 10-character serial on
     * the box. Warm-up 60 min, 15 days (16 for LumiFlex; Juggluco `SensorGlucoseData.hpp:1361`).
     */
    val AIDEX_X = SensorProfile(
        id = "aidex-x", name = "Aidex X / LinX / GlucoRx", brand = "Microtech",
        models = listOf("Aidex X", "LinX", "LumiFlex", "GlucoRx", "Wellion"),
        pairing = Pairing.TypedId(IdKind.SENSOR_SERIAL, 10), warmup = 60.minutes, lifetime = 15.days,
        calibration = Calibration.NONE, needsScan = true, verified = false,
    )

    /**
     * Dexcom G6 / ONE (`direct/dexcom/G6Driver`): the 6-character transmitter id is the whole secret. Warm-up 2 h,
     * 10 days. Dexcom is winding the platform down: last in the list.
     */
    val DEXCOM_G6 = SensorProfile(
        id = "dexcom-g6", name = "Dexcom G6 / ONE", brand = "Dexcom", models = listOf("G6", "ONE"),
        pairing = Pairing.TypedId(IdKind.TRANSMITTER_ID, 6), warmup = 2.hours, lifetime = 10.days,
        calibration = Calibration.OPTIONAL_ON_SENSOR, needsScan = true, verified = false,
    )

    /** Every family the app knows, in the order the brand step lists them: the Libre 3 first (the default). */
    val all: List<SensorProfile> = listOf(LIBRE_3, DEXCOM_G7, DEXCOM_G7_15, SMARTGUIDE, AIDEX_X, DEXCOM_G6)

    fun byId(id: String): SensorProfile? = all.firstOrNull { it.id == id }
}

/** The typed ids of [Pairing.TypedId]: what the New sensor screen accepts and what the drivers derive keys from. */
object PairingInput {
    /** As typed → as the driver wants it: upper case, no spaces or dashes (boxes print them in groups). */
    fun normalize(text: String): String = text.filter { it.isLetterOrDigit() }.uppercase()

    /** Exactly [Pairing.TypedId.length] letters and digits (ASCII). */
    fun valid(kind: Pairing.TypedId, text: String): Boolean =
        text.length == kind.length && text.all { it in '0'..'9' || it in 'A'..'Z' }
}

/**
 * The wearer's current sensor family, set by the direct source when it starts with a saved sensor and cleared when
 * it stops (Juggluco and followers: null). The home screen's untested-sensor banner reads it (`ui/SensorFeedback`).
 */
object ActiveSensor {
    val profile = kotlinx.coroutines.flow.MutableStateFlow<SensorProfile?>(null)
}
