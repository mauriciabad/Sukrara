// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.mauri.sukrara.graph.Band
import app.mauri.sukrara.graph.Bands
import app.mauri.sukrara.graph.systemPalette
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.res.painterResource
import app.mauri.sukrara.R
import app.mauri.sukrara.graph.GraphConfig
import app.mauri.sukrara.graph.GRID_HOURS
import app.mauri.sukrara.graph.LINE_RANGE
import app.mauri.sukrara.graph.MAX_LINES
import app.mauri.sukrara.graph.parseLines
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.res.stringResource
import app.mauri.sukrara.graph.GraphPlace
import app.mauri.sukrara.graph.LastPlace
import app.mauri.sukrara.graph.DEFAULT_LAST_ELSE
import app.mauri.sukrara.graph.GraphSettings
import app.mauri.sukrara.graph.RING_WINDOWS_S
import app.mauri.sukrara.graph.RingSpec
import app.mauri.sukrara.graph.SlotKind
import app.mauri.sukrara.graph.TirRange
import app.mauri.sukrara.graph.WINDOW_HOURS
import app.mauri.sukrara.graph.WINDOW_HOURS_RANGE
import app.mauri.sukrara.graph.RING_DAYS_RANGE
import app.mauri.sukrara.graph.periodLabel
import app.mauri.sukrara.widgets.SukraraHooks
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Investigation 16: the major ranges, the graph window (one default, optionally one per place), the time-in-range
 * rings and the side profile. A live preview (the real panel) sits on top. Each home-screen widget can override
 * window, rings and profile on its own (long-press → Reconfigure).
 */
