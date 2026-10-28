// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.sharing

import app.mauri.sukrara.protocol.event.Profile
import app.mauri.sukrara.protocol.follow.FollowStatus
import app.mauri.sukrara.share.ble.NearbyStatus
import app.mauri.sukrara.share.follower.FollowState
import app.mauri.sukrara.share.follower.JoinResult
import app.mauri.sukrara.share.follower.NearbyOffer
import app.mauri.sukrara.share.follower.NearbySearch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf

/**
 * What setup and the Following page do with the follower role (the `FollowerEngine`); a port so screens and view
 * models are tested without relays. The app adds what follows a join and a stop around the engine (monitoring starts,
 * the alert state and the role are reset).
 */
interface FollowerSharing {
    val state: StateFlow<FollowState>

    /** Opens the invite behind [link] and asks the wearer for access as [me]. */
    suspend fun join(
        link: String,
        me: Profile?,
    ): JoinResult

    /** Stop following (confirmed first): the key and the wearer's data go, the role is cleared. False when the key store refused. */
    suspend fun stopFollowing(): Boolean

    /** The Bluetooth link to the wearer's phone (following goes on over the internet in every state). */
    val nearby: StateFlow<NearbyStatus> get() = MutableStateFlow(NearbyStatus.Stopped)

    /** Bluetooth turned on / Nearby devices allowed: look again. */
    fun refreshNearby() = Unit

    /** *Find phones nearby* (join step): while collected, the phones in Bluetooth range offering *Pair nearby*. */
    fun findNearby(): Flow<NearbySearch> = flowOf(NearbySearch(NearbyStatus.Unsupported, emptyList()))

    /** Asks the wearer whose phone made [offer] for access as [me] (no link, no relay lookup). */
    suspend fun joinNearby(
        offer: NearbyOffer,
        me: Profile?,
    ): JoinResult = JoinResult.NotFound

    companion object {
        /** Not following anyone (screenshot tests, a wearer). */
        val None: FollowerSharing =
            object : FollowerSharing {
                override val state = MutableStateFlow<FollowState>(FollowState.NotFollowing)

                override suspend fun join(
                    link: String,
                    me: Profile?,
                ) = JoinResult.NotFound

                override suspend fun stopFollowing() = true
            }
    }
}

private val ENDED = setOf(FollowStatus.Declined, FollowStatus.InviteUsed, FollowStatus.Revoked)

/** A follow that ended: declined, the invite used by another phone, or access removed. */
val FollowState.ended: Boolean
    get() = this is FollowState.Following && status in ENDED

/** Following someone (waiting for approval or active); an unreadable key counts: never "not following" on a Keystore hiccup. */
val FollowState.following: Boolean
    get() = (this is FollowState.Following && !ended) || this is FollowState.Unreadable
