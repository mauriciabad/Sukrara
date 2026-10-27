// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.monitor.sensorend

import kotlinx.serialization.Serializable

/**
 * What the sensor-end notifier remembers per sensor (typed DataStore `sensor_end`): the end it planned for ([endsAt],
 * epoch s; 0 = none), and whether the "ends soon" warning and the "ended" notice were shown.
 */
@Serializable
data class SensorEndMemory(val endsAt: Long = 0, val warned: Boolean = false, val endedShown: Boolean = false)

/**
 * The minimal sensor-end warning (CHANGES P7; the PoC's SensorEndPlan reduced to what V1 ships, the reminders page is
 * P1). All times epoch seconds:
 * - one "Sensor ends in 6 h" warning [warnHours] before the end, shown once per sensor; missed (phone off), it shows
 *   late, but only before the end;
 * - one "Sensor ended" notice with *Pair new sensor*, at most [ENDED_GRACE_S] after the end (not days later after a
 *   reboot);
 * - a new sensor (an end more than [SAME_SENSOR_S] away from the planned one) starts over; an end that moves a little
 *   (the sensor reporting it) keeps what was shown.
 */
object SensorEndPlan {
    const val DEFAULT_WARN_HOURS = 6
    const val ENDED_GRACE_S = 12 * 3600L
    const val SAME_SENSOR_S = 24 * 3600L

    /** [clear]: remove what is shown (the sensor is gone or replaced). */
    data class Decision(val warn: Boolean, val ended: Boolean, val clear: Boolean, val next: Long?, val memory: SensorEndMemory)

    /** [endsAt] null: no sensor end known (no sensor, or the source can't tell): nothing to show, no alarm. */
    fun decide(endsAt: Long?, now: Long, memory: SensorEndMemory, warnHours: Int = DEFAULT_WARN_HOURS): Decision {
        if (endsAt == null) return Decision(warn = false, ended = false, clear = memory.endsAt != 0L, next = null, memory = SensorEndMemory())
        val replaced = memory.endsAt != 0L && kotlin.math.abs(memory.endsAt - endsAt) > SAME_SENSOR_S
        val m = if (memory.endsAt == 0L || replaced) {
            SensorEndMemory(endsAt)
        } else {
            memory.copy(endsAt = endsAt)
        }
        val warnAt = endsAt - warnHours * 3600L
        val ended = now >= endsAt
        val warn = !ended && now >= warnAt && !m.warned
        val showEnded = ended && !m.endedShown && now - endsAt <= ENDED_GRACE_S
        val next = when {
            !ended && now < warnAt -> warnAt
            !ended -> endsAt
            else -> null
        }
        return Decision(warn, showEnded, replaced, next, m.copy(warned = m.warned || warn || ended, endedShown = m.endedShown || ended))
    }
}
