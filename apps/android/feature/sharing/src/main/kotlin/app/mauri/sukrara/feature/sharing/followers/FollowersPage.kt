// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.sharing.followers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.mauri.sukrara.designsystem.haptics.rememberHaptics
import app.mauri.sukrara.designsystem.rows.FooterText
import app.mauri.sukrara.designsystem.rows.NavigationRow
import app.mauri.sukrara.designsystem.rows.SettingsGroup
import app.mauri.sukrara.designsystem.state.StateKind
import app.mauri.sukrara.designsystem.state.StateScreen
import app.mauri.sukrara.feature.sharing.R
import app.mauri.sukrara.share.owner.FollowerRecord
import app.mauri.sukrara.share.owner.PendingRequest
import java.text.DateFormat
import java.util.Date

/**
 * Who follows the wearer, and requests still waiting. A follower opens [FollowerDialog] (Remove, confirmed); the empty
 * state leads to the share screen. An old device is removed here too.
 */
@Composable
fun FollowersPage(
    followers: List<FollowerRecord>,
    pending: List<PendingRequest>,
    onReview: (PendingRequest) -> Unit,
    onRemove: (FollowerRecord) -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    val active = followers.filter { it.active }
    if (active.isEmpty() && pending.isEmpty()) {
        StateScreen(
            kind = StateKind.Empty,
            title = stringResource(R.string.sh_followers_empty_title),
            reason = stringResource(R.string.sh_followers_empty_body),
            action = stringResource(R.string.sh_followers_share),
            onAction = onShare,
            modifier = modifier,
        )
        return
    }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (pending.isNotEmpty()) {
            SettingsGroup(title = stringResource(R.string.sh_followers_waiting)) {
                for (p in pending) {
                    NavigationRow(title = p.name.ifBlank { stringResource(R.string.sh_approve_unnamed) }, summary = stringResource(R.string.sh_followers_review), onClick = { onReview(p) })
                }
            }
        }
        SettingsGroup(title = pluralStringResource(R.plurals.sh_followers_count, active.size, active.size)) {
            for (f in active) {
                NavigationRow(title = f.name.ifBlank { stringResource(R.string.sh_approve_unnamed) }, summary = stringResource(R.string.sh_followers_since, date(f.since)), onClick = { open = f.key })
            }
        }
        NavigationRow(title = stringResource(R.string.sh_followers_share), onClick = onShare)
        FooterText(stringResource(R.string.sh_followers_footer))
    }
    active.firstOrNull { it.key == open }?.let { f ->
        FollowerDialog(f, onRemove = { onRemove(f) }, onClose = { open = null })
    }
}

/** One follower: since when, and Remove — confirmed, saying what it means. */
@Composable
fun FollowerDialog(
    follower: FollowerRecord,
    onRemove: () -> Unit,
    onClose: () -> Unit,
) {
    var confirming by rememberSaveable { mutableStateOf(false) }
    val haptics = rememberHaptics()
    val name = follower.name.ifBlank { stringResource(R.string.sh_approve_unnamed) }
    if (!confirming) {
        AlertDialog(
            onDismissRequest = onClose,
            title = { Text(name) },
            text = { Text(stringResource(R.string.sh_followers_since, date(follower.since))) },
            confirmButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.sh_followers_close)) } },
            dismissButton = {
                TextButton(onClick = { confirming = true }) { Text(stringResource(R.string.sh_followers_remove), color = MaterialTheme.colorScheme.error) }
            },
        )
    } else {
        AlertDialog(
            onDismissRequest = onClose,
            title = { Text(stringResource(R.string.sh_followers_remove_title, name)) },
            text = { Text(stringResource(R.string.sh_followers_remove_text)) },
            confirmButton = {
                TextButton(onClick = {
                    haptics.confirm()
                    onRemove()
                    onClose()
                }) { Text(stringResource(R.string.sh_followers_remove), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.sh_setup_cancel)) } },
        )
    }
}

private fun date(epochS: Long): String = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(epochS * 1000))
