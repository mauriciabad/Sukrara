// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.sensorend

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * One sensor end reminder: [daysBefore] the day the sensor ends (0 = that day) at [minuteOfDay] local time.
 * Defaults: 1 day before at 09:00 and on the end day at 09:00.
 */
data class Reminder(val daysBefore: Int, val minuteOfDay: Int) {
    init { require(daysBefore in 0..MAX_DAYS && minuteOfDay in 0 until 24 * 60) }
    companion object {
        const val MAX_DAYS = 3
        val DEFAULTS = listOf(Reminder(1, 9 * 60), Reminder(0, 9 * 60))
    }
}

/** The Sensor end settings page: the reminders, the end countdown (hours: SensorLife.countdownHours) and the warm-up countdown. */
data class SensorEndConfig(val reminders: List<Reminder> = Reminder.DEFAULTS, val countdown: Boolean = true, val warmup: Boolean = true) {
    /** Sorted earliest first, duplicates removed (what the page lists and the planner uses). */
    fun normalized() = copy(reminders = reminders.distinct().sortedWith(compareByDescending<Reminder> { it.daysBefore }.thenBy { it.minuteOfDay }))

    fun toJson(): JSONObject = JSONObject().put("countdown", countdown).put("warmup", warmup).put("reminders", JSONArray().apply {
        reminders.forEach { put(JSONObject().put("days", it.daysBefore).put("minute", it.minuteOfDay)) }
    })

    companion object {
        const val MAX_REMINDERS = 6
        /** Invalid entries are dropped; a missing field keeps its default. */
        fun fromJson(o: JSONObject): SensorEndConfig {
            val a = o.optJSONArray("reminders")
            val list = if (a == null) Reminder.DEFAULTS else (0 until a.length()).mapNotNull { i ->
                a.optJSONObject(i)?.let { r -> runCatching { Reminder(r.getInt("days"), r.getInt("minute")) }.getOrNull() }
            }.take(MAX_REMINDERS)
            return SensorEndConfig(list, o.optBoolean("countdown", true), o.optBoolean("warmup", true)).normalized()
        }
    }
}

/**
 * What the scheduler remembers between runs, per sensor ([key] = serial + start, so an end that moves — the sensor
 * reporting it ended early — keeps the state): the last reminder slot shown, whether the countdown was dismissed, the
 * "ended" notice shown, and the warm-up countdown shown / dismissed / followed by "Sensor ready".
 */
data class PlanMemory(val key: String = "", val lastReminder: Long = 0, val countdownDismissed: Boolean = false, val endedShown: Boolean = false,
                      val warmupShown: Boolean = false, val warmupDismissed: Boolean = false, val readyShown: Boolean = false)

/**
 * The pure rules (JVM-tested, SensorEndPlanTest), all times unix seconds:
 *
 *  - A reminder's slot is (end day − daysBefore) at its time, in the phone's time zone. A slot is used only if it is
 *    after the sensor started and **before the end**: when the sensor ends at 07:30, "on the end day at 09:00" is
 *    simply skipped (the countdown covers that morning).
 *  - Slots that passed before the planner knew them (a new sensor, edited reminders) never fire: [PlanMemory.lastReminder]
 *    starts at "now". A slot missed while the phone was off fires late, if the sensor hasn't ended; of several missed
 *    slots only the latest one is shown.
 *  - The live countdown runs from end − countdownHours to the end (0 h = no countdown), unless dismissed for this end.
 *  - At the end, one "Sensor ended" notice, shown at most [ENDED_GRACE] after the end (not days later after a reboot).
 *  - Warm-up (a new sensor gives no readings for its first ~60 min): a live countdown from the start to [warmupEnd]
 *    while no reading has arrived, unless dismissed; like SensorLife.warming it gives up `grace` after the warm-up
 *    end without a reading (then it just goes). When the first reading arrives, one short "Sensor ready" replaces
 *    it, but only if the warm-up countdown was shown (no "ready" for a sensor that was already running when Sukrara
 *    learned about it) and within [READY_GRACE] of that reading.
 */
