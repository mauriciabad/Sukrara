// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.integrations.nightscout

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.mauri.sukrara.designsystem.dialog.Choice
import app.mauri.sukrara.designsystem.dialog.ChoiceDialog
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

/** The Test button's state in the site dialog. */
sealed interface NsTestUi {
    data object Idle : NsTestUi

    data object Testing : NsTestUi

    data class Done(val result: NsTestResult) : NsTestUi
}

class NightscoutViewModel(
    private val uploader: NightscoutUploader,
    private val wearer: Boolean,
    private val now: () -> Instant,
) : ViewModel() {
    val page: StateFlow<NsPageState?> = uploader.page.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    private val _test = MutableStateFlow<NsTestUi>(NsTestUi.Idle)
    val test: StateFlow<NsTestUi> = _test.asStateFlow()

    fun actions(close: () -> Unit = {}) =
        NightscoutActions(
            change = { t -> viewModelScope.launch { uploader.change(t) } },
            acceptConflict = { viewModelScope.launch { uploader.acceptConflict() } },
            remove = { viewModelScope.launch { uploader.remove() } },
            test = { url, kind, cred, api ->
                _test.value = NsTestUi.Testing
                viewModelScope.launch { _test.value = NsTestUi.Done(uploader.test(url, kind, cred, api)) }
            },
            save = { url, kind, cred, api ->
                viewModelScope.launch {
                    uploader.configure(url, kind, cred, api)
                    close()
                }
            },
            resetTest = { _test.value = NsTestUi.Idle },
        )

    fun nowInstant() = now()

    val isWearer get() = wearer
}

class NightscoutActions(
    val change: ((NsSettings) -> NsSettings) -> Unit = {},
    val acceptConflict: () -> Unit = {},
    val remove: () -> Unit = {},
    val test: (String, NsCredentialKind, String?, NsApi) -> Unit = { _, _, _, _ -> },
    val save: (String, NsCredentialKind, String?, NsApi) -> Unit = { _, _, _, _ -> },
    val resetTest: () -> Unit = {},
)

/** The stateful page (the app wraps it in its settings page frame). */
@Composable
fun NightscoutPage(
    viewModel: NightscoutViewModel,
    modifier: Modifier = Modifier,
) {
    val page by viewModel.page.collectAsStateWithLifecycle()
    val test by viewModel.test.collectAsStateWithLifecycle()
    page?.let { NightscoutContent(it, viewModel.isWearer, test, viewModel.nowInstant(), viewModel.actions(), modifier) }
}

/**
 * Settings › Share with apps › Nightscout upload: the site (dialog with Test), the main switch, the status with its
 * exit (*Upload anyway* when paused, the site dialog when the token was refused), what to send, the API, *Remove this
 * site*. A follower phone sees why it can't upload.
 */
@Composable
fun NightscoutContent(
    state: NsPageState,
    wearer: Boolean,
    test: NsTestUi,
    now: Instant,
    actions: NightscoutActions,
    modifier: Modifier = Modifier,
    startEditing: Boolean = false,
) = Column(modifier) {
    var editing by rememberSaveable { mutableStateOf(startEditing) }
    var choosingApi by rememberSaveable { mutableStateOf(false) }
    val s = state.settings
    val on = s.on && wearer && state.configured
    IntroText(stringResource(R.string.in_ns_intro))
    SettingsGroup {
        ValueRow(stringResource(R.string.in_ns_site), s.url.ifEmpty { stringResource(R.string.in_ns_site_none) }, {
            actions.resetTest()
            editing = true
        })
    }
    SettingsGroup {
        SwitchRow(
            stringResource(R.string.in_ns_main),
            on,
            { v -> actions.change { it.copy(on = v) } },
            enabled = wearer && state.configured,
            summary =
                when {
                    !wearer -> stringResource(R.string.in_status_follower)
                    !state.configured -> stringResource(R.string.in_ns_main_needs_site)
                    else -> null
                },
        )
        if (on) {
            val status = state.status
            if (status == UploadStatus.AuthFailed) {
                ValueRow(stringResource(R.string.in_ns_status), uploadStatusText(status, now), {
                    actions.resetTest()
                    editing = true
                })
            } else {
                InfoRow(
                    stringResource(R.string.in_ns_status),
                    Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    uploadStatusText(status, now),
                )
            }
            if (status is UploadStatus.Paused) {
                ValueRow(stringResource(R.string.in_ns_anyway), stringResource(R.string.in_ns_anyway_summary), actions.acceptConflict)
            }
        }
    }
    SettingsGroup(title = stringResource(R.string.in_ns_what)) {
        val off = if (on) null else stringResource(R.string.in_ns_unavailable)
        SwitchRow(stringResource(R.string.in_ns_readings), s.readings, { v ->
            actions.change { it.copy(readings = v) }
        }, enabled = on, summary = off ?: stringResource(R.string.in_ns_readings_summary))
        SwitchRow(stringResource(R.string.in_ns_entries), s.treatments, { v ->
            actions.change { it.copy(treatments = v) }
        }, enabled = on, summary = off ?: stringResource(R.string.in_ns_entries_summary))
        SwitchRow(stringResource(R.string.in_ns_sensor), s.sensor, { v ->
            actions.change { it.copy(sensor = v) }
        }, enabled = on, summary = off ?: stringResource(R.string.in_ns_sensor_summary))
        SwitchRow(stringResource(R.string.in_ns_battery), s.battery, { v ->
            actions.change { it.copy(battery = v) }
        }, enabled = on, summary = off ?: stringResource(R.string.in_ns_battery_summary))
    }
    SettingsGroup(title = stringResource(R.string.in_ns_advanced)) {
        ValueRow(stringResource(R.string.in_ns_api), apiLabel(s.api), { choosingApi = true }, enabled = state.configured)
        if (state.configured) {
            DangerRow(
                stringResource(R.string.in_ns_remove),
                stringResource(R.string.in_ns_remove_title),
                stringResource(R.string.in_ns_remove_text),
                stringResource(R.string.in_ns_remove_action),
                actions.remove,
            )
        }
    }
    FooterText(stringResource(R.string.in_ns_footer))
    if (choosingApi) {
        val token = state.credential == NsCredentialKind.Token
        ChoiceDialog(
            stringResource(R.string.in_ns_api),
            listOf(
                Choice(stringResource(R.string.in_ns_api_v1), NsApi.V1, stringResource(R.string.in_ns_api_v1_sub)),
                Choice(stringResource(R.string.in_ns_api_v3), NsApi.V3, stringResource(R.string.in_ns_api_v3_sub), enabled = token),
            ),
            s.api,
            { choosingApi = false },
        ) { api -> actions.change { it.copy(api = api) } }
    }
    if (editing) {
        SiteDialog(state, test, actions) { editing = false }
    }
}

