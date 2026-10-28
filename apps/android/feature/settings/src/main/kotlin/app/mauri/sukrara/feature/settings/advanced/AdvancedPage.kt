// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.settings.advanced

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mauri.sukrara.designsystem.haptics.rememberHaptics
import app.mauri.sukrara.designsystem.rows.CardRow
import app.mauri.sukrara.designsystem.rows.DangerRow
import app.mauri.sukrara.designsystem.rows.FooterText
import app.mauri.sukrara.designsystem.rows.InfoRow
import app.mauri.sukrara.designsystem.rows.NavigationRow
import app.mauri.sukrara.designsystem.rows.SettingsGroup
import app.mauri.sukrara.designsystem.rows.ValueRow
import app.mauri.sukrara.feature.settings.R
import app.mauri.sukrara.feature.settings.SettingsPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URI
import java.time.LocalDate
import app.mauri.sukrara.core.designsystem.R as DsR

/** One relay as the Advanced page lists it. */
data class RelayRow(val url: String, val connected: Boolean, val error: String? = null)

/**
 * What Settings › Advanced shows: the relays this phone talks to ([relays] empty = not sharing / following over the
 * internet), the sharing identity in short form ([identity], public only) and its key generation ([epoch]).
 */
data class AdvancedState(
    val follower: Boolean = false,
    val relays: List<RelayRow> = emptyList(),
    val identity: String? = null,
    val epoch: Int? = null,
)

/** What a settings file import did. */
sealed interface SettingsImport {
    data class Done(val count: Int, val alertsChanged: Boolean = false) : SettingsImport

    data object NotSettings : SettingsImport
}

/**
 * Settings › Advanced from the rest of the app: the relays and identity, *Check now* (reconnect to the relays at
 * once, which also fetches waiting requests), the diagnostic log and the settings file (export / import / reset).
 */
interface AdvancedPort {
    val state: Flow<AdvancedState>

    fun checkNow()

    /** The diagnostic log, oldest line first (nothing personal: DiagLog's rule). */
    suspend fun log(): String

    suspend fun exportSettings(): String

    suspend fun importSettings(text: String): SettingsImport

    suspend fun resetSettings()

    object None : AdvancedPort {
        override val state: Flow<AdvancedState> = flowOf(AdvancedState())

        override fun checkNow() = Unit

        override suspend fun log() = ""

        override suspend fun exportSettings() = "{}"

        override suspend fun importSettings(text: String): SettingsImport = SettingsImport.NotSettings

        override suspend fun resetSettings() = Unit
    }
}

/** The settings file's name: `sukrara-settings-2026-10-28.json`. */
internal fun settingsFileName(today: LocalDate): String = "sukrara-settings-$today.json"

/** Settings › Advanced with its file pickers; results show as a snackbar. */
@Composable
fun AdvancedScreen(
    port: AdvancedPort,
    today: () -> LocalDate,
    openLog: () -> Unit,
    /** The threshold review: after new alert settings (import, reset) the person checks what alerts them now. */
    openReview: () -> Unit,
    onBack: () -> Unit,
) {
    val state by port.state.collectAsStateWithLifecycle(AdvancedState())
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val res = context.resources

    fun say(text: String) {
        scope.launch { snackbar.showSnackbar(text, withDismissAction = true, duration = SnackbarDuration.Short) }
    }
    val exporter =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(MIME)) { uri ->
            uri ?: return@rememberLauncherForActivityResult
            scope.launch {
                val ok =
                    runCatching {
                        val text = withContext(NonCancellable) { port.exportSettings() }
                        withContext(Dispatchers.IO) {
                            checkNotNull(context.contentResolver.openOutputStream(uri)).use { it.write(text.encodeToByteArray()) }
                        }
                    }.isSuccess
                say(res.getString(if (ok) R.string.st_adv_exported else R.string.st_adv_write_failed))
            }
        }
    val importer =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri ?: return@rememberLauncherForActivityResult
            scope.launch {
                val text =
                    runCatching {
                        withContext(Dispatchers.IO) {
                            checkNotNull(context.contentResolver.openInputStream(uri)).use { readSmall(it) }
                        }
                    }.getOrNull()
                // applied to the end even if the page is left meanwhile (alert settings and their review flag together)
                val result =
                    when (text) {
                        null -> null
                        TOO_BIG -> SettingsImport.NotSettings
                        else -> withContext(NonCancellable) { port.importSettings(text) }
                    }
                val message =
                    when (result) {
                        null -> res.getString(R.string.st_adv_read_failed)
                        SettingsImport.NotSettings -> res.getString(R.string.st_adv_not_settings)
                        is SettingsImport.Done -> res.getQuantityString(R.plurals.st_adv_imported, result.count, result.count)
                    }
                say(message)
                if (result is SettingsImport.Done && result.alertsChanged) openReview()
            }
        }
    AdvancedContent(
        state,
        AdvancedActions(
            export = { exporter.launch(settingsFileName(today())) },
            import = { importer.launch(arrayOf(MIME, "text/plain", "*/*")) },
            reset = {
                scope.launch {
                    withContext(NonCancellable) { port.resetSettings() }
                    say(res.getString(R.string.st_adv_reset_done))
                    openReview()
                }
            },
            checkNow = {
                port.checkNow()
                say(res.getString(R.string.st_adv_checking))
            },
            openLog = openLog,
        ),
        snackbar,
        onBack,
    )
}

