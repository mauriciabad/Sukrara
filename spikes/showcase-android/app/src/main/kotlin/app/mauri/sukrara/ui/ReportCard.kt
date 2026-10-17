// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mauri.sukrara.graph.Band
import app.mauri.sukrara.graph.Bands
import app.mauri.sukrara.graph.GraphPalette
import app.mauri.sukrara.graph.GlucoseMetrics
import app.mauri.sukrara.graph.Goal
import app.mauri.sukrara.graph.GraphData
import app.mauri.sukrara.graph.GraphSettings
import app.mauri.sukrara.graph.periodLabel
import app.mauri.sukrara.graph.systemPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val REPORT_WINDOWS = listOf(86400L, 7 * 86400L, 14 * 86400L, 30 * 86400L)

/**
 * The doctor's report on the home screen (investigation 18), in the layout of LibreView's AGP page: time in the five
 * ranges as one stacked bar with the share and the consensus goal of each, then average glucose, GMI and glucose
 * variability with their goals. A goal that's met is green, one that isn't is orange. 14 days by default, like the
 * report; the sensor must have been active ≥ 70 % of the window for the numbers to count clinically.
 */
@Composable
fun GlucoseReport(lastT: Long?) {
    val ctx = LocalContext.current
    val cfg by GraphSettings.state.collectAsState()
    val pal = systemPalette(ctx, isSystemInDarkTheme())
    var window by remember { mutableLongStateOf(14 * 86400L) }
    val now = nowSeconds(60_000)
    val m by produceState<GlucoseMetrics?>(null, window, cfg.bands, lastT, now / 300) {
        value = withContext(Dispatchers.IO) { GraphData.metrics(ctx, window, cfg.bands) }
    }
    GlucoseReportContent(m, window, { window = it }, cfg.bands, pal)
}

/** The report card itself, for given numbers (the gallery renders it on the JVM). */
@Composable
fun GlucoseReportContent(m: GlucoseMetrics?, window: Long, onWindow: (Long) -> Unit, b: Bands, pal: GraphPalette) {
    val good = Color(pal.tight)
    val bad = Color(pal.high)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Glucose report", style = MaterialTheme.typography.titleMedium)
            Chips(REPORT_WINDOWS, window, ::periodLabel, onWindow)
            val mm = m
            if (mm == null || mm.empty) {
                Text("No readings in this window yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
                return@Column
            }
            if (mm.tir.coverage < Goal.MIN_COVERAGE) Text(
                "Sensor data for ${Math.round(mm.tir.coverage * 100)} % of the last ${periodLabel(window)}. The report " +
                    "is meant for 14 days with at least 70 %.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            // Time in ranges: a stacked bar, high on top, like the report.
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val bands = listOf(Band.VERY_HIGH, Band.HIGH, Band.TARGET, Band.TIGHT, Band.LOW, Band.VERY_LOW)
                Canvas(Modifier.width(28.dp).fillMaxHeight()) {
                    var y = 0f
                    val gap = 2.dp.toPx()
                    val shares = bands.map { mm.tir.share(it).toFloat() }
                    val usable = size.height - gap * (shares.count { it > 0 } - 1).coerceAtLeast(0)
                    bands.forEachIndexed { i, band ->
                        val h = shares[i] * usable
                        if (h <= 0f) return@forEachIndexed
                        drawRect(Color(pal.of(band)), Offset(0f, y), Size(size.width, h))
                        y += h + gap
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    RangeRow("Very high", "> ${b.veryHigh} mg/dL", mm.tir.veryHigh, Goal.VERY_HIGH, mm, good, bad)
                    RangeRow("High", "${b.high + 1}–${b.veryHigh} mg/dL", mm.tir.high, null, mm, good, bad)
                    RangeRow("Above range", "very high + high", mm.tir.above, Goal.ABOVE, mm, good, bad, strong = true)
                    HorizontalDivider()
                    RangeRow("Target", "${b.low}–${b.high} mg/dL", mm.tir.inRange, Goal.IN_RANGE, mm, good, bad, strong = true)
                    RangeRow("Very good", "${b.low}–${b.tight} mg/dL, part of target", mm.tir.tight, null, mm, good, bad)
                    HorizontalDivider()
                    RangeRow("Low", "${b.veryLow}–${b.low - 1} mg/dL", mm.tir.low, null, mm, good, bad)
                    RangeRow("Very low", "< ${b.veryLow} mg/dL", mm.tir.veryLow, Goal.VERY_LOW, mm, good, bad)
                    RangeRow("Below range", "low + very low", mm.tir.below, Goal.BELOW, mm, good, bad, strong = true)
                }
            }
            HorizontalDivider()
            MetricRow("Average glucose", "${Math.round(mm.mean)} mg/dL", Goal.AVERAGE, mm, good, bad)
            MetricRow("GMI", "%.1f %%  ·  %d mmol/mol".format(mm.gmi, Math.round(mm.gmiMmolMol)),
                Goal.GMI, mm, good, bad, extraGoal = "< 53 mmol/mol", caption = "Glucose Management Indicator")
            MetricRow("Glucose variability (CV)", "%.1f %%".format(mm.cv), Goal.CV, mm, good, bad)
            Text("Goals for type 1 and type 2 diabetes (international consensus, as in LibreView's report). " +
                "Sensor active ${Math.round(mm.tir.coverage * 100)} % of the window.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        }
    }
}

private fun pct(x: Double) = if (x > 0 && x < 0.005) "<1 %" else "${Math.round(x * 100)} %"

@Composable
private fun GoalChip(text: String, met: Boolean, good: Color, bad: Color) {
    val c = if (met) good else bad
    androidx.compose.foundation.layout.Row(Modifier.background(c.copy(alpha = 0.16f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        if (met) androidx.compose.material3.Icon(androidx.compose.ui.res.painterResource(app.mauri.sukrara.R.drawable.ic_sym_check), contentDescription = null,
            tint = c, modifier = Modifier.padding(end = 2.dp).size(12.dp))
        Text("Goal $text", style = MaterialTheme.typography.labelSmall, color = c)
    }
}

/** One range (or a group's total): name and mg/dL, its share, and its consensus goal when it has one. */
@Composable
private fun RangeRow(name: String, mg: String, share: Double, goal: Goal?, m: GlucoseMetrics, good: Color, bad: Color,
                     strong: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f)) {
            Text(name, fontWeight = if (strong) FontWeight.SemiBold else FontWeight.Normal)
            Text(mg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        goal?.let { GoalChip(it.text, it.met(m), good, bad) }
        Text(pct(share), fontSize = if (strong) 20.sp else 15.sp, fontWeight = if (strong) FontWeight.Bold else FontWeight.SemiBold,
            modifier = Modifier.width(56.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
    }
}

@Composable
private fun MetricRow(name: String, value: String, goal: Goal, m: GlucoseMetrics, good: Color, bad: Color,
                      extraGoal: String? = null, caption: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(name)
                caption?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            Text(value, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            GoalChip(goal.text, goal.met(m), good, bad)
            extraGoal?.let { GoalChip(it, goal.met(m), good, bad) }
        }
    }
}
