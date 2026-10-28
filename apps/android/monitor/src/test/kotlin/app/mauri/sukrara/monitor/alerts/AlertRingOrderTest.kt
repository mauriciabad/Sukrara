// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.monitor.alerts

import app.mauri.sukrara.alerts.AlertEffect
import app.mauri.sukrara.alerts.AlertEvent
import app.mauri.sukrara.alerts.AlertKind
import app.mauri.sukrara.alerts.AlertReading
import app.mauri.sukrara.alerts.AlertSettings
import app.mauri.sukrara.alerts.Firing
import app.mauri.sukrara.alerts.Side
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

    // ringOrder itself: only ringing Fires of different groups that follow each other move

    private val lowNotif = settings(lowAlarm = false)

    private fun fire(kind: AlertKind, id: String, silent: Boolean = false) =
        AlertEffect.Fire(Firing(kind, id, 1, AlertReading(1_000, 62, -3.0), 70.0, 1_000), silent)

    private fun ids(effects: List<AlertEffect>) =
        effects.map {
            when (it) {
                is AlertEffect.Fire -> (if (it.silent) "silent " else "fire ") + it.firing.id
                is AlertEffect.Clear -> "clear " + it.id
                else -> it.javaClass.simpleName
            }
        }

    @Test fun `ringing Fires of different groups move across Mirror and Back, which stay in place`() {
        val mirror = AlertEffect.Mirror(AlertEvent(id = "a", k = AlertKind.LOW, state = AlertEvent.State.entries.first(), at = 1_000))
        val effects =
            listOf(
                fire(AlertKind.LOW, "a"),
                mirror,
                AlertEffect.Back(Side.LOW),
                fire(AlertKind.FALLING_FAST, "b"),
                AlertEffect.Schedule(2_000),
            )
        assertEquals(listOf("fire b", "Mirror", "Back", "fire a", "Schedule"), ids(ringOrder(effects, lowNotif)))
    }

    @Test fun `a restart re-post stays before what replaces or clears it`() {
        // the engine re-posts what was showing (silent), then a due repeat of the same group
        val repeat = listOf(fire(AlertKind.LOW, "a", silent = true), fire(AlertKind.LOW, "a2"))
        assertEquals(ids(repeat), ids(ringOrder(repeat, lowNotif)))
        // a stale High is cleared, then no data rings
        val stale = listOf(fire(AlertKind.HIGH, "h", silent = true), AlertEffect.Clear(AlertKind.HIGH, "h"), fire(AlertKind.NO_DATA, "n"))
        assertEquals(ids(stale), ids(ringOrder(stale, lowNotif)))
        // Low became Very low: clear then fire in the same group, then a rate alert that may move only after them
        val kind =
            listOf(AlertEffect.Clear(AlertKind.LOW, "a"), fire(AlertKind.VERY_LOW, "b"), fire(AlertKind.FALLING_FAST, "c", silent = true))
        assertEquals(ids(kind), ids(ringOrder(kind, lowNotif)))
    }

    @Test fun `two ringing Fires of one group keep their order`() {
        val effects = listOf(fire(AlertKind.FALLING_FAST, "r"), fire(AlertKind.LOW, "a"), fire(AlertKind.VERY_LOW, "b"))
        // the run is [r, a] (b is a second LEVEL fire): low before falling fast there, b stays last
        assertEquals(
            listOf("fire a", "fire r", "fire b"),
            ids(ringOrder(effects, lowNotif.withRule(AlertKind.FALLING_FAST, lowNotif.rule(AlertKind.FALLING_FAST).copy(alarm = false)))),
        )
    }
}
