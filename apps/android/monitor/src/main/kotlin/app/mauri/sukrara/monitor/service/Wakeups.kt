// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.monitor.service

import app.mauri.sukrara.graph.Freshness
import java.time.Duration
import java.time.Instant

/** The two alarms of the monitor (AlarmManager in the app, a fake in tests). */
interface Alarms {
    /** One wake-up [after] from now on the monotonic clock, exact when allowed, replacing the previous one. */
    fun scheduleHealthCheck(after: Duration)

    /** One non-waking alarm at [at] (wall clock), replacing the previous one; null cancels it. */
    fun scheduleFreshnessStep(at: Instant?)
}

/**
 * The health check: every [INTERVAL] a wake-up asks the starter to (re)start the service, so a service Android killed
 * comes back even when nothing else happens (no reading, no app open). Rescheduled on every fire, boot, update and
 * time change; the alarm is on the monotonic clock, so a clock change never pushes it away. It never evaluates
 * alerts: chunk 11's alert check runs on its own alarm without the service and calls the starter too.
 */
class HealthCheck(
    private val alarms: Alarms,
    private val starter: MonitorStarter,
) {
    fun schedule() = alarms.scheduleHealthCheck(INTERVAL)

    suspend fun onFire(): StartResult {
        schedule()
        return starter.start(StartReason.HealthCheck)
    }

    companion object {
        val INTERVAL: Duration = Duration.ofMinutes(15)
    }
}

/**
 * Redraws the glucose surfaces when the latest reading crosses one of the renderer's freshness steps without a new
 * reading (time shown at 90 s, struck through at 5 min, "no readings since" at 20 min, graph gone at 3 h). One
 * non-waking alarm; none while nothing will change.
 */
class FreshnessTicker(
    private val alarms: Alarms,
) {
    private var planned: Instant? = null

    /** Arms the alarm for the latest reading at [latest], seen at [now]. */
    @Synchronized
    fun plan(
        latest: Instant?,
        now: Instant,
    ) {
        val at = nextStep(latest, now)
        if (at == planned) return
        planned = at
        alarms.scheduleFreshnessStep(at)
    }

    /** The step came: forget it so the next [plan] arms the following one. */
    @Synchronized
    fun fired() {
        planned = null
    }

    companion object {
        /** The renderer's next freshness step after [now] for a reading at [latest]; null = none. */
        fun nextStep(
            latest: Instant?,
            now: Instant,
        ): Instant? = Freshness.nextChangeAt(latest?.epochSecond, now.epochSecond)?.let(Instant::ofEpochSecond)
    }
}
