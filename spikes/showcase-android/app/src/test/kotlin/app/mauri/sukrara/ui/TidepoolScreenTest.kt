// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.ui

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.android.settingslib.spa.framework.theme.SettingsTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The Tidepool page signed out (docs/27): everything off, and the follower's own decision only on the follower's page. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class TidepoolScreenTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Test fun wearerSignedOut() {
        compose.setContent { SettingsTheme { TidepoolSettings(follower = false) {} } }
        compose.onNodeWithText("Not signed in").assertExists()
        compose.onNodeWithText("Upload to Tidepool").assertExists()
        compose.onNodeWithText("Sign in first").assertExists()
        compose.onNodeWithText("Upload the person you follow").assertDoesNotExist()
    }

    @Test fun followerHasItsOwnChoice() {
        compose.setContent { SettingsTheme { TidepoolSettings(follower = true) {} } }
        compose.onNodeWithText("Upload the person you follow").assertExists()
    }
}
