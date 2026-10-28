// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.graph

import app.mauri.sukrara.core.model.AlertUiState
import app.mauri.sukrara.core.model.GlucoseState
import app.mauri.sukrara.core.model.Reading
import app.mauri.sukrara.core.model.ReadingHistory
import app.mauri.sukrara.core.model.SensorState
import app.mauri.sukrara.core.model.Treatment
import app.mauri.sukrara.graph.stats.MetricsCache
import app.mauri.sukrara.graph.stats.toPoint
import java.time.Duration
import java.time.Instant

/**
 * The card's model from the one glucose state ([GlucoseState]) at [now]: what a card surface outside the app panel
 * (the widgets) draws. The badge is the alert runtime's answer ([AlertUiState.status]). The chart draws the live
 * window; the slots count [stored] (the stored days before it, oldest first) joined with it: the 7–30-day rings need
 * more than the live window.
 */
fun cardModelOf(
    state: GlucoseState,
    treatments: List<Treatment>,
    now: Instant,
    words: CardWords,
    stored: List<Reading> = emptyList(),
): CardModel =
    CardModel(
        readings = state.recent.map { it.toPoint() },
        treatments = treatments,
        nowS = now.epochSecond,
        words = words,
        status = state.alerts.status,
        sensor = state.sensor.cardInfo(),
        metricsOf = MetricsCache(ReadingHistory.join(stored, state.recent).map { it.toPoint() }, now.epochSecond),
    )

/** The sensor's dates as the card needs them; null when nothing is known (the card then shows no sensor badge). */
fun SensorState.cardInfo(): SensorInfo? =
    when (this) {
        is SensorState.Active -> {
            endsAt?.let { SensorInfo(startS = it.epochSecond - SENSOR_LIFE.seconds, endS = it.epochSecond) }
        }

        is SensorState.WarmingUp -> {
            val start = readyAt.epochSecond - WARMUP.seconds
            SensorInfo(startS = start, endS = start + SENSOR_LIFE.seconds, warmupEndS = readyAt.epochSecond)
        }

        else -> {
            null
        }
    }

private val SENSOR_LIFE = Duration.ofDays(15)
private val WARMUP = Duration.ofHours(1)
