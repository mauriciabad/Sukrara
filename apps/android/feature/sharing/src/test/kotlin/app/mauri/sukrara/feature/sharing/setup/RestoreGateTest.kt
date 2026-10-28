// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.sharing.setup

import app.mauri.sukrara.core.model.GlucoseState
import app.mauri.sukrara.core.model.Reading
import app.mauri.sukrara.core.model.SourceId
import app.mauri.sukrara.core.model.SourceStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class RestoreGateTest {
    private fun gate(live: Boolean = true) = RestoreGate.evaluate(live)

    @Test
    fun `disabled without a live reading`() {
        assertEquals(setOf(RestoreBlock.NoLiveReading), gate(live = false).blocks)
    }

    @Test
    fun `enabled when every condition holds`() {
        assertTrue(gate().open)
    }

    private val now = Instant.parse("2026-10-27T08:00:00Z")

    private fun state(
        source: SourceId,
        ageMin: Long,
        status: SourceStatus = SourceStatus.Live(now),
    ) = GlucoseState.Initial.copy(latest = Reading(now.minusSeconds(ageMin * 60), 120, null, source), source = status)

    @Test
    fun `live means a recent reading from this phone's own sensor`() {
        assertTrue(RestoreGate.ownSensorLive(state(SourceId.Libre3, 1), now))
        assertTrue(RestoreGate.ownSensorLive(state(SourceId.Juggluco, 14), now))
        assertFalse(RestoreGate.ownSensorLive(state(SourceId.Libre3, 16), now))
        assertFalse(RestoreGate.ownSensorLive(state(SourceId.Relay, 1), now))
        assertFalse(RestoreGate.ownSensorLive(state(SourceId.Libre3, 1, SourceStatus.Interrupted(now)), now))
        assertFalse(RestoreGate.ownSensorLive(GlucoseState.Initial, now))
    }
}
