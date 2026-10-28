// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara

import app.mauri.sukrara.feature.sharing.FollowerSharing
import app.mauri.sukrara.protocol.event.Profile
import app.mauri.sukrara.share.follower.FollowState
import app.mauri.sukrara.share.follower.FollowerEngine
import app.mauri.sukrara.share.follower.JoinResult
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

    override suspend fun join(
        link: String,
        me: Profile?,
    ): JoinResult {
        val result = engine.join(link, me)
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