@Composable
fun GraphScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val g by GraphSettings.state.collectAsState()
    fun set(f: (GraphConfig) -> GraphConfig) {
        GraphSettings.update(f)
        SukraraHooks.refresh(ctx, "graph settings", force = true)
    }
    val idx = indexing
    val last by produceState<app.mauri.sukrara.core.Reading?>(null) {
        if (!idx) value = withContext(Dispatchers.IO) { app.mauri.sukrara.widgets.RecentHistory.readings(ctx).lastOrNull() }
    }

    // one radio dialog for every "pick one of" setting on this page
    var pick by remember { mutableStateOf<Pick?>(null) }
    fun <T> choose(title: String, options: List<T>, selected: T, label: (T) -> String, onPick: (T) -> Unit) {
        @Suppress("UNCHECKED_CAST")
        pick = Pick(title, options.map { Choice(label(it), it as Any?) }, selected) { onPick(it as T) }
    }
    // and one for numbers, which also take a custom value
    var numPick by remember { mutableStateOf<NumPick?>(null) }
    val daysUnit = androidx.compose.ui.res.stringResource(R.string.unit_d)
    val hoursRange = NumberRange(WINDOW_HOURS_RANGE.first.toDouble(), WINDOW_HOURS_RANGE.last.toDouble(), androidx.compose.ui.res.stringResource(R.string.unit_h))

    SettingsPage(stringResource(R.string.graph_title), onBack) {
        // the real panel as the page's live preview (AOSP IllustrationPreference: "at the top of the screen")
        SettingsHeader { GlucosePanel(last?.sgv, last?.rate, last?.t) }

        SettingsGroup(stringResource(R.string.graph_group_ranges), titleAction = { Reset(stringResource(R.string.graph_group_ranges), g.bands != D.bands) { set { it.copy(bands = D.bands) } } }) {
            card { BandSlider(stringResource(R.string.graph_very_low_below), g.bands.veryLow, 40f..65f) { v -> set { it.copy(bands = it.bands.copy(veryLow = v)) } } }
            card { BandSlider(stringResource(R.string.graph_low_below), g.bands.low, 50f..100f) { v -> set { it.copy(bands = it.bands.copy(low = v)) } } }
            card { BandSlider(stringResource(R.string.graph_very_good_up_to), g.bands.tight, 100f..180f) { v -> set { it.copy(bands = it.bands.copy(tight = v)) } } }
            card { BandSlider(stringResource(R.string.graph_high_above), g.bands.high, 140f..250f) { v -> set { it.copy(bands = it.bands.copy(high = v)) } } }
            card { BandSlider(stringResource(R.string.graph_very_high_above), g.bands.veryHigh, 200f..350f) { v -> set { it.copy(bands = it.bands.copy(veryHigh = v)) } } }
            card { BandSlider(stringResource(R.string.graph_extremely_high_from), g.bands.extremelyHigh, 260f..400f) { v -> set { it.copy(bands = it.bands.copy(extremelyHigh = v)) } } }
        }
        Note(stringResource(R.string.graph_ranges_note, g.bands.low, g.bands.high, g.bands.tight))

        SettingsGroup(stringResource(R.string.graph_group_lines), titleAction = {
            Reset(stringResource(R.string.graph_group_lines), g.lines != D.lines || g.gridHours != D.gridHours) { set { it.copy(lines = D.lines, gridHours = D.gridHours) } }
        }) {
            card { GraphLines(g.lines) { l -> set { it.copy(lines = l) } } }
            card {
                val gi = GRID_HOURS.indexOf(g.gridHours).coerceAtLeast(0)
                Text(if (g.gridHours == 0) stringResource(R.string.graph_vertical_lines_off) else stringResource(R.string.graph_vertical_lines_every, g.gridHours), style = MaterialTheme.typography.bodyLarge)
                Slider(gi.toFloat(), { v -> GRID_HOURS[v.roundToInt()].let { h -> if (h != g.gridHours) set { it.copy(gridHours = h) } } },
                    valueRange = 0f..GRID_HOURS.lastIndex.toFloat(), steps = GRID_HOURS.size - 2)
            }
        }
        Note(stringResource(R.string.graph_lines_note))

        SettingsGroup(stringResource(R.string.graph_group_window), titleAction = {
            Reset(stringResource(R.string.graph_group_window), g.defaultHours != D.defaultHours || g.placeHours.isNotEmpty()) {
                set { it.copy(defaultHours = D.defaultHours, placeHours = D.placeHours) }
            }
        }) {
            val hours: (Int) -> String = { ctx.getString(R.string.graph_hours, it) }
            val default = stringResource(R.string.graph_window_default)
            value(default, hours(g.defaultHours)) {
                numPick = NumPick(default, WINDOW_HOURS, g.defaultHours, hours, hoursRange) { h -> set { it.copy(defaultHours = h) } }
            }
            for (p in GraphPlace.entries) {
                val label: (Int) -> String = { if (it == 0) ctx.getString(R.string.graph_window_default_hours, g.defaultHours) else hours(it) }
                val place = stringResource(graphPlaceLabel(p))
                value(place, label(g.placeHours[p] ?: 0)) {
                    numPick = NumPick(place, listOf(0) + WINDOW_HOURS, g.placeHours[p] ?: 0, label, hoursRange) { h ->
                        set { it.copy(placeHours = if (h == 0) it.placeHours - p else it.placeHours + (p to h)) }
                    }
                }
            }
        }
        Note(stringResource(R.string.graph_window_note))

        SettingsGroup(stringResource(R.string.graph_group_beside_number), titleAction = {
            Reset(stringResource(R.string.graph_group_beside_number), g.ringCount != D.ringCount || g.slots != D.slots || g.ringRange != D.ringRange || g.ringOrder != D.ringOrder ||
                    g.lastPlace != D.lastPlace || g.lastElse != D.lastElse) {
                set { it.copy(ringCount = D.ringCount, slots = D.slots, ringRange = D.ringRange, ringOrder = D.ringOrder,
                    lastPlace = D.lastPlace, lastElse = D.lastElse) }
            }
        }) {
            val none = stringResource(R.string.graph_none)
            val count: (Int) -> String = { if (it == 0) none else "$it" }
            val rings = stringResource(R.string.graph_slots)
            value(rings, count(g.ringCount)) { choose(rings, listOf(0, 1, 2, 3), g.ringCount, count) { n -> set { it.copy(ringCount = n) } } }
            for (i in 0 until g.ringCount) {
                val slot = g.slots[i]
                fun setSlot(f: (RingSpec) -> RingSpec) = set { it.copy(slots = it.slots.toMutableList().also { l -> l[i] = f(l[i]) }) }
                val name = stringResource(listOf(R.string.graph_slot_main, R.string.graph_slot_second, R.string.graph_slot_third)[i])
                val kindLabel: (SlotKind) -> String = { ctx.getString(slotKindLabel(it)) }
                value(name, kindLabel(slot.kind) + " · " + periodLabel(slot.windowS)) {
                    choose(ctx.getString(R.string.graph_slot_what, name), SlotKind.entries, slot.kind, kindLabel) { k -> setSlot { s -> s.copy(kind = k) } }
                }
                val period = stringResource(R.string.graph_slot_period, name)
                value(period, periodLabel(slot.windowS)) {
                    numPick = NumPick(period, RING_WINDOWS_S.map { it.toInt() }, slot.windowS.toInt(), { periodLabel(it.toLong()) },
                        NumberRange(RING_DAYS_RANGE.first.toDouble(), RING_DAYS_RANGE.last.toDouble(), daysUnit, scale = 86400.0)) { w ->
                        setSlot { s -> s.copy(windowS = w.toLong()) }
                    }
                }
                if (i > 0) switch(stringResource(R.string.graph_slot_faded, name), slot.faded) { v -> setSlot { s -> s.copy(faded = v) } }
            }
            val last = stringResource(R.string.graph_last)
            value(last, stringResource(lastPlaceLabel(g.lastPlace))) {
                choose(last, LastPlace.entries, g.lastPlace, { ctx.getString(lastPlaceLabel(it)) }) { p -> set { it.copy(lastPlace = p) } }
            }
            if (g.lastPlace != LastPlace.OFF) {
                val other = g.lastElse
                val noKind: SlotKind? = null
                val nothing = stringResource(R.string.graph_nothing)
                val what: (SlotKind?) -> String = { it?.let { k -> ctx.getString(slotKindLabel(k)) } ?: nothing }
                value(stringResource(R.string.graph_last_else), other?.let { what(it.kind) + " · " + periodLabel(it.windowS) } ?: nothing) {
                    choose(ctx.getString(R.string.graph_last_else_dialog), listOf(noKind) + SlotKind.entries, other?.kind, what) { k ->
                        set { it.copy(lastElse = k?.let { kind -> (it.lastElse ?: DEFAULT_LAST_ELSE).copy(kind = kind) }) }
                    }
                }
                if (other != null) value(stringResource(R.string.graph_last_else_period), periodLabel(other.windowS)) {
                    choose(ctx.getString(R.string.graph_last_else_period_dialog), RING_WINDOWS_S, other.windowS, ::periodLabel) { w ->
                        set { it.copy(lastElse = it.lastElse?.copy(windowS = w)) }
                    }
                }
            }
            val range: (TirRange) -> String = { ctx.getString(R.string.graph_tir_with_span, ctx.getString(tirRangeLabel(it)), g.bands.low, if (it == TirRange.TIGHT) g.bands.tight else g.bands.high) }
            val counts = stringResource(R.string.graph_tir_counts)
            value(counts, range(g.ringRange)) { choose(counts, TirRange.entries, g.ringRange, range) { r -> set { it.copy(ringRange = r) } } }
            card { RingOrder(g) { o -> set { it.copy(ringOrder = o) } } }
        }
        Note(stringResource(R.string.graph_beside_number_note))

        SettingsGroup(stringResource(R.string.graph_group_beside_graph), titleAction = { Reset(stringResource(R.string.graph_group_beside_graph), g.side != D.side) { set { it.copy(side = D.side) } } }) {
            switch(stringResource(R.string.graph_side), g.side,
                summary = stringResource(R.string.graph_side_summary)) { v -> set { it.copy(side = v) } }
        }
        Note(stringResource(R.string.graph_side_note))
        if (g != D) SettingsGroup { action(stringResource(R.string.graph_reset_all), icon = R.drawable.ic_sym_restart_alt) { set { D } } }
        SettingsFooter(stringResource(R.string.graph_footer))
    }
    pick?.let { p -> ChoiceDialog(p.title, p.options, p.selected, onClose = { pick = null }, onChoose = p.onPick) }
    numPick?.let { p -> IntChoiceDialog(p.title, p.presets, p.selected, p.label, p.range, onClose = { numPick = null }, onChoose = p.onPick) }
}

