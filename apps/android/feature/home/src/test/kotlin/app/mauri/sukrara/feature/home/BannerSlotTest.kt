// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.home

import app.mauri.sukrara.core.model.AlertReadiness
import app.mauri.sukrara.core.model.AlertRequirement
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
        HomeEnvironment(showcaseInstalled = true, nextSetupStep = "Alerts", backedUp = false, widgetPlaced = false, clinicUploadSet = false)
    private val broken = AlertReadiness(setOf(AlertRequirement.ExactAlarm, AlertRequirement.Notifications))

    private fun pick(
        readiness: AlertReadiness? = AlertReadiness.Ready,
        env: HomeEnvironment = HomeEnvironment(),
        problems: Int = 0,
        memory: BannerMemory = BannerMemory(),
        at: java.time.Instant = NOW,
    ) = HomeBanners.pick(readiness, env, problems, memory, at)

    @Test fun `nothing to say shows no banner`() = assertNull(pick())

    @Test fun `an unknown checklist claims nothing`() = assertNull(pick(readiness = null))

    @Test fun `alert reliability comes first and names the checklist's first problem`() {
        assertEquals(BannerKind.AlertsUnreliable, pick(broken, everything, problems = 2))
        assertEquals(AlertRequirement.Notifications, broken.firstProblem)
    }

    @Test fun `the showcase banner outranks setup, data and the nudges`() =
        assertEquals(BannerKind.TurnOffShowcase, pick(env = everything, problems = 1))

    @Test fun `damaged data comes before finish setup`() =
        assertEquals(BannerKind.DataProblem, pick(env = everything.copy(showcaseInstalled = false), problems = 1))

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
}
