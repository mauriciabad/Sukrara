// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.alerts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The persisted [AlertState]: every field survives the JSON, a restored engine goes on without re-alerting. */
class EpisodesCodecTest {
    private val t0 = 1_760_000_040L

    @Test fun engineMemoryRoundTripsAndResumesWithoutReAlerting() {
        val s = AlertSettings()
        val e = AlertEngine { "x" }
        e.reading(AlertReading(t0, 200, -0.5), s, Doses(), t0)
        e.mute(AlertKind.LOW, 30, s, Doses(), t0)
        e.relax(AlertKind.HIGH, s, Doses(), t0)
        e.muteAll(t0 + 3600, s, Doses(), t0)
        val state = AlertState(role = AlertRole.WEARER, local = e.snapshot())
        val back = AlertStateCodec.decode(AlertStateCodec.encode(state))!!
        assertEquals(AlertStateCodec.encode(state), AlertStateCodec.encode(back))
        assertEquals(e.snapshot().snoozes, back.local.snoozes)
        assertEquals(t0 + 3600, back.local.muteAllUntil)

        val restored = AlertEngine { "y" }
        val shown = restored.restore(back.local, s, t0 + 60)
        assertTrue(shown.isEmpty()) // muted by Mute all: nothing was showing
        assertTrue(restored.reading(AlertReading(t0 + 60, 200, -0.5), s, Doses(), t0 + 60).none { it is RuleAction.Fire })
        assertEquals(t0 + 3600, restored.allMuted(t0 + 60))
    }

    @Test fun episodesOverByNowAreDropped() {
        val s = AlertSettings()
        val e = AlertEngine { "x" }
        e.reading(AlertReading(t0, 60), s, Doses(), t0)
        val shown = AlertEngine().restore(e.snapshot(), s, t0 + 60)
        assertEquals(listOf(AlertKind.LOW), shown.map { it.kind })
        assertTrue(AlertEngine().restore(e.snapshot(), s, t0 + 6 * 60).isEmpty())
    }

    @Test fun garbageIsNull() {
        assertNull(AlertStateCodec.decode(""))
        assertNull(AlertStateCodec.decode("{\"v\":1}"))
        assertNull(AlertStateCodec.decode("not json"))
    }
}