/** An open "pick a number" dialog: presets and a custom value in [range]. */
private class NumPick(val title: String, val presets: List<Int>, val selected: Int, val label: (Int) -> String, val range: NumberRange,
                      val onPick: (Int) -> Unit)

/** An open "pick one of" dialog. */
private class Pick(val title: String, val options: List<Choice<Any?>>, val selected: Any?, val onPick: (Any?) -> Unit)

/** The defaults every reset goes back to. */
private val D = GraphConfig()

/** The reset icon beside a section's heading, shown only while the section differs from the defaults. */
@Composable
private fun Reset(title: String, changed: Boolean, onReset: () -> Unit) {
    if (changed) IconButton(onClick = onReset) {
        Icon(painterResource(R.drawable.ic_sym_restart_alt), contentDescription = stringResource(R.string.graph_reset_section, title))
    }
}

/** The ring's segments clockwise from 12 o'clock, each movable earlier / later. */
@Composable
private fun RingOrder(g: GraphConfig, onOrder: (List<Band>) -> Unit) {
    val pal = systemPalette(LocalContext.current, androidx.compose.foundation.isSystemInDarkTheme())
    val b = g.bands
    val ctx = LocalContext.current
    fun name(x: Band) = when (x) {
        Band.VERY_LOW -> ctx.getString(bandLabel(x), b.veryLow); Band.LOW -> ctx.getString(bandLabel(x), b.veryLow, b.low - 1)
        Band.TIGHT -> ctx.getString(bandLabel(x), b.low, b.tight); Band.TARGET -> ctx.getString(bandLabel(x), b.tight + 1, b.high)
        Band.HIGH -> ctx.getString(bandLabel(x), b.high + 1, b.veryHigh); Band.VERY_HIGH -> ctx.getString(bandLabel(x), b.veryHigh)
    }
    Text(stringResource(R.string.graph_ring_order), style = MaterialTheme.typography.bodyLarge)
    val o = g.ringOrder
    o.forEachIndexed { i, band ->
        fun move(to: Int) = onOrder(o.toMutableList().also { l -> l.add(to, l.removeAt(i)) })
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            androidx.compose.foundation.Canvas(Modifier.padding(end = 10.dp).size(14.dp)) { drawCircle(androidx.compose.ui.graphics.Color(pal.of(band))) }
            Text("${i + 1}. ${name(band)}", Modifier.weight(1f))
            IconButton(onClick = { move(i - 1) }, enabled = i > 0) {
                Icon(painterResource(R.drawable.ic_sym_arrow_upward), contentDescription = stringResource(R.string.graph_ring_move_earlier, name(band)))
            }
            IconButton(onClick = { move(i + 1) }, enabled = i < o.lastIndex) {
                Icon(painterResource(R.drawable.ic_sym_arrow_downward), contentDescription = stringResource(R.string.graph_ring_move_later, name(band)))
            }
        }
    }
}

