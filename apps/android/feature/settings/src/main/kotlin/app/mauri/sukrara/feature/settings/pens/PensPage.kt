// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.settings.pens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.mauri.sukrara.core.data.settings.SchemaSettings
import app.mauri.sukrara.core.data.settings.Setting
import app.mauri.sukrara.core.model.storage.SettingsRepository
import app.mauri.sukrara.designsystem.dialog.Choice
import app.mauri.sukrara.designsystem.dialog.ChoiceDialog
import app.mauri.sukrara.designsystem.dialog.NumberDialog
import app.mauri.sukrara.designsystem.dialog.NumberInput
import app.mauri.sukrara.designsystem.rows.FooterText
import app.mauri.sukrara.designsystem.rows.SettingsGroup
import app.mauri.sukrara.designsystem.rows.SwitchRow
import app.mauri.sukrara.designsystem.rows.ValueRow
import app.mauri.sukrara.feature.settings.R
import app.mauri.sukrara.feature.settings.SettingsPage
import app.mauri.sukrara.feature.settings.numberLabeler
import app.mauri.sukrara.settings.NumberSpec
import app.mauri.sukrara.settings.SettingsSpec
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One rule of *Automatic detection*: its setting, number format and row title / summary. */
private data class Rule(
    val setting: Setting<Double>,
    val spec: NumberSpec,
    val title: Int,
)

private val Reading = SchemaSettings.text("pens.reading", "ask", setOf("ask", "on", "off"))
private val Notify = SchemaSettings.bool("pens.notify", default = false)
private val Rules =
    listOf(
        Rule(SchemaSettings.number("pens.primeBelow"), SettingsSpec.PEN_PRIME_BELOW, R.string.st_pens_prime_below),
        Rule(SchemaSettings.number("pens.primeBefore"), SettingsSpec.PEN_PRIME_BEFORE, R.string.st_pens_prime_before),
        Rule(SchemaSettings.number("pens.cluster"), SettingsSpec.PEN_CLUSTER, R.string.st_pens_cluster),
        Rule(SchemaSettings.number("pens.pending"), SettingsSpec.PEN_PENDING, R.string.st_pens_pending),
        Rule(SchemaSettings.number("pens.offer"), SettingsSpec.PEN_OFFER, R.string.st_pens_offer),
        Rule(SchemaSettings.number("pens.match"), SettingsSpec.PEN_MATCH, R.string.st_pens_match),
        Rule(SchemaSettings.number("pens.matchUnits"), SettingsSpec.PEN_MATCH_UNITS, R.string.st_pens_match_units),
        Rule(SchemaSettings.number("pens.matchShare"), SettingsSpec.PEN_MATCH_SHARE, R.string.st_pens_match_share),
    )

/** Settings › Insulin pens as the page shows it. */
data class PensUi(
    val reading: String,
    val notify: Boolean,
    val rules: List<Double>,
)

class PensViewModel(
    private val settings: SettingsRepository,
) : ViewModel() {
    val ui: StateFlow<PensUi?> =
        combine(
            settings.flow(Reading),
            settings.flow(Notify),
            combine(Rules.map { settings.flow(it.setting) }) { it.toList() },
        ) { r, n, rules -> PensUi(r, n, rules) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setReading(value: String) {
        viewModelScope.launch { settings.set(Reading, value) }
    }

    fun setNotify(on: Boolean) {
        viewModelScope.launch { settings.set(Notify, on) }
    }

    fun setRule(
        index: Int,
        value: Double,
    ) {
        viewModelScope.launch { settings.set(Rules[index].setting, value) }
    }

    fun reset() {
        viewModelScope.launch { Rules.forEach { settings.set(it.setting, it.spec.default) } }
    }
}

/**
 * Settings › Insulin pens (the wearer's phone with NFC): *Read insulin pens* (Ask at the first tap / On / Off),
 * *Notification after each read*, the *Automatic detection* rules (the one number input each, *Reset to defaults*),
 * and which pens work.
 */
@Composable
fun PensContent(
    ui: PensUi,
    vm: PensViewModel,
    onBack: () -> Unit,
) {
    var choosing by rememberSaveable { mutableStateOf(false) }
    var editing by rememberSaveable { mutableStateOf(-1) }
    var resetting by rememberSaveable { mutableStateOf(false) }
    val label = numberLabeler()
    val readingLabels =
        mapOf(
            "ask" to stringResource(R.string.st_pens_reading_ask),
            "on" to stringResource(R.string.st_pens_reading_on),
            "off" to stringResource(R.string.st_pens_reading_off),
        )
    SettingsPage(stringResource(R.string.st_pens_title), onBack) {
        SettingsGroup {
            ValueRow(stringResource(R.string.st_pens_reading), readingLabels.getValue(ui.reading), { choosing = true })
            SwitchRow(
                stringResource(R.string.st_pens_notify),
                ui.notify,
                vm::setNotify,
                summary = stringResource(R.string.st_pens_notify_summary),
            )
        }
        FooterText(stringResource(R.string.st_pens_reading_footer))
        SettingsGroup(title = stringResource(R.string.st_pens_detection)) {
            Rules.forEachIndexed { i, r -> ValueRow(stringResource(r.title), label(r.spec, ui.rules[i]), { editing = i }) }
            ValueRow(
                stringResource(R.string.st_pens_reset),
                stringResource(R.string.st_pens_reset_summary),
                { resetting = true },
                enabled = Rules.indices.any { ui.rules[it] != Rules[it].spec.default },
            )
        }
        FooterText(stringResource(R.string.st_pens_detection_footer))
        FooterText(stringResource(R.string.st_pens_supported))
    }
    if (choosing) {
        ChoiceDialog(
            stringResource(R.string.st_pens_reading),
            listOf("ask", "on", "off").map { Choice(readingLabels.getValue(it), it) },
            ui.reading,
            { choosing = false },
            vm::setReading,
        )
    }
    if (editing >= 0) {
        val r = Rules[editing]
        val i = editing
        NumberDialog(stringResource(r.title), NumberInput(r.spec), ui.rules[i], { label(r.spec, it) }, { editing = -1 }, onChoose = {
            vm.setRule(i, it)
        })
    }
    if (resetting) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { resetting = false },
            title = { androidx.compose.material3.Text(stringResource(R.string.st_pens_reset_title)) },
            confirmButton = {
                androidx.compose.material3.TextButton({
                    vm.reset()
                    resetting = false
                }) { androidx.compose.material3.Text(stringResource(R.string.st_pens_reset_confirm)) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton({ resetting = false }) {
                    androidx.compose.material3.Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
}
