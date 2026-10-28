// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.mauri.sukrara.core.model.CardStatus
import app.mauri.sukrara.core.model.Reading
import app.mauri.sukrara.core.model.SourceId
import app.mauri.sukrara.designsystem.theme.SukraraTheme
import app.mauri.sukrara.feature.home.Samples.NOW
import app.mauri.sukrara.graph.GraphConfig
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Duration
import kotlin.math.sin

/**
 * Home's card over a month of synthetic history (never real data): zoomed out to 7 days, scrolled back a day with the
 * *Now* pill, and the multi-day slots fed by the stored days. Images go to build/outputs/roborazzi.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xxhdpi")
class HistoryGallery {
    @get:Rule val compose = createComposeRule()

    /** 30 days every 5 min: a daily wave, higher in the evenings of the older weeks. */
    private val month: List<Reading> =
        (0 until 30 * 288).map { i ->
            val t = NOW - Duration.ofMinutes(5L * (30 * 288 - 1 - i))
            val day = sin(i / 288.0 * 2 * Math.PI) * 50
            val older = if (i < 20 * 288) 25 else 0
            Reading(t, (130 + day + older + sin(i / 17.0) * 15).toInt().coerceIn(45, 380), -0.4, SourceId.Libre3)
        }

    private val glucose =
        Samples.wearer().let { w ->
            w.copy(latest = month.last(), recent = month.filter { it.t >= NOW - Duration.ofHours(24) })
        }

    private val shots =
        listOf(
            "week" to { ChartScroll(spanS = 7 * 86_400L) },
            "scrolled-now-pill" to { ChartScroll(endS = NOW.epochSecond - 86_400L) },
        )

    @Test fun gallery() {
        var scroll by mutableStateOf(ChartScroll())
        var dark by mutableStateOf(false)
        var scale by mutableFloatStateOf(1f)
        var status by mutableStateOf<CardStatus>(CardStatus.None)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                SukraraTheme(dark = dark, dynamic = false) {
                    Box(Modifier.background(MaterialTheme.colorScheme.background).padding(16.dp)) {
                        PanelCard(
                            glucose = glucose.copy(alerts = glucose.alerts.copy(status = status)),
                            history = month,
                            treatments = emptyList(),
                            now = NOW,
                            config = GraphConfig(),
                            description = "",
                            scroll = scroll,
                        )
                    }
                }
            }
        }
        for ((name, make) in shots) {
            for (isDark in listOf(false, true)) {
                for (fontScale in listOf(1f, 2f)) {
                    scroll = make()
                    dark = isDark
                    scale = fontScale
                    compose.mainClock.advanceTimeBy(2_000)
                    compose.waitForIdle()
                    val suffix = (if (isDark) "-dark" else "-light") + if (fontScale > 1f) "-font2x" else "-font1x"
                    compose.onRoot().captureRoboImage("build/outputs/roborazzi/home-history-$name$suffix.png")
                }
            }
        }
    }
}
