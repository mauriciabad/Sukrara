// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.mauri.sukrara.R
import app.mauri.sukrara.nightscout.Nightscout
import app.mauri.sukrara.nightscout.NsApi
import app.mauri.sukrara.nightscout.NsFailure
import app.mauri.sukrara.nightscout.NsSite
import app.mauri.sukrara.nightscout.Outcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Share with apps' Nightscout upload row (wearer only). */
@Composable
internal fun nightscoutUploadSummary(): String {
    val ctx = LocalContext.current
    remember { Nightscout.init(ctx) }
    val s by Nightscout.settings.collectAsState()
    val host = runCatching { java.net.URI(s.site.root).host }.getOrNull().orEmpty()
    return when {
        !s.configured -> stringResource(R.string.apps_libreview_not_set_up)
        s.on -> stringResource(R.string.ns_upload_uploading, host)
        else -> stringResource(R.string.settings_off) + " · " + host
    }
}

/**
 * Nightscout upload (docs/25): the site, the main switch, the status (the shared upload status line), what to send and
 * the API. Off by default; the main switch only once a site and a token / secret are saved.
 */
@Composable
fun NightscoutUploadSettings(onBack: () -> Unit) {
    val ctx = LocalContext.current
    remember { Nightscout.init(ctx) }
    val s by Nightscout.settings.collectAsState()
    val st by Nightscout.status.collectAsState()
    var editing by remember { mutableStateOf(false) }
    var choosingApi by remember { mutableStateOf(false) }
    val on = s.on && s.configured
    val off = stringResource(R.string.upload_unavailable)
    SettingsPage(stringResource(R.string.ns_upload_title), onBack) {
        SettingsIntro(stringResource(R.string.ns_upload_intro))
        SettingsGroup {
            value(stringResource(R.string.ns_upload_site), if (s.configured) s.site.root else stringResource(R.string.apps_libreview_not_set_up),
                icon = R.drawable.ic_sym_link) { editing = true }
            if (s.configured && s.site.insecure) info(stringResource(R.string.ns_upload_insecure), icon = R.drawable.ic_sym_error)
        }
        SettingsMainSwitch(stringResource(R.string.ns_upload_main_switch), on, enabled = s.configured) { Nightscout.setOn(ctx, it) }
        SettingsGroup {
            info(stringResource(R.string.upload_status_title), icon = R.drawable.ic_sym_cloud_sync, summary = when {
                !s.configured -> stringResource(R.string.ns_upload_status_setup)
                !on -> stringResource(R.string.settings_off)
                st.kind == "setup" -> stringResource(R.string.ns_upload_status_setup)
                st.kind == "sign_in" -> stringResource(R.string.ns_upload_status_sign_in)
                st.kind == "paused" -> stringResource(R.string.ns_upload_status_paused, st.error.orEmpty())
                else -> uploadStatusText(st)
            })
            if (on && st.kind == "paused") action(stringResource(R.string.ns_upload_anyway), stringResource(R.string.ns_upload_anyway_summary),
                icon = R.drawable.ic_sym_cloud_upload) { Nightscout.acceptConflict(ctx) }
        }
        SettingsGroup(stringResource(R.string.ns_upload_what)) {
            switch(stringResource(R.string.ns_upload_readings), s.readings, enabled = on, icon = R.drawable.ic_sym_monitoring,
                summary = if (on) stringResource(R.string.ns_upload_readings_summary) else off) { Nightscout.setReadings(ctx, it) }
            switch(stringResource(R.string.ns_upload_entries), s.treatments, enabled = on, icon = R.drawable.ic_sym_edit_note,
                summary = if (on) stringResource(R.string.ns_upload_entries_summary) else off) { Nightscout.setTreatments(ctx, it) }
            switch(stringResource(R.string.ns_upload_sensor), s.sensor, enabled = on, icon = R.drawable.ic_dia_sensor,
                summary = if (on) stringResource(R.string.ns_upload_sensor_summary) else off) { Nightscout.setSensor(ctx, it) }
            switch(stringResource(R.string.ns_upload_battery), s.battery, enabled = on, icon = R.drawable.ic_sym_battery_full,
                summary = if (on) stringResource(R.string.ns_upload_battery_summary) else off) { Nightscout.setBattery(ctx, it) }
        }
        if (s.configured) SettingsGroup(stringResource(R.string.ns_upload_advanced)) {
            value(stringResource(R.string.ns_upload_api), stringResource(if (s.api == NsApi.V3 && s.tokenKind) R.string.ns_upload_api_v3 else R.string.ns_upload_api_v1), icon = R.drawable.ic_sym_api) {
                choosingApi = true
            }
            action(stringResource(R.string.ns_upload_remove), icon = R.drawable.ic_sym_delete) { Nightscout.remove(ctx) }
        }
        SettingsFooter(stringResource(R.string.ns_upload_footer))
    }
    if (editing) NightscoutSiteDialog(s, onClose = { editing = false })
    if (choosingApi) ChoiceDialog(stringResource(R.string.ns_upload_api), listOf(
        Choice(stringResource(R.string.ns_upload_api_v1), NsApi.V1, stringResource(R.string.ns_upload_api_v1_sub)),
        Choice(stringResource(R.string.ns_upload_api_v3), NsApi.V3, stringResource(R.string.ns_upload_api_v3_sub), enabled = s.tokenKind),
    ), if (s.tokenKind) s.api else NsApi.V1, onClose = { choosingApi = false }) { Nightscout.configure(ctx, s.url, s.tokenKind, null, it) }
}

