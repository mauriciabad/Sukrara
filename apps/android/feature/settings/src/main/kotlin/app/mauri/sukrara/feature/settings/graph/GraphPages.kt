// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.settings.graph

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.mauri.sukrara.designsystem.dialog.Choice
import app.mauri.sukrara.designsystem.dialog.ChoiceDialog
import app.mauri.sukrara.designsystem.dialog.NumberDialog
import app.mauri.sukrara.designsystem.dialog.NumberInput
import app.mauri.sukrara.designsystem.rows.CardRow
import app.mauri.sukrara.designsystem.rows.DangerRow
import app.mauri.sukrara.designsystem.rows.FooterText
import app.mauri.sukrara.designsystem.rows.NavigationRow
import app.mauri.sukrara.designsystem.rows.SettingsGroup
import app.mauri.sukrara.designsystem.rows.SwitchRow
import app.mauri.sukrara.designsystem.rows.ValueRow
import app.mauri.sukrara.feature.settings.R
import app.mauri.sukrara.feature.settings.SettingsPage
import app.mauri.sukrara.feature.settings.numberLabeler
import app.mauri.sukrara.graph.GraphConfig
import app.mauri.sukrara.graph.GraphPalette
import app.mauri.sukrara.graph.GraphPlace
import app.mauri.sukrara.graph.GraphSettings
import app.mauri.sukrara.graph.LastPlace
import app.mauri.sukrara.graph.MarkShow
import app.mauri.sukrara.graph.RingSpec
import app.mauri.sukrara.graph.SlotKind
import app.mauri.sukrara.graph.stats.Band
import app.mauri.sukrara.graph.stats.TirRange
import app.mauri.sukrara.settings.NumberSpec
import app.mauri.sukrara.settings.SettingsSpec
import app.mauri.sukrara.core.designsystem.R as DsR

/** What the Graph pages can do (the ViewModel's edits; no-ops in screenshots). */
class GraphActions(
    val number: (app.mauri.sukrara.core.data.settings.Setting<Double>, Double) -> Unit = { _, _ -> },
    val bool: (app.mauri.sukrara.core.data.settings.Setting<Boolean>, Boolean) -> Unit = { _, _ -> },
    val lines: (List<Int>) -> Unit = {},
    val slots: (List<RingSpec>) -> Unit = {},
    val lastPlace: (LastPlace) -> Unit = {},
    val lastElse: (RingSpec?) -> Unit = {},
    val ringRange: (TirRange) -> Unit = {},
    val ringOrder: (List<Band>) -> Unit = {},
    val marks: (MarkShow) -> Unit = {},
    val placeHours: (GraphPlace, Double) -> Unit = { _, _ -> },
    val sensorTime: (GraphPlace, Boolean) -> Unit = { _, _ -> },
    val reset: (GraphSection) -> Unit = {},
    val openMarks: () -> Unit = {},
)

/** The page's open dialog (one at a time), saved across rotation. */
private sealed interface GraphDialog : java.io.Serializable {
    data object AddLine : GraphDialog

    data object Grid : GraphDialog

    data object DefaultHours : GraphDialog

    data class PlaceHours(val place: GraphPlace) : GraphDialog

    data object RingCount : GraphDialog

    data class SlotKindOf(val i: Int) : GraphDialog

    data class SlotPeriod(val i: Int) : GraphDialog

    data object Last : GraphDialog

    data object LastElseKind : GraphDialog

    data object LastElsePeriod : GraphDialog

    data object Range : GraphDialog
}

/** A section's Reset icon beside its title, shown only while the section differs from the defaults. */
@Composable
fun ResetIcon(
    section: GraphSection,
    config: GraphConfig,
    title: String,
    onReset: (GraphSection) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!section.changed(config)) return
    IconButton(onClick = { onReset(section) }, modifier) {
        Icon(painterResource(R.drawable.st_ic_restart_alt), contentDescription = stringResource(R.string.st_reset_section, title))
    }
}

/** The live card on top of a graph page: the app's own panel, drawn by the app (empty in tests without one). */
@Composable
fun PreviewCard(
    preview: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxWidth().padding(vertical = 8.dp)) { preview() }
}

/** A ring window or slot period in words: "12 h", "1 d", "7 d". */
@Composable
fun periodLabeler(): (Double) -> String {
    val h = stringResource(DsR.string.ds_unit_h)
    val d = stringResource(DsR.string.ds_unit_d)
    return { s ->
        val sec = s.toLong()
        if (sec % 86_400L == 0L) "${sec / 86_400L} $d" else "${sec / 3_600L} $h"
    }
}

