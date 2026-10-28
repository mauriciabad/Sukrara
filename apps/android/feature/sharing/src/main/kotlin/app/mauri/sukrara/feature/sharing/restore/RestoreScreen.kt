// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.sharing.restore

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.mauri.sukrara.designsystem.motion.Reveal
import app.mauri.sukrara.feature.sharing.R
import app.mauri.sukrara.feature.sharing.backup.PasswordManager
import app.mauri.sukrara.feature.sharing.backup.findActivity
import app.mauri.sukrara.feature.sharing.conflict.ConflictContent
import app.mauri.sukrara.feature.sharing.profile.ProfileEditor
import app.mauri.sukrara.feature.sharing.profile.ProfileSaver
import app.mauri.sukrara.feature.sharing.profile.randomAvatar
import app.mauri.sukrara.feature.sharing.setup.ConfirmDialog
import app.mauri.sukrara.feature.sharing.setup.NoteCard
import app.mauri.sukrara.feature.sharing.setup.NumberedItem
import app.mauri.sukrara.feature.sharing.setup.RestoreBlock
import app.mauri.sukrara.feature.sharing.setup.RestoreUi
import app.mauri.sukrara.feature.sharing.setup.SetupActions
import app.mauri.sukrara.feature.sharing.setup.SetupStep
import app.mauri.sukrara.feature.sharing.setup.SetupUi
import app.mauri.sukrara.feature.sharing.setup.StepButtons
import app.mauri.sukrara.feature.sharing.setup.StepHeader
import app.mauri.sukrara.protocol.event.Profile
import app.mauri.sukrara.share.owner.OwnerShareState
import app.mauri.sukrara.share.owner.usecase.RestoreIdentity
import kotlinx.coroutines.launch

/**
 * The sharing identity step: restore from a recovery code (gated, P2) or start fresh (confirmed, P10).
 * Already sharing → Continue. Another app sharing as this identity → the conflict content (no restore, no new identity).
 */
@Composable
internal fun ColumnScope.IdentityStep(
    u: SetupUi,
    actions: SetupActions,
) {
    StepHeader(stringResource(R.string.sh_identity_title), stringResource(R.string.sh_identity_body))
    when (val share = u.share) {
        is OwnerShareState.Sharing -> {
            NoteCard(stringResource(R.string.sh_identity_sharing, share.followers.count { it.active }))
            StepButtons(stringResource(R.string.sh_setup_continue), actions.completeIdentity)
        }

        is OwnerShareState.AnotherAppSharingAsYou -> {
            ConflictContent(share, onRetry = actions.retryConflict)
            StepButtons(null, {}, secondary = listOf(stringResource(R.string.sh_setup_later) to { actions.skip(SetupStep.Identity) }))
        }

        else -> {
            if (share is OwnerShareState.IdentityUnreadable) NoteCard(stringResource(R.string.sh_identity_unreadable), error = true)
            RestoreForm(u, actions)
            if (share is OwnerShareState.NoIdentity) {
                HorizontalDivider()
                StartFresh(enabled = !u.busy, actions)
            }
            StepButtons(null, {}, secondary = listOf(stringResource(R.string.sh_setup_later) to { actions.skip(SetupStep.Identity) }))
        }
    }
}