/** Address + access token or API secret, with a connection test; the credential is never shown again once saved. */
@Composable
private fun NightscoutSiteDialog(s: Nightscout.Settings, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf(s.url) }
    var token by remember { mutableStateOf(s.tokenKind) }
    var credential by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    val site = NsSite.parse(url)
    val urlError = when (site.problem) {
        null -> null
        "https" -> stringResource(R.string.ns_site_err_https)
        else -> stringResource(R.string.ns_site_err_url)
    }.takeIf { url.isNotBlank() }
    val canSave = url.isNotBlank() && site.problem == null && (credential.isNotBlank() || (s.hasCredential && token == s.tokenKind))
    val refused = stringResource(R.string.ns_site_err_auth)
    val okText = stringResource(R.string.ns_site_ok, "%s")
    val errText = stringResource(R.string.ns_site_err, "%s")
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.ns_site_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(url, { url = it; result = null }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text(stringResource(R.string.ns_site_url)) }, isError = urlError != null,
                    supportingText = urlError?.let { { Text(it) } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next))
                if (site.insecure) Text(stringResource(R.string.ns_upload_insecure), color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
                for ((value, label) in listOf(true to R.string.ns_site_kind_token, false to R.string.ns_site_kind_secret)) {
                    Row(Modifier.fillMaxWidth().selectable(token == value, onClick = { token = value; result = null }, role = Role.RadioButton),
                        verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(token == value, onClick = null)
                        Text(stringResource(label), Modifier.padding(start = 8.dp))
                    }
                }
                OutlinedTextField(credential, { credential = it; result = null }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text(stringResource(R.string.ns_site_credential)) },
                    placeholder = if (s.hasCredential && token == s.tokenKind) { { Text(stringResource(R.string.ns_site_credential_keep)) } } else null,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done))
                result?.let { (ok, text) ->
                    Text(text, color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(enabled = canSave && !busy, onClick = {
                Nightscout.configure(ctx, url, token, credential.takeIf { it.isNotBlank() }, if (token) s.api else NsApi.V1)
                credential = ""; onClose()
            }) { Text(stringResource(R.string.ns_site_save)) }
        },
        dismissButton = {
            Row {
                OutlinedButton(enabled = !busy && site.problem == null && url.isNotBlank() && credential.isNotBlank(), onClick = {
                    busy = true; result = null
                    scope.launch {
                        val r = withContext(Dispatchers.IO) { runCatching { Nightscout.test(url, token, credential, if (token) s.api else NsApi.V1) } }
                        busy = false
                        result = r.fold({ true to okText.format(it.ifEmpty { "?" }) }, { e ->
                            false to if (e is NsFailure && e.outcome is Outcome.AuthFailed) refused else errText.format(UserErrors.of(ctx, e))
                        })
                    }
                }) { Text(stringResource(if (busy) R.string.ns_site_testing else R.string.ns_site_test)) }
                TextButton(onClick = onClose) { Text(stringResource(R.string.treatment_cancel)) }
            }
        },
    )
}
