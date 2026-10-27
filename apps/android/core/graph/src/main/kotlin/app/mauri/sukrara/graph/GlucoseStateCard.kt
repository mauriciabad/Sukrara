// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.graph

import app.mauri.sukrara.core.model.GlucoseState
import app.mauri.sukrara.core.model.SensorState
import app.mauri.sukrara.core.model.Treatment
import app.mauri.sukrara.graph.stats.toPoint
import java.time.Duration
import java.time.Instant

/**
 * The card's model from the one glucose state ([GlucoseState]) at [now]: what a card surface outside the app panel
 * (the widgets) draws. The badge shows *Mute all* while it runs; the alert answer joins when the runtime exposes it.
 */
fun cardModelOf(
    state: GlucoseState,
    treatments: List<Treatment>,
    now: Instant,
    words: CardWords,
): CardModel =
    CardModel(
        readings = state.recent.map { it.toPoint() },
        treatments = treatments,
        nowS = now.epochSecond,
        words = words,
        status =
            state.alerts.mutedUntil
                ?.takeIf { it > now }
                ?.let { CardStatus.Muted(it.epochSecond) } ?: CardStatus.None,
        sensor = state.sensor.cardInfo(),
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
