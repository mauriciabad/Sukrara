// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.home

import app.mauri.sukrara.core.model.CardStatus
import app.mauri.sukrara.core.model.TreatmentKind
import org.junit.Assert.assertEquals
import org.junit.Test

/** Every pill the card shows does something; an ended mute's pill (not drawn) does nothing. */
class PillActionTest {
    private val now = 1_800_000_000L

    @Test fun everyStatus() {
        val cases =
            listOf(
                CardStatus.None to PillAction.None,
                CardStatus.Needed(lowSide = true) to PillAction.Treat(carbs = true),
                CardStatus.Needed(lowSide = false) to PillAction.Treat(carbs = false),
                CardStatus.Taken(TreatmentKind.Carbs, now - 60, now + 600) to PillAction.Quiet(lowSide = true, taken = true),
                CardStatus.Taken(TreatmentKind.RapidInsulin, now - 60, now + 600) to PillAction.Quiet(lowSide = false, taken = true),
                CardStatus.Paused(lowSide = true, until = now + 600) to PillAction.Quiet(lowSide = true, taken = false),
                CardStatus.Paused(lowSide = false, until = CardStatus.NEVER) to PillAction.Quiet(lowSide = false, taken = false),
                CardStatus.Muted(until = now + 600) to PillAction.MuteAll,
                CardStatus.Muted(until = CardStatus.NEVER) to PillAction.MuteAll,
                // already over: the renderer draws no pill
                CardStatus.Taken(TreatmentKind.Carbs, now - 900, now) to PillAction.None,
                CardStatus.Paused(lowSide = true, until = now - 1) to PillAction.None,
                CardStatus.Muted(until = now) to PillAction.None,
            )
        for ((status, action) in cases) assertEquals(status.toString(), action, PillAction.of(status, now, canAdd = true))
    }

    @Test fun withoutAddingEntriesTheTreatPillDoesNothing() {
        assertEquals(PillAction.None, PillAction.of(CardStatus.Needed(lowSide = true), now, canAdd = false))
        assertEquals(PillAction.MuteAll, PillAction.of(CardStatus.Muted(now + 60), now, canAdd = false))
    }

    /** Mute all on while out of range: the card shows the side as muted, and only Mute all's Unmute ends it. */
    @Test fun aSideMutedByMuteAllOpensMuteAll() {
        assertEquals(
            PillAction.MuteAll,
            PillAction.of(CardStatus.Paused(lowSide = false, until = now + 600), now, canAdd = true, muteAll = true),
        )
    }

    @Test fun anEndedSensorPairsANewOne() {
        assertEquals(PillAction.ReplaceSensor, PillAction.of(CardStatus.Needed(lowSide = true), now, canAdd = true, ended = true))
    }
}
