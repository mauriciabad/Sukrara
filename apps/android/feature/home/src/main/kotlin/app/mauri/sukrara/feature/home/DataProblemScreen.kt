// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.mauri.sukrara.designsystem.haptics.rememberHaptics
import java.time.Instant
import app.mauri.sukrara.core.designsystem.R as DsR

/** One damaged stored file, as the person needs to see it (`:app` maps `:core:data`'s `CorruptFile` to this). */
data class DataProblem(
    /** Which store, as `:core:data` names it ("settings:alerts", "database:history", "secret:identity", a file name). */
    val store: String,
    /** The decoder's error type (never the content): for the report only, never on screen. */
    val reason: String,
    /** The file name the damaged bytes were moved to, or null when they were left in place: for the report only. */
    val keptAs: String?,
    val at: Instant,
) {
    /** What the damage means for the person, from the store's kind (`:core:data`'s name prefixes). */
    val kind: DamagedStore
        get() =
            when {
                store.startsWith("settings") -> DamagedStore.Settings
                store.startsWith("database") -> DamagedStore.History
                store.startsWith("secret") -> DamagedStore.Keys
                else -> DamagedStore.Other
            }
}

/** The kinds of damaged data, each with its own plain sentence and way forward. */
enum class DamagedStore {
    /** Back to defaults: alert levels and mutes must be checked (safety). */
    Settings,

    /** Readings or treatments may be missing. */
    History,

    /** A key or sign-in is gone: following, sharing or the sensor pairing may need redoing. */
    Keys,

    /** A smaller state file started again from empty. */
    Other,
}

/**
 * The damaged files, each as what it means for the person (damaged settings = alert levels back to the defaults →
 * Open alert settings), Copy report (the store, error type and kept file, for a bug report) and Got it per file (the
 * exit: the banner goes away once every file is acknowledged). Back leaves without acknowledging. [onOpenAlertSettings]
 * null = this build has no alert settings screen to open (the button is hidden rather than doing nothing).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DataProblemScreen(
    problems: List<DataProblem>,
    onCopyReport: (String) -> Unit,
    onAcknowledge: (DataProblem) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenAlertSettings: (() -> Unit)? = null,
) {
    val haptics = rememberHaptics()
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.data_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(DsR.drawable.ds_ic_arrow_back), contentDescription = stringResource(R.string.data_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.data_intro), style = MaterialTheme.typography.bodyLarge)
            problems.forEach { p ->
                val (title, text) = words(p.kind)
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        FlowRow(
                            Modifier.fillMaxWidth().padding(top = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (p.kind == DamagedStore.Settings && onOpenAlertSettings != null) {
                                Button(onClick = onOpenAlertSettings) { Text(stringResource(R.string.data_open_alerts)) }
                            }
                            OutlinedButton(onClick = {
                                haptics.confirm()
                                onCopyReport(report(p))
                            }) { Text(stringResource(R.string.data_copy)) }
                            OutlinedButton(onClick = {
                                haptics.confirm()
                                onAcknowledge(p)
                            }) { Text(stringResource(R.string.data_ack)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun words(kind: DamagedStore): Pair<String, String> =
    when (kind) {
        DamagedStore.Settings -> stringResource(R.string.data_store_settings_title) to stringResource(R.string.data_store_settings_text)
        DamagedStore.History -> stringResource(R.string.data_store_history_title) to stringResource(R.string.data_store_history_text)
        DamagedStore.Keys -> stringResource(R.string.data_store_keys_title) to stringResource(R.string.data_store_keys_text)
        DamagedStore.Other -> stringResource(R.string.data_store_other_title) to stringResource(R.string.data_store_other_text)
    }

/** The plain-text report for a bug report: no file content, only what failed. */
internal fun report(p: DataProblem): String = "Sukrara damaged data\nstore: ${p.store}\nreason: ${p.reason}\nkept as: ${p.keptAs ?: "-"}\nat: ${p.at}"
