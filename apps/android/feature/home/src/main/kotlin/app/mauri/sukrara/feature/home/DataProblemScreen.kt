// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.mauri.sukrara.designsystem.haptics.rememberHaptics
import java.time.Instant

/** One damaged stored file, as the person needs to see it (`:app` maps `:core:data`'s `CorruptFile` to this). */
data class DataProblem(
    /** Which store ("settings", "treatments database", …). */
    val store: String,
    /** The decoder's error type (never the content). */
    val reason: String,
    /** The file name the damaged bytes were moved to, or null when they were left in place. */
    val keptAs: String?,
    val at: Instant,
)

/**
 * The damaged files: what failed, where the damaged copy was kept, Copy report (for a bug report), and Got it per file
 * (the exit: the banner goes away once every file is acknowledged). Back leaves without acknowledging.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DataProblemScreen(
    problems: List<DataProblem>,
    onCopyReport: (String) -> Unit,
    onAcknowledge: (DataProblem) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberHaptics()
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.data_title)) },
                navigationIcon = { TextButton(onClick = onBack) { Text(stringResource(R.string.data_back)) } },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.data_intro), style = MaterialTheme.typography.bodyLarge)
            problems.forEach { p ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.data_file, p.store, p.reason), style = MaterialTheme.typography.titleMedium)
                        Text(
                            p.keptAs?.let { stringResource(R.string.data_kept_as, it) } ?: stringResource(R.string.data_not_moved),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = {
                                haptics.confirm()
                                onCopyReport(report(p))
                            }) { Text(stringResource(R.string.data_copy)) }
                            Button(onClick = {
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

/** The plain-text report for a bug report: no file content, only what failed. */
internal fun report(p: DataProblem): String = "Sukrara damaged data\nstore: ${p.store}\nreason: ${p.reason}\nkept as: ${p.keptAs ?: "-"}\nat: ${p.at}"
