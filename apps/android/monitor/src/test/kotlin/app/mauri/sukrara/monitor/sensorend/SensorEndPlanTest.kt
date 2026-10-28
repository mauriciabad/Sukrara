// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.monitor.sensorend

import app.mauri.sukrara.core.model.SensorEndConfig
import app.mauri.sukrara.core.model.SensorReminder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/** The sensor-end rules on virtual time (Madrid, so the DST days are real ones). */
class SensorEndPlanTest {
    private val zone = ZoneId.of("Europe/Madrid")
    private val cfg = SensorEndConfig()

    private fun t(s: String) = LocalDateTime.parse(s).atZone(zone).toEpochSecond()

    /** A sensor that ends at [end] (local), started 15 days before; nothing shown yet, planned at its start. */
    private fun mem(
        end: String,
        warmupMin: Long = 60,
    ): SensorEndMemory {
        val e = t(end)
        val start = e - 15 * 86_400
        return SensorEndMemory(
            start = start,
            end = e,
            warmupEnd = start + warmupMin * 60,
            lastReminder = start,
            firstReading = start + 3600,
        )
    }

    @Test
    fun `the default reminders are the day before and the end day at 09_00, both before a 14_30 end`() {
        val m = mem("2026-03-10T14:30")
        val slots = SensorEndPlan.slots(m.start, m.end, cfg, zone).map { it.at }
        assertEquals(listOf(t("2026-03-09T09:00"), t("2026-03-10T09:00")), slots)
    }

    @Test
    fun `a reminder after the end is skipped`() {
        val m = mem("2026-03-10T07:30")
        val slots = SensorEndPlan.slots(m.start, m.end, cfg.copy(evening = false), zone).map { it.at }
        assertEquals(listOf(t("2026-03-09T09:00")), slots)
    }

    @Test
    fun `a night-time end gets an evening reminder the evening before`() {
        // 03:10 on the 10th: in the 23:00–07:00 night that started on the 9th → 21:00 on the 9th
        val m = mem("2026-03-10T03:10")
        val evening = SensorEndPlan.eveningSlot(m.end, cfg, zone)!!
        assertEquals(t("2026-03-09T21:00"), evening.at)
        assertEquals(t("2026-03-09T19:00"), evening.unlockFrom)
        val p = SensorEndPlan.plan(cfg, 8, t("2026-03-09T21:00"), zone, m)
        assertEquals(evening.at, p.remind)
        assertEquals(ReminderText.TONIGHT, p.remindText)
    }

    @Test
    fun `the first unlock of the morning brings the 09_00 reminder forward, once`() {
        val m = mem("2026-03-10T14:30")
        val unlock = t("2026-03-09T06:40")
        assertTrue(SensorEndPlan.unlockWanted(m, cfg, unlock, zone))
        val p = SensorEndPlan.plan(cfg, 8, unlock, zone, m.copy(lastUnlock = unlock))
        assertEquals(t("2026-03-09T09:00"), p.remind)
        // shown: the 09:00 alarm doesn't show it again
        val after = m.copy(lastUnlock = unlock, lastReminder = p.remind!!)
        assertNull(SensorEndPlan.plan(cfg, 8, t("2026-03-09T09:00"), zone, after).remind)
        assertFalse(SensorEndPlan.unlockWanted(after, cfg, t("2026-03-09T07:30"), zone))
    }

    @Test
    fun `an unlock before 05_00 doesn't count`() {
        val m = mem("2026-03-10T14:30")
        assertFalse(SensorEndPlan.unlockWanted(m, cfg, t("2026-03-09T04:30"), zone))
    }

    @Test
    fun `missed reminders show late, only the latest, and never after the end`() {
        val m = mem("2026-03-10T14:30")
        val p = SensorEndPlan.plan(cfg, 8, t("2026-03-10T10:00"), zone, m)
        assertEquals(t("2026-03-10T09:00"), p.remind)
        assertEquals(ReminderText.SPARE, p.remindText)
        assertNull(SensorEndPlan.plan(cfg, 8, t("2026-03-10T15:00"), zone, m).remind)
    }

