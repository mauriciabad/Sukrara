// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.sensorend

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class SensorEndPlanTest {
    private val zone = ZoneId.of("Europe/Madrid")
    private fun t(s: String) = LocalDateTime.parse(s).atZone(zone).toEpochSecond()
    private val start = t("2026-10-06T14:30")
    private val end = t("2026-10-21T14:30")   // a 15-day sensor, ends Wednesday 14:30
    private val cfg = SensorEndConfig()

    @Test fun defaultSlotsAreTheDayBeforeAndTheEndDayAtNine() {
        assertEquals(listOf(t("2026-10-20T09:00"), t("2026-10-21T09:00")), SensorEndPlan.slots(start, end, cfg.reminders, zone))
    }

    @Test fun aSlotAfterTheEndIsSkipped() {
        val early = t("2026-10-21T07:30")
        assertEquals(listOf(t("2026-10-20T09:00")), SensorEndPlan.slots(start, early, cfg.reminders, zone))
    }

    @Test fun slotsFollowTheTimeZoneAcrossTheDstChange() {
        // Europe/Madrid leaves summer time on 2026-10-25: 09:00 local on both sides
        val e = t("2026-10-26T12:00")
        assertEquals(listOf(t("2026-10-25T09:00"), t("2026-10-26T09:00")), SensorEndPlan.slots(start, e, cfg.reminders, zone))
    }

    @Test fun nextIsTheFirstReminderExactly() {
        val p = SensorEndPlan.plan(start, end, cfg, 8, t("2026-10-19T20:00"), zone, PlanMemory("k", t("2026-10-19T20:00")))
        assertNull(p.remind); assertFalse(p.countdown); assertFalse(p.ended)
        assertEquals(t("2026-10-20T09:00"), p.next); assertTrue(p.nextExact)
    }

    @Test fun aDueReminderFiresOnceAndALateOneStillFires() {
        val mem = PlanMemory("k", t("2026-10-19T20:00"))
        assertEquals(t("2026-10-20T09:00"), SensorEndPlan.plan(start, end, cfg, 8, t("2026-10-20T09:02"), zone, mem).remind)
        // phone off from 08:00 to 11:00: still shown at 11:00
        assertEquals(t("2026-10-20T09:00"), SensorEndPlan.plan(start, end, cfg, 8, t("2026-10-20T11:00"), zone, mem).remind)
        // already shown
        assertNull(SensorEndPlan.plan(start, end, cfg, 8, t("2026-10-20T11:00"), zone, mem.copy(lastReminder = t("2026-10-20T09:00"))).remind)
    }

    @Test fun slotsBeforeTheMemoryNeverFire() {
        // a new sensor or edited reminders: lastReminder = now, so the past 09:00 doesn't fire at 10:00
        assertNull(SensorEndPlan.plan(start, end, cfg, 8, t("2026-10-20T10:00"), zone, PlanMemory("k", t("2026-10-20T10:00"))).remind)
    }

    @Test fun countdownRunsInTheLastHoursWithProgressTicks() {
        val mem = PlanMemory("k", t("2026-10-21T09:00"))
        val before = SensorEndPlan.plan(start, end, cfg, 8, t("2026-10-21T06:00"), zone, mem.copy(lastReminder = t("2026-10-20T09:00")))
        assertFalse(before.countdown); assertEquals(t("2026-10-21T06:30"), before.next); assertTrue(before.nextExact)
        val inside = SensorEndPlan.plan(start, end, cfg, 8, t("2026-10-21T10:00"), zone, mem)
        assertTrue(inside.countdown); assertEquals(t("2026-10-21T10:15"), inside.next); assertFalse(inside.nextExact)
        // the last tick before the end is replaced by the exact end
        val last = SensorEndPlan.plan(start, end, cfg, 8, t("2026-10-21T14:20"), zone, mem)
        assertEquals(end, last.next); assertTrue(last.nextExact)
    }

    @Test fun countdownOffOrDismissedOrZeroHours() {
        val now = t("2026-10-21T10:00")
        val mem = PlanMemory("k", now)
        assertFalse(SensorEndPlan.plan(start, end, cfg.copy(countdown = false), 8, now, zone, mem).countdown)
        assertFalse(SensorEndPlan.plan(start, end, cfg, 0, now, zone, mem).countdown)
        assertFalse(SensorEndPlan.plan(start, end, cfg, 8, now, zone, mem.copy(countdownDismissed = true)).countdown)
    }

    @Test fun endedIsShownOnceWithinTheGrace() {
        val mem = PlanMemory("k", end)
        val p = SensorEndPlan.plan(start, end, cfg, 8, end, zone, mem)
        assertTrue(p.ended); assertFalse(p.countdown); assertNull(p.remind); assertNull(p.next)
        assertFalse(SensorEndPlan.plan(start, end, cfg, 8, end + 60, zone, mem.copy(endedShown = true)).ended)
        assertFalse(SensorEndPlan.plan(start, end, cfg, 8, end + 2 * 86400, zone, mem).ended)
    }

    @Test fun dayOffsetIsInCalendarDays() {
        assertEquals(1L, SensorEndPlan.dayOffset(end, t("2026-10-20T23:59"), zone))
        assertEquals(0L, SensorEndPlan.dayOffset(end, t("2026-10-21T00:01"), zone))
        assertEquals(2L, SensorEndPlan.dayOffset(end, t("2026-10-19T09:00"), zone))
    }

    @Test fun progressIsTheTimeLeftInTheWindow() {
        assertEquals(1000, SensorEndPlan.leftPerMille(end, 8, end - 9 * 3600))
        assertEquals(500, SensorEndPlan.leftPerMille(end, 8, end - 4 * 3600))
        assertEquals(0, SensorEndPlan.leftPerMille(end, 8, end + 10))
    }

    @Test fun configRoundTripDropsInvalidAndSorts() {
        val c = SensorEndConfig(listOf(Reminder(0, 600), Reminder(2, 480), Reminder(0, 600)), countdown = false)
        assertEquals(SensorEndConfig(listOf(Reminder(2, 480), Reminder(0, 600)), false), SensorEndConfig.fromJson(c.toJson()))
        val bad = JSONObject("""{"reminders":[{"days":9,"minute":0},{"days":1,"minute":540}]}""")
        assertEquals(SensorEndConfig(listOf(Reminder(1, 540))), SensorEndConfig.fromJson(bad))
        assertEquals(SensorEndConfig(), SensorEndConfig.fromJson(JSONObject()))
    }

    // ------------------------------------------------------------------------------------------------ warm-up

    private val ws = t("2026-10-21T18:45")   // a new sensor, started 18:45, readings from 19:45
    private val we = ws + 3600
    private val wEnd = ws + 15 * 86400
    private fun wp(now: Long, mem: PlanMemory = PlanMemory("n", ws), first: Long? = null) =
        SensorEndPlan.plan(ws, wEnd, cfg, 8, now, zone, mem, we, first)

    @Test fun warmupCountdownRunsUntilReadingsStart() {
        val p = wp(ws + 600)
        assertTrue(p.warmup); assertFalse(p.ready)
        assertEquals(ws + 600 + SensorEndPlan.WARMUP_TICK, p.next); assertFalse(p.nextExact)
        // still waiting 10 min after the warm-up end; the countdown gives up 15 min after it (exact)
        assertTrue(wp(we + 600).warmup)
        val last = wp(we + 15 * 60 - 120)
        assertEquals(we + 15 * 60, last.next); assertTrue(last.nextExact)
        assertFalse(wp(we + 15 * 60).warmup)
    }

    @Test fun theCountdownNeverGoesBelowZero() {
        // before the end: the timer runs, ticks while they land clear of the end ...
        assertFalse(wp(ws + 600).warmupWaiting)
        // ... and the last stretch is the exact alarm at the warm-up's end, never a late tick past it
        val near = wp(we - 4 * 60)
        assertFalse(near.warmupWaiting); assertEquals(we, near.next); assertTrue(near.nextExact)
        val clear = wp(we - 10 * 60)
        assertEquals(we - 10 * 60 + SensorEndPlan.WARMUP_TICK, clear.next); assertFalse(clear.nextExact)
        // from the warm-up's end with no reading: "Almost ready" (no timer, no tick), until the give-up (exact)
        for (now in listOf(we, we + 60, we + 6 * 60, we + 15 * 60 - 1)) {
            val p = wp(now, PlanMemory("n", ws, warmupShown = true))
            assertTrue(p.warmup); assertTrue(p.warmupWaiting); assertFalse(p.ready)
            assertEquals(we + 15 * 60, p.next); assertTrue(p.nextExact)
        }
        // the end countdown: the last tick lands before the end; the end itself is an exact alarm → "Sensor ended"
        val endNear = SensorEndPlan.plan(start, end, cfg, 8, end - 10 * 60, zone, PlanMemory("k", start))
        assertTrue(endNear.countdown); assertEquals(end, endNear.next); assertTrue(endNear.nextExact)
        val atEnd = SensorEndPlan.plan(start, end, cfg, 8, end, zone, PlanMemory("k", start))
        assertFalse(atEnd.countdown); assertTrue(atEnd.ended)
    }

    @Test fun aReadingEndsTheWarmupInEveryState() {
        val shown = PlanMemory("n", ws, warmupShown = true)
        // the reading during the countdown, at its end, or while "Almost ready" (the user's case: 6 min past the end)
        for ((now, first) in listOf(ws + 3000 to ws + 2990, we to we, we + 6 * 60 to we + 5 * 60, we + 14 * 60 to we + 14 * 60)) {
            val p = wp(now, shown, first)
            assertFalse(p.warmup); assertFalse(p.warmupWaiting); assertTrue(p.ready)
            assertTrue(p.next == null || p.next!! > now)
        }
        // "ready" already shown (its post may have been dropped): still no warm-up
        val again = wp(we + 7 * 60, shown.copy(readyShown = true), first = we + 5 * 60)
        assertFalse(again.warmup); assertFalse(again.ready)
    }

    @Test fun aRestartDoesNotBringTheWarmupBack() {
        // the phone, 2026-10-18: the first reading at 21:07 (59 min in), "ready" shown; an app update at 21:08 restarts
        // the process, which knows no reading yet (first = null): the remembered first reading keeps the warm-up over
        val after = PlanMemory("n", ws, warmupShown = true, readyShown = true, firstReading = we - 58)
        for (now in listOf(we - 20, we + 60, we + 6 * 60)) {
            val p = wp(now, after, first = null)
            assertFalse(p.warmup); assertFalse(p.warmupWaiting); assertFalse(p.ready)
        }
        // without that memory the same restart would show the countdown again (what the phone did)
        assertTrue(wp(we - 20, PlanMemory("n", ws, warmupShown = true, readyShown = true), first = null).warmup)
    }

    @Test fun theFirstReadingBringsReadyOnce() {
        val shown = PlanMemory("n", ws, warmupShown = true)
        val early = wp(ws + 3000, shown, first = ws + 2950)
        assertFalse(early.warmup); assertTrue(early.ready)
        assertFalse(wp(we + 60, shown).ready)   // no reading yet: still warming up, not ready
        assertFalse(wp(we + 60, shown.copy(readyShown = true), first = we + 30).ready)
        assertFalse(wp(we + 2 * 3600, shown, first = we).ready)   // too late to be news
        assertFalse(wp(we + 20 * 60, shown).ready)   // gave up without a reading: no "ready"
    }

    @Test fun noReadyWithoutTheWarmupCountdownAndNoWarmupWhenUnknownOrDismissed() {
        assertFalse(wp(we + 60, first = we + 30).ready)   // Sukrara learned about the sensor after its warm-up
        assertFalse(SensorEndPlan.plan(ws, wEnd, cfg, 8, ws + 600, zone, PlanMemory("n", ws), 0, null).warmup)
        assertFalse(wp(ws + 600, PlanMemory("n", ws, warmupShown = true, warmupDismissed = true)).warmup)
        assertFalse(wp(we + 60, PlanMemory("n", ws, warmupShown = true, warmupDismissed = true), first = we + 30).ready)
        assertFalse(SensorEndPlan.plan(ws, wEnd, cfg.copy(warmup = false), 8, ws + 600, zone, PlanMemory("n", ws), we, null).warmup)
    }
}
