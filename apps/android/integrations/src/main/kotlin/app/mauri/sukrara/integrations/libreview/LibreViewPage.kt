// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.integrations.libreview

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant

/** The sign-in dialog's state. */
sealed interface LvSignInUi {
    data object Idle : LvSignInUi

    data object Busy : LvSignInUi

    data class Done(val result: LvSignIn) : LvSignInUi
}

class LibreViewViewModel(
    private val uploader: LibreViewUploader,
    /** The email of the LibreView account the sensor screens signed in with, to start from. */
    private val accountEmail: Flow<String?>,
    private val wearer: Boolean,
    private val now: () -> Instant,
) : ViewModel() {
    val page: StateFlow<LvPageState?> = uploader.page.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    private val _signIn = MutableStateFlow<LvSignInUi>(LvSignInUi.Idle)
    val signIn: StateFlow<LvSignInUi> = _signIn.asStateFlow()
    private val suggested = MutableStateFlow("")
    val suggestedEmail: StateFlow<String> = suggested.asStateFlow()

    init {
        viewModelScope.launch { suggested.value = runCatching { accountEmail.first() }.getOrNull().orEmpty() }
    }

    fun actions() =
        LibreViewActions(
            change = { t -> viewModelScope.launch { runCatching { uploader.change(t) } } },
            signIn = { email, password, takeOver ->
                _signIn.value = LvSignInUi.Busy
                viewModelScope.launch {
                    val r = runCatching { uploader.signIn(email, password, takeOver) }.getOrElse { LvSignIn.Failed("not saved") }
                    _signIn.value = LvSignInUi.Done(r)
                }
            },
            signOut = { viewModelScope.launch { runCatching { uploader.signOut() } } },
            reset = { _signIn.value = LvSignInUi.Idle },
        )

    fun nowInstant() = now()

    val isWearer get() = wearer
}

class LibreViewActions(
    val change: ((LvSettings) -> LvSettings) -> Unit = {},
    val signIn: (String, String, Boolean) -> Unit = { _, _, _ -> },
    val signOut: () -> Unit = {},
    val reset: () -> Unit = {},
)

@Composable
fun LibreViewPage(
    viewModel: LibreViewViewModel,
    modifier: Modifier = Modifier,
) {
    val page by viewModel.page.collectAsStateWithLifecycle()
    val signIn by viewModel.signIn.collectAsStateWithLifecycle()
    val suggested by viewModel.suggestedEmail.collectAsStateWithLifecycle()
    var editing by rememberSaveable { mutableStateOf(false) }
    page?.let {
        LibreViewContent(
            it,
            viewModel.isWearer,
            signIn,
            suggested,
            viewModel.nowInstant(),
            viewModel.actions(),
            editing,
            { v -> editing = v },
            modifier,
        )
    }
}

/** One upload state in this page's words (the account, not a site). */
@Composable
fun libreViewStatusText(
    status: UploadStatus,
    now: Instant,
): String =
    when (status) {
        UploadStatus.Setup -> stringResource(R.string.in_lv_status_setup)
        UploadStatus.AuthFailed -> stringResource(R.string.in_lv_status_auth)
        else -> uploadStatusText(status, now)
    }

/**
 * Settings › Share with apps › LibreView: the account (sign in / signed in as), the main switch (turning it off always
 * works), the status (*Sign in again* when the token stopped working), glucose and entries, *Sign out*.
 */
