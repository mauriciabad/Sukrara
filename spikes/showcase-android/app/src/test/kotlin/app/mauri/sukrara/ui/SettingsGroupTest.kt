// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.android.settingslib.spa.framework.theme.SettingsTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A row must show a new value as soon as the state it reads changes. Before SettingsGroup was inline, a state read
 * inside its rows recomposed only the content lambda, which filled a list nobody drew again: switches kept showing
 * the old summary until the page was opened again (seen with "Sensor end in calendar" and the Nightscout switch).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class SettingsGroupTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Test fun rowsFollowTheStateTheyRead() {
        val on = mutableStateOf(false)
        compose.setContent { SettingsTheme { SettingsGroup { info("Feature", if (on.value) "On" else "Off") } } }
        compose.onNodeWithText("Off").assertExists()
        compose.runOnIdle { on.value = true }
        compose.onNodeWithText("On").assertExists()
        compose.onNodeWithText("Off").assertDoesNotExist()
    }
}
