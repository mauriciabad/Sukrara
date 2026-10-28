// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.sensor.newsensor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** CLAUDE.md "Complete flows": every step the person can be in shows a way out. */
class NewSensorPageTest {
    @Test
    fun `every step but the tap being read has exactly one exit, shown last`() {
        Steps.all.filter { it.second != NewSensorStep.ReadingNfc }.forEach { (name, step) ->
            val actions = step.page().actions
            assertEquals(name, 1, actions.count { it.isExit })
            assertTrue(name, actions.last().isExit)
        }
    }

    @Test
    fun `the tap being read has no button (stopping could lose a started sensor's pairing) and shows progress`() {
        val page = NewSensorStep.ReadingNfc.page()
        assertTrue(page.actions.isEmpty())
        assertTrue(page.busy)
    }

    @Test
    fun `before the tap the exit cancels, after it the exit only closes the screen`() {
        Steps.all.forEach { (name, step) ->
            val exit = step.page().actions.lastOrNull() ?: return@forEach
            val expected = when {
                step.isPaired -> NewSensorAction.Close
                step.isFinal || step is NewSensorStep.NfcFailed || step is NewSensorStep.TakeoverFailed -> NewSensorAction.Done
                else -> NewSensorAction.Cancel
            }
            assertEquals(name, expected, exit)
        }
    }

    @Test
    fun `a dead end offers Juggluco first - no core, no NFC, not a Libre 3`() {
        listOf(NewSensorStep.CoreMissing, NewSensorStep.NoNfc, NewSensorStep.NfcFailed(NfcFailure.NotLibre3)).forEach {
            assertEquals(it.toString(), NewSensorAction.UseJuggluco, it.page().actions.first())
        }
    }

    @Test
    fun `a failed tap can always switch to Juggluco`() {
        NfcFailure.entries.forEach { assertTrue(NewSensorStep.NfcFailed(it).page().actions.contains(NewSensorAction.UseJuggluco)) }
    }
}
