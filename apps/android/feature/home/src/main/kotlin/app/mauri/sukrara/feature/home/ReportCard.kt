// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.home

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.mauri.sukrara.core.model.Reading
import app.mauri.sukrara.designsystem.haptics.rememberHaptics
import app.mauri.sukrara.designsystem.motion.LocalReducedMotion
import app.mauri.sukrara.graph.GraphPalette
import app.mauri.sukrara.graph.stats.Band
import app.mauri.sukrara.graph.stats.Bands
import app.mauri.sukrara.graph.stats.GlucoseMetrics
import app.mauri.sukrara.graph.stats.GlucosePoint
import app.mauri.sukrara.graph.stats.Goal
import app.mauri.sukrara.graph.stats.ShareText
import app.mauri.sukrara.graph.stats.toPoint
import java.time.Instant
import java.util.Locale

/** The report's periods, days; it opens on [REPORT_DEFAULT_DAYS] like the clinic's report (not stored: a view control). */
internal val REPORT_DAYS = listOf(1, 7, 14, 30)
internal const val REPORT_DEFAULT_DAYS = 14

/** The report's numbers over the [days] before [nowS] (re-counted every 5 minutes, not every frame). */
internal fun reportMetrics(
    points: List<GlucosePoint>,
    nowS: Long,
    days: Int,
    bands: Bands,
): GlucoseMetrics {
    val to = nowS / REPORT_STEP_S * REPORT_STEP_S
    return GlucoseMetrics.of(points, to - days * DAY_S, to, bands)
}

private const val DAY_S = 86_400L
private const val REPORT_STEP_S = 300L

/**
 * The doctor's report on home, in the layout of the clinic's glucose report: time in the five ranges as one stacked
 * bar with each share and its consensus goal, then average glucose, GMI and glucose variability with theirs. A met
 * goal is green with a check, a missed one orange. Counts [history] (home's 31 days).
 */
@Composable
internal fun ReportCard(
    history: List<Reading>,
    now: Instant,
    bands: Bands,
    modifier: Modifier = Modifier,
) {
    var days by rememberSaveable { mutableIntStateOf(REPORT_DEFAULT_DAYS) }
    val points = remember(history) { history.map { it.toPoint() } }
    val step = now.epochSecond / REPORT_STEP_S
    val metrics = remember(points, days, bands, step) { reportMetrics(points, now.epochSecond, days, bands) }
    ReportCardContent(metrics, days, { days = it }, bands, modifier)
}

