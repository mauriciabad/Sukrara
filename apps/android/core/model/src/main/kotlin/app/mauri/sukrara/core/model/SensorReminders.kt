// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.core.model

/**
 * One sensor-end reminder: [daysBefore] the day the sensor ends (0 = that day) at [minuteOfDay] local time.
 */
data class SensorReminder(
    val daysBefore: Int,
    val minuteOfDay: Int,
) {
    init {
        require(daysBefore in 0..MAX_DAYS && minuteOfDay in 0 until MINUTES_PER_DAY) { "invalid reminder" }
    }

    companion object {
        const val MAX_DAYS = 3
        const val MINUTES_PER_DAY = 24 * 60

        /** The day before and the end day, both at 09:00. */
        val DEFAULTS = listOf(SensorReminder(1, 9 * 60), SensorReminder(0, 9 * 60))
    }
}

/**
 * Settings › Sensor end (schema `sensor.reminders`): the [reminders]; [unlock] = a morning (or evening) reminder comes
 * at the first unlock if that is earlier; [evening] = one more reminder the evening before a night-time end, the night
 * being [nightStart]–[nightEnd] (minutes of the day, may wrap midnight: Android doesn't share its Bedtime schedule);
 * [countdown] = the end countdown in the last `sensor.countdownHours`; [warmup] = the warm-up countdown.
 */
data class SensorEndConfig(
    val reminders: List<SensorReminder> = SensorReminder.DEFAULTS,
    val countdown: Boolean = true,
    val warmup: Boolean = true,
    val unlock: Boolean = true,
    val evening: Boolean = true,
    val nightStart: Int = DEFAULT_NIGHT_START,
    val nightEnd: Int = DEFAULT_NIGHT_END,
) {
    init {
        require(nightStart in 0 until SensorReminder.MINUTES_PER_DAY && nightEnd in 0 until SensorReminder.MINUTES_PER_DAY)
        require(nightStart != nightEnd) { "the night needs two different times" }
    }

    /** Earliest first (most days before, then time), duplicates removed, at most [MAX_REMINDERS]. */
    fun normalized(): SensorEndConfig =
        copy(
            reminders =
                reminders
                    .distinct()
                    .sortedWith(compareByDescending<SensorReminder> { it.daysBefore }.thenBy { it.minuteOfDay })
                    .take(MAX_REMINDERS),
        )

    companion object {
        const val MAX_REMINDERS = 6
        const val DEFAULT_NIGHT_START = 23 * 60
        const val DEFAULT_NIGHT_END = 7 * 60
    }
}
