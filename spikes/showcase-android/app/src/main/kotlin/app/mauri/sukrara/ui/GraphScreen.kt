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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.mauri.sukrara.graph.Bands
import app.mauri.sukrara.graph.GraphConfig
import app.mauri.sukrara.graph.GraphPlace
import app.mauri.sukrara.graph.GraphSettings
import app.mauri.sukrara.graph.Profile
import app.mauri.sukrara.graph.RING_WINDOWS_S
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
        "${g.defaultHours} h · ranges ${g.bands.low}–${g.bands.tight}–${g.bands.high} · " +
            if (g.ringCount == 0) "no rings" else g.rings().joinToString(" + ") { it.period },
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
        BandSlider("Low below", g.bands.low, 50f..100f) { v -> set { it.copy(bands = it.bands.copy(low = v)) } }
        BandSlider("Very good up to", g.bands.tight, 100f..180f) { v -> set { it.copy(bands = it.bands.copy(tight = v)) } }
        BandSlider("High above", g.bands.high, 140f..250f) { v -> set { it.copy(bands = it.bands.copy(high = v)) } }
        TextButton(onClick = { set { it.copy(bands = Bands()) } }, enabled = g.bands != Bands()) { Text("Reset to 70 / 140 / 180") }
        Note("In range is ${g.bands.low}–${g.bands.high} mg/dL; very good (${g.bands.low}–${g.bands.tight}) is the part of it " +
            "where people without diabetes spend most of the day. The ranges colour the number, the line, the status bar " +
            "and the time in range everywhere.")

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

        SectionTitle("Time in range rings")
        Chips(listOf(0, 1, 2, 3), g.ringCount, { if (it == 0) "None" else "$it" }) { n -> set { it.copy(ringCount = n) } }
        for (i in 0 until g.ringCount) {
            Text(listOf("Main ring", "Second ring", "Third ring")[i], style = MaterialTheme.typography.bodyMedium)
            Chips(RING_WINDOWS_S, g.ringWindows[i], ::periodLabel) { w ->
                set { it.copy(ringWindows = it.ringWindows.toMutableList().also { l -> l[i] = w }) }
            }
        }
        Text("Number in the middle", style = MaterialTheme.typography.bodyMedium)
        Chips(TirRange.entries, g.ringRange, { "${it.label} (${g.bands.low}–${if (it == TirRange.TIGHT) g.bands.tight else g.bands.high})" }) { r ->
            set { it.copy(ringRange = r) }
        }
        Note("Rings that don't fit are hidden, the last one first. A ring needs data for at least a quarter of its window.")

        SectionTitle("Distribution beside the graph")
        Column(Modifier.selectableGroup()) {
            for (p in Profile.entries) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()
                .selectable(g.profile == p, role = Role.RadioButton) { set { it.copy(profile = p) } }) {
                RadioButton(g.profile == p, onClick = null)
                Column(Modifier.weight(1f).padding(start = 8.dp, top = 6.dp, bottom = 6.dp)) {
                    Text(p.label)
                    Text(p.blurb, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                }
            }
        }
        Toggle("Draw it as dots", g.profileDots, enabled = g.profile != Profile.NONE,
            sub = if (g.profileDots) "Rows of dots, like the line" else "A filled area") { v -> set { it.copy(profileDots = v) } }
        HorizontalDivider()
        Note("The distribution covers the same time as the graph. Its rectangles are as wide as the share of time in each " +
            "range, and the line fades out underneath.")
    }
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