/** The card for given numbers (the gallery renders it with synthetic data). */
@Composable
internal fun ReportCardContent(
    metrics: GlucoseMetrics,
    days: Int,
    onDays: (Int) -> Unit,
    bands: Bands,
    modifier: Modifier = Modifier,
) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val palette = GraphPalette.of(dark)
    val tone = GoalTone(good = Color(palette.tight), bad = Color(palette.high))
    val haptics = rememberHaptics()
    Card(modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Column(
            Modifier.padding(16.dp).animateContentSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.report_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            PeriodPicker(days) {
                haptics.tick()
                onDays(it)
            }
            if (metrics.empty) {
                Text(
                    pluralStringResource(R.plurals.report_empty, days, days),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }
            if (metrics.tir.coverage < Goal.MIN_COVERAGE) {
                Text(
                    stringResource(R.string.report_low_coverage, ShareText.coverage(metrics.tir.coverage)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Ranges(metrics, bands, palette, tone)
            HorizontalDivider()
            MetricRow(
                stringResource(R.string.report_average),
                "${Math.round(metrics.mean)} mg/dL",
                listOf(Goal.AVERAGE.text),
                Goal.AVERAGE.met(metrics),
                tone,
            )
            MetricRow(
                stringResource(R.string.report_gmi),
                String.format(Locale.getDefault(), "%.1f %% · %d mmol/mol", metrics.gmi, Math.round(metrics.gmiMmolMol)),
                listOf(Goal.GMI.text, "< 53 mmol/mol"),
                Goal.GMI.met(metrics),
                tone,
                caption = stringResource(R.string.report_gmi_caption),
            )
            MetricRow(
                stringResource(R.string.report_cv),
                String.format(Locale.getDefault(), "%.1f %%", metrics.cv),
                listOf(Goal.CV.text),
                Goal.CV.met(metrics),
                tone,
            )
            Text(
                stringResource(R.string.report_footer, ShareText.coverage(metrics.tir.coverage)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

private data class GoalTone(
    val good: Color,
    val bad: Color,
)

@Composable
private fun PeriodPicker(
    days: Int,
    onDays: (Int) -> Unit,
) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        REPORT_DAYS.forEachIndexed { i, d ->
            val spoken = pluralStringResource(R.plurals.report_days, d, d)
            SegmentedButton(
                selected = d == days,
                onClick = { if (d != days) onDays(d) },
                shape = SegmentedButtonDefaults.itemShape(i, REPORT_DAYS.size),
                modifier = Modifier.semantics { contentDescription = spoken },
                icon = {},
            ) { Text(stringResource(R.string.report_days_short, d), maxLines = 1) }
        }
    }
}

/** Time in ranges: a stacked bar (high on top, like the clinic report) beside each range's share and goal. */
@Composable
private fun Ranges(
    m: GlucoseMetrics,
    b: Bands,
    palette: GraphPalette,
    tone: GoalTone,
) {
    val reduced = LocalReducedMotion.current
    val order = listOf(Band.VERY_HIGH, Band.HIGH, Band.TARGET, Band.TIGHT, Band.LOW, Band.VERY_LOW)
    val shares =
        order.map { band ->
            val target = m.tir.share(band).toFloat()
            animateFloatAsState(target, if (reduced) snap() else spring(stiffness = 300f), label = "share").value
        }
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Canvas(Modifier.width(24.dp).fillMaxHeight()) {
            val gap = 2.dp.toPx()
            val total = shares.sum().coerceAtLeast(1e-6f)
            val usable = size.height - gap * (shares.count { it > 0.001f } - 1).coerceAtLeast(0)
            var y = 0f
            order.forEachIndexed { i, band ->
                val h = shares[i] / total * usable
                if (h <= 0.5f) return@forEachIndexed
                drawRect(Color(palette.of(band)), Offset(0f, y), Size(size.width, h))
                y += h + gap
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            RangeRow(stringResource(R.string.report_very_high), "> ${b.veryHigh} mg/dL", m.tir.veryHigh, Goal.VERY_HIGH, tone)
            RangeRow(stringResource(R.string.report_high), "${b.high + 1}–${b.veryHigh} mg/dL", m.tir.high, null, tone)
            RangeRow(
                stringResource(R.string.report_above),
                stringResource(R.string.report_above_detail),
                m.tir.above,
                Goal.ABOVE,
                tone,
                strong = true,
            )
            HorizontalDivider()
            RangeRow(
                stringResource(R.string.report_in_range),
                "${b.low}–${b.high} mg/dL",
                m.tir.inRange,
                Goal.IN_RANGE,
                tone,
                strong = true,
            )
            RangeRow(
                stringResource(R.string.report_very_good),
                stringResource(R.string.report_very_good_detail, "${b.low}–${b.tight}"),
                m.tir.tight,
                null,
                tone,
            )
            HorizontalDivider()
            RangeRow(stringResource(R.string.report_low), "${b.veryLow}–${b.low - 1} mg/dL", m.tir.low, null, tone)
            RangeRow(stringResource(R.string.report_very_low), "< ${b.veryLow} mg/dL", m.tir.veryLow, Goal.VERY_LOW, tone)
            RangeRow(
                stringResource(R.string.report_below),
                stringResource(R.string.report_below_detail),
                m.tir.below,
                Goal.BELOW,
                tone,
                strong = true,
            )
        }
    }
}

@Composable
private fun goalWords(met: Boolean) = stringResource(if (met) R.string.report_goal_met else R.string.report_goal_missed)

/** One range (or a group's total): name and mg/dL, its share and its goal when it has one; TalkBack reads it as one line. */
@Composable
private fun RangeRow(
    name: String,
    detail: String,
    share: Double,
    goal: Goal?,
    tone: GoalTone,
    strong: Boolean = false,
) {
    val number = "${ShareText.of(share, goal)} %"
    val met = goal?.metShare(share)
    val goalText = goal?.let { stringResource(R.string.report_goal, it.text) }
    val spoken = listOfNotNull(name, detail, number, goalText, met?.let { goalWords(it) }).joinToString(", ")
    Row(
        Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = spoken },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(name, fontWeight = if (strong) FontWeight.SemiBold else FontWeight.Normal)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (goalText != null && met != null) GoalChip(goalText, met, tone, Modifier.padding(top = 2.dp))
        }
        Text(
            number,
            style = if (strong) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleSmall,
            fontWeight = if (strong) FontWeight.Bold else FontWeight.SemiBold,
            textAlign = TextAlign.End,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.widthIn(min = 64.dp),
        )
    }
}

@Composable
private fun MetricRow(
    name: String,
    value: String,
    goals: List<String>,
    met: Boolean,
    tone: GoalTone,
    caption: String? = null,
) {
    val goalTexts = goals.map { stringResource(R.string.report_goal, it) }
    val spoken = (listOfNotNull(name, value) + goalTexts + goalWords(met)).joinToString(", ")
    Column(Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = spoken }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(name)
                caption?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { goalTexts.forEach { GoalChip(it, met, tone) } }
    }
}

@Composable
private fun GoalChip(
    text: String,
    met: Boolean,
    tone: GoalTone,
    modifier: Modifier = Modifier,
) {
    val c = if (met) tone.good else tone.bad
    Row(
        modifier.background(c.copy(alpha = 0.16f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (met) {
            Icon(
                painterResource(R.drawable.home_ic_check),
                contentDescription = null,
                tint = c,
                modifier = Modifier.padding(end = 2.dp).size(12.dp),
            )
        }
        Text(text, style = MaterialTheme.typography.labelSmall, color = c)
    }
}
