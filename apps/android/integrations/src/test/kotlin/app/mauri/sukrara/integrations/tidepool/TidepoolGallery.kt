// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.integrations.tidepool

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
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
import app.mauri.sukrara.core.model.Role
import app.mauri.sukrara.designsystem.theme.SukraraTheme
import app.mauri.sukrara.integrations.upload.UploadStatus
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Duration
import java.time.Instant

/**
 * The Tidepool page for the UX review (synthetic state only): light + dark × default + largest font, plus Spanish.
 * Images go to build/outputs/roborazzi (no goldens are committed).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xxhdpi")
class TidepoolGallery {
    @get:Rule val compose = createComposeRule()

    private val now = Instant.parse("2026-10-27T14:00:00Z")
    private val on = TpSettings(on = true)
    private val email = "person@example.com"

    private fun page(state: TpPageState): @Composable () -> Unit =
        { TidepoolContent(state, TpSignInUi.Idle, now, TidepoolActions(), false, {}) }

    private val pages: List<Pair<String, @Composable () -> Unit>> =
        listOf(
            "signed-out" to page(TpPageState(TpSettings(), false, "", UploadStatus.Off, Role.Wearer)),
            "ok" to page(TpPageState(on, true, email, UploadStatus.Ok(now - Duration.ofMinutes(3), 14), Role.Wearer)),
            "error" to page(TpPageState(on, true, email, UploadStatus.Failing("HTTP 503", now - Duration.ofHours(2)), Role.Wearer)),
            "signed-out-by-tidepool" to page(TpPageState(on, false, email, UploadStatus.AuthFailed, Role.Wearer)),
            "follower-choice" to page(TpPageState(on, true, email, UploadStatus.Off, Role.Follower)),
        )

    private fun gallery(
        dark: Boolean,
        fontScale: Float,
        prefix: String = "",
    ) {
        var current by mutableStateOf(pages.first().second)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                SukraraTheme(dark = dark, dynamic = false) {
                    Column(
                        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainer).verticalScroll(rememberScrollState()),
                    ) {
                        current()
                    }
                }
            }
        }
        val theme = if (dark) "dark" else "light"
        val font = if (fontScale > 1f) "font2x" else "font1x"
        pages.forEach { (name, page) ->
            current = page
            compose.waitForIdle()
            compose.onRoot().captureRoboImage("build/outputs/roborazzi/tidepool-$prefix$name-$theme-$font.png")
        }
    }

    @Test fun lightDefault() = gallery(dark = false, fontScale = 1f)

    @Test fun darkDefault() = gallery(dark = true, fontScale = 1f)

    @Test fun lightLargest() = gallery(dark = false, fontScale = 2f)

    @Test fun darkLargest() = gallery(dark = true, fontScale = 2f)

    @Test
    @Config(qualifiers = "es")
    fun spanish() = gallery(dark = false, fontScale = 1f, prefix = "es-")
}
