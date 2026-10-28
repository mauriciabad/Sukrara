// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.sharing.setup

import app.mauri.sukrara.core.model.GlucoseState
import app.mauri.sukrara.core.model.SourceId
import app.mauri.sukrara.core.model.SourceStatus
import java.time.Duration
import java.time.Instant

/** Why restoring the identity isn't possible yet; each one has its own sentence and way forward on screen. */
enum class RestoreBlock {
    /** P2: this app hasn't received a live reading from its own sensor yet. */
    NoLiveReading,
}

/**
 * The restore gate (CHANGES P2). Restoring moves the stream to this phone, so the phone that shared until now stops
 * reaching the followers: allowed only once this phone has the sensor. The screen always shows the warning sentence.
 */
data class RestoreGate(val blocks: Set<RestoreBlock>) {
    val open: Boolean get() = blocks.isEmpty()

    companion object {
        /** A reading older than this doesn't prove the sensor runs here now. */
        val LIVE_WINDOW: Duration = Duration.ofMinutes(15)

        private val OWN_SOURCES = setOf(SourceId.Libre3, SourceId.Juggluco)

        fun evaluate(ownSensorLive: Boolean): RestoreGate = RestoreGate(if (ownSensorLive) emptySet() else setOf(RestoreBlock.NoLiveReading))

        /** Readings are arriving live from this phone's own sensor (not a follower feed), the newest ≤ 15 min old. */
        fun ownSensorLive(
            state: GlucoseState,
            now: Instant,
        ): Boolean {
            val latest = state.latest ?: return false
            return state.source is SourceStatus.Live &&
                latest.source in OWN_SOURCES &&
                !latest.t.isAfter(now.plusSeconds(60)) &&
                Duration.between(latest.t, now) <= LIVE_WINDOW
        }
    }
}
