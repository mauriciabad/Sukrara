// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.sensorend

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
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

/**
 * The Sensor end settings page: the reminders, [unlock] (morning reminders, and the evening one, come at the first
 * unlock if that is earlier), [evening] (a reminder the evening before a night-time end), the night window
 * [nightStart]–[nightEnd] (minutes of the day; it may wrap midnight; Android doesn't share its Bedtime schedule with
 * other apps, so the user sets it here), the end countdown (hours: SensorLife.countdownHours) and the warm-up countdown.
 */
data class SensorEndConfig(val reminders: List<Reminder> = Reminder.DEFAULTS, val countdown: Boolean = true, val warmup: Boolean = true,
                           val unlock: Boolean = true, val evening: Boolean = true,
                           val nightStart: Int = DEFAULT_NIGHT_START, val nightEnd: Int = DEFAULT_NIGHT_END) {
    init { require(nightStart in 0 until 24 * 60 && nightEnd in 0 until 24 * 60 && nightStart != nightEnd) }

    /** Sorted earliest first, duplicates removed (what the page lists and the planner uses). */
    fun normalized() = copy(reminders = reminders.distinct().sortedWith(compareByDescending<Reminder> { it.daysBefore }.thenBy { it.minuteOfDay }))

    fun toJson(): JSONObject = JSONObject().put("countdown", countdown).put("warmup", warmup).put("reminders", JSONArray().apply {
        reminders.forEach { put(JSONObject().put("days", it.daysBefore).put("minute", it.minuteOfDay)) }
    }).put("unlock", unlock).put("evening", evening).put("nightStart", nightStart).put("nightEnd", nightEnd)

    companion object {
        const val MAX_REMINDERS = 6
        const val DEFAULT_NIGHT_START = 23 * 60
        const val DEFAULT_NIGHT_END = 7 * 60
        /** Invalid entries are dropped; a missing field keeps its default (an invalid night window: the default one). */
        fun fromJson(o: JSONObject): SensorEndConfig {
            val a = o.optJSONArray("reminders")
            val list = if (a == null) Reminder.DEFAULTS else (0 until a.length()).mapNotNull { i ->
                a.optJSONObject(i)?.let { r -> runCatching { Reminder(r.getInt("days"), r.getInt("minute")) }.getOrNull() }
            }.take(MAX_REMINDERS)
            val ns = o.optInt("nightStart", DEFAULT_NIGHT_START)
            val ne = o.optInt("nightEnd", DEFAULT_NIGHT_END)
            val night = if (ns in 0 until 24 * 60 && ne in 0 until 24 * 60 && ns != ne) ns to ne else DEFAULT_NIGHT_START to DEFAULT_NIGHT_END
            return SensorEndConfig(list, o.optBoolean("countdown", true), o.optBoolean("warmup", true),
                o.optBoolean("unlock", true), o.optBoolean("evening", true), night.first, night.second).normalized()
        }
    }
}

/**
 * What the scheduler remembers between runs, per sensor ([key] = serial + start, so an end that moves — the sensor
 * reporting it ended early — keeps the state): the last reminder slot shown, whether the countdown was dismissed, the
 * "ended" notice shown, the warm-up countdown shown / dismissed / followed by "Sensor ready", and [firstReading]: the
 * time of the first reading seen since the start (0 = none yet). Kept here because the newest reading lives only in
 * memory (Live): after a restart (an app update, 2026-10-18) the warm-up must not come back before the next reading.
 * [lastUnlock]: the latest unlock that could bring a reminder forward (SensorEnd.unlocked records only those).
 * [lastReminder] is a slot's *time*, so a reminder an unlock brought forward (06:40 for "09:00") stays shown: neither a
 * later unlock nor the 09:00 alarm, nor a restart in between, shows it again.
 */
data class PlanMemory(val key: String = "", val lastReminder: Long = 0, val countdownDismissed: Boolean = false, val endedShown: Boolean = false,
                      val warmupShown: Boolean = false, val warmupDismissed: Boolean = false, val readyShown: Boolean = false,
                      val firstReading: Long = 0, val lastUnlock: Long = 0)

