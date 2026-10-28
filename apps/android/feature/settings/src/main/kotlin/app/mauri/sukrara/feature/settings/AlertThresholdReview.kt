// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.mauri.sukrara.alerts.AlertKind
import app.mauri.sukrara.alerts.AlertSettings
import app.mauri.sukrara.core.model.Clock
import app.mauri.sukrara.core.model.storage.SettingsRepository
import app.mauri.sukrara.designsystem.dialog.NumberDialog
import app.mauri.sukrara.designsystem.rows.SettingsGroup
import app.mauri.sukrara.designsystem.rows.ValueRow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** The result the setup step gets back. */
sealed interface ThresholdReviewResult {
    data class Confirmed(val settings: AlertSettings) : ThresholdReviewResult

    data object Cancelled : ThresholdReviewResult
}

/**
 * Review alert thresholds (never silently defaulted). Shows the thresholds in use as a draft; changes
 * stay in the draft until *Use these thresholds*, which saves them and the confirmation time together. Leaving
 * (Cancel / back) stores nothing.
 */
class ThresholdReviewViewModel(
    private val store: AlertSettingsStore,
    private val settings: SettingsRepository,
    private val clock: Clock,
) : ViewModel() {
    private val _draft = MutableStateFlow<AlertSettings?>(null)

    /** Null while the stored settings load. */
    val draft: StateFlow<AlertSettings?> = _draft.asStateFlow()

    init {
        viewModelScope.launch { _draft.value = store.settings.first() }
    }

    fun setThreshold(
        kind: AlertKind,
        value: Double,
    ) {
        _draft.value = _draft.value?.let(AlertEdits.threshold(kind, value))
    }

    /** Saves the reviewed thresholds onto the stored settings (other fields stay as stored) and the confirmation. */
    fun confirm(onDone: (ThresholdReviewResult) -> Unit) {
        val reviewed = _draft.value ?: return
        viewModelScope.launch {
            var saved = reviewed
            store.update { current ->
                ReviewedKinds.fold(current) { acc, kind -> acc.withRule(kind, acc.rule(kind).copy(threshold = reviewed.rule(kind).threshold)) }
                    .also { saved = it }
            }
            settings.set(SchemaPrefs.ThresholdsConfirmedAt, clock.now().toString())
            onDone(ThresholdReviewResult.Confirmed(saved))
        }
    }

    companion object {
        /** The kinds whose thresholds are reviewed: the five levels and no data. */
        val ReviewedKinds =
            listOf(AlertKind.VERY_LOW, AlertKind.LOW, AlertKind.HIGH, AlertKind.VERY_HIGH, AlertKind.EXTREMELY_HIGH, AlertKind.NO_DATA)
    }
}

/** The review's content: one row per reviewed kind, then *Use these thresholds* and *Cancel*. */
@Composable
fun AlertThresholdReviewContent(
    draft: AlertSettings,
    onThreshold: (AlertKind, Double) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    SettingsPage(stringResource(R.string.st_review_title), onCancel) {
        Text(
            stringResource(R.string.st_review_intro),
            Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodyLarge,
        )
        AlertThresholdRows(draft, onThreshold)
        FlowRow(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, androidx.compose.ui.Alignment.End),
        ) {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.st_cancel)) }
            Button(onClick = onConfirm) { Text(stringResource(R.string.st_review_confirm)) }
        }
    }
}

/**
 * The reviewed thresholds of [draft]: one row per kind ("Low — Below 70 mg/dL", "Off (…)"), each opening THE number
 * dialog limited to the values that keep the levels in order. This review and setup's alert step (the app passes it
 * in) show the same rows.
 */
@Composable
fun AlertThresholdRows(
    draft: AlertSettings,
    onThreshold: (AlertKind, Double) -> Unit,
) {
    var editing by rememberSaveable { mutableStateOf<AlertKind?>(null) }
    SettingsGroup {
        for (kind in ThresholdReviewViewModel.ReviewedKinds) {
            val rule = draft.rule(kind)
            val sentence = thresholdSentence(kind, rule.threshold)
            ValueRow(
                kindName(kind),
                if (rule.on) sentence else stringResource(R.string.st_review_off, sentence),
                { editing = kind },
            )
        }
    }
    editing?.let { kind ->
        val spec = SchemaPrefs.thresholdSpec(kind)
        val label = numberLabeler()
        NumberDialog(kindName(kind), thresholdInput(draft, kind), draft.rule(kind).threshold, { label(spec, it) }, { editing = null }) {
            onThreshold(kind, it)
        }
    }
}
