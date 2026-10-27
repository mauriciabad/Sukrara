// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.core.model

import java.time.Instant

/**
 * One glucose value. [mgdl] is always mg/dL (the unit setting only changes how it is shown); [rateMgdlPerMin] is the
 * sensor's trend rate when it sends one.
 */
data class Reading(
    val t: Instant,
    val mgdl: Int,
    val rateMgdlPerMin: Double?,
    val source: SourceId,
)

/** Where readings come from. A value class so new sources are added without touching every `when`. */
@JvmInline
value class SourceId(val id: String) {
    companion object {
        /** The phone talks to a Libre 3 sensor over Bluetooth (wearer). */
        val Libre3 = SourceId("libre3")

        /** Juggluco's local web server on the same phone (wearer). */
        val Juggluco = SourceId("juggluco")

        /** The wearer's readings over Nostr relays (follower). */
        val Relay = SourceId("relay")

        /** The wearer's phone nearby over Bluetooth (follower). */
        val Nearby = SourceId("nearby")
    }
}

/** The trend arrow's direction, in the Nightscout direction names other apps understand. */
enum class Trend {
    DoubleDown,
    SingleDown,
    FortyFiveDown,
    Flat,
    FortyFiveUp,
    SingleUp,
    DoubleUp,
    Unknown,
}

/** Whether this phone wears the sensor or follows someone who does. */
enum class Role { Wearer, Follower }