@Composable
fun LibreViewContent(
    state: LvPageState,
    wearer: Boolean,
    signIn: LvSignInUi,
    suggestedEmail: String,
    now: Instant,
    actions: LibreViewActions,
    editing: Boolean,
    setEditing: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) = Column(modifier) {
    val s = state.settings
    val on = s.on && wearer && state.signedIn
    IntroText(stringResource(R.string.in_lv_intro))
    SettingsGroup {
        ValueRow(
            stringResource(R.string.in_lv_account),
            if (state.signedIn) {
                state.email.ifEmpty {
                    stringResource(
                        R.string.in_lv_signed_in,
                    )
                }
            } else {
                stringResource(R.string.in_lv_sign_in)
            },
            {
                actions.reset()
                setEditing(true)
            },
            enabled = wearer,
        )
    }
    SettingsGroup {
        SwitchRow(
            stringResource(R.string.in_lv_main),
            s.on,
            { v -> actions.change { it.copy(on = v) } },
            // switching off always works (signed out, or no longer the wearer); switching on needs an account
            enabled = s.on || (wearer && state.signedIn),
            summary =
                when {
                    !wearer -> stringResource(R.string.in_status_follower)
                    !state.signedIn -> stringResource(R.string.in_lv_main_needs_account)
                    else -> null
                },
        )
        if (on) {
            val status = state.status
            if (status == UploadStatus.AuthFailed) {
                ValueRow(stringResource(R.string.in_ns_status), libreViewStatusText(status, now), {
                    actions.reset()
                    setEditing(true)
                })
            } else {
                InfoRow(
                    stringResource(R.string.in_ns_status),
                    Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    libreViewStatusText(status, now),
                )
            }
        }
    }
    SettingsGroup(title = stringResource(R.string.in_ns_what)) {
        val off = if (on) null else stringResource(R.string.in_ns_unavailable)
        SwitchRow(stringResource(R.string.in_lv_glucose), s.glucose, { v ->
            actions.change { it.copy(glucose = v) }
        }, enabled = on, summary = off ?: glucoseSummary(state))
        SwitchRow(stringResource(R.string.in_ns_entries), s.treatments, { v ->
            actions.change { it.copy(treatments = v) }
        }, enabled = on, summary = off ?: stringResource(R.string.in_lv_entries_summary))
    }
    if (state.signedIn) {
        SettingsGroup {
            DangerRow(
                stringResource(R.string.in_lv_sign_out),
                stringResource(R.string.in_lv_sign_out_title),
                stringResource(R.string.in_lv_sign_out_text),
                stringResource(R.string.in_lv_sign_out_action),
                actions.signOut,
            )
        }
    }
    FooterText(stringResource(R.string.in_lv_footer))
    if (editing) {
        SignInDialog(state.email.ifEmpty { suggestedEmail }, signIn, actions) { setEditing(false) }
    }
}

@Composable
private fun glucoseSummary(state: LvPageState): String {
    val from = state.glucoseFrom ?: return stringResource(R.string.in_lv_glucose_summary)
    val ctx = LocalContext.current
    val at =
        DateUtils.formatDateTime(
            ctx,
            from.toEpochMilli(),
            DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH,
        )
    return stringResource(R.string.in_lv_glucose_from, at)
}

/** Email + password (the password stays in this dialog only), then *Take over* if another device uploads. */
@Composable
private fun SignInDialog(
    initialEmail: String,
    ui: LvSignInUi,
    actions: LibreViewActions,
    onClose: () -> Unit,
) {
    var email by rememberSaveable { mutableStateOf(initialEmail) }
    // never in saved state: a rotation asks for it again
    var password by remember { mutableStateOf("") }
    // a (re)created dialog starts from the fields (after a rotation the password is gone, so no lone Take over)
    val reset by rememberUpdatedState(actions.reset)
    LaunchedEffect(Unit) { if (ui !is LvSignInUi.Busy) reset() }
    // signed in: close (also after the activity was recreated mid sign-in)
    val close by rememberUpdatedState(onClose)
    LaunchedEffect(ui) { if (ui is LvSignInUi.Done && ui.result == LvSignIn.Done) close() }
    val busy = ui == LvSignInUi.Busy
    val otherDevice = ui is LvSignInUi.Done && ui.result == LvSignIn.OtherDevice
    val ready = email.isNotBlank() && password.isNotEmpty() && !busy
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(if (otherDevice) R.string.in_lv_take_over_title else R.string.in_lv_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (otherDevice) {
                    Text(stringResource(R.string.in_lv_take_over_text), style = MaterialTheme.typography.bodyMedium)
                } else {
                    OutlinedTextField(
                        email,
                        {
                            email = it
                            actions.reset()
                        },
                        label = { Text(stringResource(R.string.in_lv_email)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, autoCorrectEnabled = false),
                        modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.EmailAddress },
                    )
                    OutlinedTextField(
                        password,
                        {
                            password = it
                            actions.reset()
                        },
                        label = { Text(stringResource(R.string.in_lv_password)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                        supportingText = { Text(stringResource(R.string.in_lv_password_note)) },
                        modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.Password },
                    )
                }
                val result =
                    when (ui) {
                        LvSignInUi.Idle -> {
                            null
                        }

                        LvSignInUi.Busy -> {
                            stringResource(R.string.in_lv_signing_in)
                        }

                        is LvSignInUi.Done -> {
                            when (val r = ui.result) {
                                LvSignIn.Done, LvSignIn.OtherDevice -> null
                                LvSignIn.BadCredentials -> stringResource(R.string.in_lv_bad_credentials)
                                is LvSignIn.Failed -> stringResource(R.string.in_lv_failed, r.why)
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
            Row {
                if (otherDevice) {
                    TextButton(onClick = { actions.signIn(email, password, true) }, enabled = ready) {
                        Text(stringResource(R.string.in_lv_take_over))
                    }
                } else {
                    TextButton(onClick = { actions.signIn(email, password, false) }, enabled = ready) {
                        Text(stringResource(R.string.in_lv_sign_in_action))
                    }
                }
            }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.in_cancel)) } },
    )
}