private const val MIME = "application/json"

/** A settings file is a few KB: anything over this isn't one (and isn't read into memory). */
private const val MAX_BYTES = 1_000_000

private const val TOO_BIG = "\u0000too big"

private fun readSmall(input: java.io.InputStream): String {
    val bytes = input.readNBytes(MAX_BYTES + 1)
    return if (bytes.size > MAX_BYTES) TOO_BIG else bytes.decodeToString()
}

class AdvancedActions(
    val export: () -> Unit = {},
    val import: () -> Unit = {},
    val reset: () -> Unit = {},
    val checkNow: () -> Unit = {},
    val openLog: () -> Unit = {},
)

/** Settings › Advanced: the settings file, the relays with *Check now*, the log and the identity. */
@Composable
fun AdvancedContent(
    state: AdvancedState,
    actions: AdvancedActions,
    snackbar: SnackbarHostState? = null,
    onBack: () -> Unit,
) {
    val haptics = rememberHaptics()
    SettingsPage(stringResource(R.string.st_adv_title), onBack, snackbar = snackbar) {
        SettingsGroup(title = stringResource(R.string.st_adv_file)) {
            NavigationRow(
                stringResource(R.string.st_adv_export),
                actions.export,
                summary = stringResource(R.string.st_adv_export_summary),
                icon = DsR.drawable.ds_ic_file_export,
            )
            NavigationRow(
                stringResource(R.string.st_adv_import),
                actions.import,
                summary = stringResource(R.string.st_adv_import_summary),
                icon = DsR.drawable.ds_ic_file_upload,
            )
            DangerRow(
                title = stringResource(R.string.st_adv_reset),
                confirmTitle = stringResource(R.string.st_adv_reset_title),
                confirmText = stringResource(R.string.st_adv_reset_text),
                confirmAction = stringResource(R.string.st_adv_reset_confirm),
                onConfirm = actions.reset,
                summary = stringResource(R.string.st_adv_reset_summary),
                icon = DsR.drawable.ds_ic_restart_alt,
            )
        }
        SettingsGroup(title = stringResource(R.string.st_adv_relays)) {
            if (state.relays.isEmpty()) {
                InfoRow(
                    stringResource(if (state.follower) R.string.st_adv_no_relays_follower else R.string.st_adv_no_relays),
                    icon = DsR.drawable.ds_ic_public,
                )
            } else {
                for (relay in state.relays) {
                    InfoRow(
                        hostOf(relay.url),
                        icon = DsR.drawable.ds_ic_hub,
                        summary =
                            when {
                                relay.connected -> stringResource(R.string.st_adv_relay_connected)
                                relay.error != null -> stringResource(R.string.st_adv_relay_error, relay.error)
                                else -> stringResource(R.string.st_adv_relay_connecting)
                            },
                    )
                }
                val connected = state.relays.count { it.connected }
                ValueRow(
                    stringResource(R.string.st_adv_check_now),
                    pluralStringResource(R.plurals.st_adv_relays_connected, state.relays.size, connected, state.relays.size),
                    {
                        haptics.confirm()
                        actions.checkNow()
                    },
                    icon = DsR.drawable.ds_ic_update,
                )
            }
        }
        SettingsGroup(title = stringResource(R.string.st_adv_diagnostics)) {
            NavigationRow(
                stringResource(R.string.st_adv_log),
                actions.openLog,
                summary = stringResource(R.string.st_adv_log_summary),
                icon = DsR.drawable.ds_ic_receipt_long,
            )
            state.identity?.let { id ->
                InfoRow(
                    stringResource(if (state.follower) R.string.st_adv_this_phone else R.string.st_adv_identity),
                    summary = state.epoch?.let { stringResource(R.string.st_adv_identity_epoch, id, it) } ?: id,
                    icon = DsR.drawable.ds_ic_key,
                )
            }
        }
        FooterText(stringResource(R.string.st_adv_footer))
    }
}

/** `wss://relay.example.org/` → `relay.example.org`. */
internal fun hostOf(url: String): String = runCatching { URI(url).host }.getOrNull()?.takeIf { it.isNotEmpty() } ?: url

/** The diagnostic log, newest first (the last [MAX_LINES]), selectable for copying. */
@Composable
fun LogScreen(
    port: AdvancedPort,
    onBack: () -> Unit,
) {
    val text by produceState<String?>(null, port) { value = port.log() }
    LogContent(text?.let(::newestFirst), onBack)
}

internal fun newestFirst(log: String): List<String> =
    log
        .lines()
        .filter { it.isNotBlank() }
        .takeLast(MAX_LINES)
        .asReversed()

private const val MAX_LINES = 300

@Composable
fun LogContent(
    lines: List<String>?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsPage(stringResource(R.string.st_adv_log), onBack, modifier) {
        if (lines == null) return@SettingsPage
        SettingsGroup {
            CardRow {
                if (lines.isEmpty()) {
                    Text(stringResource(R.string.st_adv_log_empty), style = MaterialTheme.typography.bodyMedium)
                } else {
                    SelectionContainer {
                        Text(
                            lines.joinToString("\n"),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        }
    }
}
