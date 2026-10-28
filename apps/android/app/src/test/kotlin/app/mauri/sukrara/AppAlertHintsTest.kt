// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara

import app.mauri.sukrara.alerts.AlertSettings
import app.mauri.sukrara.alerts.AlertSource
import app.mauri.sukrara.alerts.QuietRule
import app.mauri.sukrara.core.model.GlucoseState
import app.mauri.sukrara.core.model.Reading
import app.mauri.sukrara.core.model.Role
import app.mauri.sukrara.core.model.SourceId
import app.mauri.sukrara.core.testing.FakeClock
import app.mauri.sukrara.feature.treatments.QuietRules
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration

/** The dialog's focus follows the latest fresh reading against the low rules, not a fired alert. */
class AppAlertHintsTest {
    private val clock = FakeClock()
    private val settings = MutableStateFlow<AlertSettings?>(AlertSettings())
    private val glucose = MutableStateFlow(GlucoseState.Initial.copy(role = Role.Wearer))
    private val hints = AppAlertHints(settings, glucose, clock)

    private fun latest(
        mgdl: Int,
        age: Duration = Duration.ofMinutes(1),
    ) {
        val r = Reading(clock.now() - age, mgdl, null, SourceId.Libre3)
        glucose.value = glucose.value.copy(latest = r, recent = listOf(r))
    }

    @Test fun lowIsTheLatestFreshReadingUnderTheLowRule() {
        latest(60)
        assertTrue(hints.lowNow())
        latest(45)
        assertTrue(hints.lowNow()) // very low is the low side too
        latest(110)
        assertFalse(hints.lowNow())
        latest(60, age = Duration.ofMinutes(30))
        assertFalse(hints.lowNow()) // stale: nothing is known now
        // a low is a low whether or not its alert is on
        settings.value = AlertSettings(on = false)
        latest(60)
        assertTrue(hints.lowNow())
        // settings not read yet: the default threshold
        settings.value = null
        assertTrue(hints.lowNow())
    }

    @Test fun quietFollowsTheRulesOnThisPhone() =
        runTest {
            assertEquals(QuietRules(60, 15), hints.quiet.first())
            settings.value = AlertSettings(afterCarbs = QuietRule(on = false, min = 15))
            assertEquals(QuietRules(60, null), hints.quiet.first())
            glucose.value = glucose.value.copy(role = Role.Follower)
            settings.value = AlertSettings(source = AlertSource.WEARER)
            assertEquals(QuietRules.NONE, hints.quiet.first())
            settings.value = AlertSettings(source = AlertSource.OWN)
            assertEquals(QuietRules(60, 15), hints.quiet.first())
            settings.value = null
            assertEquals(QuietRules.NONE, hints.quiet.first())
        }
}
