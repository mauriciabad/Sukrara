// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.sensor.sensorend

import app.mauri.sukrara.core.model.SensorState
import kotlinx.serialization.Serializable
import java.time.Duration
import java.time.Instant

/** A sensor-life notification to post now. */
sealed interface SensorNotice {
    /** "Sensor ends in 3 h (at 14:30)" — tap opens the new-sensor flow. */
    data class EndingSoon(val endsAt: Instant) : SensorNotice

    /** "Sensor ended — Pair new sensor" (opens the new-sensor flow). */
    data object Ended : SensorNotice

    /** "Sensor ready": the warm-up this phone saw is over. */
    data object Ready : SensorNotice
}

/**
 * What the warning remembers between runs (a typed store), so each notice fires once per sensor: keyed by the end
 * time (an Instant, so a time-zone change or DST never moves or repeats it).
 */
@Serializable
data class SensorEndMemory(
    val endEpoch: Long? = null,
    val warned: Boolean = false,
    val endedShown: Boolean = false,
    /** A warm-up was seen; "Sensor ready" follows when the sensor turns active. */
    val warmingUp: Boolean = false,
)

data class SensorEndDecision(val notices: List<SensorNotice>, val nextAlarm: Instant?, val memory: SensorEndMemory)

/**
 * The sensor-end warning, P0-lite (CHANGES P7; the full reminders planner is P1): one "ends soon" notification
 * [warnHours] before the end (the `sensor.countdownHours` setting: the warning comes when the countdown starts), one
 * "Sensor ended" within [ENDED_GRACE] of the end, and "Sensor ready" once after a warm-up. Pure (SensorEndWarningTest).
 */
object SensorEndWarning {
    /** "Sensor ended" is not shown days later (after a phone that was off for a weekend). */
    val ENDED_GRACE: Duration = Duration.ofHours(12)

    fun decide(state: SensorState, memory: SensorEndMemory, warnHours: Int, now: Instant): SensorEndDecision {
        val notices = mutableListOf<SensorNotice>()
        var mem = memory
        when (state) {
            is SensorState.WarmingUp -> mem = mem.copy(warmingUp = true)
            is SensorState.Active -> {
                if (mem.warmingUp) {
                    notices += SensorNotice.Ready
                    mem = mem.copy(warmingUp = false)
                }
                state.endsAt?.let { end -> mem = forEnd(mem, end) }
            }
            SensorState.Ended -> if (!mem.endedShown) {
                notices += SensorNotice.Ended
                mem = mem.copy(endedShown = true, warmingUp = false)
            }
            else -> Unit
        }
        val end = (state as? SensorState.Active)?.endsAt
            ?: return SensorEndDecision(notices, null, mem)
        val warnAt = end.minus(Duration.ofHours(warnHours.toLong()))
        when {
            now.isBefore(warnAt) -> Unit
            now.isBefore(end) -> if (!mem.warned) {
                notices += SensorNotice.EndingSoon(end)
                mem = mem.copy(warned = true)
            }
            now.isBefore(end.plus(ENDED_GRACE)) -> if (!mem.endedShown) {
                notices += SensorNotice.Ended
                mem = mem.copy(endedShown = true)
            }
        }
        val next = listOf(warnAt, end).firstOrNull { it.isAfter(now) }
        return SensorEndDecision(notices, next, mem)
    }

    /** A new end (a new sensor, or the sensor reporting another end) starts its notices afresh. */
    private fun forEnd(mem: SensorEndMemory, end: Instant): SensorEndMemory =
        if (mem.endEpoch == end.epochSecond) mem else mem.copy(endEpoch = end.epochSecond, warned = false, endedShown = false)
}
