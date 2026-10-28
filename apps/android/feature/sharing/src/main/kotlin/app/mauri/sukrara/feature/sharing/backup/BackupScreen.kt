// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.sharing.backup

import android.content.ClipData
import android.content.ClipDescription
import android.os.PersistableBundle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import app.mauri.sukrara.designsystem.haptics.rememberHaptics
import app.mauri.sukrara.designsystem.rows.FooterText
import app.mauri.sukrara.designsystem.rows.InfoRow
import app.mauri.sukrara.designsystem.rows.NavigationRow
import app.mauri.sukrara.designsystem.rows.SettingsGroup
import app.mauri.sukrara.feature.sharing.R
import app.mauri.sukrara.feature.sharing.setup.NoteCard
import app.mauri.sukrara.share.owner.OwnerShareState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** How a Save to password manager went (shown under the row until the next try). */
enum class SaveOutcome { Saved, Cancelled, Failed, NoIdentity }

/**
 * Backup: the sharing identity somewhere besides this phone, so a new phone keeps the followers. Save to the password
 * manager (recommended; the system's sheet asks first), a recovery code for people who want a printable sheet, and
 * whether the relays hold the encrypted follower list. Leaving needs nothing: an unsaved code is simply not a backup.
 */
@Composable
fun BackupScreen(
    state: OwnerShareState.Sharing,
    saveToPasswordManager: suspend () -> SaveOutcome,
    recoveryCode: suspend (password: String) -> String?,
    onRecoveryKeep: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    var outcome by remember { mutableStateOf<SaveOutcome?>(null) }
    var saving by remember { mutableStateOf(false) }
    var showRecovery by remember { mutableStateOf(false) }
    val saved = state.passwordManagerSavedAt > 0
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(if (state.backedUp) R.string.sh_backup_intro_saved else R.string.sh_backup_intro_missing),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        SettingsGroup {
            NavigationRow(
                title = stringResource(if (saved) R.string.sh_backup_save_again else R.string.sh_backup_save),
                summary =
                    if (saved) {
                        stringResource(R.string.sh_backup_saved_at, date(state.passwordManagerSavedAt))
                    } else {
                        stringResource(R.string.sh_backup_recommended)
                    },
                enabled = !saving,
                onClick = {
                    saving = true
                    outcome = null
                    scope.launch {
                        outcome = saveToPasswordManager()
                        saving = false
                        if (outcome == SaveOutcome.Saved) haptics.confirm() else haptics.reject()
                    }
                },
            )
            NavigationRow(
                title = stringResource(R.string.sh_recovery_title),
                summary =
                    if (state.recoveryExportedAt > 0) {
                        stringResource(R.string.sh_recovery_kept_at, date(state.recoveryExportedAt))
                    } else {
                        stringResource(R.string.sh_recovery_summary)
                    },
                onClick = { showRecovery = true },
            )
            InfoRow(
                title = stringResource(R.string.sh_backup_relays),
                summary =
                    if (state.backupPublishedAt > 0) {
                        stringResource(R.string.sh_backup_relays_updated, date(state.backupPublishedAt))
                    } else {
                        stringResource(R.string.sh_backup_relays_not_yet)
                    },
            )
        }
        outcome?.let { o ->
            Column(Modifier.padding(horizontal = 16.dp).semantics { liveRegion = LiveRegionMode.Polite }) {
                NoteCard(
                    stringResource(
                        when (o) {
                            SaveOutcome.Saved -> R.string.sh_backup_saved
                            SaveOutcome.Cancelled -> R.string.sh_backup_cancelled
                            SaveOutcome.Failed -> R.string.sh_backup_failed
                            SaveOutcome.NoIdentity -> R.string.sh_backup_no_identity
                        },
                    ),
                    error = o == SaveOutcome.Failed || o == SaveOutcome.NoIdentity,
                )
            }
        }
        FooterText(stringResource(R.string.sh_backup_footer))
    }
    if (showRecovery) {
        RecoveryDialog(recoveryCode, onKeep = onRecoveryKeep, onClose = { showRecovery = false })
    }
}

/**
 * A recovery code: the identity encrypted with a password of at least [MIN_PASSWORD] characters (made in 1–3 s), then
 * shown once with Copy & close. The dialog's window is secure (no screenshots, hidden in Recents), the copy is
 * marked sensitive, and neither the password nor the code outlives the dialog. Closing during the work drops it.
 */
@Composable
fun RecoveryDialog(
    recoveryCode: suspend (String) -> String?,
    onKeep: () -> Unit,
    onClose: () -> Unit,
) {
    var password by remember { mutableStateOf("") }
    var code by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current
    val haptics = rememberHaptics()
    val label = stringResource(R.string.sh_recovery_title)
    AlertDialog(
        onDismissRequest = onClose,
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        title = { Text(label) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.sh_recovery_text))
                val shown = code
                if (shown == null) {
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text(stringResource(R.string.sh_recovery_password)) },
                        supportingText = { Text(stringResource(R.string.sh_recovery_password_rule, MIN_PASSWORD)) },
                        singleLine = true,
                        enabled = !working,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (working) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CircularProgressIndicator(Modifier.size(20.dp))
                            Text(stringResource(R.string.sh_recovery_working))
                        }
                    }
                    if (failed) Text(stringResource(R.string.sh_recovery_failed), color = MaterialTheme.colorScheme.error)
                } else {
                    SelectionContainer { Text(shown, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
                    Text(stringResource(R.string.sh_recovery_keep), style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            val shown = code
            if (shown == null) {
                Button(
                    enabled = password.length >= MIN_PASSWORD && !working,
                    onClick = {
                        working = true
                        failed = false
                        scope.launch {
                            code =
                                try {
                                    recoveryCode(password)
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (_: Exception) {
                                    null
                                }
                            failed = code == null
                            working = false
                            password = ""
                        }
                    },
                ) { Text(stringResource(R.string.sh_recovery_create)) }
            } else {
                Button(onClick = {
                    val clip =
                        ClipData.newPlainText(label, shown).apply {
                            description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
                        }
                    scope.launch {
                        clipboard.setClipEntry(clip.toClipEntry())
                        haptics.confirm()
                        onKeep()
                        onClose()
                    }
                }) { Text(stringResource(R.string.sh_recovery_copy_close)) }
            }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.sh_followers_close)) } },
    )
}

const val MIN_PASSWORD = 12

private fun date(epochS: Long): String = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(epochS * 1000))
