// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.sharing.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.mauri.sukrara.designsystem.haptics.rememberHaptics
import app.mauri.sukrara.feature.sharing.R
import app.mauri.sukrara.feature.sharing.setup.NoteCard
import app.mauri.sukrara.protocol.event.Avatar
import app.mauri.sukrara.protocol.event.Profile

/** Whether the last save went through. */
enum class ProfileSave { Idle, Saving, Failed }

/** Keeps an edit across rotation: the name and the picture's JSON. */
internal val ProfileSaver: Saver<Profile, List<String?>> =
    Saver(save = { listOf(it.name, it.avatar?.toString()) }, restore = { Profile(it[0].orEmpty(), Avatar.parse(it[1])) })

/**
 * "How you appear": the wearer's name and picture as followers see them (the roster carries it at once). Save is
 * enabled once there is a name; Cancel leaves without changing anything. [save] = Failed: another app shares as this
 * identity or there is none (the sharing page says which); the edit stays.
 */
@Composable
fun ProfileScreen(
    current: Profile?,
    save: ProfileSave,
    onSave: (Profile) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var edited by rememberSaveable(stateSaver = ProfileSaver) { mutableStateOf(current ?: Profile("", randomAvatar())) }
    val haptics = rememberHaptics()
    Column(modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ProfileEditor(edited, { edited = it })
        Text(stringResource(R.string.sh_profile_followers_note))
        if (save == ProfileSave.Failed) {
            Column(Modifier.semantics { liveRegion = LiveRegionMode.Assertive }) {
                NoteCard(stringResource(R.string.sh_profile_save_failed), error = true)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    haptics.confirm()
                    onSave(edited)
                },
                enabled = edited.name.isNotBlank() && save != ProfileSave.Saving,
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.sh_profile_save)) }
            TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.sh_setup_cancel)) }
        }
    }
}
