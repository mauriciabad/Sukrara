// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.monitor.alerts

import app.mauri.sukrara.alerts.AlertKind
import app.mauri.sukrara.alerts.AlertSettings
import app.mauri.sukrara.core.model.Reading
import app.mauri.sukrara.core.model.SourceId
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * When one reading fires several alerts, the one that must be heard is posted first: Android lets an app make one
 * sound per second, so only the first posted rings (the others show without sound).
 */
class AlertRingOrderTest {
    private val rig = AlertRig()

    private fun settings(lowAlarm: Boolean) =
        AlertSettings()
            .let { it.withRule(AlertKind.LOW, it.rule(AlertKind.LOW).copy(on = true, alarm = lowAlarm)) }
            .let { it.withRule(AlertKind.FALLING_FAST, it.rule(AlertKind.FALLING_FAST).copy(on = true, alarm = !lowAlarm)) }

    private suspend fun lowAndFallingFast(s: AlertSettings): List<String> {
        rig.store.setSettings(s)
        val runtime = rig.runtime()
        runtime.onState(rig.wearerState(latest = Reading(rig.clock.now(), 62, -3.0, SourceId.Libre3)))
        return rig.presenter.log.toList()
    }

    @Test fun `an alarm-level alert is posted first`() =
        runTest {
            assertEquals(listOf("show low", "show falling_fast"), lowAndFallingFast(settings(lowAlarm = true)))
        }

    @Test fun `an alarm-level rate alert goes before a notification-level low`() =
        runTest {
            assertEquals(listOf("show falling_fast", "show low"), lowAndFallingFast(settings(lowAlarm = false)))
        }

    @Test fun `same level - the level alert before the rate alert`() =
        runTest {
            val s =
                AlertSettings()
                    .let { it.withRule(AlertKind.LOW, it.rule(AlertKind.LOW).copy(on = true, alarm = false)) }
                    .let { it.withRule(AlertKind.FALLING_FAST, it.rule(AlertKind.FALLING_FAST).copy(on = true, alarm = false)) }
            assertEquals(listOf("show low", "show falling_fast"), lowAndFallingFast(s))
        }
}
