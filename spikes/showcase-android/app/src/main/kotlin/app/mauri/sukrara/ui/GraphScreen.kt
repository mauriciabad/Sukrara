// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import app.mauri.sukrara.R
import app.mauri.sukrara.graph.Band
import app.mauri.sukrara.graph.Bands
import app.mauri.sukrara.graph.DEFAULT_LAST_ELSE
import app.mauri.sukrara.graph.GraphConfig
import app.mauri.sukrara.graph.GraphPlace
import app.mauri.sukrara.graph.GraphSettings
import app.mauri.sukrara.graph.LastPlace
import app.mauri.sukrara.graph.RingSpec
import app.mauri.sukrara.graph.SlotKind
import app.mauri.sukrara.graph.TirRange
import app.mauri.sukrara.graph.parseLines
import app.mauri.sukrara.graph.periodLabel
import app.mauri.sukrara.graph.systemPalette
import app.mauri.sukrara.settings.NumberSpec
import app.mauri.sukrara.settings.SettingsSpec
import app.mauri.sukrara.settings.WearerSettings
import app.mauri.sukrara.treatments.MarkShow
import app.mauri.sukrara.widgets.SukraraHooks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Investigation 16: the major ranges, the graph window (one default, optionally one per place), the time-in-range
 * rings and the side profile. A live preview (the real panel) sits on top. Each home-screen widget can override
 * window, rings and profile on its own (long-press → Reconfigure). Every number is the one number input
 * ([NumberDialog]) with its schema spec (spikes/settings/settings.json). A follower sees the settings it mirrors from
 * the wearer only in Custom (Settings from, [SettingsFromGroup]); per-place windows and sensor time are always its own.
 */
/** Where a graph shows: the app, its notification, the launcher widgets. */
private fun placeIcon(p: GraphPlace) = when (p) {
    GraphPlace.APP -> R.drawable.ic_sym_smartphone
    GraphPlace.NOTIFICATION -> R.drawable.ic_sym_notifications
    GraphPlace.WIDGETS -> R.drawable.ic_sym_widgets
}

