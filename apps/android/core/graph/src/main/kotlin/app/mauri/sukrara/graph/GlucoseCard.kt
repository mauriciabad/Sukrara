// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.graph

import app.mauri.sukrara.core.model.Reading
import app.mauri.sukrara.core.model.SensorState

/**
 * The one card renderer's entry for surfaces. This first part (chunk 10, so the glucose notification has its face
 * before the drawing port lands — chunk 08 extends this object with `draw` / `image`) is [face]: what a text or icon
 * place shows for the latest reading.
 */
object GlucoseCard {
    /**
     * THE look of [reading] at [nowS] (epoch s) on [surface], with the sensor's own states first: ended, warming up
     * (its ready time where the number goes). Every glyph surface takes its value, trend mark and freshness from here.
     */
    fun face(
        reading: Reading?,
        nowS: Long,
        sensor: SensorState,
        @Suppress("UNUSED_PARAMETER") surface: CardSurface,
    ): GlucoseLook {
        val warmup = (sensor as? SensorState.WarmingUp)?.let { Freshness.timeText(it.readyAt.epochSecond, nowS) }
        return Freshness.look(
            sgv = reading?.mgdl,
            t = reading?.t?.epochSecond,
            rate = reading?.rateMgdlPerMin?.toDouble(),
            nowS = nowS,
            ended = sensor == SensorState.Ended,
            warmup = warmup,
        )
    }
}
