// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.home

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import app.mauri.sukrara.core.model.AlertReadiness
import app.mauri.sukrara.core.model.AlertRequirement
import app.mauri.sukrara.core.model.AlertUiState
import app.mauri.sukrara.core.model.GlucoseState
import app.mauri.sukrara.core.model.LinkState
import app.mauri.sukrara.core.model.Role
import app.mauri.sukrara.core.model.SensorState
import app.mauri.sukrara.core.model.ServiceState
import app.mauri.sukrara.core.model.StopReason
import app.mauri.sukrara.core.model.TreatmentKind
import app.mauri.sukrara.designsystem.theme.SukraraTheme
import app.mauri.sukrara.feature.home.Samples.NOW
import app.mauri.sukrara.feature.home.banners.BannerKind
import app.mauri.sukrara.graph.CardSurface
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Duration

/**
 * The home screen's states for the UX review (synthetic data only): light + dark × default + largest font, one phone
 * size, plus Spanish in range. Renders the registered card surface [CardSurface.APP_PANEL] through the real renderer.
 * Images go to build/outputs/roborazzi (copied to the review folder by hand; no goldens are committed).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xxhdpi")
class HomeGallery {
    @get:Rule val compose = createComposeRule()

    private val surface = CardSurface.APP_PANEL

    private fun ui(
        glucose: GlucoseState,
        banner: BannerKind? = null,
        withTreatments: Boolean = false,
        problems: Int = 0,
    ): HomeUiState {
        val look = homeLook(glucose, NOW)
        val treatments =
            if (withTreatments) {
                listOf(Samples.treatment("a", TreatmentKind.Carbs, 30.0, 25), Samples.treatment("b", TreatmentKind.RapidInsulin, 4.5, 30))
            } else {
                emptyList()
            }
        return HomeUiState(
            glucose = glucose,
            now = NOW,
            look = look,
            reason = NoDataReason.of(glucose, look),
            banner = banner,
            firstProblem = AlertReadiness(setOf(AlertRequirement.ExactAlarm)).firstProblem,
            nextSetupStep = "Alert thresholds",
            dataProblems = List(problems) { DataProblem("settings", "SerializationException", "settings.corrupt.json", NOW) },
            treatments = treatments,
            undo = null,
        )
    }

    private val states: List<Pair<String, HomeUiState>> by lazy {
        val follower = Samples.wearer().copy(role = Role.Follower, link = LinkState("Ana", NOW.minusSeconds(40)))
        listOf(
            "in-range" to ui(Samples.wearer(), withTreatments = true),
            "low" to ui(Samples.wearer(last = 62)),
            "high" to ui(Samples.wearer(last = 256)),
            "stale-6min" to ui(Samples.wearer(ageMin = 6)),
            "stale-25min" to ui(Samples.wearer(ageMin = 25)),
            "no-sensor-yet" to ui(Samples.noSensorYet()),
            "setup-incomplete" to ui(GlucoseState.Initial),
            "warming-up" to ui(Samples.wearer(ageMin = 25, sensor = SensorState.WarmingUp(NOW.plus(Duration.ofMinutes(42))))),
            "sensor-ended" to ui(Samples.wearer(sensor = SensorState.Ended)),
            "bluetooth-off" to ui(Samples.wearer(ageMin = 25, sensor = SensorState.BluetoothOff)),
            "core-missing" to ui(Samples.wearer(sensor = SensorState.CoreMissing)),
            "monitoring-stopped" to
                ui(Samples.wearer(ageMin = 25).copy(service = ServiceState.Stopped(StopReason.StartDenied)), banner = BannerKind.MonitoringStopped),
            "wearer-silent" to ui(Samples.wearer(ageMin = 30).copy(role = Role.Follower, link = LinkState("Ana", NOW.minus(Duration.ofMinutes(30))))),
            "link-revoked" to ui(follower.copy(link = LinkState("Ana", NOW.minus(Duration.ofMinutes(5)), revoked = true))),
            "follower" to ui(follower),
            "muted" to ui(Samples.wearer().copy(alerts = AlertUiState(mutedUntil = NOW.plus(Duration.ofMinutes(72))))),
            "alerts-unreliable" to ui(Samples.wearer(), banner = BannerKind.AlertsUnreliable),
            "finish-setup" to ui(Samples.wearer(), banner = BannerKind.FinishSetup),
            "data-problem" to ui(Samples.wearer(), banner = BannerKind.DataProblem, problems = 1),
        )
    }

    private fun gallery(
        dark: Boolean,
        fontScale: Float,
        only: List<String>? = null,
        prefix: String = "",
    ) {
        var current by mutableStateOf(states.first().second)
        compose.mainClock.autoAdvance = true
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                SukraraTheme(dark = dark, dynamic = false) { HomeContent(current, HomeCallbacks()) }
            }
        }
        val theme = if (dark) "dark" else "light"
        val font = if (fontScale > 1f) "font2x" else "font1x"
        states.filter { only == null || it.first in only }.forEach { (name, state) ->
            current = state
            compose.waitForIdle()
            compose.onRoot().captureRoboImage("build/outputs/roborazzi/home-$prefix$name-$theme-$font.png")
        }
    }

    @Test fun lightDefault() = gallery(dark = false, fontScale = 1f)

    @Test fun darkDefault() = gallery(dark = true, fontScale = 1f)

    @Test fun lightLargest() = gallery(dark = false, fontScale = 2f)

    @Test fun darkLargest() = gallery(dark = true, fontScale = 2f)

    @Test @Config(qualifiers = "es")
    fun spanishInRange() = gallery(dark = true, fontScale = 1f, only = listOf("in-range", "no-sensor-yet"), prefix = "es-")

    /** A small phone at the largest font: a safety banner must be on screen without scrolling (ux-batch1 F2). */
    @Test @Config(qualifiers = "w360dp-h640dp-xhdpi")
    fun smallPhoneSafetyBanner() =
        gallery(dark = false, fontScale = 2f, only = listOf("alerts-unreliable", "monitoring-stopped"), prefix = "360x640-")

    @Test fun dataProblemScreen() {
        compose.setContent {
            SukraraTheme(dark = true, dynamic = false) {
                DataProblemScreen(
                    listOf(
                        DataProblem("settings:alerts", "SerializationException", "settings.corrupt-1767.json", NOW),
                        DataProblem("database:history", "sqlite corruption", null, NOW),
                    ),
                    onCopyReport = {},
                    onAcknowledge = {},
                    onBack = {},
                    onOpenAlertSettings = {},
                )
            }
        }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/home-data-problem-screen-dark-font1x.png")
        check(surface == CardSurface.APP_PANEL)
    }
}