@Composable
fun GraphScreen(follower: Boolean, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val g by GraphSettings.state.collectAsState()
    val from by WearerSettings.mode.collectAsState()
    val custom = !follower || from == WearerSettings.CUSTOM
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
    // and the one number input for every number
    var numPick by remember { mutableStateOf<NumPick?>(null) }
    fun number(title: String, spec: NumberSpec, selected: Number, label: (Int) -> String, reject: (Int) -> String? = { null }, onPick: (Int) -> Unit) {
        numPick = NumPick(title, spec, selected.toDouble(), { label(it.roundToLong().toInt()) }, { reject(it.roundToLong().toInt()) }) { onPick(it.roundToLong().toInt()) }
    }
    val mgdl = stringResource(R.string.unit_mgdl)
    val S = SettingsSpec

    SettingsPage(stringResource(R.string.graph_title), onBack) {
        // the real panel as the page's live preview (AOSP IllustrationPreference: "at the top of the screen")
        SettingsHeader { GlucosePanel(last?.sgv, last?.rate, last?.t) }
        if (follower) SettingsFromGroup()

        if (custom) {
            SettingsGroup(stringResource(R.string.graph_group_ranges), titleAction = { Reset(stringResource(R.string.graph_group_ranges), g.bands != D.bands) { set { it.copy(bands = D.bands) } } }) {
                @Composable
                fun band(title: String, spec: NumberSpec, v: Int, icon: Int, put: (Bands, Int) -> Bands) =
                    value(title, "$v $mgdl", icon = icon) { number(title, spec, v, { "$it $mgdl" }) { x -> set { it.copy(bands = put(it.bands, x)) } } }
                band(stringResource(R.string.graph_very_low_below), S.BAND_VERY_LOW, g.bands.veryLow, R.drawable.ic_dia_very_low) { b, v -> b.copy(veryLow = v) }
                band(stringResource(R.string.graph_low_below), S.BAND_LOW, g.bands.low, R.drawable.ic_dia_low) { b, v -> b.copy(low = v) }
                band(stringResource(R.string.graph_very_good_up_to), S.BAND_TIGHT, g.bands.tight, R.drawable.ic_dia_range) { b, v -> b.copy(tight = v) }
                band(stringResource(R.string.graph_high_above), S.BAND_HIGH, g.bands.high, R.drawable.ic_dia_high) { b, v -> b.copy(high = v) }
                band(stringResource(R.string.graph_very_high_above), S.BAND_VERY_HIGH, g.bands.veryHigh, R.drawable.ic_dia_very_high) { b, v -> b.copy(veryHigh = v) }
                band(stringResource(R.string.graph_extremely_high_from), S.BAND_EXTREMELY_HIGH, g.bands.extremelyHigh, R.drawable.ic_dia_extremely_high) { b, v -> b.copy(extremelyHigh = v) }
            }
            Note(stringResource(R.string.graph_ranges_note, g.bands.low, g.bands.high, g.bands.tight))

            SettingsGroup(stringResource(R.string.graph_group_lines), titleAction = {
                Reset(stringResource(R.string.graph_group_lines), g.lines != D.lines || g.gridHours != D.gridHours) { set { it.copy(lines = D.lines, gridHours = D.gridHours) } }
            }) {
                if (g.lines.isNotEmpty()) card { GraphLines(g.lines) { l -> set { it.copy(lines = l) } } }
                val addLine = stringResource(R.string.graph_line_add_title)
                val exists = stringResource(R.string.graph_line_exists)
                action(addLine, stringResource(R.string.graph_line_add_summary, S.LINE.maxItems), icon = R.drawable.ic_sym_add,
                    enabled = g.lines.size < S.LINE.maxItems) {
                    number(addLine, S.LINE, S.LINE.default, { "$it $mgdl" }, reject = { if (it in g.lines) exists else null }) { v ->
                        set { it.copy(lines = parseLines((it.lines + v).joinToString(","))) }
                    }
                }
                val grid: (Int) -> String = { if (it == 0) ctx.getString(R.string.settings_off) else ctx.getString(R.string.graph_vertical_every, it) }
                val vertical = stringResource(R.string.graph_vertical_lines)
                value(vertical, grid(g.gridHours), icon = R.drawable.ic_sym_schedule) { number(vertical, S.GRID_HOURS, g.gridHours, grid) { h -> set { it.copy(gridHours = h) } } }
            }
            Note(stringResource(R.string.graph_lines_note))
        }

        SettingsGroup(stringResource(R.string.graph_group_window), titleAction = {
            Reset(stringResource(R.string.graph_group_window), (custom && g.defaultHours != D.defaultHours) || g.placeHours.isNotEmpty()) {
                set { it.copy(defaultHours = if (custom) D.defaultHours else it.defaultHours, placeHours = D.placeHours) }
            }
        }) {
            val hours: (Int) -> String = { ctx.getString(R.string.graph_hours, it) }
            val default = stringResource(R.string.graph_window_default)
            if (custom) value(default, hours(g.defaultHours), icon = R.drawable.ic_sym_timelapse) {
                number(default, S.GRAPH_HOURS, g.defaultHours, hours) { h -> set { it.copy(defaultHours = h) } }
            }
            for (p in GraphPlace.entries) {
                val label: (Int) -> String = { if (it == 0) ctx.getString(R.string.graph_window_default_hours, g.defaultHours) else hours(it) }
                val place = stringResource(graphPlaceLabel(p))
                value(place, label(g.placeHours[p] ?: 0), icon = placeIcon(p)) {
                    number(place, S.PLACE_HOURS, g.placeHours[p] ?: 0, label) { h ->
                        set { it.copy(placeHours = if (h == 0) it.placeHours - p else it.placeHours + (p to h)) }
                    }
                }
            }
        }
        Note(stringResource(R.string.graph_window_note))

        if (custom) {
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
                value(rings, count(g.ringCount), icon = R.drawable.ic_sym_donut_large) { number(rings, S.RING_COUNT, g.ringCount, count) { n -> set { it.copy(ringCount = n) } } }
                val period: (Int) -> String = { periodLabel(it.toLong()) }
                for (i in 0 until g.ringCount) {
                    val slot = g.slots[i]
                    fun setSlot(f: (RingSpec) -> RingSpec) = set { it.copy(slots = it.slots.toMutableList().also { l -> l[i] = f(l[i]) }) }
                    val name = stringResource(listOf(R.string.graph_slot_main, R.string.graph_slot_second, R.string.graph_slot_third)[i])
                    val kindLabel: (SlotKind) -> String = { ctx.getString(slotKindLabel(it)) }
                    value(name, kindLabel(slot.kind) + " · " + periodLabel(slot.windowS), icon = R.drawable.ic_sym_donut_small) {
                        choose(ctx.getString(R.string.graph_slot_what, name), SlotKind.entries, slot.kind, kindLabel) { k -> setSlot { s -> s.copy(kind = k) } }
                    }
                    val periodTitle = stringResource(R.string.graph_slot_period, name)
                    value(periodTitle, periodLabel(slot.windowS), icon = R.drawable.ic_sym_date_range) {
                        number(periodTitle, S.RING_WINDOW, slot.windowS, period) { w -> setSlot { s -> s.copy(windowS = w.toLong()) } }
                    }
                    if (i > 0) switch(stringResource(R.string.graph_slot_faded, name), slot.faded, icon = R.drawable.ic_sym_opacity) { v -> setSlot { s -> s.copy(faded = v) } }
                }
                val lastTitle = stringResource(R.string.graph_last)
                value(lastTitle, stringResource(lastPlaceLabel(g.lastPlace)), icon = R.drawable.ic_sym_last_page) {
                    choose(lastTitle, LastPlace.entries, g.lastPlace, { ctx.getString(lastPlaceLabel(it)) }) { p -> set { it.copy(lastPlace = p) } }
                }
                if (g.lastPlace != LastPlace.OFF) {
                    val other = g.lastElse
                    val noKind: SlotKind? = null
                    val nothing = stringResource(R.string.graph_nothing)
                    val what: (SlotKind?) -> String = { it?.let { k -> ctx.getString(slotKindLabel(k)) } ?: nothing }
                    value(stringResource(R.string.graph_last_else), other?.let { what(it.kind) + " · " + periodLabel(it.windowS) } ?: nothing, icon = R.drawable.ic_sym_more_horiz) {
                        choose(ctx.getString(R.string.graph_last_else_dialog), listOf(noKind) + SlotKind.entries, other?.kind, what) { k ->
                            set { it.copy(lastElse = k?.let { kind -> (it.lastElse ?: DEFAULT_LAST_ELSE).copy(kind = kind) }) }
                        }
                    }
                    if (other != null) {
                        value(stringResource(R.string.graph_last_else_period), periodLabel(other.windowS), icon = R.drawable.ic_sym_date_range) {
                            number(ctx.getString(R.string.graph_last_else_period_dialog), S.RING_WINDOW, other.windowS, period) { w ->
                                set { it.copy(lastElse = it.lastElse?.copy(windowS = w.toLong())) }
                            }
                        }
                        switch(stringResource(R.string.graph_last_else_faded), other.faded, icon = R.drawable.ic_sym_opacity) { v -> set { it.copy(lastElse = it.lastElse?.copy(faded = v)) } }
                    }
                }
                val range: (TirRange) -> String = { ctx.getString(R.string.graph_tir_with_span, ctx.getString(tirRangeLabel(it)), g.bands.low, if (it == TirRange.TIGHT) g.bands.tight else g.bands.high) }
                val counts = stringResource(R.string.graph_tir_counts)
                value(counts, range(g.ringRange), icon = R.drawable.ic_dia_range) { choose(counts, TirRange.entries, g.ringRange, range) { r -> set { it.copy(ringRange = r) } } }
                switch(stringResource(R.string.graph_ring_marks), g.ringMarks, icon = R.drawable.ic_sym_workspace_premium,
                    summary = stringResource(R.string.graph_ring_marks_summary)) { v -> set { it.copy(ringMarks = v) } }
            }
            Note(stringResource(R.string.graph_beside_number_note))

            SettingsGroup(stringResource(R.string.graph_ring_order), titleAction = {
                Reset(stringResource(R.string.graph_ring_order), g.ringOrder != D.ringOrder) { set { it.copy(ringOrder = D.ringOrder) } }
            }) {
                card { RingOrder(g) { o -> set { it.copy(ringOrder = o) } } }
            }
            Note(stringResource(R.string.graph_ring_order_note))

            SettingsGroup(stringResource(R.string.graph_group_beside_graph), titleAction = {
                Reset(stringResource(R.string.graph_group_beside_graph), g.side != D.side || g.columnCrown != D.columnCrown) {
                    set { it.copy(side = D.side, columnCrown = D.columnCrown) }
                }
            }) {
                switch(stringResource(R.string.graph_side), g.side, icon = R.drawable.ic_sym_bar_chart) { v -> set { it.copy(side = v) } }
                switch(stringResource(R.string.graph_column_crown), g.columnCrown, icon = R.drawable.ic_sym_crown,
                    summary = stringResource(R.string.graph_column_crown_summary)) { v -> set { it.copy(columnCrown = v) } }
            }
            Note(stringResource(R.string.graph_side_note))

            SettingsGroup(stringResource(R.string.graph_marks_title), titleAction = {
                Reset(stringResource(R.string.graph_marks_title), g.marks != D.marks) { set { it.copy(marks = D.marks) } }
            }) {
                link(stringResource(R.string.graph_marks_title), marksSummary(g.marks), icon = R.drawable.ic_dia_carbs, to = GRAPH_MARKS_ROUTE)
            }
        }

        SettingsGroup(stringResource(R.string.graph_group_sensor), titleAction = {
            Reset(stringResource(R.string.graph_group_sensor), g.sensorHidden != D.sensorHidden) { set { it.copy(sensorHidden = D.sensorHidden) } }
        }) {
            for (p in GraphPlace.entries) switch(stringResource(graphPlaceLabel(p)), g.sensorTime(p), icon = placeIcon(p)) { v ->
                set { it.copy(sensorHidden = if (v) it.sensorHidden - p else it.sensorHidden + p) }
            }
        }
        Note(stringResource(R.string.graph_sensor_note))
        if (custom && g != D) SettingsGroup { action(stringResource(R.string.graph_reset_all), icon = R.drawable.ic_sym_restart_alt) { set { D } } }
        SettingsFooter(stringResource(R.string.graph_footer))
    }
    pick?.let { p -> ChoiceDialog(p.title, p.options, p.selected, onClose = { pick = null }, onChoose = p.onPick) }
    numPick?.let { p -> NumberDialog(p.title, p.spec, p.selected, p.label, onClose = { numPick = null }, reject = p.reject, onChoose = p.onPick) }
}

