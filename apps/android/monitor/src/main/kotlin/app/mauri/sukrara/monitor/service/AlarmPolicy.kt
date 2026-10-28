// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.monitor.service

/** The monitor's wake-ups (every alarm the app sets). */
enum class Wakeup {
    /** The alert runtime's check (no-data deadline, a mute ending, a repeat) and the sensor-end check. */
    AlertCheck,

    /** Every 15 min: restart the monitoring service if Android killed it. */
    HealthCheck,

    /** Redraw the glucose surfaces at a freshness step (nobody needs it while the screen is off). */
    FreshnessStep,

    /** A sensor countdown's progress bar refresh (only matters while the screen is on; never spends Doze quota). */
    SensorTick,
}

/** The AlarmManager call a [Wakeup] uses. */
enum class AlarmCall {
    /** `setExactAndAllowWhileIdle` on the elapsed clock: on time in Doze, may start the foreground service. */
    ExactWhileIdle,

    /** `setAndAllowWhileIdle`: fires in Doze within minutes; can't start the foreground service. */
    InexactWhileIdle,

    /** `setExact` on the wall clock, not waking the phone. */
    ExactRtc,

    /** `setWindow` on the wall clock (1 min), not waking the phone. */
    WindowRtc,
}

/**
 * Which call each wake-up uses (the one policy; `AndroidAlarms` applies it).
 *
 * Checks that protect the person ([Wakeup.AlertCheck], [Wakeup.HealthCheck]) are exact and allowed while idle. With
 * `USE_EXACT_ALARM` (granted at install, not revocable by the user) Android 12+ gives an app 72 such alarms an hour in
 * Doze (AOSP `AlarmManagerService` `allow_while_idle_quota`); the monitor needs at most ~4 health checks + ~12 alert
 * repeats + a sensor-end check an hour, so the health check never delays a no-data alarm. The "once per 9 minutes"
 * rule of older docs is the compat quota of apps without the exact permission (and of `setAndAllowWhileIdle`).
 *
 * `setAlarmClock` is deliberately not used: it would show an alarm icon and become the phone's "next alarm" on the lock
 * screen and clock widgets (hiding the person's wake-up alarm) for a deadline that moves with every reading, and it
 * needs the same exact-alarm permission. If exact alarms are ever refused, the checks fall back to inexact while-idle
 * alarms (late by minutes rather than missed; they then share the compat quota of 7 an hour, so the health check and
 * the alert check can delay each other) and the alert checklist names the exact-alarm permission.
 */
object AlarmPolicy {
    fun call(
        wakeup: Wakeup,
        canScheduleExact: Boolean,
    ): AlarmCall =
        when (wakeup) {
            Wakeup.AlertCheck, Wakeup.HealthCheck -> if (canScheduleExact) AlarmCall.ExactWhileIdle else AlarmCall.InexactWhileIdle
            Wakeup.FreshnessStep -> if (canScheduleExact) AlarmCall.ExactRtc else AlarmCall.WindowRtc
            Wakeup.SensorTick -> AlarmCall.WindowRtc
        }
}
