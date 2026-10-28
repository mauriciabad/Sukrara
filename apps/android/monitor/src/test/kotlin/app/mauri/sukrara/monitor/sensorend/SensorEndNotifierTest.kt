// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.monitor.sensorend

import app.mauri.sukrara.core.model.SensorState
import app.mauri.sukrara.core.testing.FakeClock
import app.mauri.sukrara.core.testing.InMemoryStateStore
import app.mauri.sukrara.monitor.alerts.AlertAlarm
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

/** one warning a few hours before the sensor ends, one "ended" notice, each once per sensor. */
class SensorEndNotifierTest {
    private val clock = FakeClock(Instant.parse("2026-01-15T12:00:00Z"))
    private val store = InMemoryStateStore(SensorEndMemory())
    private val shown = mutableListOf<String>()
    private var alarmAt: Long? = null
    private val notifier = SensorEndNotifier(
        store,
        object : SensorEndPresenter {
            override fun warn(endsAt: Long, now: Long) { shown += "warn" }
            override fun ended() { shown += "ended" }
            override fun clear() { shown += "clear" }
        },
        AlertAlarm { alarmAt = it },
        clock,
    )
    private val end = clock.now().plus(Duration.ofDays(2))

    /** Wakes at the planned alarm, as AlarmManager would. */
    private suspend fun wake() {
        clock.instant = Instant.ofEpochSecond(requireNotNull(alarmAt))
        notifier.check()
    }

    @Test fun warnsSixHoursBeforeThenEndedOnce() = runTest {
        notifier.onSensor(SensorState.Active(end))
        assertEquals(end.epochSecond - 6 * 3600, alarmAt)
        wake()
        assertEquals(listOf("warn"), shown)
        assertEquals(end.epochSecond, alarmAt)
        notifier.onSensor(SensorState.Active(end)) // a reading's update doesn't warn again
        wake()
        assertEquals(listOf("warn", "ended"), shown)
        assertNull(alarmAt)
        notifier.check() // a reboot after the end doesn't repeat it
        assertEquals(listOf("warn", "ended"), shown)
    }

    @Test fun missedWarningShowsLateButNeverAfterTheEnd() = runTest {
        clock.instant = end.minus(Duration.ofHours(2)) // the phone was off at the warning time
        notifier.onSensor(SensorState.Active(end))
        assertEquals(listOf("warn"), shown)

        val decision = SensorEndPlan.decide(end.epochSecond, end.epochSecond + 3600, SensorEndMemory(end.epochSecond))
        assertFalse(decision.warn)
        assertTrue(decision.ended)
    }

    @Test fun endedLongAgoIsNotAnnouncedDaysLater() {
        val d = SensorEndPlan.decide(end.epochSecond, end.epochSecond + 2 * 86_400, SensorEndMemory())
        assertFalse(d.warn)
        assertFalse(d.ended)
        assertNull(d.next)
    }

    @Test fun aNewSensorStartsOverAndClearsTheOldNotice() = runTest {
        clock.instant = end.minus(Duration.ofHours(1))
        notifier.onSensor(SensorState.Active(end))
        val next = end.plus(Duration.ofDays(14))
        notifier.onSensor(SensorState.Active(next))
        assertEquals(listOf("warn", "clear"), shown)
        assertEquals(next.epochSecond - 6 * 3600, alarmAt)
    }

    @Test fun noSensorClearsAndDisarms() = runTest {
        notifier.onSensor(SensorState.Active(end))
        notifier.onSensor(SensorState.None)
        assertEquals(listOf("clear"), shown)
        assertNull(alarmAt)
    }
}
