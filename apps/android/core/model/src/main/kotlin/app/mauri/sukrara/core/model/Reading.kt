// Sukrara — https://github.com/mauriciabad/sukrara
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

        /** The other sensor families the phone reads itself (wearer); untested on real sensors. */
        val DexcomG7 = SourceId("dexcom-g7")
        val DexcomG6 = SourceId("dexcom-g6")
        val SmartGuide = SourceId("accu-chek-smartguide")
        val AidexX = SourceId("aidex-x")

        /** Every source that is this phone's own sensor (not a follower feed). */
        val Wearer: Set<SourceId> = setOf(Libre3, Juggluco, DexcomG7, DexcomG6, SmartGuide, AidexX)

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

/** The stored days and the live window as one history. */
object ReadingHistory {
    /**
     * [stored] (older readings, oldest first, read now and then) up to where the [live] window starts, then the whole
     * live window (always the fresher copy: a backfill inside it shows at once). Nothing counts twice.
     */
    fun join(
        stored: List<Reading>,
        live: List<Reading>,
    ): List<Reading> {
        val start = live.firstOrNull()?.t ?: return stored
        return stored.filter { it.t.isBefore(start) } + live
    }
}