@Composable
fun slotKindName(kind: SlotKind): String =
    stringResource(
        when (kind) {
            SlotKind.TIR -> R.string.st_slot_tir
            SlotKind.GMI -> R.string.st_slot_gmi
            SlotKind.AVERAGE -> R.string.st_slot_average
            SlotKind.CV -> R.string.st_slot_cv
        },
    )

@Composable
private fun placeName(p: GraphPlace): String =
    stringResource(
        when (p) {
            GraphPlace.APP -> R.string.st_place_app
            GraphPlace.NOTIFICATION -> R.string.st_place_notification
            GraphPlace.WIDGETS -> R.string.st_place_widgets
        },
    )

@Composable
private fun lastPlaceName(p: LastPlace): String =
    stringResource(
        when (p) {
            LastPlace.OFF -> R.string.st_last_off
            LastPlace.NEAR_VALUE -> R.string.st_last_near
            LastPlace.END -> R.string.st_last_end
        },
    )

/** A range's name with its span ("In range 70–180 mg/dL"), as the ring-order list and the ring-count choice say it. */
@Composable
fun bandName(
    band: Band,
    c: GraphConfig,
): String {
    val b = c.bands
    return when (band) {
        Band.VERY_LOW -> stringResource(R.string.st_ring_very_low, b.veryLow)
        Band.LOW -> stringResource(R.string.st_ring_low, b.veryLow, b.low - 1)
        Band.TIGHT -> stringResource(R.string.st_ring_tight, b.low, b.tight)
        Band.TARGET -> stringResource(R.string.st_ring_target, b.tight + 1, b.high)
        Band.HIGH -> stringResource(R.string.st_ring_high, b.high + 1, b.veryHigh)
        Band.VERY_HIGH -> stringResource(R.string.st_ring_very_high, b.veryHigh)
    }
}

/**
 * Settings › Graph: the live card on top, then lines across the graph, time shown (default and per place), what sits
 * beside the number (slots, last treatment, ring centre, rewards), the ring order, beside the graph, treatments on the
 * graph, the sensor's time per place and Reset all. Every section has its own Reset while it differs from the defaults.
 */
