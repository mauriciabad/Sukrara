// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara

import app.mauri.sukrara.alerts.AlertReading
import app.mauri.sukrara.alerts.AlertSettings
import app.mauri.sukrara.alerts.AlertSource
import app.mauri.sukrara.alerts.Rules
import app.mauri.sukrara.alerts.Side
import app.mauri.sukrara.core.model.Clock
import app.mauri.sukrara.core.model.GlucoseState
import app.mauri.sukrara.core.model.Role
import app.mauri.sukrara.feature.treatments.AlertHints
import app.mauri.sukrara.feature.treatments.QuietRules
import kotlinx.coroutines.flow.StateFlow

/** The treatment dialog's view of the alert rules: the latest reading against them, and the after-treatment quiet. */
class AppAlertHints(
    private val settings: StateFlow<AlertSettings>,
    private val glucose: StateFlow<GlucoseState>,
    private val clock: Clock,
) : AlertHints {
    override fun lowNow(): Boolean {
        val latest = glucose.value.latest ?: return false
        val reading = AlertReading(latest.t.epochSecond, latest.mgdl, latest.rateMgdlPerMin)
        return Rules.levelOf(reading, settings.value, clock.now().epochSecond)?.side == Side.LOW
    }

    override fun quiet(): QuietRules {
        val s = settings.value
        // alerts off, or a follower that mirrors the wearer's alerts: nothing this phone decides quiets anything
        if (!s.on || (glucose.value.role == Role.Follower && s.source == AlertSource.WEARER)) return QuietRules.NONE
        return QuietRules(
            afterInsulinMin = s.afterInsulin.min.takeIf { s.afterInsulin.on },
            afterCarbsMin = s.afterCarbs.min.takeIf { s.afterCarbs.on },
        )
    }
}
