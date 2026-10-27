// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.sharing.setup

import app.mauri.sukrara.core.model.GlucoseState
import app.mauri.sukrara.core.model.SourceId
import app.mauri.sukrara.core.model.SourceStatus
import app.mauri.sukrara.share.owner.usecase.RestoreIdentity
import java.time.Duration
import java.time.Instant

/** Why restoring the identity isn't possible yet; each one has its own sentence and way forward on screen. */
enum class RestoreBlock {
    /** P2: this app hasn't received a live reading from its own sensor yet. */
    NoLiveReading,

    /** A3: Sukrara Showcase is installed and the person hasn't confirmed its sharing is off. */
    ShowcaseMayBeSharing,
}

/**
 * The restore gate (CHANGES P2, A3). Restoring rotates the stream to this phone, so the Showcase stops reaching the
 * followers: allowed only when this app already has the sensor AND the Showcase can't keep publishing as the same
 * identity. The screen always shows the warning sentence, open or closed.
 */
data class RestoreGate(val blocks: Set<RestoreBlock>) {
    val open: Boolean get() = blocks.isEmpty()

    companion object {
        /** A reading older than this doesn't prove the sensor runs here now. */
        val LIVE_WINDOW: Duration = Duration.ofMinutes(15)

        private val OWN_SOURCES = setOf(SourceId.Libre3, SourceId.Juggluco)

        fun evaluate(
            ownSensorLive: Boolean,
            showcaseInstalled: Boolean,
            showcaseSharingOffConfirmed: Boolean,
        ): RestoreGate =
            RestoreGate(
                buildSet {
                    if (!ownSensorLive) add(RestoreBlock.NoLiveReading)
                    if (!RestoreIdentity.restoreAllowed(showcaseInstalled, showcaseSharingOffConfirmed)) add(RestoreBlock.ShowcaseMayBeSharing)
                },
            )

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
