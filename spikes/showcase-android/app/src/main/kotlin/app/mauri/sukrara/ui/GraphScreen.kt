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
import app.mauri.sukrara.graph.DEFAULT_RING_ORDER
import app.mauri.sukrara.graph.systemPalette
import androidx.compose.foundation.layout.size
import app.mauri.sukrara.graph.GraphConfig
import app.mauri.sukrara.graph.GraphPlace
import app.mauri.sukrara.graph.GraphSettings
import app.mauri.sukrara.graph.RING_WINDOWS_S
import app.mauri.sukrara.graph.RingSpec
import app.mauri.sukrara.graph.SlotKind
import app.mauri.sukrara.graph.TirRange
import app.mauri.sukrara.graph.WINDOW_HOURS
import app.mauri.sukrara.graph.periodLabel
import app.mauri.sukrara.widgets.SukraraHooks
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** Settings entry: "Graph & time in range" + a one-line summary. */
@Composable
fun GraphRow(onClick: () -> Unit) {
    val g by GraphSettings.state.collectAsState()
    ValueRow("Graph & time in range",
        "${g.defaultHours} h · ranges ${g.bands.veryLow}/${g.bands.low}–${g.bands.tight}–${g.bands.high}/${g.bands.veryHigh} · " +
            if (g.ringCount == 0) "no rings" else g.rings().joinToString(" + ") { "${it.kind.short.ifEmpty { "TIR" }} ${it.period}" },
        enabled = true, onClick = onClick)
}

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
    val last by produceState<app.mauri.sukrara.core.Reading?>(null) {
        value = withContext(Dispatchers.IO) { app.mauri.sukrara.widgets.RecentHistory.readings(ctx).lastOrNull() }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Graph & time in range", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = onBack) { Text("Done") }
        }
        GlucosePanel(last?.sgv, last?.rate, last?.t)

        SectionTitle("Ranges")
        BandSlider("Very low below", g.bands.veryLow, 40f..65f) { v -> set { it.copy(bands = it.bands.copy(veryLow = v)) } }
        BandSlider("Low below", g.bands.low, 50f..100f) { v -> set { it.copy(bands = it.bands.copy(low = v)) } }
        BandSlider("Very good up to", g.bands.tight, 100f..180f) { v -> set { it.copy(bands = it.bands.copy(tight = v)) } }
        BandSlider("High above", g.bands.high, 140f..250f) { v -> set { it.copy(bands = it.bands.copy(high = v)) } }
        BandSlider("Very high above", g.bands.veryHigh, 200f..350f) { v -> set { it.copy(bands = it.bands.copy(veryHigh = v)) } }
        TextButton(onClick = { set { it.copy(bands = Bands()) } }, enabled = g.bands != Bands()) { Text("Reset to 54 / 70 / 140 / 180 / 250") }
        Note("In range is ${g.bands.low}–${g.bands.high} mg/dL; very good (${g.bands.low}–${g.bands.tight}) is the part of it " +
            "where people without diabetes spend most of the day. Very low and very high are the levels of the LibreView " +
            "report. The ranges colour the number, the line, the status bar and the time in range everywhere.")

        SectionTitle("Graph window")
        Text("Default", style = MaterialTheme.typography.bodyMedium)
        Chips(WINDOW_HOURS, g.defaultHours, { "$it h" }) { h -> set { it.copy(defaultHours = h) } }
        for (p in GraphPlace.entries) {
            Text(p.label, style = MaterialTheme.typography.bodyMedium)
            Chips(listOf(0) + WINDOW_HOURS, g.placeHours[p] ?: 0, { if (it == 0) "Default (${g.defaultHours} h)" else "$it h" }) { h ->
                set { it.copy(placeHours = if (h == 0) it.placeHours - p else it.placeHours + (p to h)) }
            }
        }
        Note("Each widget can have its own window: touch and hold it, then tap Reconfigure.")

        SectionTitle("Beside the number")
        Chips(listOf(0, 1, 2, 3), g.ringCount, { if (it == 0) "None" else "$it" }) { n -> set { it.copy(ringCount = n) } }
        for (i in 0 until g.ringCount) {
            val slot = g.slots[i]
            fun setSlot(f: (RingSpec) -> RingSpec) = set { it.copy(slots = it.slots.toMutableList().also { l -> l[i] = f(l[i]) }) }
            Text(listOf("Main", "Second", "Third")[i], style = MaterialTheme.typography.titleSmall)
            Chips(SlotKind.entries, slot.kind, { it.label }) { k -> setSlot { s -> s.copy(kind = k) } }
            Chips(RING_WINDOWS_S, slot.windowS, ::periodLabel) { w -> setSlot { s -> s.copy(windowS = w) } }
            if (i > 0) Toggle("Fainter than the main one", slot.faded) { v -> setSlot { s -> s.copy(faded = v) } }
        }
        Text("Time in range counts", style = MaterialTheme.typography.bodyMedium)
        Chips(TirRange.entries, g.ringRange, { "${it.label} (${g.bands.low}–${if (it == TirRange.TIGHT) g.bands.tight else g.bands.high})" }) { r ->
            set { it.copy(ringRange = r) }
        }
        RingOrder(g) { o -> set { it.copy(ringOrder = o) } }
        Note("Each slot is a time-in-range ring or a number from your doctor's report: GMI, average glucose or " +
            "variability (CV), with a green arc when its goal is met. Slots that don't fit are hidden, the last one " +
            "first. A slot needs data for at least a quarter of its window.")

        SectionTitle("Beside the graph")
        Toggle("Time in range and distribution", g.side,
            sub = "The % of time below, in and above range, and how much time was spent at each level") { v ->
            set { it.copy(side = v) }
        }
        HorizontalDivider()
        Note("The distribution covers the same time as the graph, and the line fades out underneath it. Each widget " +
            "can turn it off on its own.")
    }
}

