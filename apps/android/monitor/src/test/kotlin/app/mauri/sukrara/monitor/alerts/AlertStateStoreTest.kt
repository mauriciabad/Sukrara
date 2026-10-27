// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.monitor.alerts

import app.mauri.sukrara.alerts.AlertGroup
import app.mauri.sukrara.alerts.AlertKind
import app.mauri.sukrara.alerts.AlertSettings
import app.mauri.sukrara.alerts.KindRule
import app.mauri.sukrara.core.model.AlertQuietReason
import app.mauri.sukrara.core.model.AlertUiState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What survives a process death (a new runtime over the same stores).
 */
class AlertStateStoreTest {
    private val rig = AlertRig()

    @Test fun episodeAndMuteSurviveProcessDeathWithoutRingingAgain() = runTest {
        val first = rig.runtime()
        first.onState(rig.wearerState(latest = rig.reading(60)))
        assertEquals(listOf("show low"), rig.presenter.log)
        first.mute("low", 30)
        rig.presenter.log.clear()

        val second = rig.runtime() // the process died
        second.start()
        assertFalse("an ongoing alert never rings again after a restart", rig.presenter.log.any { it.startsWith("show") })
        assertEquals(AlertQuietReason.Muted, rig.published.quiet.single().reason)
        assertTrue(rig.presenter.slots[AlertGroup.LEVEL]!!.startsWith("muted low"))
    }

    @Test fun muteAllSurvivesAndHasItsWayOut() = runTest {
        val first = rig.runtime()
        first.onState(rig.wearerState(latest = rig.reading(120)))
        first.muteAll(null)
        assertEquals(AlertUiState.UNTIL_TURNED_OFF, rig.published.mutedUntil)
        val second = rig.runtime()
        second.start()
        assertEquals(AlertUiState.UNTIL_TURNED_OFF, rig.published.mutedUntil)
        second.unmuteAll()
        assertEquals(null, rig.published.mutedUntil)
    }


    @Test fun recordRoundTripsThroughJson() = runTest {
        rig.runtime().onState(rig.wearerState(latest = rig.reading(60)))
        val saved = rig.record.data.first()
        val back = Json.decodeFromString(AlertRecord.serializer(), Json.encodeToString(AlertRecord.serializer(), saved))
        assertEquals(Json.encodeToString(AlertRecord.serializer(), saved), Json.encodeToString(AlertRecord.serializer(), back))
        assertTrue(back.monitoring)
    }

    @Test fun badSettingsJsonReadsAsDefaults() = runTest {
        rig.settingsRepo.set(AlertStateStore.SETTINGS, "not json")
        assertEquals(AlertSettings(), rig.store.settings.first())
        val custom = AlertSettings().withRule(AlertKind.LOW, KindRule(on = true, threshold = 75.0, repeatMin = 5))
        rig.store.setSettings(custom)
        assertEquals(75.0, rig.store.settings.first().rule(AlertKind.LOW).threshold, 0.0)
    }

    @Test fun updatesFromTwoScreensBothLand() = runTest {
        rig.store.updateSettings { it.withRule(AlertKind.LOW, it.rule(AlertKind.LOW).copy(threshold = 75.0)) }
        rig.store.updateSettings { it.withRule(AlertKind.HIGH, it.rule(AlertKind.HIGH).copy(threshold = 200.0)) }
        val stored = rig.store.settings.first()
        assertEquals(75.0, stored.rule(AlertKind.LOW).threshold, 0.0)
        assertEquals(200.0, stored.rule(AlertKind.HIGH).threshold, 0.0)
    }
}