    @Test
    fun `the words name the day, or say to take the spare on an end day outside the night`() {
        val m = mem("2026-03-10T14:30")
        assertEquals(ReminderText.ENDS, SensorEndPlan.plan(cfg, 8, t("2026-03-09T09:00"), zone, m).remindText)
    }

    @Test
    fun `slots across the spring DST change keep their wall-clock time`() {
        // Madrid moves 02:00 → 03:00 on 29 March 2026
        val m = mem("2026-03-30T14:30")
        val slots = SensorEndPlan.slots(m.start, m.end, cfg, zone).map { it.at }
        assertEquals(listOf(t("2026-03-29T09:00"), t("2026-03-30T09:00")), slots)
        assertEquals(23 * 3600L, t("2026-03-29T09:00") - t("2026-03-28T09:00"))
    }

    @Test
    fun `the end countdown runs in its last hours unless dismissed, then hands over to ended`() {
        val m = mem("2026-03-10T14:30")
        assertFalse(SensorEndPlan.plan(cfg, 8, t("2026-03-10T06:00"), zone, m).countdown)
        val running = SensorEndPlan.plan(cfg, 8, t("2026-03-10T07:00"), zone, m.copy(lastReminder = t("2026-03-10T09:00")))
        assertTrue(running.countdown)
        assertFalse(SensorEndPlan.plan(cfg, 8, t("2026-03-10T07:00"), zone, m.copy(countdownDismissed = true)).countdown)
        assertFalse(SensorEndPlan.plan(cfg.copy(countdown = false), 8, t("2026-03-10T07:00"), zone, m).countdown)
        val at = SensorEndPlan.plan(cfg, 8, t("2026-03-10T14:30"), zone, m)
        assertFalse(at.countdown)
        assertTrue(at.ended)
        assertFalse(SensorEndPlan.plan(cfg, 8, t("2026-03-11T03:00"), zone, m).ended) // 12 h grace passed
        assertFalse(SensorEndPlan.plan(cfg, 8, t("2026-03-10T15:00"), zone, m.copy(endedShown = true)).ended)
    }

    @Test
    fun `a progress tick never stands in for an event`() {
        val m = mem("2026-03-10T14:30").copy(lastReminder = t("2026-03-10T09:00"))
        val p = SensorEndPlan.plan(cfg, 8, t("2026-03-10T14:20"), zone, m)
        assertEquals(m.end, p.next)
        assertTrue(p.nextExact)
        val early = SensorEndPlan.plan(cfg, 8, t("2026-03-10T10:00"), zone, m)
        assertEquals(t("2026-03-10T10:15"), early.next)
        assertFalse(early.nextExact)
    }

    @Test
    fun `warm-up counts down, then almost ready without a timer, then gives up 15 minutes after`() {
        val start = t("2026-03-01T18:45")
        val m = SensorEndMemory(start = start, end = start + 15 * 86_400, warmupEnd = start + 3600, lastReminder = start)
        val counting = SensorEndPlan.plan(cfg, 8, start + 600, zone, m)
        assertTrue(counting.warmup)
        assertFalse(counting.warmupWaiting)
        val waiting = SensorEndPlan.plan(cfg, 8, start + 3600 + 60, zone, m.copy(warmupShown = true))
        assertTrue(waiting.warmup)
        assertTrue(waiting.warmupWaiting)
        assertTrue(waiting.nextExact) // the give-up time, no tick
        assertFalse(SensorEndPlan.plan(cfg, 8, start + 3600 + 15 * 60, zone, m).warmup)
        assertFalse(SensorEndPlan.plan(cfg.copy(warmup = false), 8, start + 600, zone, m).warmup)
    }

