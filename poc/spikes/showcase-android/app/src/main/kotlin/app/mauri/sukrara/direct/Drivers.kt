// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.direct

import app.mauri.sukrara.core.Pairing
import app.mauri.sukrara.core.PairingInput
import app.mauri.sukrara.core.SensorProfile
import app.mauri.sukrara.core.SensorProfiles
import app.mauri.sukrara.direct.aidex.AidexXDriver
import app.mauri.sukrara.direct.cgms.SmartGuideDriver
import app.mauri.sukrara.direct.dexcom.G6Driver
import app.mauri.sukrara.direct.dexcom.G7Driver

/**
 * The driver registry: every sensor family the direct source can read, keyed by its profile (investigation 23).
 * [DirectSource] looks the saved sensor's profile up here and lets the entry build the driver. The Libre 3's entry is
 * step 1's, unchanged; steps 2–5 added the untested families ([SensorProfile.verified] = false).
 */
internal object Drivers {
    class Entry(
        val profile: SensorProfile,
        /** Why this saved sensor can't be driven (a status detail), or null when it can. */
        val unsupported: (SensorPairing) -> String?,
        val make: (pairing: SensorPairing, events: SensorEvents, prefs: DriverPrefs) -> SensorDriver,
    )

    val all: List<Entry> = listOf(
        Entry(
            SensorProfiles.LIBRE_3,
            unsupported = { p -> if (p.securityVersion != 0) "security version ${p.securityVersion} (Lingo) is not implemented" else null },
            make = { p, events, prefs -> Libre3Driver(p, events, prefs) },
        ),
        Entry(SensorProfiles.DEXCOM_G7, unsupported = { null },
            make = { p, events, prefs -> G7Driver(p, events, prefs, SensorProfiles.DEXCOM_G7) }),
        Entry(SensorProfiles.DEXCOM_G7_15, unsupported = { null },
            make = { p, events, prefs -> G7Driver(p, events, prefs, SensorProfiles.DEXCOM_G7_15) }),
        Entry(SensorProfiles.SMARTGUIDE, unsupported = { null },
            make = { p, events, prefs -> SmartGuideDriver(p, events, prefs) }),
        Entry(SensorProfiles.AIDEX_X, unsupported = { p -> typedId(p, SensorProfiles.AIDEX_X) },
            make = { p, events, prefs -> AidexXDriver(p, events, prefs) }),
        Entry(SensorProfiles.DEXCOM_G6, unsupported = { p -> typedId(p, SensorProfiles.DEXCOM_G6) },
            make = { p, events, prefs -> G6Driver(p, events, prefs) }),
    )

    /** A typed id of the wrong shape (a pairing file edited by hand): the driver would derive the wrong keys. */
    private fun typedId(p: SensorPairing, profile: SensorProfile): String? {
        val kind = profile.pairing as? Pairing.TypedId ?: return null
        return if (PairingInput.valid(kind, p.serial)) null else "${profile.name}: \"${p.serial}\" is not a valid id"
    }

    fun byId(profileId: String): Entry? = all.firstOrNull { it.profile.id == profileId }
}
