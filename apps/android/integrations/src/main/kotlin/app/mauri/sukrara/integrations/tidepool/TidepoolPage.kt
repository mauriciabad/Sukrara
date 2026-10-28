// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.integrations.tidepool

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.mauri.sukrara.core.model.Role
import app.mauri.sukrara.designsystem.rows.DangerRow
import app.mauri.sukrara.designsystem.rows.FooterText
import app.mauri.sukrara.designsystem.rows.InfoRow
import app.mauri.sukrara.designsystem.rows.SettingsGroup
import app.mauri.sukrara.designsystem.rows.SwitchRow
import app.mauri.sukrara.designsystem.rows.ValueRow
import app.mauri.sukrara.integrations.R
import app.mauri.sukrara.integrations.ui.IntroText
import app.mauri.sukrara.integrations.ui.uploadStatusText
import app.mauri.sukrara.integrations.upload.UploadStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant

/** The sign-in dialog's state. */
sealed interface TpSignInUi {
    data object Idle : TpSignInUi

    data object Busy : TpSignInUi

    data class Done(val result: TpSignIn) : TpSignInUi
}

class TidepoolViewModel(
    private val uploader: TidepoolUploader,
    private val now: () -> Instant,
) : ViewModel() {
    val page: StateFlow<TpPageState?> = uploader.page.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    private val _signIn = MutableStateFlow<TpSignInUi>(TpSignInUi.Idle)
    val signIn: StateFlow<TpSignInUi> = _signIn.asStateFlow()

    fun actions() =
        TidepoolActions(
            change = { t -> viewModelScope.launch { runCatching { uploader.change(t) } } },
            signIn = { email, password ->
                _signIn.value = TpSignInUi.Busy
                viewModelScope.launch {
                    val r = runCatching { uploader.signIn(email, password) }.getOrElse { TpSignIn.Failed("not saved") }
                    _signIn.value = TpSignInUi.Done(r)
                }
            },
            signOut = { viewModelScope.launch { runCatching { uploader.signOut() } } },
            reset = { _signIn.value = TpSignInUi.Idle },
        )

    fun nowInstant() = now()
}

class TidepoolActions(
    val change: ((TpSettings) -> TpSettings) -> Unit = {},
    val signIn: (String, String) -> Unit = { _, _ -> },
    val signOut: () -> Unit = {},
    val reset: () -> Unit = {},
)

@Composable
fun TidepoolPage(
    viewModel: TidepoolViewModel,
    modifier: Modifier = Modifier,
) {
    val page by viewModel.page.collectAsStateWithLifecycle()
    val signIn by viewModel.signIn.collectAsStateWithLifecycle()
    var editing by rememberSaveable { mutableStateOf(false) }
    page?.let {
        TidepoolContent(it, signIn, viewModel.nowInstant(), viewModel.actions(), editing, { v -> editing = v }, modifier)
    }
}

/** The upload's state in this page's words (an account, not a site): also for Share with apps' Tidepool row. */
@Composable
fun tidepoolStatusText(
    status: UploadStatus,
    now: Instant,
): String =
    when (status) {
        UploadStatus.Setup -> stringResource(R.string.in_tp_status_setup)
        UploadStatus.AuthFailed -> stringResource(R.string.in_tp_status_auth)
        else -> uploadStatusText(status, now)
    }

/**
 * Settings › Share with apps › Tidepool: the account (sign in / signed in as), the main switch (turning it off always
 * works), the status (*Sign in again* when Tidepool ended the session), on a follower's phone the separate *Upload the
 * person you follow*, then glucose and entries, *Sign out*.
 */
