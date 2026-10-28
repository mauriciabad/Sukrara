// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.sharing.share

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mauri.sukrara.designsystem.state.StateKind
import app.mauri.sukrara.designsystem.state.StateScreen
import app.mauri.sukrara.feature.sharing.R
import app.mauri.sukrara.feature.sharing.conflict.IdentityConflictScreen
import app.mauri.sukrara.feature.sharing.followers.FollowersActions
import app.mauri.sukrara.feature.sharing.followers.FollowersPage
import app.mauri.sukrara.share.owner.OwnerShareState

/**
 * Settings › Sharing: the followers page while sharing (a waiting request opens the approve dialog the app shows over
 * every screen, [onReview]), the conflict screen while another app shares as this identity, and the way into setup's
 * identity step when there is no identity yet. The app places it under the settings top bar (title and Back).
 */
@Composable
fun SharingScreen(
    viewModel: SharingViewModel,
    onSetUpSharing: () -> Unit,
    onShare: () -> Unit,
    onReview: (String) -> Unit,
    onProfile: () -> Unit,
    onBackup: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val writerRefused by viewModel.writerRefused.collectAsStateWithLifecycle()
    val nearby by viewModel.nearby.collectAsStateWithLifecycle()
    Box(modifier.fillMaxSize()) {
        when (val s = state) {
            OwnerShareState.Loading -> {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            }

            OwnerShareState.NoIdentity, is OwnerShareState.IdentityUnreadable -> {
                StateScreen(
                    kind = if (s is OwnerShareState.IdentityUnreadable) StateKind.Error else StateKind.Empty,
                    title = stringResource(R.string.sh_identity_title),
                    reason =
                        stringResource(
                            if (s is OwnerShareState.IdentityUnreadable) R.string.sh_identity_unreadable else R.string.sh_identity_body,
                        ),
                    action = stringResource(R.string.sh_sharing_set_up),
                    onAction = onSetUpSharing,
                )
            }

            is OwnerShareState.AnotherAppSharingAsYou -> {
                IdentityConflictScreen(s, viewModel::retryConflict, onClose)
            }

            is OwnerShareState.Sharing -> {
                FollowersPage(
                    s,
                    FollowersActions(
                        review = onReview,
                        remove = { viewModel.remove(it) },
                        setWriter = viewModel::setWriter,
                        share = onShare,
                        profile = onProfile,
                        backup = onBackup,
                        closeFollower = viewModel::writerSeen,
                        setNearby = viewModel::setNearby,
                        refreshNearby = viewModel::refreshNearby,
                    ),
                    writerRefused = writerRefused,
                    nearby = nearby,
                )
            }
        }
    }
}