/** Which words a reminder uses (SensorEnd.reminder). */
enum class ReminderText {
    /** "Sensor ends tomorrow at 14:30" · "Have a new sensor ready." */
    ENDS,
    /** The end day, ending later today outside the night: "Sensor ends today at 7:08 pm" · "Take your spare sensor with you". */
    SPARE,
    /** The evening before a night-time end: "Sensor ends tonight at 3:10" · "Change it before bed". */
    TONIGHT,
}

/**
 * A reminder's moment: [at] (its configured time: the fallback alarm), [unlockFrom] (the first unlock from this time
 * until [at] shows it early; null = only at [at]) and [evening] (the evening-before-the-night reminder).
 */
data class Slot(val at: Long, val unlockFrom: Long? = null, val evening: Boolean = false)

/**
 * The pure rules (JVM-tested, SensorEndPlanTest), all times unix seconds:
 *
 *  - A reminder's slot is (end day − daysBefore) at its time, in the phone's time zone. A slot is used only if it is
 *    after the sensor started and **before the end**: when the sensor ends at 07:30, "on the end day at 09:00" is
 *    simply skipped (the countdown covers that morning).
 *  - **Fitted to the user's day** (round "routine", 2026-10-18; the reminders are about the sensor's end, so they come
 *    when the user picks up the phone rather than at a fixed clock time). A reminder set for the morning (after
 *    [MORNING_FROM] 05:00 and before [NOON]) shows at the **first unlock** of that day from 05:00, if that is before its
 *    time; otherwise at its time, as before (the alarm stays as the fallback). Only an unlock after
 *    [PlanMemory.lastReminder] counts (one before a new sensor or a settings change doesn't).
 *  - **A night-time end gets an evening reminder**: if the end falls in the night window (start inclusive, end
 *    exclusive; default 23:00–07:00, it may wrap midnight), one more slot at bedtime − [EVENING_BEFORE] (21:00) on the
 *    evening before that night, or at the first unlock from [EVENING_UNLOCK] before it (19:00). The end then means no
 *    readings and a no-data alert while the user sleeps; this is the moment to change it.
 *  - Words ([ReminderText]): the evening slot says "tonight"; on the end day, an end outside the night window says
 *    to take the spare sensor along; everything else names the day.
 *  - Slots that passed before the planner knew them (a new sensor, edited reminders) never fire: [PlanMemory.lastReminder]
 *    starts at "now". A slot missed while the phone was off fires late, if the sensor hasn't ended; of several missed
 *    slots only the latest one is shown.
 *  - The live countdown runs from end − countdownHours to the end (0 h = no countdown), unless dismissed for this end.
 *  - At the end, one "Sensor ended" notice, shown at most [ENDED_GRACE] after the end (not days later after a reboot).
 *  - Warm-up (a new sensor gives no readings for its first ~60 min): a live countdown from the start to [warmupEnd]
 *    while no reading has arrived, unless dismissed. **A countdown never goes below zero**: at [warmupEnd] (an exact
 *    alarm) it becomes "Almost ready" without a timer ([Plan.warmupWaiting]) and, like SensorLife.warming, gives up
 *    `grace` after the warm-up end without a reading (then it just goes). The first reading ends it at once (cancelled,
 *    whatever state it's in); one short "Sensor ready" follows, but only if the warm-up countdown was shown (no
 *    "ready" for a sensor that was already running when Sukrara learned about it) and within [READY_GRACE] of that
 *    reading. The end countdown likewise stops at the end (exact alarm) and hands over to "Sensor ended".
 *  - A progress tick is inexact (non-waking, at most [TICK_WINDOW] late): it is used only when it fires before the
 *    next event even if late, so an event (warm-up end, sensor end) is never left to a late tick.
 */