@Composable
fun GraphContent(
    c: GraphConfig,
    actions: GraphActions,
    onBack: () -> Unit,
    preview: @Composable () -> Unit = {},
) {
    var dialog by rememberSaveable { mutableStateOf<GraphDialog?>(null) }
    val label = numberLabeler()
    val period = periodLabeler()
    val mgdl = stringResource(R.string.st_mgdl)
    SettingsPage(stringResource(R.string.st_graph_title), onBack) {
        PreviewCard(preview)

        val linesTitle = stringResource(R.string.st_graph_lines)
        SettingsGroup(title = linesTitle, titleAction = { ResetIcon(GraphSection.Lines, c, linesTitle, actions.reset) }) {
            if (c.lines.isNotEmpty()) CardRow { LineChips(c.lines, mgdl) { actions.lines(c.lines - it) } }
            val max = SettingsSpec.LINE.maxItems
            ValueRow(
                stringResource(R.string.st_graph_line_add),
                stringResource(if (c.lines.size < max) R.string.st_graph_line_add_summary else R.string.st_graph_line_full, max),
                { dialog = GraphDialog.AddLine },
                icon = R.drawable.st_ic_add,
                enabled = c.lines.size < max,
            )
            ValueRow(stringResource(R.string.st_graph_grid), label(SettingsSpec.GRID_HOURS, c.gridHours.toDouble()), {
                dialog = GraphDialog.Grid
            })
        }

        val timeTitle = stringResource(R.string.st_graph_hours)
        SettingsGroup(title = timeTitle, titleAction = { ResetIcon(GraphSection.Time, c, timeTitle, actions.reset) }) {
            ValueRow(stringResource(R.string.st_graph_hours_default), label(SettingsSpec.GRAPH_HOURS, c.defaultHours.toDouble()), {
                dialog = GraphDialog.DefaultHours
            })
            for (p in GraphPlace.entries) {
                val own = c.placeHours[p]
                ValueRow(
                    placeName(p),
                    own?.let { label(SettingsSpec.PLACE_HOURS, it.toDouble()) }
                        ?: stringResource(R.string.st_graph_hours_same, label(SettingsSpec.GRAPH_HOURS, c.defaultHours.toDouble())),
                    { dialog = GraphDialog.PlaceHours(p) },
                )
            }
        }

        val besideTitle = stringResource(R.string.st_graph_beside)
        SettingsGroup(title = besideTitle, titleAction = { ResetIcon(GraphSection.Beside, c, besideTitle, actions.reset) }) {
            ValueRow(stringResource(R.string.st_graph_rings), label(SettingsSpec.RING_COUNT, c.ringCount.toDouble()), {
                dialog = GraphDialog.RingCount
            })
            for (i in 0 until c.ringCount.coerceIn(0, c.slots.size)) {
                val slot = c.slots[i]
                val name = slotName(i)
                ValueRow(name, slotKindName(slot.kind) + " · " + period(slot.windowS.toDouble()), { dialog = GraphDialog.SlotKindOf(i) })
                ValueRow(stringResource(R.string.st_slot_period, name), period(slot.windowS.toDouble()), {
                    dialog = GraphDialog.SlotPeriod(i)
                })
                if (i > 0) {
                    SwitchRow(stringResource(R.string.st_slot_faded, name), slot.faded, { on ->
                        actions.slots(c.slots.toMutableList().also { it[i] = slot.copy(faded = on) })
                    })
                }
            }
            ValueRow(stringResource(R.string.st_last), lastPlaceName(c.lastPlace), { dialog = GraphDialog.Last })
            if (c.lastPlace != LastPlace.OFF) {
                val other = c.lastElse
                ValueRow(
                    stringResource(R.string.st_last_else),
                    other?.let { slotKindName(it.kind) + " · " + period(it.windowS.toDouble()) } ?: stringResource(R.string.st_nothing),
                    { dialog = GraphDialog.LastElseKind },
                )
                if (other != null) {
                    ValueRow(stringResource(R.string.st_last_else_period), period(other.windowS.toDouble()), {
                        dialog = GraphDialog.LastElsePeriod
                    })
                    SwitchRow(stringResource(R.string.st_last_else_faded), other.faded, { on -> actions.lastElse(other.copy(faded = on)) })
                }
            }
            ValueRow(stringResource(R.string.st_ring_counts), rangeName(c.ringRange, c), { dialog = GraphDialog.Range })
            SwitchRow(
                stringResource(R.string.st_graph_marks),
                c.ringMarks,
                { actions.bool(GraphSettings.RingMarks, it) },
                summary = stringResource(R.string.st_graph_marks_summary),
            )
        }

        val orderTitle = stringResource(R.string.st_ring_order)
        SettingsGroup(title = orderTitle, titleAction = { ResetIcon(GraphSection.RingOrder, c, orderTitle, actions.reset) }) {
            CardRow { RingOrderList(c, actions.ringOrder) }
        }
        FooterText(stringResource(R.string.st_ring_order_footer))

        val besideGraphTitle = stringResource(R.string.st_graph_beside_graph)
        SettingsGroup(
            title = besideGraphTitle,
            titleAction = { ResetIcon(GraphSection.BesideGraph, c, besideGraphTitle, actions.reset) },
        ) {
            SwitchRow(
                stringResource(R.string.st_graph_side),
                c.side,
                { actions.bool(GraphSettings.Side, it) },
                summary = stringResource(R.string.st_graph_side_summary),
            )
            SwitchRow(
                stringResource(R.string.st_graph_crown),
                c.columnCrown,
                { actions.bool(GraphSettings.ColumnCrown, it) },
                enabled = c.side,
            )
        }

        SettingsGroup {
            NavigationRow(stringResource(R.string.st_marks_title), actions.openMarks, summary = marksSummary(c.marks))
        }

        val sensorTitle = stringResource(R.string.st_graph_sensor)
        SettingsGroup(title = sensorTitle, titleAction = { ResetIcon(GraphSection.SensorTime, c, sensorTitle, actions.reset) }) {
            for (p in GraphPlace.entries) SwitchRow(placeName(p), c.sensorTime(p), { actions.sensorTime(p, it) })
        }

        if (GraphSection.All.changed(c)) {
            SettingsGroup {
                DangerRow(
                    stringResource(R.string.st_graph_reset_all),
                    stringResource(R.string.st_graph_reset_all_title),
                    stringResource(R.string.st_graph_reset_all_text),
                    stringResource(R.string.st_reset),
                    { actions.reset(GraphSection.All) },
                    icon = R.drawable.st_ic_restart_alt,
                )
            }
        }
        FooterText(stringResource(R.string.st_graph_footer))
    }
    GraphDialogs(dialog, c, actions, label, period, mgdl) { dialog = null }
}

@Composable
private fun slotName(i: Int) =
    stringResource(listOf(R.string.st_slot_main, R.string.st_slot_second, R.string.st_slot_third).getOrElse(i) { R.string.st_slot_third })

