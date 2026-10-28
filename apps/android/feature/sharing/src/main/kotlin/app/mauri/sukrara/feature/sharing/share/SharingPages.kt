// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.sharing.share

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mauri.sukrara.designsystem.state.StateKind
import app.mauri.sukrara.designsystem.state.StateScreen
import app.mauri.sukrara.feature.sharing.R
import app.mauri.sukrara.feature.sharing.backup.BackupScreen
import app.mauri.sukrara.feature.sharing.backup.PasswordManager
import app.mauri.sukrara.feature.sharing.backup.SaveOutcome
import app.mauri.sukrara.feature.sharing.backup.findActivity
import app.mauri.sukrara.feature.sharing.profile.ProfileScreen
import app.mauri.sukrara.share.owner.OwnerShareState

/** "How you appear" on the engine: saves, then [onDone]; without an identity it leads to setting one up. */
@Composable
fun ProfilePage(
    viewModel: SharingViewModel,
    onDone: () -> Unit,
    onSetUpSharing: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val save by viewModel.profileSave.collectAsStateWithLifecycle()
    when (val s = state) {
        is OwnerShareState.Sharing -> {
            ProfileScreen(s.profile, save, onSave = { viewModel.setProfile(it, onDone) }, onCancel = onDone, modifier = modifier)
        }

        OwnerShareState.Loading -> {
            CircularProgressIndicator(modifier)
        }

        else -> {
            NotSharing(s, onSetUpSharing, modifier)
        }
    }
}

/** Backup on the engine; the password manager's sheet shows over this activity. */
@Composable
fun BackupPage(
    viewModel: SharingViewModel,
    onSetUpSharing: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val activity = LocalContext.current.findActivity()
    when (val s = state) {
        is OwnerShareState.Sharing -> {
            BackupScreen(
                s,
                saveToPasswordManager = {
                    var outcome = SaveOutcome.NoIdentity
                    if (activity != null) {
                        viewModel.saveToPasswordManager { id, secret ->
                            val saved = PasswordManager.save(activity, id, secret)
                            outcome =
                                when (saved) {
                                    PasswordManager.Saved.Saved -> SaveOutcome.Saved
                                    PasswordManager.Saved.Cancelled -> SaveOutcome.Cancelled
                                    PasswordManager.Saved.Failed -> SaveOutcome.Failed
                                }
                            saved == PasswordManager.Saved.Saved
                        }
                    }
                    outcome
                },
                recoveryCode = viewModel::recoveryCode,
                onRecoveryKeep = viewModel::recoverySaved,
                modifier = modifier,
            )
        }

        OwnerShareState.Loading -> {
            CircularProgressIndicator(modifier)
        }

        else -> {
            NotSharing(s, onSetUpSharing, modifier)
        }
    }
}

/** No identity (→ set one up), an unreadable one, or another app sharing as it (Settings › Sharing explains; Back leaves). */
@Composable
private fun NotSharing(
    state: OwnerShareState,
    onSetUpSharing: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state is OwnerShareState.AnotherAppSharingAsYou) {
        StateScreen(
            kind = StateKind.Error,
            title = stringResource(R.string.sh_paused_title),
            reason = stringResource(R.string.sh_paused_body),
            modifier = modifier.fillMaxSize(),
        )
        return
    }
    StateScreen(
        kind = if (state is OwnerShareState.IdentityUnreadable) StateKind.Error else StateKind.Empty,
        title = stringResource(R.string.sh_identity_title),
        reason =
            stringResource(
                if (state is OwnerShareState.IdentityUnreadable) R.string.sh_identity_unreadable else R.string.sh_identity_body,
            ),
        action = stringResource(R.string.sh_sharing_set_up),
        onAction = onSetUpSharing,
        modifier = modifier.fillMaxSize(),
    )
}
