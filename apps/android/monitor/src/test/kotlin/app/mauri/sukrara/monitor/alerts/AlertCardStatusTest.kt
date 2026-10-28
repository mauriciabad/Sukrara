// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.monitor.alerts

import app.mauri.sukrara.alerts.AlertKind
import app.mauri.sukrara.alerts.AlertStatus
import app.mauri.sukrara.alerts.Doses
import app.mauri.sukrara.alerts.Rules
import app.mauri.sukrara.core.model.CardStatus
import app.mauri.sukrara.core.model.TreatmentKind
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Duration
import app.mauri.sukrara.alerts.TreatmentKind as EngineTreatmentKind

/** The badge's answer the runtime publishes for every card surface (contracts/graph/status.json's statuses). */
class AlertCardStatusTest {
    private val rig = AlertRig()

    @Test fun lowAsksForCarbsThenTheCarbsCoverIt() = runTest {
        val runtime = rig.runtime()
        val low = rig.reading(60)
        runtime.onState(rig.wearerState(latest = low))
        assertEquals(CardStatus.Needed(lowSide = true), rig.published.status)
        val at = rig.clock.now().epochSecond
        runtime.onDoses(Doses(carbs = listOf(at)))
        assertEquals(CardStatus.Taken(TreatmentKind.Carbs, at, at + 15 * 60), rig.published.status)
    }

    @Test fun highAsksForInsulin() = runTest {
        val runtime = rig.runtime()
        runtime.onState(rig.wearerState(latest = rig.reading(260)))
        assertEquals(CardStatus.Needed(lowSide = false), rig.published.status)
    }

    @Test fun inRangeShowsNothingAndMuteAllShowsMuted() = runTest {
        val runtime = rig.runtime()
        runtime.onState(rig.wearerState(latest = rig.reading(120)))
        assertEquals(CardStatus.None, rig.published.status)
        runtime.muteAll(60)
        assertEquals(CardStatus.Muted(rig.clock.now().epochSecond + 3600), rig.published.status)
        runtime.muteAll(null)
        assertEquals(CardStatus.Muted(CardStatus.NEVER), rig.published.status)
    }

    @Test fun aSideMutedFromTheNotificationIsPaused() = runTest {
        val runtime = rig.runtime()
        runtime.onState(rig.wearerState(latest = rig.reading(60)))
        runtime.mute("low", 30)
        assertEquals(CardStatus.Paused(lowSide = true, until = rig.clock.now().epochSecond + 30 * 60), rig.published.status)
        rig.clock.advance(Duration.ofMinutes(31))
        runtime.onState(rig.wearerState(latest = rig.reading(62)))
        assertEquals(CardStatus.Needed(lowSide = true), rig.published.status)
    }

    @Test fun mappingKeepsNeverAndTheTreatment() {
        assertEquals(CardStatus.Paused(false, CardStatus.NEVER), AlertRuntime.cardStatusOf(AlertStatus.Paused(AlertKind.EXTREMELY_HIGH, Rules.NEVER)))
        assertEquals(
            CardStatus.Taken(TreatmentKind.RapidInsulin, 10, 20),
            AlertRuntime.cardStatusOf(AlertStatus.Taken(AlertKind.HIGH, EngineTreatmentKind.RAPID, 10, 20)),
        )
        assertEquals(CardStatus.Needed(true), AlertRuntime.cardStatusOf(AlertStatus.Needed(AlertKind.FALLING_FAST)))
    }
}