@Composable
private fun apiLabel(api: NsApi) = stringResource(if (api == NsApi.V3) R.string.in_ns_api_v3 else R.string.in_ns_api_v1)

/** The site dialog: address, token or secret (kept when left empty), Test (read-only) and Save. */
@Composable
private fun SiteDialog(
    state: NsPageState,
    test: NsTestUi,
    actions: NightscoutActions,
    onClose: () -> Unit,
) {
    var url by rememberSaveable { mutableStateOf(state.settings.url) }
    var kind by rememberSaveable { mutableStateOf(state.credential ?: NsCredentialKind.Token) }
    // the secret is never kept in saved state: a rotation asks for it again
    var credential by androidx.compose.runtime.remember { mutableStateOf("") }
    val site = NsSite.parse(url)
    val problem =
        when {
            url.isBlank() -> null
            site.problem == NsSiteProblem.Https -> stringResource(R.string.in_ns_problem_https)
            site.problem == NsSiteProblem.Url -> stringResource(R.string.in_ns_problem_url)
            else -> null
        }
    val canKeep = state.credential == kind
    val ready = url.isNotBlank() && site.problem == null && (credential.isNotBlank() || canKeep)
    val api = state.settings.api
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.in_ns_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    url,
                    {
                        url = it
                        actions.resetTest()
                    },
                    label = { Text(stringResource(R.string.in_ns_dialog_url)) },
                    singleLine = true,
                    isError = problem != null,
                    supportingText = problem?.let { { Text(it) } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                Column(Modifier.selectableGroup()) {
                    for (k in NsCredentialKind.entries) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .selectable(kind == k, role = Role.RadioButton) {
                                    kind = k
                                    actions.resetTest()
                                },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(kind == k, onClick = null)
                            Text(
                                stringResource(
                                    if (k ==
                                        NsCredentialKind.Token
                                    ) {
                                        R.string.in_ns_dialog_token
                                    } else {
                                        R.string.in_ns_dialog_secret
                                    },
                                ),
                                Modifier.semantics {},
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    }
                }
                OutlinedTextField(
                    credential,
                    {
                        credential = it
                        actions.resetTest()
                    },
                    label = { Text(stringResource(R.string.in_ns_dialog_credential)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    supportingText = if (canKeep) ({ Text(stringResource(R.string.in_ns_dialog_keep)) }) else null,
                    modifier = Modifier.fillMaxWidth(),
                )
                val result =
                    when (test) {
                        NsTestUi.Idle -> {
                            null
                        }

                        NsTestUi.Testing -> {
                            stringResource(R.string.in_ns_testing)
                        }

                        is NsTestUi.Done -> {
                            when (val r = test.result) {
                                is NsTestResult.Ok -> {
                                    r.version?.takeIf { it.isNotEmpty() }?.let { stringResource(R.string.in_ns_test_ok, it) }
                                        ?: stringResource(R.string.in_ns_test_ok_plain)
                                }

                                NsTestResult.AuthFailed -> {
                                    stringResource(R.string.in_ns_test_auth)
                                }

                                is NsTestResult.Failed -> {
                                    stringResource(R.string.in_ns_test_failed, r.why)
                                }
                            }
                        }
                    }
                if (result != null) {
                    Text(
                        result,
                        Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        color =
                            if (test is NsTestUi.Done && test.result !is NsTestResult.Ok) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        },
        confirmButton = {
            Row {
                TextButton(
                    onClick = { actions.test(url, kind, credential.ifBlank { null }, api) },
                    enabled =
                        ready && test != NsTestUi.Testing,
                ) {
                    Text(stringResource(R.string.in_ns_dialog_test))
                }
                TextButton(onClick = {
                    actions.save(url, kind, credential.ifBlank { null }, api)
                    onClose()
                }, enabled = ready) { Text(stringResource(R.string.in_ns_dialog_save)) }
            }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.in_cancel)) } },
    )
}