@Composable
private fun rangeName(
    r: TirRange,
    c: GraphConfig,
): String =
    when (r) {
        TirRange.IN_RANGE -> stringResource(R.string.st_ring_counts_in_range, c.bands.low, c.bands.high)
        TirRange.TIGHT -> stringResource(R.string.st_ring_counts_tight, c.bands.low, c.bands.tight)
    }

@Composable
private fun GraphDialogs(
    dialog: GraphDialog?,
    c: GraphConfig,
    actions: GraphActions,
    label: (NumberSpec, Double) -> String,
    period: (Double) -> String,
    mgdl: String,
    close: () -> Unit,
) {
    fun number(spec: NumberSpec) = { v: Double -> label(spec, v) }
    when (dialog) {
        null -> {
            Unit
        }

        GraphDialog.AddLine -> {
            val spec = SettingsSpec.LINE
            val exists = stringResource(R.string.st_graph_line_exists)
            NumberDialog(
                stringResource(R.string.st_graph_line_add),
                NumberInput(spec, reject = { v -> exists.takeIf { v.toInt() in c.lines } }),
                spec.default,
                { "${spec.text(it)} $mgdl" },
                close,
            ) { v -> actions.lines((c.lines + v.toInt()).distinct().sorted()) }
        }

        GraphDialog.Grid -> {
            NumberDialog(
                stringResource(R.string.st_graph_grid),
                NumberInput(SettingsSpec.GRID_HOURS),
                c.gridHours.toDouble(),
                number(
                    SettingsSpec.GRID_HOURS,
                ),
                close,
            ) { actions.number(GraphSettings.GridHours, it) }
        }

        GraphDialog.DefaultHours -> {
            NumberDialog(
                stringResource(R.string.st_graph_hours_default),
                NumberInput(SettingsSpec.GRAPH_HOURS),
                c.defaultHours.toDouble(),
                number(SettingsSpec.GRAPH_HOURS),
                close,
            ) { actions.number(GraphSettings.Hours, it) }
        }

        is GraphDialog.PlaceHours -> {
            NumberDialog(
                placeName(dialog.place),
                NumberInput(SettingsSpec.PLACE_HOURS),
                (c.placeHours[dialog.place] ?: 0).toDouble(),
                number(SettingsSpec.PLACE_HOURS),
                close,
            ) { actions.placeHours(dialog.place, it) }
        }

        GraphDialog.RingCount -> {
            NumberDialog(
                stringResource(R.string.st_graph_rings),
                NumberInput(SettingsSpec.RING_COUNT),
                c.ringCount.toDouble(),
                number(
                    SettingsSpec.RING_COUNT,
                ),
                close,
            ) { actions.number(GraphSettings.RingCount, it) }
        }

        is GraphDialog.SlotKindOf -> {
            val i = dialog.i
            val slot = c.slots.getOrNull(i) ?: return close()
            ChoiceDialog(
                stringResource(R.string.st_slot_what, slotName(i)),
                SlotKind.entries.map { Choice(slotKindName(it), it) },
                slot.kind,
                close,
            ) { k -> actions.slots(c.slots.toMutableList().also { it[i] = slot.copy(kind = k) }) }
        }

        is GraphDialog.SlotPeriod -> {
            val i = dialog.i
            val slot = c.slots.getOrNull(i) ?: return close()
            NumberDialog(
                stringResource(R.string.st_slot_period, slotName(i)),
                NumberInput(SettingsSpec.RING_WINDOW),
                slot.windowS.toDouble(),
                period,
                close,
            ) { w -> actions.slots(c.slots.toMutableList().also { it[i] = slot.copy(windowS = w.toLong()) }) }
        }

        GraphDialog.Last -> {
            ChoiceDialog(stringResource(R.string.st_last), LastPlace.entries.map { Choice(lastPlaceName(it), it) }, c.lastPlace, close) {
                actions.lastPlace(it)
            }
        }

        GraphDialog.LastElseKind -> {
            val none: SlotKind? = null
            ChoiceDialog(
                stringResource(R.string.st_last_else),
                listOf(
                    Choice(stringResource(R.string.st_nothing), none),
                ) + SlotKind.entries.map { Choice<SlotKind?>(slotKindName(it), it) },
                c.lastElse?.kind,
                close,
            ) { k -> actions.lastElse(k?.let { (c.lastElse ?: GraphConfig.DEFAULT_LAST_ELSE).copy(kind = it) }) }
        }

        GraphDialog.LastElsePeriod -> {
            val other = c.lastElse ?: return close()
            NumberDialog(
                stringResource(R.string.st_last_else_period),
                NumberInput(SettingsSpec.RING_WINDOW),
                other.windowS.toDouble(),
                period,
                close,
            ) {
                actions.lastElse(other.copy(windowS = it.toLong()))
            }
        }

        GraphDialog.Range -> {
            ChoiceDialog(
                stringResource(R.string.st_ring_counts),
                TirRange.entries.map { Choice(rangeName(it, c), it) },
                c.ringRange,
                close,
            ) {
                actions.ringRange(it)
            }
        }
    }
}