/** The ring's segments clockwise from 12 o'clock, each movable earlier / later. */
@Composable
private fun RingOrder(g: GraphConfig, onOrder: (List<Band>) -> Unit) {
    val pal = systemPalette(LocalContext.current, androidx.compose.foundation.isSystemInDarkTheme())
    val b = g.bands
    fun name(x: Band) = when (x) {
        Band.VERY_LOW -> "Very low (< ${b.veryLow})"; Band.LOW -> "Low (${b.veryLow}–${b.low - 1})"
        Band.TIGHT -> "Very good (${b.low}–${b.tight})"; Band.TARGET -> "In range (${b.tight + 1}–${b.high})"
        Band.HIGH -> "High (${b.high + 1}–${b.veryHigh})"; Band.VERY_HIGH -> "Very high (> ${b.veryHigh})"
    }
    Text("Ring order, clockwise from the top", style = MaterialTheme.typography.bodyMedium)
    val o = g.ringOrder
    o.forEachIndexed { i, band ->
        fun move(to: Int) = onOrder(o.toMutableList().also { l -> l.add(to, l.removeAt(i)) })
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            androidx.compose.foundation.Canvas(Modifier.padding(end = 10.dp).size(14.dp)) { drawCircle(androidx.compose.ui.graphics.Color(pal.of(band))) }
            Text("${i + 1}. ${name(band)}", Modifier.weight(1f))
            TextButton(onClick = { move(i - 1) }, enabled = i > 0) { Text("↑") }
            TextButton(onClick = { move(i + 1) }, enabled = i < o.lastIndex) { Text("↓") }
        }
    }
    TextButton(onClick = { onOrder(DEFAULT_RING_ORDER) }, enabled = o != DEFAULT_RING_ORDER) { Text("Reset order") }
}

@Composable
private fun BandSlider(label: String, value: Int, range: ClosedFloatingPointRange<Float>, onChange: (Int) -> Unit) {
    Column {
        Text("$label: $value mg/dL", style = MaterialTheme.typography.bodyMedium)
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
private fun Note(text: String) =
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
