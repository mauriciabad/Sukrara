// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.monitor.sensorend

import app.mauri.sukrara.core.model.SensorEndConfig
import app.mauri.sukrara.core.model.SensorState
import app.mauri.sukrara.core.testing.FakeClock
import app.mauri.sukrara.core.testing.InMemoryStateStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

/** The notifier on virtual time: what shows when, driven by the hub, the alarm, unlocks and swipes. */
class SensorEndNotifierTest {
    private val clock = FakeClock(Instant.parse("2026-01-15T12:00:00Z"))
    private val store = InMemoryStateStore(SensorEndMemory())
    private val shown = mutableListOf<String>()
    private var alarm: Pair<Long?, Boolean>? = null
    private var settings = SensorEndSettings(SensorEndConfig(), countdownHours = 8, chip = true)

    private val presenter =
        object : SensorEndPresenter {
            override fun remind(end: Long, now: Long, text: ReminderText) {
                shown += "remind:$text"
            }

            override fun countdown(end: Long, hours: Int, now: Long, promote: Boolean) {
                shown += "countdown:promote=$promote"
            }

            override fun warmup(start: Long, warmupEnd: Long, now: Long, waiting: Boolean) {
                shown += if (waiting) "almost-ready" else "warmup"
            }

            override fun ready() {
                shown += "ready"
            }

            override fun ended() {
                shown += "ended"
            }

            override fun cancel(notice: SensorNotice) = Unit
        }
    private val notifier = SensorEndNotifier(store, presenter, { at, exact -> alarm = at to exact }, clock) { settings }

    private val end = Instant.parse("2026-01-17T14:30:00Z")

    private suspend fun hub(
        sensor: SensorState = SensorState.Active(end),
        latest: Instant? = clock.now(),
        wearer: Boolean = true,
    ) = notifier.update(wearer, sensor, latest?.epochSecond, settings)

    /** Wakes at the planned alarm, as AlarmManager would. */
    private suspend fun wake() {
        clock.instant = Instant.ofEpochSecond(requireNotNull(alarm?.first))
        notifier.check()
    }

    @Test fun remindersThenCountdownThenEndedOnce() =
        runTest {
            hub()
            assertEquals(Instant.parse("2026-01-16T09:00:00Z").epochSecond, alarm?.first)
            wake()
            assertEquals(listOf("remind:ENDS"), shown)
            hub() // a reading's update shows nothing again
            assertEquals(listOf("remind:ENDS"), shown)
            wake() // 06:30 on the end day: the countdown window opens
            assertTrue(shown.last().startsWith("countdown"))
            clock.instant = end
            notifier.check()
            assertEquals("ended", shown.last())
            notifier.check()
            assertEquals(1, shown.count { it == "ended" })
        }

    @Test fun theEndCountdownAsksForTheChipOnlyWhileTheGlucoseChipIsOff() =
        runTest {
            clock.instant = end - Duration.ofHours(2)
            hub()
            assertTrue("countdown:promote=false" in shown)
            settings = settings.copy(chip = false)
            hub()
            assertTrue("countdown:promote=true" in shown)
        }

    @Test fun aSwipedCountdownStaysAwayForThisSensor() =
        runTest {
            clock.instant = end - Duration.ofHours(2)
            hub()
            notifier.dismissed(warmup = false)
            shown.clear()
            hub()
            notifier.check()
            assertFalse(shown.any { it.startsWith("countdown") })
        }

    @Test fun anEarlyEndSaysEndedNow() =
        runTest {
            hub()
            clock.instant = Instant.parse("2026-01-16T20:00:00Z")
            hub(SensorState.Ended)
            assertEquals("ended", shown.last())
        }

    @Test fun warmUpThenAlmostReadyThenReadyOnTheFirstReading() =
        runTest {
            val readyAt = clock.now().plus(Duration.ofMinutes(50))
            hub(SensorState.WarmingUp(readyAt), latest = clock.now().minus(Duration.ofDays(1)))
            assertEquals("warmup", shown.last())
            clock.instant = readyAt.plusSeconds(60)
            notifier.check()
            assertEquals("almost-ready", shown.last())
            hub(SensorState.Active(readyAt.plus(Duration.ofDays(15))), latest = clock.now())
            assertEquals("ready", shown.last())
        }

    @Test fun aFollowerGetsNothingAndForgets() =
        runTest {
            hub()
            hub(wearer = false)
            assertEquals(SensorEndMemory(), store.data.first())
            assertEquals(null, alarm?.first)
        }

    @Test fun noSensorClearsAndAlarmFindsNothing() =
        runTest {
            hub()
            hub(SensorState.None)
            notifier.check()
            assertTrue(shown.isEmpty())
            assertEquals(null, alarm?.first)
        }
}