    @Test
    fun `the first reading ends the warm-up and says ready once, only if the countdown showed`() {
        val start = t("2026-03-01T18:45")
        val m =
            SensorEndMemory(start = start, end = start + 15 * 86_400, warmupEnd = start + 3600, lastReminder = start, warmupShown = true)
        val p = SensorEndPlan.plan(cfg, 8, start + 3500, zone, m, firstReading = start + 3400)
        assertFalse(p.warmup)
        assertTrue(p.ready)
        assertFalse(SensorEndPlan.plan(cfg, 8, start + 3500, zone, m.copy(readyShown = true), firstReading = start + 3400).ready)
        assertFalse(SensorEndPlan.plan(cfg, 8, start + 3500, zone, m.copy(warmupShown = false), firstReading = start + 3400).ready)
    }

    @Test
    fun `a new sensor starts fresh with past slots not firing, the same one keeps its memory`() {
        val now = t("2026-03-09T10:00")
        val old = mem("2026-03-10T14:30").copy(endedShown = true, countdownDismissed = true)
        val fresh = SensorEndPlan.remember(old, now - 3600, now + 15 * 86_400, now, cfg.hashCode(), now)
        assertEquals(now, fresh.lastReminder)
        assertFalse(fresh.countdownDismissed)
        val same =
            SensorEndPlan.remember(
                old.copy(config = cfg.hashCode()),
                old.start + 600,
                old.end + 600,
                old.warmupEnd,
                cfg.hashCode(),
                now,
            )
        assertTrue(same.countdownDismissed)
        assertEquals(old.end + 600, same.end)
    }

    @Test
    fun `warm-up to active with a start derived differently is the same sensor`() {
        val now = t("2026-03-01T20:00")
        val warm =
            SensorEndMemory(
                start = now - 3600,
                end = now - 3600 + 15 * 86_400,
                warmupEnd = now,
                lastReminder = now,
                warmupShown = true,
                config = 7,
            )
        // the sensor says 14 days: its derived start is a day off, its end matches within hours
        val active = SensorEndPlan.remember(warm, warm.start + 86_400 + 60, warm.end + 60, now + 86_400, 7, now)
        assertTrue(active.warmupShown)
    }

    @Test
    fun `an early end stays when the source still reports the planned end`() {
        val m = mem("2026-03-10T14:30").copy(config = cfg.hashCode())
        val now = t("2026-03-09T12:00")
        val ended = m.copy(end = now - 60)
        val again = SensorEndPlan.remember(ended, m.start, m.end, m.warmupEnd, cfg.hashCode(), now)
        assertEquals(now - 60, again.end)
    }

    @Test
    fun `changed settings make past slots not fire and bring a dismissed countdown back`() {
        val m = mem("2026-03-10T14:30").copy(config = 1, countdownDismissed = true)
        val now = t("2026-03-10T10:00")
        val r = SensorEndPlan.remember(m, m.start, m.end, m.warmupEnd, 2, now)
        assertEquals(now, r.lastReminder)
        assertFalse(r.countdownDismissed)
        assertNull(SensorEndPlan.plan(cfg, 8, now, zone, r).remind)
    }

    @Test
    fun `up to six reminders, sorted, duplicates removed`() {
        val many = (0..3).flatMap { d -> listOf(SensorReminder(d, 9 * 60), SensorReminder(d, 9 * 60), SensorReminder(d, 20 * 60)) }
        val n = SensorEndConfig(reminders = many).normalized().reminders
        assertEquals(SensorEndConfig.MAX_REMINDERS, n.size)
        assertEquals(SensorReminder(3, 9 * 60), n.first())
    }

    @Test
    fun `bars`() {
        assertEquals(1000, SensorEndPlan.leftPerMille(10_000, 1, 10_000 - 3600))
        assertEquals(500, SensorEndPlan.leftPerMille(10_000, 1, 10_000 - 1800))
        assertEquals(500, SensorEndPlan.warmupPerMille(0, 3600, 1800))
    }
}
