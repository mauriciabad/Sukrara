// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.home

import app.mauri.sukrara.alerts.Side
import app.mauri.sukrara.core.model.AlertQuietReason
import app.mauri.sukrara.core.model.AlertQuietSide
import app.mauri.sukrara.core.model.AlertUiState
import app.mauri.sukrara.designsystem.alerts.QuietExit
import org.junit.Assert.assertEquals
import org.junit.Test

/** Which quiet states home shows where: the pill's sheet, a chip each for the rest, the runtime action of each exit. */
class QuietTargetTest {
    private fun q(
        kind: String,
        reason: AlertQuietReason?,
        relaxMin: Int = 0,
    ) = AlertQuietSide(
        kind,
        matching = true,
        reason = reason,
        until = Samples.NOW.plusSeconds(600),
        relaxMin = relaxMin,
        canRemindLess = true,
    )

    private val alerts =
        AlertUiState(
            quiet =
                listOf(
                    q("very_low", AlertQuietReason.AfterCarbs),
                    q("high", AlertQuietReason.Muted, relaxMin = 30),
                    q("falling_fast", null, relaxMin = 20),
                ),
        )

    @Test fun thePillOpensItsSideWithTheExitItPromised() {
        assertEquals(QuietTarget(Side.LOW, QuietExit.AlertAnyway), QuietTarget.of(PillAction.Quiet(lowSide = true, taken = true)))
        assertEquals(QuietTarget(Side.HIGH, QuietExit.Unmute), QuietTarget.of(PillAction.Quiet(lowSide = false, taken = false)))
        assertEquals(QuietTarget(null), QuietTarget.of(PillAction.MuteAll))
        assertEquals(null, QuietTarget.of(PillAction.None))
    }

    @Test fun chipsAreWhatThePillDoesNotShow() {
        // the low pill stands for the low side; the high mute that also reminds less and the fast fall get chips
        assertEquals(
            listOf("high", "falling_fast"),
            QuietTarget.chips(alerts, PillAction.Quiet(lowSide = true, taken = true)).map { it.kind },
        )
        // no quiet pill: every quiet side has a chip
        assertEquals(3, QuietTarget.chips(alerts, PillAction.None).size)
    }

    @Test fun theSheetListsItsSide() {
        assertEquals(listOf("very_low"), QuietTarget.rows(alerts, Side.LOW).map { it.kind })
        assertEquals(3, QuietTarget.rows(alerts, null).size)
    }

    @Test fun eachExitIsOneRuntimeAction() {
        val high = alerts.quiet[1]
        assertEquals(HomeAlertAction.EndQuiet("high"), HomeAlertAction.exit(high, QuietExit.Unmute))
        assertEquals(HomeAlertAction.EndQuiet("very_low"), HomeAlertAction.exit(alerts.quiet[0], QuietExit.AlertAnyway))
        assertEquals(HomeAlertAction.RemindAsUsual("high"), HomeAlertAction.exit(high, QuietExit.RemindAsUsual))
    }
}
