// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.sensor

import app.mauri.sukrara.core.model.SourceId
import kotlinx.serialization.Serializable

/** What the person does to pair a sensor of a family; the new-sensor flow shows one setup per kind. */
sealed interface SensorSetup {
    /** One NFC tap gives the Bluetooth address, the PIN and the start time (Libre 3). */
    data object NfcTap : SensorSetup

    /**
     * The vendor's own app pairs the sensor and Sukrara reads along, writing nothing to it (Dexcom G7 / ONE+ / Stelo).
     * The vendor app must stay installed and running.
     */
    data object VendorApp : SensorSetup

    /** The person types an id printed on the device or its box ([length] letters and digits); every key comes from it. */
    data class TypedId(val kind: IdKind, val length: Int) : SensorSetup {
        /** As typed → as the driver wants it: upper case, no spaces or dashes (boxes print them in groups). */
        fun normalize(text: String): String = text.filter { it.isLetterOrDigit() }.uppercase()

        /** Exactly [length] ASCII letters and digits, after [normalize]. */
        fun valid(id: String): Boolean = id.length == length && id.all { it in '0'..'9' || it in 'A'..'Z' }
    }

    /** Standard Bluetooth pairing with the PIN printed on the sensor's cap, typed into Android's own dialog. */
    data class BlePasskey(val digits: Int) : SensorSetup

    /** Another app on this phone owns the sensor (Juggluco): nothing to pair. */
    data object None : SensorSetup
}

enum class IdKind { TransmitterId, SensorSerial }

/**
 * The sensor families Sukrara reads. [warmupMin] / [wearMin] are the nominal values used when the sensor doesn't say;
 * a Libre 3's NFC patch info carries both and wins. [verified] = false: Sukrara's support was never confirmed on a real
 * sensor; the new-sensor flow tags it "Untested", asks for a confirmation and the home screen asks for feedback while
 * it is the wearer's sensor. [label] is the brand name (not translated). [source] tags the readings.
 */
@Serializable
enum class SensorProfile(
    val id: String,
    val label: String,
    val warmupMin: Int,
    val wearMin: Int,
    val verified: Boolean,
) {
    /**
     * FreeStyle Libre 3 / 3 Plus, read directly over NFC + Bluetooth. 15 days (the 3 Plus; LibreView `wearDuration` 21600)
     * and a 60 min warm-up when the patch info doesn't say (a Libre 3 says 14 days).
     */
    Libre3("libre3", "FreeStyle Libre 3 / 3 Plus", warmupMin = 60, wearMin = 15 * DAY, verified = true),

    /** Dexcom G7 (10 day) and ONE+: read along with the Dexcom app (observer). Warm-up 30 min. */
    DexcomG7("dexcom-g7", "Dexcom G7 / ONE+", warmupMin = 30, wearMin = 10 * DAY, verified = false),

    /** The 15-day G7 and Stelo: the same sensor and driver, a longer life. */
    DexcomG7Long("dexcom-g7-15", "Dexcom G7 15 Day / Stelo", warmupMin = 30, wearMin = 15 * DAY, verified = false),

    /** Accu-Chek SmartGuide: the standard Bluetooth CGM Service, bonded with the PIN on the cap. 60 min, 14 days. */
    SmartGuide("accu-chek-smartguide", "Accu-Chek SmartGuide", warmupMin = 60, wearMin = 14 * DAY, verified = false),

    /** Microtech Aidex X and its relabels (LinX, LumiFlex, GlucoRx, Wellion): keys from the serial on the box. */
    AidexX("aidex-x", "Aidex X / LinX / GlucoRx", warmupMin = 60, wearMin = 15 * DAY, verified = false),

    /** Dexcom G6 / ONE: the 6-character transmitter id is the whole secret. Warm-up 2 h, 10 days. */
    DexcomG6("dexcom-g6", "Dexcom G6 / ONE", warmupMin = 120, wearMin = 10 * DAY, verified = false),

    /** Juggluco's local web server on this phone: Juggluco owns the sensor. It doesn't publish the wear length. */
    Juggluco("juggluco", "Juggluco", warmupMin = 60, wearMin = 15 * DAY, verified = true),
    ;

    val setup: SensorSetup
        get() =
            when (this) {
                Libre3 -> SensorSetup.NfcTap
                DexcomG7, DexcomG7Long -> SensorSetup.VendorApp
                SmartGuide -> SensorSetup.BlePasskey(digits = 6)
                AidexX -> SensorSetup.TypedId(IdKind.SensorSerial, length = 10)
                DexcomG6 -> SensorSetup.TypedId(IdKind.TransmitterId, length = 6)
                Juggluco -> SensorSetup.None
            }

    /** The phone scans to find the sensor (pairing gives no address): every family but the Libre 3. */
    val needsScan: Boolean get() = this != Libre3 && this != Juggluco

    /** What the readings of this family are tagged with. */
    val source: SourceId
        get() =
            when (this) {
                Libre3 -> SourceId.Libre3
                DexcomG7, DexcomG7Long -> SourceId.DexcomG7
                SmartGuide -> SourceId.SmartGuide
                AidexX -> SourceId.AidexX
                DexcomG6 -> SourceId.DexcomG6
                Juggluco -> SourceId.Juggluco
            }

    companion object {
        /** The families a phone can pair itself, in the order the new-sensor flow lists them (the Libre 3 first). */
        val pairable: List<SensorProfile> = listOf(Libre3, DexcomG7, DexcomG7Long, SmartGuide, AidexX, DexcomG6)
    }
}

private const val DAY = 24 * 60
