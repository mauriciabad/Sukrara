// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.mauri.sukrara.core.data.settings.Setting
import app.mauri.sukrara.core.model.storage.SettingsRepository
import app.mauri.sukrara.designsystem.dialog.NumberDialog
import app.mauri.sukrara.designsystem.dialog.NumberInput
import app.mauri.sukrara.designsystem.rows.FooterText
import app.mauri.sukrara.designsystem.rows.SettingsGroup
import app.mauri.sukrara.designsystem.rows.ValueRow
import app.mauri.sukrara.feature.settings.graph.PreviewCard
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import app.mauri.sukrara.core.designsystem.R as DsR

/** The six stored ranges the Ranges page shows (mg/dL), lowest first. */
data class GraphPrefs(
    val bands: List<Double> = SchemaPrefs.Bands.map { it.default },
) {
    val isDefault: Boolean get() = bands == SchemaPrefs.Bands.map { it.default }
}

object BandOrder {
    /**
     * Why [value] can't be band [index] (it must stay above the band below and under the band above, so the graph's
     * colours never cross), or null when it fits. Returns the allowed open interval (low, high) on failure.
     */
    fun conflict(
        bands: List<Double>,
        index: Int,
        value: Double,
    ): Pair<Double?, Double?>? {
        val below = bands.getOrNull(index - 1)
        val above = bands.getOrNull(index + 1)
        val ok = (below == null || value > below) && (above == null || value < above)
        return if (ok) null else below to above
    }
}

/** Reads and writes the ranges. */
class GraphPrefsViewModel(
    private val settings: SettingsRepository,
) : ViewModel() {
    /** Null until the stored values have loaded (edits are never checked against defaults). */
    val prefs: StateFlow<GraphPrefs?> =
        combine(SchemaPrefs.Bands.map { settings.flow(it) }) { GraphPrefs(it.toList()) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun set(
        setting: Setting<Double>,
        value: Double,
    ) {
        viewModelScope.launch { settings.set(setting, value) }
    }

    /** Every range back to its default (the order of the defaults is valid, so writing them one by one is safe). */
    fun reset() {
        viewModelScope.launch { SchemaPrefs.Bands.forEach { settings.set(it, it.default) } }
    }
}

/**
 * Ranges: the live card, the six bands the graph colours and time in range use (mg/dL), Reset ranges. [readOnly] = a
 * follower not in Custom: the bands in use, nothing to change ([preview] says why and opens *Settings from*).
 */
@Composable
fun RangesContent(
    prefs: GraphPrefs,
    onSet: (Setting<Double>, Double) -> Unit,
    onBack: () -> Unit,
    onReset: () -> Unit = {},
    readOnly: Boolean = false,
    preview: @Composable () -> Unit = {},
) {
    var editing by rememberSaveable { mutableStateOf<Int?>(null) }
    val titles =
        listOf(
            R.string.st_band_very_low,
            R.string.st_band_low,
            R.string.st_band_tight,
            R.string.st_band_high,
            R.string.st_band_very_high,
            R.string.st_band_extremely_high,
        )
    val bandIcons =
        listOf(
            DsR.drawable.ds_ic_dia_very_low,
            DsR.drawable.ds_ic_dia_low,
            DsR.drawable.ds_ic_dia_range,
            DsR.drawable.ds_ic_dia_high,
            DsR.drawable.ds_ic_dia_very_high,
            DsR.drawable.ds_ic_dia_extremely_high,
        )
    SettingsPage(stringResource(R.string.st_ranges_title), onBack) {
        PreviewCard(preview)
        SettingsGroup {
            SchemaPrefs.Bands.forEachIndexed { i, setting ->
                ValueRow(
                    stringResource(titles[i]),
                    numberLabel(SchemaPrefs.bandSpec(setting), prefs.bands[i]),
                    { editing = i },
                    icon = bandIcons[i],
                    readOnly = readOnly,
                )
            }
        }

        if (!prefs.isDefault && !readOnly) {
            SettingsGroup {
                ValueRow(
                    stringResource(R.string.st_ranges_reset),
                    stringResource(R.string.st_ranges_reset_summary),
                    onReset,
                    icon = DsR.drawable.ds_ic_restart_alt,
                )
            }
        }
        FooterText(stringResource(R.string.st_ranges_footer))
    }
    editing?.let { i ->
        val setting = SchemaPrefs.Bands[i]
        val spec = SchemaPrefs.bandSpec(setting)
        val between = stringResource(R.string.st_band_between)
        val input =
            NumberInput(
                spec,
                presets = spec.presets.filter { BandOrder.conflict(prefs.bands, i, it) == null },
                reject = { v ->
                    BandOrder.conflict(prefs.bands, i, v)?.let { (lo, hi) ->
                        between.format(lo?.let(spec::text) ?: spec.text(spec.min), hi?.let(spec::text) ?: spec.text(spec.max))
                    }
                },
            )
        val label = numberLabeler()
        NumberDialog(stringResource(titles[i]), input, prefs.bands[i], { label(spec, it) }, { editing = null }) {
            onSet(setting, it)
        }
    }
}