object SensorEndPlan {
    /** Countdown progress refresh while the window runs (non-waking: it only matters when the screen is on). */
    const val TICK = 15 * 60L
    const val ENDED_GRACE = 12 * 3600L
    /** Warm-up progress refresh (the warm-up is about an hour). */
    const val WARMUP_TICK = 5 * 60L
    const val READY_GRACE = 3600L

    data class Plan(
        /** A reminder slot to show now (its time), or null. */
        val remind: Long?,
        val countdown: Boolean,
        val ended: Boolean,
        /** When to run again, or null = nothing left for this sensor. */
        val next: Long?,
        /** [next] is a reminder / window start / end (exact, waking) rather than a progress tick. */
        val nextExact: Boolean,
        /** The warm-up countdown shows now. */
        val warmup: Boolean = false,
        /** "Sensor ready" (warm-up over) to show now. */
        val ready: Boolean = false,
    )

    fun slots(start: Long, end: Long, reminders: List<Reminder>, zone: ZoneId): List<Long> {
        val endDay = Instant.ofEpochSecond(end).atZone(zone).toLocalDate()
        return reminders.map { r ->
            endDay.minusDays(r.daysBefore.toLong()).atTime(LocalTime.ofSecondOfDay(r.minuteOfDay * 60L)).atZone(zone).toEpochSecond()
        }.filter { it in (start + 1) until end }.distinct().sorted()
    }

    fun windowStart(end: Long, hours: Int) = end - hours * 3600L

    /**
     * [warmupEnd]: when the sensor should give its first reading (SensorInfo.warmupEndEpoch, 0 = none);
     * [firstReading]: the first reading's time since the start, or null while none has arrived; [grace]: how long
     * after [warmupEnd] the warm-up countdown waits for it (SensorLife.WARMUP_GRACE_S).
     */
    fun plan(start: Long, end: Long, cfg: SensorEndConfig, hours: Int, now: Long, zone: ZoneId, mem: PlanMemory,
             warmupEnd: Long = 0, firstReading: Long? = null, grace: Long = 15 * 60L): Plan {
        val slots = slots(start, end, cfg.reminders, zone)
        val remind = slots.lastOrNull { it > mem.lastReminder && it <= now }?.takeIf { now < end }
        val win = if (cfg.countdown && hours > 0) windowStart(end, hours) else null
        val countdown = win != null && now in win until end && !mem.countdownDismissed
        val ended = now >= end && now < end + ENDED_GRACE && !mem.endedShown
        val warmingUp = cfg.warmup && warmupEnd > start && now in start until warmupEnd + grace && firstReading == null && now < end
        val warmup = warmingUp && !mem.warmupDismissed
        val ready = firstReading != null && mem.warmupShown && !mem.warmupDismissed && !mem.readyShown &&
            now < firstReading + READY_GRACE && now < end
        val events = listOfNotNull(slots.firstOrNull { it > now }, win?.takeIf { it > now }, end.takeIf { it > now },
            (warmupEnd + grace).takeIf { warmingUp && it > now })
        val event = events.minOrNull()
        val tick = when { warmup -> now + WARMUP_TICK; countdown -> now + TICK; else -> null }
        return when {
            tick != null && (event == null || tick < event) -> Plan(remind, countdown, ended, tick, false, warmup, ready)
            else -> Plan(remind, countdown, ended, event, event != null, warmup, ready)
        }
    }

    /** Calendar days from [now] to [end] in [zone]: 0 = today, 1 = tomorrow … (words of the notification). */
    fun dayOffset(end: Long, now: Long, zone: ZoneId): Long {
        fun day(t: Long): LocalDate = Instant.ofEpochSecond(t).atZone(zone).toLocalDate()
        return ChronoUnit.DAYS.between(day(now), day(end))
    }

    /** The countdown bar: the time left in the window, per mille (1000 at the window start, 0 at the end). */
    fun leftPerMille(end: Long, hours: Int, now: Long): Int {
        val span = hours * 3600L
        if (span <= 0) return 0
        return (((end - now).coerceIn(0, span) * 1000) / span).toInt()
    }
}
