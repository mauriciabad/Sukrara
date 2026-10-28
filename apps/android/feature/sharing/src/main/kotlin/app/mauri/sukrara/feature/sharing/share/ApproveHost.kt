// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.sharing.share

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mauri.sukrara.share.owner.OwnerShareState
import app.mauri.sukrara.share.owner.PendingRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * Requests the wearer put off with Later, for as long as the app process lives (a rotation doesn't bring them back;
 * reopening the app later does). Settings › Sharing's "Tap to review" takes one out again.
 */
class LaterRequests {
    val ids = MutableStateFlow<Set<String>>(emptySet())

    fun later(follower: String) = ids.update { it + follower }

    fun review(follower: String) = ids.update { it - follower }
}

/** The request the wearer sees now: the oldest one waiting that wasn't put off. */
fun requestToShow(state: OwnerShareState, later: Set<String>): PendingRequest? =
    (state as? OwnerShareState.Sharing)?.pending?.sortedBy { it.at }?.firstOrNull { it.follower !in later }

/**
 * The approve dialog over whatever screen is open (the app places it above its navigation, except over setup and
 * sensor pairing, where the wearer is busy). The request notification and Settings › Sharing lead to the same dialog.
 */
@Composable
fun ApproveHost(
    viewModel: SharingViewModel,
    later: LaterRequests,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val putOff by later.ids.collectAsStateWithLifecycle()
    val approving by viewModel.approve.collectAsStateWithLifecycle()
    val request = requestToShow(state, putOff) ?: return
    val mine = approving?.takeIf { it.follower == request.follower }
    ApproveDialog(
        request,
        working = mine?.working == true,
        failed = mine?.failed == true,
        onAllow = { code -> viewModel.approve(request.follower, code) },
        onDeny = { viewModel.deny(request.follower) },
        onLater = { later.later(request.follower) },
    )
}
