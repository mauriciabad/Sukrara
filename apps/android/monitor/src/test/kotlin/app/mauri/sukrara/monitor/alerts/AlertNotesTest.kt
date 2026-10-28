// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.monitor.alerts

import app.mauri.sukrara.core.model.AlertNote
import app.mauri.sukrara.core.model.AlertNoteType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Duration
import java.time.Instant

/** Settings › Alerts › Lately: what the runtime remembers, newest first, bounded, and kept across a restart. */
class AlertNotesTest {
    @Test fun alertMutesAndClearAreRememberedAcrossARestart() =
        runBlocking {
            val rig = AlertRig()
            val runtime = rig.runtime()
            runtime.onState(rig.wearerState(rig.reading(120)))
            rig.clock.instant = rig.clock.instant + Duration.ofMinutes(5)
            runtime.onState(rig.wearerState(rig.reading(60)))
            runtime.mute("low", 30)
            runtime.muteAll(60)
            runtime.unmuteAll()
            val types = rig.published.lately.map { it.type }
            assertEquals(
                listOf(AlertNoteType.AllUnmuted, AlertNoteType.AllMuted, AlertNoteType.Muted, AlertNoteType.Fired),
                types.take(4),
            )
            val fired = rig.published.lately.first { it.type == AlertNoteType.Fired }
            assertEquals("low", fired.kind)
            assertEquals(60, fired.mgdl)
            assertEquals(
                60,
                rig.published.lately
                    .first { it.type == AlertNoteType.AllMuted }
                    .minutes,
            )

            // a process death: the new runtime publishes the same history
            val again = rig.runtime()
            again.start()
            again.onState(rig.wearerState(rig.reading(60)))
            assertEquals(
                types,
                rig.published.lately
                    .map { it.type }
                    .drop(rig.published.lately.size - types.size),
            )
        }

    @Test fun keepsTheNewestLines() {
        val at = Instant.ofEpochSecond(1_000)
        val many = List(AlertNotes.MAX + 5) { AlertNote(at.plusSeconds(it.toLong()), AlertNoteType.AllUnmuted) }
        val kept = AlertNotes.append(emptyList(), many)
        assertEquals(AlertNotes.MAX, kept.size)
        assertEquals(many.last().at.epochSecond, kept.last().t)
    }

    @Test fun unknownSavedTypeIsDropped() {
        assertEquals(null, SavedNote(1, "Gone").note())
    }
}
