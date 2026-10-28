// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.share.owner

import app.mauri.sukrara.protocol.SignedEvent
import app.mauri.sukrara.protocol.event.Profile
import app.mauri.sukrara.protocol.owner.IdentityCheck
import app.mauri.sukrara.share.ble.NearbyServerCore
import app.mauri.sukrara.share.ble.NearbyStatus
import app.mauri.sukrara.share.transport.RelayStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

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
        /** How the wearer appears to followers (null = never set). */
        val profile: Profile? = null,
        /** Active followers that may add entries ("Can add entries"). */
        val writers: Set<String> = emptySet(),
        val passwordManagerSavedAt: Long = 0,
        /** When this phone restored the identity (from a code or the password manager, so it exists elsewhere). */
        val restoredAt: Long = 0,
    ) : OwnerShareState {
        /** The identity is somewhere besides this phone: the password manager, a recovery code kept, or where it came from. */
        val backedUp: Boolean get() = passwordManagerSavedAt > 0 || recoveryExportedAt > 0 || restoredAt > 0
    }

    /**
     * Another device (e.g. the old phone after a restore) publishes as this identity. Roster / backup / rotation and follower
     * changes are paused; readings and alerts continue ([followersReceiving] says whether followers still accept them).
     * Exits: "I turned it off — retry", Help.
     */
    data class AnotherAppSharingAsYou(
        val reason: IdentityCheck.Reason,
        val followersReceiving: Boolean,
        val relays: Map<String, RelayStatus>,
    ) : OwnerShareState
}

/** What an Allow did. */
enum class ApproveResult {
    Allowed,

    /** The invite expired or was used, or the request isn't waiting any more: a new invite is needed. */
    Gone,

    /** Another app shares as this identity: follower changes wait until that is solved. */
    Paused,

    /** Other phones tried the same link: the code from the follower's phone is needed. */
    NeedsCode,
}

/**
 * Bluetooth sharing with nearby followers: the GATT server (`AndroidNearbyServer`), [None] where there is none (tests).
 * It serves exactly while the engine runs ([start] / [stop]) and only events the engine hands it ([publish]).
 */
interface OwnerNearby {
    /** What Settings › Sharing shows for the Bluetooth row. */
    val status: StateFlow<NearbyStatus>

    /** Connected follower devices whose identity proof named an active follower, oldest first. */
    val links: StateFlow<List<NearbyServerCore.Named>>

    fun start(host: Host)

    fun stop()

    /** The followers changed (advertise only while there is one, B§3). */
    fun refresh()

    fun publish(event: SignedEvent)

    /** An event a central sent that the engine accepted (a writer's entry): to the other centrals. */
    fun forward(event: SignedEvent, except: String)

    /** "Forget this device": drops the links [follower]'s proof named. */
    fun forget(follower: String)

    /** The engine side: the stream id and followers for INFO and the identity proof, and incoming events. */
    interface Host : NearbyServerCore.Host {
        fun onEvent(event: SignedEvent, peer: String)
    }

    object None : OwnerNearby {
        override val status: StateFlow<NearbyStatus> = MutableStateFlow(NearbyStatus.Stopped)
        override val links: StateFlow<List<NearbyServerCore.Named>> = MutableStateFlow(emptyList())

        override fun start(host: Host) = Unit

        override fun stop() = Unit

        override fun refresh() = Unit

        override fun publish(event: SignedEvent) = Unit

        override fun forward(event: SignedEvent, except: String) = Unit

        override fun forget(follower: String) = Unit
    }
}

/** Gift wraps already handled (the sharing ledger in Room); [markSeen] is false for one seen before. */
fun interface SeenWraps {
    suspend fun markSeen(id: String, at: Long): Boolean
}
