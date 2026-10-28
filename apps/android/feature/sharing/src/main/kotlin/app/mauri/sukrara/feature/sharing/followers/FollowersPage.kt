// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.sharing.followers

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.mauri.sukrara.designsystem.avatar.AvatarImage
import app.mauri.sukrara.designsystem.haptics.rememberHaptics
import app.mauri.sukrara.designsystem.motion.Reveal
import app.mauri.sukrara.designsystem.rows.FooterText
import app.mauri.sukrara.designsystem.rows.InfoRow
import app.mauri.sukrara.designsystem.rows.NavigationRow
import app.mauri.sukrara.designsystem.rows.SettingsGroup
import app.mauri.sukrara.designsystem.rows.SwitchRow
import app.mauri.sukrara.feature.sharing.BLOCKED
import app.mauri.sukrara.feature.sharing.NearbyFixRow
import app.mauri.sukrara.feature.sharing.NearbyUi
import app.mauri.sukrara.feature.sharing.PairNearbyRow
import app.mauri.sukrara.feature.sharing.R
import app.mauri.sukrara.feature.sharing.nearbySummary
import app.mauri.sukrara.feature.sharing.share.shownName
import app.mauri.sukrara.protocol.event.Profile
import app.mauri.sukrara.share.ble.AndroidNearbyServer
import app.mauri.sukrara.share.owner.FollowerRecord
import app.mauri.sukrara.share.owner.OwnerShareState
import java.text.DateFormat
import java.util.Date

/** What the followers page can do; each goes to the engine or another page. */
class FollowersActions(
    val review: (String) -> Unit = {},
    val remove: (String) -> Unit = {},
    val setWriter: (String, Boolean) -> Unit = { _, _ -> },
    val share: () -> Unit = {},
    val profile: () -> Unit = {},
    val backup: () -> Unit = {},
    /** The follower dialog closed (its refusal note goes away). */
    val closeFollower: () -> Unit = {},
    /** The Bluetooth switch. */
    val setNearby: (Boolean) -> Unit = {},
    /** Bluetooth turned on / Nearby devices allowed: look again. */
    val refreshNearby: () -> Unit = {},
    /** *Pair nearby* on (10 min) / *Stop*. */
    val pairNearby: (Boolean) -> Unit = {},
)

/**
 * Settings › Sharing while sharing: how the wearer appears (opens How you appear), Backup, requests waiting, the
 * followers with their pictures and whether they can add entries (a follower opens [FollowerDialog]), and the removed
 * ones folded under a count. Share invites someone.
 */
@Composable
fun FollowersPage(
    state: OwnerShareState.Sharing,
    actions: FollowersActions,
    modifier: Modifier = Modifier,
    writerRefused: String? = null,
    nearby: NearbyUi = NearbyUi(),
    /** The clock for *Pair nearby*'s countdown (epoch s; screenshot tests fix it). */
    now: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    var showRemoved by rememberSaveable { mutableStateOf(false) }
    val active = state.followers.filter { it.active }
    val removed = state.followers.filter { !it.active }.sortedByDescending { it.revoked }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingsGroup {
            YouRow(state.profile, active.size, actions.profile)
            NavigationRow(
                title = stringResource(R.string.sh_backup_title),
                summary = stringResource(if (state.backedUp) R.string.sh_backup_row_done else R.string.sh_backup_row_missing),
                onClick = actions.backup,
            )
            NavigationRow(title = stringResource(R.string.sh_followers_share), onClick = actions.share)
        }
        if (state.pending.isNotEmpty()) {
            SettingsGroup(title = stringResource(R.string.sh_followers_waiting)) {
                for (p in state.pending) {
                    PersonRow(p.avatarJson, p.name, stringResource(R.string.sh_followers_review)) { actions.review(p.follower) }
                }
            }
        }
        SettingsGroup(title = pluralStringResource(R.plurals.sh_followers_count, active.size, active.size)) {
            if (active.isEmpty()) {
                InfoRow(
                    stringResource(R.string.sh_followers_empty_title),
                    summary = stringResource(R.string.sh_followers_empty_body),
                )
            }
            for (f in active) {
                val since = stringResource(R.string.sh_followers_since, date(f.since))
                val scope = stringResource(if (f.key in state.writers) R.string.sh_followers_can_add else R.string.sh_followers_sees_only)
                PersonRow(f.avatarJson, f.name, "$since · $scope") { open = f.key }
            }
        }
        SettingsGroup(title = stringResource(R.string.sh_nearby_group)) {
            SwitchRow(
                title = stringResource(R.string.sh_nearby_title),
                checked = nearby.on,
                onCheckedChange = actions.setNearby,
                summary = nearbySummary(nearby.status, nearby.on, wearer = true),
            )
            if (nearby.on) NearbyFixRow(nearby.status, AndroidNearbyServer.PERMISSIONS, actions.refreshNearby)
            // an open pairing always keeps its switch (the way to stop it)
            if ((nearby.on && nearby.status !in BLOCKED) ||
                nearby.pairingUntil != null
            ) {
                PairNearbyRow(nearby.pairingUntil, actions.pairNearby, now)
            }
        }
        // only links whose identity proof named an active follower: an unnamed one is never listed (no made-up name)
        val devices = nearby.links.mapNotNull { l -> active.firstOrNull { it.key == l.follower }?.let { it to l } }
        if (nearby.on && devices.isNotEmpty()) {
            SettingsGroup(title = stringResource(R.string.sh_nearby_devices)) {
                for ((f, link) in devices) {
                    val device = stringResource(if (link.device == "mac") R.string.sh_nearby_mac else R.string.sh_nearby_phone)
                    PersonRow(
                        f.avatarJson,
                        stringResource(R.string.sh_nearby_device_name, shownName(f.name), device),
                        stringResource(R.string.sh_nearby_device_connected),
                    ) { open = f.key }
                }
            }
        }
        if (removed.isNotEmpty()) {
            Row(Modifier.padding(horizontal = 16.dp)) {
                FilledTonalButton(
                    onClick = { showRemoved = !showRemoved },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Badge(containerColor = MaterialTheme.colorScheme.secondary) { Text(removed.size.toString()) }
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(if (showRemoved) R.string.sh_followers_removed_hide else R.string.sh_followers_removed_show))
                }
            }
            Reveal(showRemoved) {
                SettingsGroup(title = stringResource(R.string.sh_followers_removed)) {
                    for (f in removed) {
                        PersonRow(
                            f.avatarJson,
                            f.name,
                            stringResource(R.string.sh_followers_removed_at, date(f.revoked ?: 0)),
                            onClick = null,
                        )
                    }
                }
            }
        }
        FooterText(stringResource(R.string.sh_followers_footer))
    }
    active.firstOrNull { it.key == open }?.let { f ->
        FollowerDialog(
            f,
            writer = f.key in state.writers,
            refused = writerRefused == f.key,
            onWriter = { actions.setWriter(f.key, it) },
            onRemove = { actions.remove(f.key) },
            onClose = {
                open = null
                actions.closeFollower()
            },
        )
    }
}

