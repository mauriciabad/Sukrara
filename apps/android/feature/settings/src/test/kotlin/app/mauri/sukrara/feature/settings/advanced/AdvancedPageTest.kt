// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.settings.advanced

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.mauri.sukrara.designsystem.theme.SukraraTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AdvancedPageTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `helpers name the file, the relay host and order the log`() {
        assertEquals("sukrara-settings-2026-10-28.json", settingsFileName(LocalDate.of(2026, 10, 28)))
        assertEquals("relay.example.invalid", hostOf("wss://relay.example.invalid/"))
        assertEquals("not a url", hostOf("not a url"))
        assertEquals(listOf("c", "b"), newestFirst("a\nb\n\nc\n").take(2))
        assertEquals(300, newestFirst((1..400).joinToString("\n")).size)
    }

    @Test
    fun `reset asks first, then runs, reconnect and log are one tap`() {
        var reset = 0
        var check = 0
        var log = 0
        compose.setContent {
            SukraraTheme(dark = false, dynamic = false) {
                AdvancedContent(
                    AdvancedState(relays = listOf(RelayRow("wss://r.example.invalid", connected = false))),
                    AdvancedActions(reset = { reset++ }, checkNow = { check++ }, openLog = { log++ }),
                ) {}
            }
        }
        compose.onNodeWithText("Reset all settings").performScrollTo().performClick()
        assertEquals(0, reset)
        compose.onNodeWithText("Reset").performClick()
        assertEquals(1, reset)
        compose.onNodeWithText("Reconnect now").performScrollTo().performClick()
        compose.onNodeWithText("Log").performScrollTo().performClick()
        assertTrue(check == 1 && log == 1)
    }
}
