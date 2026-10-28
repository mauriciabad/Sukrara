// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara

import app.mauri.sukrara.feature.sharing.FollowerSharing
import app.mauri.sukrara.protocol.event.Profile
import app.mauri.sukrara.share.ble.NearbyStatus
import app.mauri.sukrara.share.follower.FollowState
import app.mauri.sukrara.share.follower.FollowerEngine
import app.mauri.sukrara.share.follower.JoinResult
import app.mauri.sukrara.share.follower.NearbyOffer
import app.mauri.sukrara.share.follower.NearbySearch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * The follower role for setup and the Following page, with what the rest of the app does around it: a join starts
 * monitoring afresh (no alarm armed from a previous role: the alert state and "monitoring since" are reset, then the
 * service connects and sends the request); Stop following resets them the same way and clears the role (setup again).
 */
class AppFollowerSharing(
    private val engine: FollowerEngine,
    /** Alert state and "monitoring since" back to nothing (the alert runtime's reset + the hub's). */
    private val resetMonitoring: suspend () -> Unit,
    private val startMonitoring: suspend () -> Unit,
    private val clearRole: suspend () -> Unit,
) : FollowerSharing {
    override val state: StateFlow<FollowState> = engine.state

    override val nearby: StateFlow<NearbyStatus> = engine.nearbyStatus

    override fun refreshNearby() = engine.refreshNearby()

    override suspend fun join(
        link: String,
        me: Profile?,
    ): JoinResult = started(engine.join(link, me))

    override fun findNearby(): Flow<NearbySearch> = engine.findNearby()

    override suspend fun joinNearby(
        offer: NearbyOffer,
        me: Profile?,
    ): JoinResult = started(engine.joinNearby(offer, me))

    private suspend fun started(result: JoinResult): JoinResult {
        if (result == JoinResult.Joined) {
            resetMonitoring()
            startMonitoring()
        }
        return result
    }

    override suspend fun stopFollowing(): Boolean {
        if (!engine.unfollow()) return false
        resetMonitoring()
        clearRole()
        return true
    }
}
