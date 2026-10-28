// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.home

import app.mauri.sukrara.core.model.AlertReadiness
import app.mauri.sukrara.core.model.AlertRequirement
import app.mauri.sukrara.core.model.ModuleState
import app.mauri.sukrara.feature.home.Samples.NOW
import app.mauri.sukrara.feature.home.banners.BannerKind
import app.mauri.sukrara.feature.home.banners.BannerMemory
import app.mauri.sukrara.feature.home.banners.HomeBanners
import app.mauri.sukrara.feature.home.banners.HomeEnvironment
import app.mauri.sukrara.feature.home.banners.SETUP_LATER
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Duration

class BannerSlotTest {
    private val everything =
        HomeEnvironment(nextSetupStep = "Alerts", backedUp = false, widgetPlaced = false, clinicUploadSet = false)
    private val broken = AlertReadiness(setOf(AlertRequirement.ExactAlarm, AlertRequirement.Notifications))

    private fun pick(
        readiness: AlertReadiness? = AlertReadiness.Ready,
        env: HomeEnvironment = HomeEnvironment(),
        problems: Int = 0,
        memory: BannerMemory = BannerMemory(),
        at: java.time.Instant = NOW,
        stopped: Boolean = false,
    ) = HomeBanners.pick(readiness, env, stopped, problems, memory, at)

    @Test fun `nothing to say shows no banner`() = assertNull(pick())

    @Test fun `an unknown checklist claims nothing`() = assertNull(pick(readiness = null))

    @Test fun `alert reliability comes first and names the checklist's first problem`() {
        assertEquals(BannerKind.AlertsUnreliable, pick(broken, everything, problems = 2))
        assertEquals(AlertRequirement.Notifications, broken.firstProblem)
    }

    @Test fun `monitoring stopped outranks everything, even with a value on screen`() =
        assertEquals(BannerKind.MonitoringStopped, pick(broken, everything, problems = 1, stopped = true))

    @Test fun `damaged data comes before finish setup`() =
        assertEquals(BannerKind.DataProblem, pick(env = everything, problems = 1))

    @Test fun `safety banners can't be dismissed`() {
        BannerKind.entries.filter { it.safety }.forEach { kind ->
            val failed = runCatching { BannerMemory().dismiss(kind, NOW) }.isFailure
            assertEquals("$kind dismissible", true, failed)
        }
    }

    @Test fun `finish setup later hides it for a day, then it comes back`() {
        val env = HomeEnvironment(nextSetupStep = "Alerts", backedUp = false)
        val later = BannerMemory().dismiss(BannerKind.FinishSetup, NOW)
        assertEquals(BannerKind.NotBackedUp, pick(env = env, memory = later, at = NOW.plus(Duration.ofHours(23))))
        assertEquals(BannerKind.FinishSetup, pick(env = env, memory = later, at = NOW.plus(SETUP_LATER).plusSeconds(1)))
    }

    @Test fun `a dismissed nudge stays dismissed and the next one takes the slot`() {
        val env = HomeEnvironment(backedUp = false, widgetPlaced = false)
        val memory = BannerMemory().dismiss(BannerKind.NotBackedUp, NOW)
        assertEquals(BannerKind.AddWidget, pick(env = env, memory = memory, at = NOW.plus(Duration.ofDays(30))))
    }

    @Test fun `Undo and Show closed tips bring closed tips back`() {
        val env = HomeEnvironment(backedUp = false, widgetPlaced = false)
        val two = BannerMemory().dismiss(BannerKind.NotBackedUp, NOW).dismiss(BannerKind.AddWidget, NOW)
        assertEquals(2, two.closed)
        assertEquals(null, pick(env = env, memory = two))
        assertEquals(BannerKind.AddWidget, pick(env = env, memory = two.restore(BannerKind.AddWidget)))
        assertEquals(BannerKind.NotBackedUp, pick(env = env, memory = two.restoreAll()))
        assertEquals(0, two.restoreAll().closed)
        // a kind this build no longer has isn't counted
        assertEquals(0, BannerMemory(mapOf("Gone" to 1L)).closed)
    }

    @Test fun `a closed restart card stays closed for that state and build, and comes back with a new build`() {
        val env = HomeEnvironment(backedUp = false, systemUiRestart = ModuleState.Restart, build = 7)
        assertEquals(BannerKind.RestartSystemUi, pick(env = env))
        val memory = BannerMemory().dismissKey(env.restartKey!!, NOW)
        assertEquals(BannerKind.NotBackedUp, pick(env = env, memory = memory))
        assertEquals(BannerKind.RestartSystemUi, pick(env = env.copy(build = 8), memory = memory))
        assertEquals(BannerKind.RestartSystemUi, pick(env = env.copy(systemUiRestart = ModuleState.Update), memory = memory))
        assertEquals(BannerKind.NotBackedUp, pick(env = env.copy(systemUiRestart = null), memory = memory))
    }
}
