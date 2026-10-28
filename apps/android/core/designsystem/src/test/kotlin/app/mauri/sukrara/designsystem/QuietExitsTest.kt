// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.designsystem

import app.mauri.sukrara.alerts.Side
import app.mauri.sukrara.core.model.AlertQuietReason
import app.mauri.sukrara.core.model.AlertQuietSide
import app.mauri.sukrara.core.model.AlertUiState
import app.mauri.sukrara.designsystem.alerts.QuietExit
import app.mauri.sukrara.designsystem.alerts.QuietExits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Every quiet state shown has its way out (Complete flows), the same on home, Settings and TalkBack. */
class QuietExitsTest {
    private val until = Instant.ofEpochSecond(1_800_000_000)

    private fun side(
        kind: String,
        reason: AlertQuietReason?,
        relaxMin: Int = 0,
    ) = AlertQuietSide(kind, matching = true, reason = reason, until = until, relaxMin = relaxMin, canRemindLess = true)

    @Test fun aMuteHasUnmuteAndItsLengths() {
        val q = side("low", AlertQuietReason.Muted)
        assertEquals(listOf(QuietExit.Unmute), QuietExits.of(q))
        assertEquals(listOf(15, 30, 60, 120), QuietExits.changeTo(q))
        assertEquals(Side.LOW, QuietExits.side(q))
    }

    @Test fun aTreatmentOrFallingQuietHasAlertMeAnywayAndNoLengths() {
        for (r in listOf(AlertQuietReason.AfterCarbs, AlertQuietReason.AfterInsulin, AlertQuietReason.FallingBack)) {
            val q = side("high", r)
            assertEquals(listOf(QuietExit.AlertAnyway), QuietExits.of(q))
            assertTrue(QuietExits.changeTo(q).isEmpty())
        }
    }

    @Test fun remindingLessAddsRemindAsUsual() {
        assertEquals(listOf(QuietExit.RemindAsUsual), QuietExits.of(side("low", null, relaxMin = 30)))
        assertEquals(
            listOf(QuietExit.Unmute, QuietExit.RemindAsUsual),
            QuietExits.of(side("low", AlertQuietReason.Muted, relaxMin = 30)),
        )
    }

    @Test fun activeKeepsOnlyStatesWithAWayOut() {
        val alerts =
            AlertUiState(
                quiet =
                    listOf(
                        side("low", AlertQuietReason.Muted),
                        // not quiet and not reminding less: nothing to show
                        side("high", null),
                        side("unknown_kind", AlertQuietReason.Muted),
                        side("falling_fast", null, relaxMin = 20),
                    ),
            )
        assertEquals(listOf("low", "falling_fast"), QuietExits.active(alerts).map { it.kind })
    }
}
