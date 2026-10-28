// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.sharing.share

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.mauri.sukrara.designsystem.haptics.rememberHaptics
import app.mauri.sukrara.feature.sharing.R
import app.mauri.sukrara.share.owner.PendingRequest

/**
 * Someone used an invite and asks to follow. The wearer compares the code with the one on the follower's phone, then
 * Allow (haptic) or Deny. Dismissing keeps the request waiting. [request.others] > 0: more phones tried the same link.
 */
@Composable
fun ApproveDialog(
    request: PendingRequest,
    onAllow: () -> Unit,
    onDeny: () -> Unit,
    onLater: () -> Unit,
) {
    val haptics = rememberHaptics()
    val name = request.name.ifBlank { stringResource(R.string.sh_approve_unnamed) }
    AlertDialog(
        onDismissRequest = onLater,
        title = { Text(stringResource(R.string.sh_approve_title, name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (request.label.isNotBlank()) Text(stringResource(R.string.sh_approve_invite_for, request.label))
                Text(stringResource(R.string.sh_approve_code_intro))
                val spaced = request.code.toList().joinToString(" ")
                Text(
                    request.code,
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.semantics { contentDescription = spaced },
                )
                if (request.others > 0) {
                    Text(
                        pluralStringResource(R.plurals.sh_approve_others, request.others, request.others),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Text(stringResource(R.string.sh_approve_they_see), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                haptics.confirm()
                onAllow()
            }) { Text(stringResource(R.string.sh_approve_allow)) }
        },
        dismissButton = {
            TextButton(onClick = {
                haptics.reject()
                onDeny()
            }) { Text(stringResource(R.string.sh_approve_deny)) }
        },
    )
}