@Composable
fun TidepoolContent(
    state: TpPageState,
    signIn: TpSignInUi,
    now: Instant,
    actions: TidepoolActions,
    editing: Boolean,
    setEditing: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) = Column(modifier) {
    val s = state.settings
    val follower = state.role == Role.Follower
    val on = s.on && state.signedIn
    val open = {
        actions.reset()
        setEditing(true)
    }
    IntroText(stringResource(if (follower) R.string.in_tp_intro_follower else R.string.in_tp_intro))
    SettingsGroup {
        ValueRow(
            stringResource(R.string.in_tp_account),
            if (state.signedIn) {
                state.email.ifEmpty {
                    stringResource(
                        R.string.in_tp_signed_in,
                    )
                }
            } else {
                stringResource(R.string.in_tp_sign_in)
            },
            open,
        )
    }
    SettingsGroup {
        SwitchRow(
            stringResource(R.string.in_tp_main),
            s.on,
            { v -> actions.change { it.copy(on = v) } },
            // switching off always works (also once Tidepool ended the session); switching on needs an account
            enabled = s.on || state.signedIn,
            summary = if (!state.signedIn && !s.on) stringResource(R.string.in_tp_main_needs_account) else null,
        )
        if (s.on) {
            val status = state.status
            when {
                status == UploadStatus.AuthFailed || status == UploadStatus.Setup -> {
                    ValueRow(stringResource(R.string.in_ns_status), tidepoolStatusText(status, now), open)
                }

                else -> {
                    InfoRow(
                        stringResource(R.string.in_ns_status),
                        Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        if (state.needsChoice) stringResource(R.string.in_tp_status_choice) else tidepoolStatusText(status, now),
                    )
                }
            }
        }
    }
    val off = if (on) null else stringResource(R.string.in_ns_unavailable)
    if (follower) {
        SettingsGroup {
            SwitchRow(
                stringResource(R.string.in_tp_follow),
                s.uploadFollowed,
                { v -> actions.change { it.copy(uploadFollowed = v) } },
                // switching off always works
                enabled = on || s.uploadFollowed,
                summary = off ?: stringResource(if (s.uploadFollowed) R.string.in_tp_follow_on else R.string.in_tp_follow_off),
            )
        }
    }
    SettingsGroup(title = stringResource(R.string.in_ns_what)) {
        SwitchRow(stringResource(R.string.in_tp_glucose), s.glucose, { v ->
            actions.change { it.copy(glucose = v) }
        }, enabled = on, summary = off ?: stringResource(R.string.in_tp_glucose_summary))
        SwitchRow(stringResource(R.string.in_ns_entries), s.treatments, { v ->
            actions.change { it.copy(treatments = v) }
        }, enabled = on, summary = off ?: stringResource(R.string.in_ns_entries_summary))
    }
    if (state.signedIn) {
        SettingsGroup {
            DangerRow(
                stringResource(R.string.in_tp_sign_out),
                stringResource(R.string.in_tp_sign_out_title),
                stringResource(R.string.in_tp_sign_out_text),
                stringResource(R.string.in_tp_sign_out),
                actions.signOut,
            )
        }
    }
    FooterText(stringResource(R.string.in_tp_footer))
    if (editing) SignInDialog(state.email, signIn, actions) { setEditing(false) }
}

/** Email + password (the password stays in this dialog only). */
@Composable
private fun SignInDialog(
    initialEmail: String,
    ui: TpSignInUi,
    actions: TidepoolActions,
    onClose: () -> Unit,
) {
    var email by rememberSaveable { mutableStateOf(initialEmail) }
    // never in saved state: a rotation asks for it again
    var password by remember { mutableStateOf("") }
    val reset by rememberUpdatedState(actions.reset)
    LaunchedEffect(Unit) { if (ui !is TpSignInUi.Busy) reset() }
    // signed in: close (also after the activity was recreated mid sign-in)
    val close by rememberUpdatedState(onClose)
    LaunchedEffect(ui) { if (ui is TpSignInUi.Done && ui.result == TpSignIn.Done) close() }
    val busy = ui == TpSignInUi.Busy
    val ready = email.isNotBlank() && password.isNotEmpty() && !busy
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.in_tp_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    email,
                    {
                        email = it
                        actions.reset()
                    },
                    label = { Text(stringResource(R.string.in_tp_email)) },
                    singleLine = true,
                    keyboardOptions =
                        KeyboardOptions(keyboardType = KeyboardType.Email, autoCorrectEnabled = false, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.EmailAddress },
                )
                OutlinedTextField(
                    password,
                    {
                        password = it
                        actions.reset()
                    },
                    label = { Text(stringResource(R.string.in_tp_password)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions =
                        KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Done),
                    supportingText = { Text(stringResource(R.string.in_tp_password_note)) },
                    modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.Password },
                )
                val result =
                    when (ui) {
                        TpSignInUi.Idle -> {
                            null
                        }

                        TpSignInUi.Busy -> {
                            stringResource(R.string.in_tp_signing_in)
                        }

                        is TpSignInUi.Done -> {
                            when (val r = ui.result) {
                                TpSignIn.Done -> null
                                TpSignIn.BadCredentials -> stringResource(R.string.in_tp_bad_credentials)
                                is TpSignIn.Failed -> stringResource(R.string.in_tp_failed, r.why)
                            }
                        }
                    }
                if (result != null) {
                    Text(
                        result,
                        Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        color = if (busy) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { actions.signIn(email, password) }, enabled = ready) {
                Text(stringResource(R.string.in_tp_sign_in_action))
            }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.in_cancel)) } },
    )
}
