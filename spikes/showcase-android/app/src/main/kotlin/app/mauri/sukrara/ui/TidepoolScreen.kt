// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.mauri.sukrara.R
import app.mauri.sukrara.tidepool.Tidepool
import app.mauri.sukrara.tidepool.TidepoolClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Share with apps' Tidepool row (both roles). */
@Composable
internal fun tidepoolSummary(): String {
    val ctx = LocalContext.current
    remember { Tidepool.init(ctx) }
    val s by Tidepool.settings.collectAsState()
    return when {
        !s.signedIn -> stringResource(R.string.apps_libreview_not_set_up)
        s.on -> stringResource(R.string.apps_libreview_uploading, s.email)
        else -> stringResource(R.string.settings_off) + " · " + s.email
    }
}

/**
 * Tidepool (docs/27): the account, then the main switch, then what goes up. A follower gets one more, separate
 * decision: uploading the person it follows (off by default; without it nothing is uploaded).
 */
@Composable
fun TidepoolSettings(follower: Boolean, onBack: () -> Unit) {
    val ctx = LocalContext.current
    remember { Tidepool.init(ctx) }
    val s by Tidepool.settings.collectAsState()
    val st by Tidepool.status.collectAsState()
    var signIn by remember { mutableStateOf(false) }
    val on = s.on && s.signedIn
    val off = stringResource(R.string.tidepool_unavailable)
    SettingsPage("Tidepool", onBack) {
        SettingsIntro(stringResource(if (follower) R.string.tidepool_intro_follower else R.string.tidepool_intro_wearer))
        SettingsGroup {
            value(stringResource(R.string.tidepool_account), if (s.signedIn) s.email else stringResource(R.string.tidepool_not_signed_in),
                icon = R.drawable.ic_sym_person) { signIn = true }
        }
        SettingsMainSwitch(stringResource(R.string.tidepool_main_switch), on, enabled = s.signedIn) { Tidepool.setOn(ctx, it) }
        SettingsGroup {
            info(stringResource(R.string.tidepool_status), when {
                !s.signedIn && s.on -> stringResource(R.string.tidepool_status_sign_in)   // Tidepool ended the session
                !s.signedIn -> stringResource(R.string.tidepool_sign_in_first)
                !on -> stringResource(R.string.settings_off)
                else -> statusText(st)
            })
            if (follower) switch(stringResource(R.string.tidepool_follow), s.uploadFollowed, enabled = on,
                summary = if (!on) off else stringResource(if (s.uploadFollowed) R.string.tidepool_follow_on else R.string.tidepool_follow_off)) {
                Tidepool.setUploadFollowed(ctx, it)
            }
            switch(stringResource(R.string.tidepool_glucose), s.glucose, enabled = on,
                summary = if (on) stringResource(R.string.tidepool_glucose_summary) else off) { Tidepool.setGlucose(ctx, it) }
            switch(stringResource(R.string.tidepool_entries), s.treatments, enabled = on,
                summary = if (on) stringResource(R.string.tidepool_entries_summary) else off) { Tidepool.setTreatments(ctx, it) }
        }
        SettingsFooter(stringResource(R.string.tidepool_footer))
    }
    if (signIn) TidepoolSignIn(s, onClose = { signIn = false })
}

@Composable
private fun statusText(st: Tidepool.Status): String = when (st.kind) {
    "sign_in" -> stringResource(R.string.tidepool_status_sign_in)
    "not_allowed" -> stringResource(R.string.tidepool_status_not_allowed)
    "error" -> stringResource(R.string.tidepool_status_error, st.error.orEmpty())
    else -> if (st.lastOkMs > 0) stringResource(R.string.tidepool_status_ok, app.mauri.sukrara.graph.Clock.dateTime(st.lastOkMs / 1000))
        else stringResource(R.string.tidepool_status_waiting)
}

/** Email + password, used once for Tidepool's login; only the session is kept. Signed in: sign out. */
@Composable
private fun TidepoolSignIn(s: Tidepool.Settings, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf(s.email) }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val refused = stringResource(R.string.tidepool_error_credentials)
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.tidepool_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(email, { email = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.tidepool_email)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next))
                OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text(stringResource(R.string.onboarding_password)) }, visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done))
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            Button(enabled = !busy && email.isNotBlank() && password.isNotEmpty(), onClick = {
                busy = true; error = null
                scope.launch {
                    val r = withContext(Dispatchers.IO) { runCatching { Tidepool.signIn(ctx, email, password) } }
                    busy = false
                    r.onSuccess { password = ""; onClose() }
                        .onFailure { error = if (it is TidepoolClient.SignedOut) refused else it.message ?: it.javaClass.simpleName }
                }
            }) { Text(stringResource(if (busy) R.string.tidepool_signing_in else R.string.tidepool_sign_in)) }
        },
        dismissButton = {
            androidx.compose.foundation.layout.Row {
                if (s.signedIn) TextButton(onClick = { Tidepool.signOut(ctx); onClose() }) { Text(stringResource(R.string.tidepool_sign_out)) }
                TextButton(onClick = onClose) { Text(stringResource(R.string.treatment_cancel)) }
            }
        },
    )
}
