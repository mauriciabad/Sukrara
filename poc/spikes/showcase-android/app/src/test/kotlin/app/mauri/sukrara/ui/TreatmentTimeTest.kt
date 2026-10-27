// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import app.mauri.sukrara.treatments.NewEntry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** "When?" in minutes ago (user, 2026-10-21): the chip says "16 min ago" and Save logs the entries 16 min before saving. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class TreatmentTimeTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Test fun minutesAgoResolvesWhenSaving() = minutesAgo()

    /** A full-width minutes field inside AlertDialog never got idle at this width (an endless layout pass). */
    @Test @Config(qualifiers = "w411dp-h891dp") fun minutesAgoOnAWidePhone() = minutesAgo()

    private fun minutesAgo() {
        var saved = emptyList<NewEntry>()
        compose.setContent { MaterialTheme { TreatmentDialog({}, { saved = it }, inWindow = true) } }
        compose.onNodeWithText("Now").performClick()
        compose.onNodeWithTag("when_ago").performClick()
        compose.onNodeWithText("15 min").performClick()
        compose.onNodeWithTag("when_minutes").performTextReplacement("1440")
        compose.onNodeWithText("OK").assertIsNotEnabled()             // a day or more: like the time picker, no
        compose.onNodeWithTag("when_minutes").performTextReplacement("16")
        compose.onNodeWithText("OK").performClick()
        compose.onNodeWithText("16 min ago").assertExists()
        compose.onNodeWithTag("treatment_carbs").performTextInput("30")
        val before = System.currentTimeMillis() / 1000
        compose.onNodeWithText("Save").performClick()
        val after = System.currentTimeMillis() / 1000
        assertEquals(1, saved.size)
        assert(saved[0].at in before - 960..after - 960) { "at ${saved[0].at}, want ${before - 960}..${after - 960}" }
    }
}