/** The extra lines as input chips; a tap removes one (its × says so to TalkBack). */
@Composable
private fun LineChips(
    lines: List<Int>,
    mgdl: String,
    onRemove: (Int) -> Unit,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (l in lines) {
            val remove = stringResource(R.string.st_graph_line_remove, l)
            InputChip(
                selected = false,
                onClick = { onRemove(l) },
                label = { Text("$l $mgdl") },
                trailingIcon = {
                    Icon(painterResource(R.drawable.st_ic_close), contentDescription = remove, Modifier.size(InputChipDefaults.AvatarSize))
                },
            )
        }
    }
}

/** The ring's segments clockwise from 12 o'clock, each with its colour and range. */
@Composable
private fun RingOrderList(
    c: GraphConfig,
    onOrder: (List<Band>) -> Unit,
) {
    val palette = GraphPalette.of(isSystemInDarkTheme())
    ReorderList(c.ringOrder, onOrder) { band ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            val color = Color(palette.of(band))
            Canvas(Modifier.padding(end = 12.dp).size(14.dp)) { drawCircle(color) }
            Text(bandName(band, c), style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun markKindName(k: String): String =
    stringResource(
        when (k) {
            "rapid" -> R.string.st_marks_rapid
            "long" -> R.string.st_marks_long
            "carbs" -> R.string.st_marks_carbs
            else -> R.string.st_marks_note
        },
    )

/** "Everything", "Carbs: point only" (the default) or "Custom for each kind". */
@Composable
fun marksSummary(m: MarkShow): String {
    val pointOnly =
        MarkShow.KINDS.filter { k ->
            MarkShow.parts(k).size > 1 && MarkShow.parts(k).all { m.has(k, it) == (it == MarkShow.POINT) }
        }
    val full = MarkShow.KINDS.filter { k -> MarkShow.parts(k).all { m.has(k, it) } }
    return when {
        full.size == MarkShow.KINDS.size -> {
            stringResource(R.string.st_marks_summary_all)
        }

        (full + pointOnly).toSet().size == MarkShow.KINDS.size && pointOnly.isNotEmpty() -> {
            stringResource(R.string.st_marks_summary_point, pointOnly.map { markKindName(it) }.joinToString(", "))
        }

        else -> {
            stringResource(R.string.st_marks_summary_custom)
        }
    }
}

/**
 * Settings › Graph › Treatments on the graph: a group per kind, a switch per part that kind has (point, mute line,
 * amount, time), the live card on top. One setting for every graph.
 */
@Composable
fun GraphMarksContent(
    c: GraphConfig,
    actions: GraphActions,
    onBack: () -> Unit,
    preview: @Composable () -> Unit = {},
) {
    val title = stringResource(R.string.st_marks_title)
    SettingsPage(title, onBack) {
        PreviewCard(preview)
        for (k in MarkShow.KINDS) {
            SettingsGroup(title = markKindName(k)) {
                for (p in MarkShow.parts(k)) {
                    val (name, summary) =
                        when (p) {
                            MarkShow.POINT -> stringResource(R.string.st_marks_point) to null
                            MarkShow.LINE -> stringResource(R.string.st_marks_line) to stringResource(R.string.st_marks_line_summary)
                            MarkShow.AMOUNT -> stringResource(R.string.st_marks_amount) to null
                            else -> stringResource(R.string.st_marks_time) to null
                        }
                    SwitchRow(name, c.marks.has(k, p), { on -> actions.marks(c.marks.with(k, p, on)) }, summary = summary)
                }
            }
        }
        if (GraphSection.Marks.changed(c)) {
            SettingsGroup {
                ValueRow(
                    stringResource(R.string.st_marks_reset),
                    "",
                    { actions.reset(GraphSection.Marks) },
                    icon = R.drawable.st_ic_restart_alt,
                )
            }
        }
        FooterText(stringResource(R.string.st_marks_footer))
    }
}
