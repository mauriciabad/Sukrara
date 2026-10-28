// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.sensor

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import app.mauri.sukrara.designsystem.theme.SukraraTheme
import app.mauri.sukrara.feature.sensor.libreview.LibreViewSignInContent
import app.mauri.sukrara.feature.sensor.libreview.SignInError
import app.mauri.sukrara.feature.sensor.libreview.SignInUi
import app.mauri.sukrara.feature.sensor.newsensor.NewSensorContent
import app.mauri.sukrara.feature.sensor.newsensor.NewSensorStep
import app.mauri.sukrara.feature.sensor.newsensor.Steps
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Every new-sensor step and the LibreView sign-in states for the UX review (synthetic only): light + dark × default +
 * largest font, one phone size, plus Spanish. Images go to build/outputs/roborazzi (no goldens are committed).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xxhdpi")
class SensorGallery {
    @get:Rule val compose = createComposeRule()

    /** The two screens closing themselves show for a frame at most: not reviewed. */
    private val steps = Steps.all.filter { it.second != NewSensorStep.LeftRunning && it.second != NewSensorStep.Cancelled }

    private val signIn =
        listOf(
            "loading" to SignInUi.Loading,
            "signed-out" to SignInUi.SignedOut(),
            "working" to SignInUi.Working,
            "wrong-password" to SignInUi.SignedOut(SignInError.WrongPassword),
            "accept-terms" to SignInUi.SignedOut(SignInError.AcceptTerms),
            "failed" to SignInUi.SignedOut(SignInError.Failed),
            "signed-in" to SignInUi.SignedIn("ana@example.com"),
        )

    @Composable
    private fun Frame(dark: Boolean, fontScale: Float, content: @Composable () -> Unit) {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
            SukraraTheme(dark = dark, dynamic = false) {
                // the app's Scaffold gives the screens their surface and content colour
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() }
            }
        }
    }

    private fun gallery(dark: Boolean, fontScale: Float, prefix: String = "", only: Set<String>? = null) {
        val theme = if (dark) "dark" else "light"
        val font = if (fontScale > 1f) "font2x" else "font1x"
        var step by mutableStateOf(steps.first().second)
        var ui by mutableStateOf<SignInUi>(SignInUi.SignedOut())
        var showSignIn by mutableStateOf(false)
        compose.mainClock.autoAdvance = true
        compose.setContent {
            Frame(dark, fontScale) {
                if (showSignIn) {
                    LibreViewSignInContent(ui, canSubmit = { _, _ -> true }, onSignIn = { _, _ -> }, onSignOut = {}, onBack = {})
                } else {
                    NewSensorContent(step, Steps.NOW) {}
                }
            }
        }
        steps.filter { only == null || it.first in only }.forEach { (name, s) ->
            step = s
            compose.waitForIdle()
            compose.onRoot().captureRoboImage("build/outputs/roborazzi/newsensor-$prefix$name-$theme-$font.png")
        }
        showSignIn = true
        signIn.filter { only == null || it.first in only }.forEach { (name, s) ->
            ui = s
            compose.waitForIdle()
            compose.onRoot().captureRoboImage("build/outputs/roborazzi/libreview-$prefix$name-$theme-$font.png")
        }
    }

    @Test fun lightDefault() = gallery(dark = false, fontScale = 1f)

    @Test fun darkDefault() = gallery(dark = true, fontScale = 1f)

    @Test fun lightLargest() = gallery(dark = false, fontScale = 2f)

    @Test fun darkLargest() = gallery(dark = true, fontScale = 2f)

    @Test
    @Config(qualifiers = "+es")
    fun spanish() =
        gallery(
            dark = true,
            fontScale = 1f,
            prefix = "es-",
            only = setOf("needs-libreview", "takeover-failed", "warming-up", "accept-terms", "signed-in"),
        )
}
