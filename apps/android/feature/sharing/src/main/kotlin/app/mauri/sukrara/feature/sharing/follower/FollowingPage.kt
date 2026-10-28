// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.sharing.follower

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.mauri.sukrara.designsystem.avatar.AvatarImage
import app.mauri.sukrara.designsystem.haptics.rememberHaptics
import app.mauri.sukrara.feature.sharing.FollowerSharing
import app.mauri.sukrara.feature.sharing.NearbyFixRow
import app.mauri.sukrara.feature.sharing.R
import app.mauri.sukrara.feature.sharing.nearbySummary
import app.mauri.sukrara.feature.sharing.setup.NoteCard
import app.mauri.sukrara.protocol.follow.FollowStatus
import app.mauri.sukrara.share.ble.AndroidNearbyCentral
import app.mauri.sukrara.share.ble.NearbyStatus
import app.mauri.sukrara.share.follower.FollowState
import kotlinx.coroutines.launch

/** The Following page's view model: the follower state and Stop following. */
class FollowingViewModel(
    private val follower: FollowerSharing,
) : ViewModel() {
    val state = follower.state
    val nearby = follower.nearby

    suspend fun stop(): Boolean = follower.stopFollowing()

    fun refreshNearby() = follower.refreshNearby()

    companion object {
        fun factory(follower: FollowerSharing) = viewModelFactory { initializer { FollowingViewModel(follower) } }
    }
}

/**
 * Settings › Sharing on a follower phone: who this phone follows (picture and name from the wearer's roster), whether
 * the request still waits (with the code the wearer may ask for), when the last reading arrived, whether this phone may
 * add entries, the relay connection, and the way out: Stop following (confirmed). A follow that ended says why and
 * offers Join with a new link (setup's join step). [onLeft] runs after Stop following worked (the app reopens setup).
 */
@Composable
fun FollowingPage(
    viewModel: FollowingViewModel,
    onJoinAgain: () -> Unit,
    onLeft: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val nearby by viewModel.nearby.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    var failed by rememberSaveable { mutableStateOf(false) }
    FollowingContent(
        state = state,
        failed = failed,
        onStop = {
            scope.launch {
                val ok = viewModel.stop()
                failed = !ok
                if (ok) {
                    haptics.confirm()
                    onLeft()
                }
            }
        },
        onJoinAgain = onJoinAgain,
        nearby = nearby,
        onRefreshNearby = viewModel::refreshNearby,
        modifier = modifier,
    )
}

/** The page for [state] (also what the screenshot tests render). */
@Composable
fun FollowingContent(
    state: FollowState,
    failed: Boolean,
    onStop: () -> Unit,
    onJoinAgain: () -> Unit,
    modifier: Modifier = Modifier,
    nearby: NearbyStatus = NearbyStatus.Stopped,
    onRefreshNearby: () -> Unit = {},
) {
    var confirming by rememberSaveable { mutableStateOf(false) }
    val someone = stringResource(R.string.sh_join_someone)
    val name = (state as? FollowState.Following)?.wearer?.name?.takeIf { it.isNotBlank() } ?: someone
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        when (state) {
            FollowState.Loading -> {
                CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
            }

            FollowState.NotFollowing -> {
                OutlinedButton(onClick = onJoinAgain, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.sh_join_title)) }
            }

            is FollowState.Unreadable -> {
                NoteCard(stringResource(R.string.sh_following_unreadable), error = true)
            }

            FollowState.Damaged -> {
                NoteCard(stringResource(R.string.sh_following_damaged), error = true)
            }

            is FollowState.Following -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AvatarImage(state.wearer?.avatar, name, size = 64.dp)
                    Text(
                        stringResource(
                            if (state.status == FollowStatus.Active) R.string.sh_following_active else R.string.sh_following_waiting,
                            name,
                        ),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(start = 16.dp).semantics { heading() },
                    )
                }
                when (state.status) {
                    FollowStatus.Declined, FollowStatus.InviteUsed, FollowStatus.Revoked -> {
                        val why =
                            when (state.status) {
                                FollowStatus.Declined -> R.string.sh_join_declined
                                FollowStatus.InviteUsed -> R.string.sh_join_used
                                else -> R.string.sh_join_revoked
                            }
                        NoteCard(stringResource(why, name), error = true)
                        OutlinedButton(onClick = onJoinAgain, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.sh_following_join_again))
                        }
                    }

                    FollowStatus.New, FollowStatus.Requested -> {
                        NoteCard(stringResource(R.string.sh_following_code, state.code.toList().joinToString(" ")))
                    }

                    FollowStatus.Active -> {
                        val heard = state.lastHeardAt
                        Text(
                            if (heard == null) {
                                stringResource(R.string.sh_following_never)
                            } else {
                                stringResource(
                                    R.string.sh_following_last_heard,
                                    DateUtils.getRelativeTimeSpanString(heard.toEpochMilli()).toString(),
                                )
                            },
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            stringResource(if (state.canWrite) R.string.sh_following_can_write else R.string.sh_following_cannot_write),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
                val relays = state.relays
                Text(
                    if (relays.isEmpty()) {
                        stringResource(R.string.sh_following_relays_off)
                    } else {
                        stringResource(R.string.sh_following_relays, relays.values.count { it.connected }, relays.size)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // "connected" only once the phone proved it's this wearer's (B§6), never on a bare connection
                Text(
                    stringResource(R.string.sh_following_nearby, nearbySummary(nearby, on = true, wearer = false)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                NearbyFixRow(nearby, AndroidNearbyCentral.PERMISSIONS, onRefreshNearby)
            }
        }
        if (failed) NoteCard(stringResource(R.string.sh_following_stop_failed), error = true)
        if (state !is FollowState.Loading && state != FollowState.NotFollowing) {
            OutlinedButton(
                onClick = { confirming = true },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.sh_following_stop)) }
        }
    }
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(stringResource(R.string.sh_following_stop_title, name)) },
            text = { Text(stringResource(R.string.sh_following_stop_text)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirming = false
                        onStop()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text(stringResource(R.string.sh_following_stop)) }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
}
