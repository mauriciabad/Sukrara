// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.sensor.sensorend

import app.mauri.sukrara.core.model.SensorState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Duration
import java.time.Instant

class SensorEndWarningTest {
    // 2026-10-25 is the EU DST change: the end is an Instant, so the wall-clock jump can't move or repeat a notice
    private val end: Instant = Instant.parse("2026-10-25T03:30:00Z")
    private val active = SensorState.Active(end)

    private fun decide(now: Instant, mem: SensorEndMemory = SensorEndMemory(), state: SensorState = active) =
        SensorEndWarning.decide(state, mem, warnHours = 3, now = now)

    @Test fun `before the window nothing shows and the alarm is the warning time`() {
        val d = decide(end.minus(Duration.ofHours(10)))
        assertEquals(emptyList<SensorNotice>(), d.notices)
        assertEquals(end.minus(Duration.ofHours(3)), d.nextAlarm)
    }

    @Test fun `the warning fires once in the window, then the alarm is the end`() {
        val first = decide(end.minus(Duration.ofHours(2)))
        assertEquals(listOf(SensorNotice.EndingSoon(end)), first.notices)
        assertEquals(end, first.nextAlarm)
        val again = decide(end.minus(Duration.ofHours(1)), first.memory)
        assertEquals(emptyList<SensorNotice>(), again.notices)
    }

    @Test fun `ended once at the end, not again, and not days later`() {
        val atEnd = decide(end.plusSeconds(1))
        assertEquals(listOf(SensorNotice.Ended), atEnd.notices)
        assertNull(atEnd.nextAlarm)
        assertEquals(emptyList<SensorNotice>(), decide(end.plusSeconds(60), atEnd.memory).notices)
        assertEquals(emptyList<SensorNotice>(), decide(end.plus(Duration.ofDays(2))).notices)
    }

    @Test fun `the source saying Ended shows it once and routes to the flow`() {
        val d = decide(end, state = SensorState.Ended)
        assertEquals(listOf(SensorNotice.Ended), d.notices)
        assertEquals(emptyList<SensorNotice>(), decide(end, d.memory, SensorState.Ended).notices)
    }

    @Test fun `a new sensor gets its own warning`() {
        val old = decide(end.minus(Duration.ofHours(1))).memory
        val newEnd = end.plus(Duration.ofDays(15))
        val d = SensorEndWarning.decide(SensorState.Active(newEnd), old, 3, newEnd.minus(Duration.ofHours(2)))
        assertEquals(listOf(SensorNotice.EndingSoon(newEnd)), d.notices)
    }

    @Test fun `no known end means no warning and no alarm`() {
        val d = decide(end, state = SensorState.Active(null))
        assertEquals(emptyList<SensorNotice>(), d.notices)
        assertNull(d.nextAlarm)
    }

    @Test fun `ready once after a warm-up this phone saw`() {
        val warming = decide(end.minus(Duration.ofDays(14)), state = SensorState.WarmingUp(end.minus(Duration.ofDays(14))))
        assertEquals(emptyList<SensorNotice>(), warming.notices)
        val ready = decide(end.minus(Duration.ofDays(13)), warming.memory)
        assertEquals(listOf(SensorNotice.Ready), ready.notices)
        assertEquals(emptyList<SensorNotice>(), decide(end.minus(Duration.ofDays(13)), ready.memory).notices)
    }

    @Test fun `a running sensor taken over shows no ready`() =
        assertEquals(emptyList<SensorNotice>(), decide(end.minus(Duration.ofDays(5))).notices)
}
