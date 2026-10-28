// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara

import app.mauri.sukrara.alerts.AlertKind
import app.mauri.sukrara.alerts.AlertReading
import app.mauri.sukrara.alerts.AlertSettings
import app.mauri.sukrara.alerts.AlertSource
import app.mauri.sukrara.alerts.Rules
import app.mauri.sukrara.core.model.Clock
import app.mauri.sukrara.core.model.GlucoseState
import app.mauri.sukrara.core.model.Role
import app.mauri.sukrara.feature.treatments.AlertHints
import app.mauri.sukrara.feature.treatments.QuietRules
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * The treatment dialog's view of the alert rules: the latest reading against the low threshold, and the
 * after-treatment quiet. [settings] = the stored alert settings, null until they're read (no quiet line is claimed).
 */
class AppAlertHints(
    private val settings: StateFlow<AlertSettings?>,
    private val glucose: StateFlow<GlucoseState>,
    private val clock: Clock,
) : AlertHints {
    /**
     * The latest fresh reading is under the low threshold, whether or not the low alert is on (eating is what a low
     * needs either way); before the settings are read, the default threshold.
     */
    override fun lowNow(): Boolean {
        val latest = glucose.value.latest ?: return false
        val reading = AlertReading(latest.t.epochSecond, latest.mgdl, latest.rateMgdlPerMin)
        if (!Rules.fresh(reading, clock.now().epochSecond)) return false
        val s = settings.value ?: AlertSettings()
        return latest.mgdl < s.rule(AlertKind.LOW).threshold
    }

    override val quiet: Flow<QuietRules> =
        combine(settings, glucose) { s, g -> rules(s, g.role) }.distinctUntilChanged()

    private fun rules(
        s: AlertSettings?,
        role: Role?,
    ): QuietRules {
        // not read yet, alerts off, or a follower that mirrors the wearer's alerts: nothing this phone decides is quiet
        if (s == null || !s.on || (role == Role.Follower && s.source == AlertSource.WEARER)) return QuietRules.NONE
        return QuietRules(
            afterInsulinMin = s.afterInsulin.min.takeIf { s.afterInsulin.on },
            afterCarbsMin = s.afterCarbs.min.takeIf { s.afterCarbs.on },
        )
    }
}
