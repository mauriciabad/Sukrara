// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.settings

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import app.mauri.sukrara.alerts.AlertKind
import app.mauri.sukrara.alerts.AlertSettings
import app.mauri.sukrara.core.model.AlertUiState
import app.mauri.sukrara.core.testing.FakeClock
import app.mauri.sukrara.designsystem.theme.SukraraTheme
import app.mauri.sukrara.feature.settings.graph.GraphActions
import app.mauri.sukrara.feature.settings.graph.GraphContent
import app.mauri.sukrara.graph.GraphConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * A follower not in Custom (*Settings from*): the mirrored rows show the values in use and can't be changed; this
 * phone's own rows (alarm, sounds, the time per place, no data) stay editable.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ReadOnlyRowsTest {
    @get:Rule val compose = createComposeRule()

    private val clock = FakeClock(Instant.parse("2026-10-27T14:00:00Z"))

    private fun kindPage(
        kind: AlertKind,
        mirrored: Boolean,
    ) = compose.setContent {
        SukraraTheme(dark = false, dynamic = false) {
            AlertKindContent(kind, AlertsUi(AlertSettings(), AlertUiState(), mirrored = mirrored), clock, AlertKindActions()) {}
        }
    }

    @Test fun mirroredAlertShowsItsRulesReadOnly() {
        kindPage(AlertKind.LOW, mirrored = true)
        compose.onNodeWithText("Use Low alert").assertIsNotEnabled()
        compose.onNodeWithText("Glucose").assertIsNotEnabled()
        compose.onNodeWithText("Like an alarm").assertIsEnabled()
    }

    @Test fun noDataStaysThisPhones() {
        kindPage(AlertKind.NO_DATA, mirrored = true)
        compose.onNodeWithText("Like an alarm").assertIsEnabled()
        assertTrue(!AlertsUi(AlertSettings(), AlertUiState(), mirrored = true).mirrors(AlertKind.NO_DATA))
    }

    @Test fun customEditsEverything() {
        kindPage(AlertKind.LOW, mirrored = false)
        compose.onNodeWithText("Glucose").assertIsEnabled()
    }

    @Test fun mirroredAlertsPageHasNoThresholdReview() {
        compose.setContent {
            SukraraTheme(dark = false, dynamic = false) {
                AlertsSettingsContent(AlertsUi(AlertSettings(), AlertUiState(), mirrored = true), null, clock, AlertsActions()) {}
            }
        }
        assertEquals(0, compose.onAllNodesWithText("Review alert thresholds").fetchSemanticsNodes().size)
    }

    @Test fun graphMirroredSectionsReadOnlyPerPlaceTimeEditable() {
        compose.setContent {
            SukraraTheme(dark = false, dynamic = false) { GraphContent(GraphConfig(), GraphActions(), {}, readOnly = true) }
        }
        compose.onNodeWithText("Vertical lines every").assertIsNotEnabled()
        // the time shown in the app is this phone's own
        compose.onAllNodesWithText("App")[0].assertIsEnabled()
    }

    @Test fun rangesReadOnly() {
        compose.setContent {
            SukraraTheme(dark = false, dynamic = false) { RangesContent(GraphPrefs(), { _, _ -> }, {}, readOnly = true) }
        }
        compose.onNodeWithText("Low below").assertIsNotEnabled()
    }
}