/** The route of [GraphMarksScreen]. */
const val GRAPH_MARKS_ROUTE = "graph_marks"

@Composable
private fun kindName(k: String) = stringResource(when (k) {
    "rapid" -> R.string.graph_marks_kind_rapid; "long" -> R.string.graph_marks_kind_long
    "carbs" -> R.string.graph_marks_kind_carbs; else -> R.string.graph_marks_kind_note
})

/** "Carbs: point only" (the default), "Point, mute line, amount and time" (everything) or "Custom for each kind". */
@Composable
private fun marksSummary(m: MarkShow): String {
    val pointOnly = MarkShow.KINDS.filter { k -> MarkShow.parts(k).size > 1 && MarkShow.parts(k).all { m.has(k, it) == (it == MarkShow.POINT) } }
    val full = MarkShow.KINDS.filter { k -> MarkShow.parts(k).all { m.has(k, it) } }
    val names = pointOnly.map { kindName(it) }
    return when {
        full.size == MarkShow.KINDS.size -> stringResource(R.string.graph_marks_summary_all)
        (full + pointOnly).toSet().size == MarkShow.KINDS.size && pointOnly.isNotEmpty() ->
            stringResource(R.string.graph_marks_summary_point, names.joinToString(", "))
        else -> stringResource(R.string.graph_marks_summary_custom)
    }
}