@Composable
private fun ColumnScope.RestoreForm(
    u: SetupUi,
    actions: SetupActions,
) {
    // the secret stays in this composition only: never saved state, cleared once restored
    var code by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    Text(stringResource(R.string.sh_restore_title), style = MaterialTheme.typography.titleMedium)
    NoteCard(stringResource(R.string.sh_restore_warning))
    // the gate as a numbered checklist that ticks itself: 1 sensor live here, 2 the code
    val sensorLive = RestoreBlock.NoLiveReading !in u.gate.blocks
    NumberedItem(1, stringResource(R.string.sh_restore_step_sensor), done = sensorLive) {
        if (!sensorLive) Text(stringResource(R.string.sh_restore_block_no_reading), style = MaterialTheme.typography.bodyMedium)
    }
    NumberedItem(2, stringResource(R.string.sh_restore_step_code)) {
        Text(
            stringResource(R.string.sh_restore_where),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    val restoring = u.restore is RestoreUi.Busy
    // the password manager fills the same field (masked; an ncryptsec then asks for its password), then restores
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var asking by remember { mutableStateOf(false) }
    var managerError by remember { mutableStateOf<Int?>(null) }
    OutlinedButton(
        onClick = {
            val activity = context.findActivity() ?: return@OutlinedButton
            asking = true
            managerError = null
            actions.clearRestoreError()
            scope.launch {
                when (val got = PasswordManager.get(activity)) {
                    is PasswordManager.Got.Secret -> {
                        code = got.text
                        if (!got.text.trim().startsWith("ncryptsec")) actions.restore(got.text, null)
                    }

                    PasswordManager.Got.NoneSaved -> {
                        managerError = R.string.sh_restore_manager_none
                    }

                    PasswordManager.Got.Cancelled -> {
                        Unit
                    }

                    PasswordManager.Got.Failed -> {
                        managerError = R.string.sh_restore_manager_failed
                    }
                }
                asking = false
            }
        },
        // the gate first: the secret is never fetched while the restore couldn't run
        enabled = u.gate.open && !restoring && !u.busy && !asking,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
    ) { Text(stringResource(R.string.sh_restore_manager)) }
    managerError?.let {
        Text(
            stringResource(it),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
        )
    }
    OutlinedTextField(
        value = code,
        onValueChange = {
            code = it
            actions.clearRestoreError()
        },
        label = { Text(stringResource(R.string.sh_restore_code)) },
        singleLine = true,
        enabled = !restoring,
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        visualTransformation = PasswordVisualTransformation(),
    )
    Reveal(code.trim().startsWith("ncryptsec")) {
        OutlinedTextField(
            value = password,
            onValueChange = {
                password = it
                actions.clearRestoreError()
            },
            label = { Text(stringResource(R.string.sh_restore_password)) },
            singleLine = true,
            enabled = !restoring,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            visualTransformation = PasswordVisualTransformation(),
        )
    }
    when (val r = u.restore) {
        RestoreUi.Busy -> {
            Row(Modifier.semantics { liveRegion = LiveRegionMode.Polite }, verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.heightIn(max = 24.dp))
                Text(stringResource(R.string.sh_restore_busy), modifier = Modifier.padding(start = 12.dp))
            }
        }

        is RestoreUi.Failed -> {
            Text(
                restoreError(r.result),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
            )
        }

        is RestoreUi.Restored -> {
            LaunchedEffect(r) {
                code = ""
                password = ""
            }
            NoteCard(stringResource(R.string.sh_restore_done, r.followers))
        }

        RestoreUi.Idle -> {
            Unit
        }
    }
    StepButtons(
        stringResource(R.string.sh_restore_action),
        onPrimary = { actions.restore(code, password) },
        enabled = u.gate.open && code.isNotBlank() && !restoring && !u.busy,
    )
}

/** Start fresh: how the wearer appears to followers (a name is needed), then a confirmed new identity. */
@Composable
private fun ColumnScope.StartFresh(
    enabled: Boolean,
    actions: SetupActions,
) {
    var confirming by rememberSaveable { mutableStateOf(false) }
    var profile by rememberSaveable(stateSaver = ProfileSaver) { mutableStateOf(Profile("", randomAvatar())) }
    Text(stringResource(R.string.sh_fresh_title), style = MaterialTheme.typography.titleMedium)
    Text(stringResource(R.string.sh_fresh_body), style = MaterialTheme.typography.bodyMedium)
    ProfileEditor(profile, { profile = it })
    StepButtons(
        null,
        {},
        secondary = listOf(stringResource(R.string.sh_fresh_action) to { if (enabled && profile.name.isNotBlank()) confirming = true }),
    )
    if (profile.name.isBlank()) {
        Text(
            stringResource(R.string.sh_fresh_needs_name),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (confirming) {
        ConfirmDialog(
            title = stringResource(R.string.sh_fresh_confirm_title),
            text = stringResource(R.string.sh_fresh_confirm_text),
            confirm = stringResource(R.string.sh_fresh_confirm),
            onConfirm = { actions.startFresh(profile) },
            onDismiss = { confirming = false },
        )
    }
}

/** Every restore result has its own sentence and way forward; none drops the person into Start fresh. */
@Composable
fun restoreError(result: RestoreIdentity.Result): String =
    when (result) {
        RestoreIdentity.Result.NeedsPassword -> stringResource(R.string.sh_restore_err_needs_password)
        RestoreIdentity.Result.WrongPassword -> stringResource(R.string.sh_restore_err_wrong_password)
        RestoreIdentity.Result.NotAKey -> stringResource(R.string.sh_restore_err_not_a_key)
        RestoreIdentity.Result.RelaysUnreachable -> stringResource(R.string.sh_restore_err_offline)
        is RestoreIdentity.Result.NoBackupFound -> stringResource(R.string.sh_restore_err_no_backup)
        RestoreIdentity.Result.BackupUnreadable -> stringResource(R.string.sh_restore_err_unreadable)
        RestoreIdentity.Result.BackupOutdated -> stringResource(R.string.sh_restore_err_outdated)
        RestoreIdentity.Result.AlreadySharing -> stringResource(R.string.sh_restore_err_already)
        is RestoreIdentity.Result.Restored -> ""
    }
