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
import app.mauri.sukrara.designsystem.rows.SwitchRow
import app.mauri.sukrara.designsystem.rows.ValueRow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The graph settings the Ranges and Graph pages show (stored values, mg/dL / hours). */
data class GraphPrefs(
    val bands: List<Double> = SchemaPrefs.Bands.map { it.default },
    val hours: Double = SchemaPrefs.GraphHours.default,
    val gridHours: Double = SchemaPrefs.GridHours.default,
    val ringCount: Double = SchemaPrefs.RingCount.default,
    val side: Boolean = SchemaPrefs.Side.default,
    val crown: Boolean = SchemaPrefs.ColumnCrown.default,
    val ringMarks: Boolean = SchemaPrefs.RingMarks.default,
)

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

/** Reads and writes the schema settings of the Ranges and Graph pages. */
class GraphPrefsViewModel(
    private val settings: SettingsRepository,
) : ViewModel() {
    /** Null until the stored values have loaded (edits are never checked against defaults). */
    val prefs: StateFlow<GraphPrefs?> =
        combine(
            combine(SchemaPrefs.Bands.map { settings.flow(it) }) { it.toList() },
            settings.flow(SchemaPrefs.GraphHours),
            settings.flow(SchemaPrefs.GridHours),
            settings.flow(SchemaPrefs.RingCount),
            combine(settings.flow(SchemaPrefs.Side), settings.flow(SchemaPrefs.ColumnCrown), settings.flow(SchemaPrefs.RingMarks)) { a, b, c -> Triple(a, b, c) },
        ) { bands, hours, grid, rings, (side, crown, marks) -> GraphPrefs(bands, hours, grid, rings, side, crown, marks) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun <T> set(
        setting: Setting<T>,
        value: T,
    ) where T : Any {
        viewModelScope.launch { settings.set(setting, value) }
    }
}

/** Ranges: the six bands the graph colours and time in range use (mg/dL). */
@Composable
fun RangesContent(
    prefs: GraphPrefs,
    onSet: (Setting<Double>, Double) -> Unit,
    onBack: () -> Unit,
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
    SettingsPage(stringResource(R.string.st_ranges_title), onBack) {
        SettingsGroup {
            SchemaPrefs.Bands.forEachIndexed { i, setting ->
                ValueRow(stringResource(titles[i]), numberLabel(SchemaPrefs.bandSpec(setting), prefs.bands[i]), { editing = i })
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

/** Graph basics: time shown, the column beside it, vertical lines, rings. */
@Composable
fun GraphBasicsContent(
    prefs: GraphPrefs,
    onSetNumber: (Setting<Double>, Double) -> Unit,
    onSetBool: (Setting<Boolean>, Boolean) -> Unit,
    onBack: () -> Unit,
) {
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    val numbers =
        listOf(
            Triple(SchemaPrefs.GraphHours, R.string.st_graph_hours, prefs.hours),
            Triple(SchemaPrefs.GridHours, R.string.st_graph_grid, prefs.gridHours),
            Triple(SchemaPrefs.RingCount, R.string.st_graph_rings, prefs.ringCount),
        )
    SettingsPage(stringResource(R.string.st_graph_title), onBack) {
        SettingsGroup {
            for ((setting, title, value) in numbers) {
                ValueRow(stringResource(title), numberLabel(SchemaPrefs.spec(setting.id), value), { editing = setting.id })
            }
        }
        SettingsGroup {
            SwitchRow(stringResource(R.string.st_graph_side), prefs.side, { onSetBool(SchemaPrefs.Side, it) }, summary = stringResource(R.string.st_graph_side_summary))
            SwitchRow(stringResource(R.string.st_graph_crown), prefs.crown, { onSetBool(SchemaPrefs.ColumnCrown, it) }, enabled = prefs.side)
            SwitchRow(
                stringResource(R.string.st_graph_marks),
                prefs.ringMarks,
                { onSetBool(SchemaPrefs.RingMarks, it) },
                summary = stringResource(R.string.st_graph_marks_summary),
            )
        }
        FooterText(stringResource(R.string.st_graph_footer))
    }
    numbers.firstOrNull { it.first.id == editing }?.let { (setting, title, value) ->
        val spec = SchemaPrefs.spec(setting.id)
        val label = numberLabeler()
        NumberDialog(stringResource(title), NumberInput(spec), value, { label(spec, it) }, { editing = null }) {
            onSetNumber(setting, it)
        }
    }
}
