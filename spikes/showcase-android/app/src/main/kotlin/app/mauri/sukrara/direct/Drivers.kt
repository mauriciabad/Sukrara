// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.direct

import app.mauri.sukrara.core.SensorProfile
import app.mauri.sukrara.core.SensorProfiles

/**
 * The driver registry: every sensor family the direct source can read, keyed by its profile (investigation 23).
 * [DirectSource] looks the saved sensor's profile up here and lets the entry build the driver. Later families
 * (Dexcom G7 observer, SmartGuide, Aidex X, …) add an entry; the Libre 3's stays as it is.
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
    )

    fun byId(profileId: String): Entry? = all.firstOrNull { it.profile.id == profileId }
}
