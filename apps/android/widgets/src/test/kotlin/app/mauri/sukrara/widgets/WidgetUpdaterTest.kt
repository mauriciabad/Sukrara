// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.widgets

import app.mauri.sukrara.core.model.GlucoseState
import app.mauri.sukrara.core.model.LinkState
import app.mauri.sukrara.core.model.Reading
import app.mauri.sukrara.core.model.Role
import app.mauri.sukrara.core.model.SourceId
import app.mauri.sukrara.core.model.SourceStatus
import app.mauri.sukrara.core.model.Treatment
import app.mauri.sukrara.core.testing.FakeClock
import app.mauri.sukrara.graph.GraphConfig
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.time.Duration
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class WidgetUpdaterTest {
    private val t0 = Instant.parse("2026-10-27T08:00:00Z")
    private val clock = FakeClock(t0)
    private val glucose = MutableStateFlow(state(reading(t0, 112)))
    private val treatments = MutableStateFlow(emptyList<Treatment>())
    private val config = MutableStateFlow(GraphConfig())
    private val placements = MutableStateFlow(WidgetPlacements())
    private val ticks = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val screen = MutableStateFlow(true)
    private var dark = false
    private var redraws = 0

    private fun reading(
        at: Instant,
        mgdl: Int,
    ) = Reading(at, mgdl, 0.2, SourceId("test"))

    private fun state(latest: Reading) =
        GlucoseState.Initial.copy(role = Role.Wearer, latest = latest, recent = listOf(latest), source = SourceStatus.Live(latest.t))

    private fun TestScope.updater() =
        WidgetUpdater(backgroundScope, clock, glucose, treatments, config, placements, ticks, screen, { dark }, { redraws++ })
            .also {
                it.start()
                runCurrent()
            }

    @Test
    fun `draws once at start and again only for a new reading`() =
        runTest {
            val u = updater()
            assertEquals(1, redraws)
            glucose.value = glucose.value.copy(source = SourceStatus.Live(t0.plusSeconds(5)))
            runCurrent()
            assertEquals("a change the picture doesn't show draws nothing", 1, redraws)
            clock.advance(Duration.ofMinutes(1))
            glucose.value = state(reading(clock.now(), 118))
            runCurrent()
            assertEquals(2, redraws)
            assertEquals(
                118,
                u.frame.value
                    ?.glucose
                    ?.latest
                    ?.mgdl,
            )
        }

    @Test
    fun `a freshness tick redraws only when the step changed`() =
        runTest {
            updater()
            clock.advance(Duration.ofSeconds(30))
            ticks.emit(Unit)
            runCurrent()
            assertEquals("same step (fresh)", 1, redraws)
            clock.advance(Duration.ofMinutes(5))
            ticks.emit(Unit)
            runCurrent()
            assertEquals("struck through at 5 min", 2, redraws)
            clock.advance(Duration.ofMinutes(15))
            ticks.emit(Unit)
            runCurrent()
            assertEquals("hidden at 20 min", 3, redraws)
        }

    @Test
    fun `nothing is drawn while the screen is off and one redraw catches up when it comes on`() =
        runTest {
            updater()
            screen.value = false
            runCurrent()
            repeat(3) {
                clock.advance(Duration.ofMinutes(1))
                glucose.value = state(reading(clock.now(), 120 + it))
                runCurrent()
            }
            assertEquals(1, redraws)
            screen.value = true
            runCurrent()
            assertEquals(2, redraws)
            screen.value = false
            runCurrent()
            screen.value = true
            runCurrent()
            assertEquals("screen on without a change draws nothing", 2, redraws)
        }

    @Test
    fun `settings, theme and the follower name redraw but the link heartbeat does not`() =
        runTest {
            updater()
            placements.value = WidgetPlacements(mapOf(7 to WidgetOverrides(hours = 3)))
            runCurrent()
            assertEquals(2, redraws)
            config.value = GraphConfig(defaultHours = 6)
            runCurrent()
            assertEquals(3, redraws)
            dark = true
            ticks.emit(Unit)
            runCurrent()
            assertEquals(4, redraws)
            glucose.value = glucose.value.copy(role = Role.Follower, link = LinkState("Ana", t0))
            runCurrent()
            assertEquals(5, redraws)
            glucose.value = glucose.value.copy(link = LinkState("Ana", t0.plusSeconds(30)))
            runCurrent()
            assertEquals(5, redraws)
        }

    @Test
    fun `current gives a frame before anything was drawn (screen off at start)`() =
        runTest {
            screen.value = false
            val u = updater()
            assertEquals(0, redraws)
            assertNotNull(u.current())
        }
}