object SensorEndPlan {
    /** Countdown progress refresh while the window runs (non-waking: it only matters when the screen is on). */
    const val TICK = 15 * 60L
    const val ENDED_GRACE = 12 * 3600L
    /** Warm-up progress refresh (the warm-up is about an hour). */
    const val WARMUP_TICK = 5 * 60L
    const val READY_GRACE = 3600L
    /** How late a progress tick may fire (SensorEnd schedules it with this window). */
    const val TICK_WINDOW = 60L
    /** The morning starts at 05:00: from then, the first unlock brings a morning reminder forward. */
    const val MORNING_FROM = 5 * 60
    /** Reminders set before 12:00 are morning reminders. */
    const val NOON = 12 * 60
    /** The evening reminder: this long before the night window starts (23:00 → 21:00), in minutes. */
    const val EVENING_BEFORE = 2 * 60
    /** ... or at the first unlock from this long before it (19:00), in minutes. */
    const val EVENING_UNLOCK = 2 * 60

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
        /** ... past its end, still without a reading: "Almost ready", no timer (never a negative countdown). */
        val warmupWaiting: Boolean = false,
        /** "Sensor ready" (warm-up over) to show now. */
        val ready: Boolean = false,
        /** The words of [remind]. */
        val remindText: ReminderText = ReminderText.ENDS,
    )

    private fun local(t: Long, zone: ZoneId) = Instant.ofEpochSecond(t).atZone(zone).toLocalDateTime()
    private fun epoch(t: LocalDateTime, zone: ZoneId) = t.atZone(zone).toEpochSecond()
    private fun at(day: LocalDate, minute: Int) = day.atTime(LocalTime.ofSecondOfDay(minute * 60L))

    /** Whether [t] falls in the night window (its start inclusive, its end exclusive). */
    fun inNight(t: Long, cfg: SensorEndConfig, zone: ZoneId): Boolean {
        val m = local(t, zone).toLocalTime().toSecondOfDay() / 60
        return if (cfg.nightStart < cfg.nightEnd) m in cfg.nightStart until cfg.nightEnd else m >= cfg.nightStart || m < cfg.nightEnd
    }

    /** The evening reminder before a night-time [end] (bedtime − 2 h of the night it falls in), or null. */
    fun eveningSlot(end: Long, cfg: SensorEndConfig, zone: ZoneId): Slot? {
        if (!cfg.evening || !inNight(end, cfg, zone)) return null
        val e = local(end, zone)
        val m = e.toLocalTime().toSecondOfDay() / 60
        // the night started the day before when the end is in its after-midnight part
        val nightDay = if (cfg.nightStart > cfg.nightEnd && m < cfg.nightEnd) e.toLocalDate().minusDays(1) else e.toLocalDate()
        val evening = at(nightDay, cfg.nightStart).minusMinutes(EVENING_BEFORE.toLong())
        return Slot(epoch(evening, zone), if (cfg.unlock) epoch(evening.minusMinutes(EVENING_UNLOCK.toLong()), zone) else null, evening = true)
    }

    /**
     * The reminder slots after [start] and before [end], earliest first: the configured reminders (morning ones open
     * at 05:00 for an unlock when [SensorEndConfig.unlock]) and the evening one. Two at the same time: the evening one.
     */
    fun slots(start: Long, end: Long, cfg: SensorEndConfig, zone: ZoneId): List<Slot> {
        val endDay = local(end, zone).toLocalDate()
        val configured = cfg.reminders.map { r ->
            val day = endDay.minusDays(r.daysBefore.toLong())
            val morning = cfg.unlock && r.minuteOfDay > MORNING_FROM && r.minuteOfDay < NOON
            Slot(epoch(at(day, r.minuteOfDay), zone), if (morning) epoch(at(day, MORNING_FROM), zone) else null)
        }
        return (listOfNotNull(eveningSlot(end, cfg, zone)) + configured)
            .filter { it.at in (start + 1) until end }.distinctBy { it.at }.sortedBy { it.at }
    }

    /** [s] is due: its time has come, or an unlock in its window (newer than the last reminder) brought it forward. */
    private fun due(s: Slot, now: Long, mem: PlanMemory) = now >= s.at ||
        (s.unlockFrom != null && mem.lastUnlock in s.unlockFrom until s.at && mem.lastUnlock > mem.lastReminder && mem.lastUnlock <= now)

    /**
     * An unlock at [now] would bring a reminder forward (SensorEnd.unlocked records it and re-plans only then, so the
     * other unlocks of the day cost nothing).
     */
    fun unlockWanted(start: Long, end: Long, cfg: SensorEndConfig, now: Long, zone: ZoneId, mem: PlanMemory): Boolean =
        now < end && slots(start, end, cfg, zone).any { s -> s.unlockFrom != null && now in s.unlockFrom until s.at && s.at > mem.lastReminder }

    /** The words for a reminder shown at [now] ([ReminderText]). */
    fun text(slot: Slot, end: Long, now: Long, cfg: SensorEndConfig, zone: ZoneId): ReminderText = when {
        slot.evening -> ReminderText.TONIGHT
        dayOffset(end, now, zone) == 0L && !inNight(end, cfg, zone) -> ReminderText.SPARE
        else -> ReminderText.ENDS
    }

    fun windowStart(end: Long, hours: Int) = end - hours * 3600L

    /**
     * [warmupEnd]: when the sensor should give its first reading (SensorInfo.warmupEndEpoch, 0 = none);
     * [firstReading]: the newest reading's time if it is since the start, or null (none, or none known in this process:
     * [PlanMemory.firstReading] counts too); [grace]: how long
     * after [warmupEnd] the warm-up countdown waits for it (SensorLife.WARMUP_GRACE_S).
     */
    fun plan(start: Long, end: Long, cfg: SensorEndConfig, hours: Int, now: Long, zone: ZoneId, mem: PlanMemory,
             warmupEnd: Long = 0, firstReading: Long? = null, grace: Long = 15 * 60L): Plan {
        val first = mem.firstReading.takeIf { it > 0 } ?: firstReading
        val slots = slots(start, end, cfg, zone)
        val shown = slots.lastOrNull { it.at > mem.lastReminder && due(it, now, mem) }?.takeIf { now < end }
        val remind = shown?.at
        val remindText = shown?.let { text(it, end, now, cfg, zone) } ?: ReminderText.ENDS
        // a slot an unlock brought forward needs no alarm at its time any more
        val last = maxOf(mem.lastReminder, remind ?: 0)
        val win = if (cfg.countdown && hours > 0) windowStart(end, hours) else null
        val countdown = win != null && now in win until end && !mem.countdownDismissed
        val ended = now >= end && now < end + ENDED_GRACE && !mem.endedShown
        val warmingUp = cfg.warmup && warmupEnd > start && now in start until warmupEnd + grace && first == null && now < end
        val warmup = warmingUp && !mem.warmupDismissed
        val ready = first != null && mem.warmupShown && !mem.warmupDismissed && !mem.readyShown &&
            now < first + READY_GRACE && now < end
        val waiting = warmup && now >= warmupEnd
        val events = listOfNotNull(slots.firstOrNull { it.at > now && it.at > last }?.at, win?.takeIf { it > now }, end.takeIf { it > now },
            warmupEnd.takeIf { warmingUp && it > now }, (warmupEnd + grace).takeIf { warmingUp && it > now })
        val event = events.minOrNull()
        // "Almost ready" has no timer and no bar to refresh: no tick
        val tick = when { warmup && !waiting -> now + WARMUP_TICK; countdown -> now + TICK; else -> null }
        return when {
            tick != null && (event == null || tick + TICK_WINDOW < event) -> Plan(remind, countdown, ended, tick, false, warmup, waiting, ready, remindText)
            else -> Plan(remind, countdown, ended, event, event != null, warmup, waiting, ready, remindText)
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
