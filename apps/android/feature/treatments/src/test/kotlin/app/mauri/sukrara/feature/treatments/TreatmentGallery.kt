// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.treatments

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.mauri.sukrara.designsystem.theme.SukraraTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant
import java.time.ZoneOffset

/**
 * The quick Add treatment dialog for the UX review (synthetic data only): empty, filled, error and over the lock
 * screen, light + dark × default + largest font, one phone size. Images go to build/outputs/roborazzi.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xxhdpi", application = TestTreatmentsApp::class)
class TreatmentGallery {
    @get:Rule val compose = createComposeRule()

    private val now = Instant.parse("2026-06-15T12:30:00Z")

    private val states: List<Pair<String, () -> TreatmentFormState>> =
        listOf(
            "empty" to { TreatmentFormState() },
            "filled" to { TreatmentFormState(rapid = "4.5", carbs = "45", note = "Pasta", at = now.minusSeconds(1200)) },
            "error" to { TreatmentFormState(rapid = "120", carbs = "30") },
            // minutes-ago mode and the quiet line (insulin + carbs entered 16 min ago)
            "ago-quiet" to { TreatmentFormState(rapid = "3", carbs = "20", ago = 16) },
        )

    @Test fun gallery() {
        val shots = mutableListOf<Triple<String, TreatmentFormState, LockInfo?>>()
        for ((name, make) in states) shots += Triple(name, make(), null)
        shots += Triple("lock", TreatmentFormState(carbs = "15"), LockInfo(12) {})
        var current by mutableStateOf(shots.first())
        var dark by mutableStateOf(false)
        var scale by mutableFloatStateOf(1f)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                SukraraTheme(dark = dark, dynamic = false) {
                    Box(
                        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f)).padding(16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        TreatmentDialogContent(
                            form = current.second,
                            focusCarbs = false,
                            zone = ZoneOffset.UTC,
                            now = { now },
                            onSave = { _, _ -> },
                            onCancel = {},
                            onOpenApp = if (current.third == null) ({}) else null,
                            lock = current.third,
                            quiet = QuietRules(afterInsulinMin = 60, afterCarbsMin = 30),
                        )
                    }
                }
            }
        }
        for (shot in shots) {
            for (isDark in listOf(false, true)) {
                for (fontScale in listOf(1f, 2f)) {
                    current = shot
                    dark = isDark
                    scale = fontScale
                    compose.waitForIdle()
                    val suffix = (if (isDark) "-dark" else "-light") + if (fontScale > 1f) "-largest" else ""
                    compose.onRoot().captureRoboImage("build/outputs/roborazzi/treatment-${shot.first}$suffix.png")
                }
            }
        }
    }

    /** The *When?* dialog in its *Minutes ago* mode with the 15-minute chip picked (light: dark is the same dialog). */
    @Test fun whenMinutesAgo() {
        val form = TreatmentFormState(rapid = "2")
        compose.setContent {
            SukraraTheme(dark = false, dynamic = false) {
                TreatmentDialogContent(
                    form = form,
                    focusCarbs = false,
                    zone = ZoneOffset.UTC,
                    now = { now },
                    onSave = { _, _ -> },
                    onCancel = {},
                )
            }
        }
        compose.onNodeWithTag("treatment-when").performClick()
        compose.onNodeWithTag("when-ago").performClick()
        compose.onNodeWithText("15 min").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("treatment-time").captureRoboImage("build/outputs/roborazzi/treatment-when-ago-light.png")
        compose.onNodeWithText("OK").performClick()
        compose.waitForIdle()
        check(form.ago == 15) { "ago ${form.ago}" }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/treatment-when-ago-chip-light.png")
    }
}
