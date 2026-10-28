// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.graph

import app.mauri.sukrara.core.model.GlucoseState
import app.mauri.sukrara.core.model.SensorState
import app.mauri.sukrara.graph.stats.toPoint

/**
 * [GlucoseCard.face] for a hub [state]: its newest reading and the sensor states the face decides on (ended; warming
 * up with its ready time where the number goes). The badge's label and life ring aren't used by the face. Kept out
 * of GlucoseCard.kt: that file runs inside System UI (HookCodeTest), and the app-side glyph surfaces only need this.
 */
fun GlucoseCard.face(
    state: GlucoseState,
    nowS: Long,
    surface: CardSurface,
): GlucoseLook {
    val sensor =
        when (val s = state.sensor) {
            SensorState.Ended -> {
                SensorBadge.Look(label = "", life = 0f, ended = true)
            }

            is SensorState.WarmingUp -> {
                SensorBadge.Look(
                    label = "",
                    life = 0f,
                    ended = false,
                    warmup = Freshness.timeText(s.readyAt.epochSecond, nowS),
                    readyAt = s.readyAt.epochSecond,
                )
            }

            else -> {
                null
            }
        }
    return face(state.latest?.toPoint(), nowS, sensor, surface)
}