/**
 * Settings › Graph › Treatments on the graph: one group per treatment kind (Android's settings pattern: "Group
 * settings in smaller relevant groups … headings between groups", and "Use a switch or checkbox for a clear on and off
 * state", developer.android.com/design/ui/mobile/guides/patterns/settings), each with a switch for every part that
 * kind has ([MarkShow.parts]): point, mute line, amount label, time label. The real panel on top shows the effect.
 * Global (every graph surface); a follower in Wearer / Default mode doesn't get here ([GraphScreen] shows the link only
 * in Custom: the setting is mirrored).
 */
@Composable
fun GraphMarksScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val g by GraphSettings.state.collectAsState()
    val idx = indexing
    val last by produceState<app.mauri.sukrara.core.Reading?>(null) {
        if (!idx) value = withContext(Dispatchers.IO) { app.mauri.sukrara.widgets.RecentHistory.readings(ctx).lastOrNull() }
    }
    fun set(k: String, part: String, v: Boolean) {
        GraphSettings.update { it.copy(marks = it.marks.with(k, part, v)) }
        SukraraHooks.refresh(ctx, "graph settings", force = true)
    }
    SettingsPage(stringResource(R.string.graph_marks_title), onBack) {
        SettingsHeader { GlucosePanel(last?.sgv, last?.rate, last?.t) }
        for (k in MarkShow.KINDS) {
            val icon = when (k) {
                "rapid" -> R.drawable.ic_dia_insulin_rapid; "long" -> R.drawable.ic_dia_insulin_long
                "carbs" -> R.drawable.ic_dia_carbs; else -> R.drawable.ic_sym_edit_note
            }
            SettingsGroup(kindName(k)) {
                for (p in MarkShow.parts(k)) when (p) {
                    MarkShow.POINT -> switch(stringResource(R.string.graph_marks_point), g.marks.has(k, p), icon = icon) { v -> set(k, p, v) }
                    MarkShow.LINE -> switch(stringResource(R.string.graph_marks_line), g.marks.has(k, p), icon = R.drawable.ic_sym_notifications_paused,
                        summary = stringResource(R.string.graph_marks_line_summary)) { v -> set(k, p, v) }
                    MarkShow.AMOUNT -> switch(stringResource(R.string.graph_marks_amount), g.marks.has(k, p), icon = R.drawable.ic_sym_123) { v -> set(k, p, v) }
                    MarkShow.TIME -> switch(stringResource(R.string.graph_marks_time), g.marks.has(k, p), icon = R.drawable.ic_sym_schedule) { v -> set(k, p, v) }
                }
            }
        }
        Note(stringResource(R.string.graph_marks_note))
    }
}

/** An open number dialog: the setting's spec, its value and how a value reads. */
private class NumPick(val title: String, val spec: NumberSpec, val selected: Double, val label: (Double) -> String,
                      val reject: (Double) -> String?, val onPick: (Double) -> Unit)

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

/** The ring's segments clockwise from 12 o'clock, reordered by drag and drop ([ReorderList]). */
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
    ReorderList(g.ringOrder, onOrder) { band -> RingRow(pal.of(band), name(band)) }
}

/**
 * The extra lines as input chips (tap one to remove it); "Add a line" opens the number input. Input chips are the
 * Material pattern for user-entered items with an X for removal (developer.android.com/develop/ui/compose/components/chip).
 */
@Composable
private fun GraphLines(lines: List<Int>, onLines: (List<Int>) -> Unit) {
    val mgdl = stringResource(R.string.unit_mgdl)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (l in lines) InputChip(selected = false, onClick = { onLines(lines - l) }, label = { Text("$l $mgdl") },
            trailingIcon = {
                Icon(painterResource(R.drawable.ic_sym_close), contentDescription = stringResource(R.string.graph_line_remove, l),
                    Modifier.size(InputChipDefaults.AvatarSize))
            })
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