/** The wearer: picture, name (or "Add your name") and how many follow; opens How you appear. */
@Composable
private fun YouRow(
    profile: Profile?,
    followers: Int,
    onClick: () -> Unit,
) {
    val name = profile?.name?.ifBlank { null }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .background(MaterialTheme.colorScheme.surfaceBright)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        AvatarImage(profile?.avatar, name.orEmpty(), size = 48.dp)
        Column(Modifier.weight(1f)) {
            Text(
                name ?: stringResource(R.string.sh_profile_add_name),
                style = MaterialTheme.typography.titleMedium,
                color = if (name == null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                stringResource(R.string.sh_profile_row_summary, pluralStringResource(R.plurals.sh_followers_count, followers, followers)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A person: picture, name, a line about them; tappable when [onClick] is set. */
@Composable
internal fun PersonRow(
    avatarJson: String?,
    name: String,
    summary: String,
    onClick: (() -> Unit)?,
) {
    val shown = shownName(name).ifBlank { stringResource(R.string.sh_approve_unnamed) }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .background(MaterialTheme.colorScheme.surfaceBright)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        AvatarImage(avatarJson, shown, size = 40.dp)
        Column(Modifier.weight(1f)) {
            Text(shown, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * One follower: picture, since when, *Can add entries* (a refused change snaps back and says why), and Remove —
 * confirmed, saying what it means.
 */
@Composable
fun FollowerDialog(
    follower: FollowerRecord,
    writer: Boolean,
    refused: Boolean,
    onWriter: (Boolean) -> Unit,
    onRemove: () -> Unit,
    onClose: () -> Unit,
) {
    var confirming by rememberSaveable { mutableStateOf(false) }
    val haptics = rememberHaptics()
    val name = shownName(follower.name).ifBlank { stringResource(R.string.sh_approve_unnamed) }
    if (!confirming) {
        AlertDialog(
            onDismissRequest = onClose,
            icon = { AvatarImage(follower.avatarJson, name, size = 64.dp) },
            title = { Text(name, maxLines = 2) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.sh_followers_since, date(follower.since)))
                    SwitchRow(
                        title = stringResource(R.string.sh_followers_writer),
                        checked = writer,
                        onCheckedChange = onWriter,
                        summary = stringResource(R.string.sh_followers_writer_summary),
                    )
                    if (refused) Text(stringResource(R.string.sh_followers_writer_refused), color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.sh_followers_close)) } },
            dismissButton = {
                TextButton(
                    onClick = { confirming = true },
                ) { Text(stringResource(R.string.sh_followers_remove), color = MaterialTheme.colorScheme.error) }
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
