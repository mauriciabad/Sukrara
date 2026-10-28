// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.settings

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import app.mauri.sukrara.alerts.AlertKind
import app.mauri.sukrara.core.model.AlertQuietReason
import app.mauri.sukrara.core.model.AlertQuietSide
import app.mauri.sukrara.core.model.AlertUiState
import app.mauri.sukrara.core.testing.FakeClock
import app.mauri.sukrara.designsystem.theme.SukraraTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/** The Settings strips word their state with `MuteText`, the lines of contracts/alerts/copy.json (en + es). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MuteStripTextTest {
    @get:Rule val compose = createComposeRule()

    private val now = Instant.parse("2026-10-27T14:00:00Z")
    private val clock = FakeClock(now)

    private fun side(
        reason: AlertQuietReason?,
        min: Long?,
    ) = AlertQuietSide("low", matching = true, reason = reason, until = min?.let { now.plusSeconds(it * 60) }, relaxMin = 30, canRemindLess = false)

    private fun showStrip(q: AlertQuietSide) =
        compose.setContent { SukraraTheme(dark = false, dynamic = false) { QuietStrip(AlertKind.VERY_LOW, q, clock, AlertKindActions()) } }

    @Test fun muteAll() {
        compose.setContent {
            SukraraTheme(dark = false, dynamic = false) { MuteAllStrip(AlertUiState(mutedUntil = now.plusSeconds(72 * 60)), clock, {}, {}) }
        }
        compose.onNodeWithText("Alerts muted · 1 h 12 min left").assertExists()
    }

    @Test fun lowQuietAfterCarbs() {
        showStrip(side(AlertQuietReason.AfterCarbs, 12))
        compose.onNodeWithText("Low alerts muted after carbs · 12 min left").assertExists()
    }

    @Test fun lowMutedWithoutAnEnd() {
        showStrip(side(AlertQuietReason.Muted, null))
        compose.onNodeWithText("Low alerts muted").assertExists()
    }

    @Test fun anEndedMuteShowsNothing() {
        showStrip(side(AlertQuietReason.AfterCarbs, 0))
        compose.onNodeWithText("Low alerts muted after carbs", substring = true).assertDoesNotExist()
    }

    @Test fun remindLess() {
        showStrip(side(null, null))
        compose.onNodeWithText("Low reminders every 30 min").assertExists()
    }

    @Test @Config(qualifiers = "es")
    fun spanish() {
        showStrip(side(AlertQuietReason.Muted, 1))
        compose.onNodeWithText("Alertas de glucosa baja silenciadas · queda 1 min").assertExists()
    }
}