/**
 * The extra lines as input chips (tap one to remove it) and a field to add another. Input chips are the Material
 * pattern for user-entered items with an X for removal (developer.android.com/develop/ui/compose/components/chip).
 */
@Composable
private fun GraphLines(lines: List<Int>, onLines: (List<Int>) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val v = text.toIntOrNull()
    val ok = v != null && v in LINE_RANGE && v !in lines && lines.size < MAX_LINES
    fun add() { if (ok) { onLines(parseLines((lines + v!!).joinToString(","))); text = "" } }
    if (lines.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (l in lines) InputChip(selected = false, onClick = { onLines(lines - l) }, label = { Text("$l mg/dL") },
            trailingIcon = {
                Icon(painterResource(R.drawable.ic_sym_close), contentDescription = stringResource(R.string.graph_line_remove, l),
                    Modifier.size(InputChipDefaults.AvatarSize))
            })
    }
    if (lines.size < MAX_LINES) Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(text, { t -> text = t.filter(Char::isDigit).take(3) }, Modifier.weight(1f),
            label = { Text(stringResource(R.string.graph_line_add_label)) }, singleLine = true,
            isError = text.isNotEmpty() && !ok,
            supportingText = if (text.isNotEmpty() && !ok) {
                { Text(if (v in lines) stringResource(R.string.graph_line_exists) else "${LINE_RANGE.first}–${LINE_RANGE.last} mg/dL") }
            } else null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { add() }))
        TextButton(onClick = ::add, enabled = ok, modifier = Modifier.padding(start = 8.dp)) { Text(stringResource(R.string.graph_line_add)) }
    }
}

@Composable
private fun BandSlider(label: String, value: Int, range: ClosedFloatingPointRange<Float>, onChange: (Int) -> Unit) {
    Column {
        Text("$label: $value mg/dL", style = MaterialTheme.typography.bodyLarge)
        Slider(value.toFloat(), { onChange((it / 5).roundToInt() * 5) }, valueRange = range,
            steps = ((range.endInclusive - range.start) / 5).toInt() - 1)
    }
}

@Composable
internal fun <T> Chips(options: List<T>, selected: T, label: (T) -> String, onPick: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (o in options) FilterChip(o == selected, { onPick(o) }, label = { Text(label(o)) })
    }
}

@Composable
private fun Note(text: String) = Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.padding(horizontal = 16.dp))
