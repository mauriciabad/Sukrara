// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.spike.sensors

import dev.spike.sensors.aidex.AidexX
import dev.spike.sensors.aidex.AidexXDriver
import dev.spike.sensors.cgms.CgmService
import dev.spike.sensors.cgms.SmartGuideDriver
import dev.spike.sensors.dexcom.Dexcom
import dev.spike.sensors.dexcom.G6Driver
import dev.spike.sensors.dexcom.G7Driver
import java.util.UUID

/**
 * What this spike can drive, and what each one needs from the user. Sukrara's "Pair new sensor" flow would show
 * exactly this list (investigation 23 / docs/23 "Architecture").
 */
object Drivers {
    data class Entry(
        val profile: SensorProfile,
        /** The GATT service to look for once connected. */
        val service: UUID,
        /** What the user has to type or scan. */
        val secret: String,
        val notes: String,
        val make: (secret: String, events: SensorEvents) -> SensorDriver,
    )

    val all: List<Entry> = listOf(
        Entry(SensorProfiles.DEXCOM_G7, Dexcom.CGM_SERVICE, "nothing (observer) or the 4-digit applicator code",
            "Reads along with the official Dexcom app, writing nothing. Its own pairing needs a Dexcom app " +
            "certificate Sukrara does not have.") { _, events -> G7Driver(events, G7Driver.Mode.OBSERVE) },
        Entry(SensorProfiles.DEXCOM_G6, Dexcom.CGM_SERVICE, "the 6-character transmitter id",
            "Full pairing on our own: the id is the whole secret.") { secret, events -> G6Driver(secret, events) },
        Entry(SmartGuideDriver.PROFILE, CgmService.SERVICE, "the 6-digit PIN from the cap (Android's pairing dialog)",
            "Public Bluetooth CGM Service; values already calibrated.") { _, events -> SmartGuideDriver(events) },
        Entry(AidexXDriver.PROFILE, AidexX.SERVICE, "the sensor serial from the box code",
            "Keys derived from the serial; also broadcasts readings in its advertisement.") { secret, events ->
            AidexXDriver(secret, events)
        },
    )

    /** Which driver could own this advertisement (several may answer; the UI asks the user). */
    fun candidates(advert: Advert, events: SensorEvents, secret: String = ""): List<Entry> =
        all.filter { runCatching { it.make(secret, events).matches(advert) }.getOrDefault(false) }
}
