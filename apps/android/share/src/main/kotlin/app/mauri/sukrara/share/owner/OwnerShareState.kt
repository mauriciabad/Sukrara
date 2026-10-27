// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.share.owner

import app.mauri.sukrara.protocol.SignedEvent
import app.mauri.sukrara.protocol.owner.IdentityCheck
import app.mauri.sukrara.share.transport.RelayStatus

/** What the wearer's sharing shows (home banner, Settings › Sharing). Every state has a way out (FEATURES.md). */
sealed interface OwnerShareState {
    data object Loading : OwnerShareState

    /** No sharing identity yet: create one or restore from a recovery code. */
    data object NoIdentity : OwnerShareState

    /**
     * The stored identity exists but can't be read. Blocking: never replaced silently. Exits: retry (when
     * [transient]), restore from the recovery code.
     */
    data class IdentityUnreadable(val transient: Boolean) : OwnerShareState

    data class Sharing(
        val epoch: Int,
        val followers: List<FollowerRecord>,
        val pending: List<PendingRequest>,
        val relays: Map<String, RelayStatus>,
        val backupPublishedAt: Long,
        val recoveryExportedAt: Long,
    ) : OwnerShareState

    /**
     * A3: another app (e.g. Sukrara Showcase) publishes as this identity. Roster / backup / rotation and follower
     * changes are paused; readings and alerts continue ([followersReceiving] says whether followers still accept them).
     * Exits: "I turned it off — retry", Help.
     */
    data class AnotherAppSharingAsYou(
        val reason: IdentityCheck.Reason,
        val followersReceiving: Boolean,
        val relays: Map<String, RelayStatus>,
    ) : OwnerShareState
}

/** Bluetooth sharing with nearby followers (chunk 25). Until then a no-op. */
interface OwnerNearby {
    fun publish(event: SignedEvent)

    object None : OwnerNearby {
        override fun publish(event: SignedEvent) = Unit
    }
}

/** Gift wraps already handled (the sharing ledger in Room); [markSeen] is false for one seen before. */
fun interface SeenWraps {
    suspend fun markSeen(id: String, at: Long): Boolean
}
