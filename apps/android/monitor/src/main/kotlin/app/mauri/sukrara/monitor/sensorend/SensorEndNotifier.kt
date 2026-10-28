// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.monitor.sensorend

import app.mauri.sukrara.core.model.Clock
import app.mauri.sukrara.core.model.SensorEndConfig
import app.mauri.sukrara.core.model.SensorState
import app.mauri.sukrara.core.model.storage.StateStore
import app.mauri.sukrara.graph.cardInfo
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The sensor notices the notifier shows (each its own notification). */
enum class SensorNotice { REMINDER, COUNTDOWN, WARMUP, READY, ENDED }

/** What the sensor-end notifier shows (Android: [AndroidSensorEndPresenter]; tests: a fake). All times epoch s. */
interface SensorEndPresenter {
    fun remind(end: Long, now: Long, text: ReminderText)

    /** The end countdown; [promote] = ask for a Live Update (only while the glucose chip is off). */
    fun countdown(end: Long, hours: Int, now: Long, promote: Boolean)

    /** The warm-up countdown to [warmupEnd]; [waiting] = past it, "Almost ready" without a timer. */
    fun warmup(start: Long, warmupEnd: Long, now: Long, waiting: Boolean)

    fun ready()

    fun ended()

    fun cancel(notice: SensorNotice)
}

/** The notifier's one alarm: [exact] = an event (waking, on time), else a progress tick (non-waking); null cancels. */
fun interface SensorEndAlarm {
    fun set(at: Long?, exact: Boolean)
}

/** The settings the notifier plans with: Settings › Sensor end, the countdown hours, the glucose chip. */
data class SensorEndSettings(val config: SensorEndConfig, val countdownHours: Int, val chip: Boolean)

/**
 * Sensor-end reminders, the end and warm-up countdowns, *Sensor ready* and *Sensor ended* for the wearer
 * ([SensorEndPlan] has the rules). Every trigger recomputes what should show **now** and sets one alarm for the next
 * moment that matters, so a changed sensor, zone or setting never leaves a stale alarm:
 * - [update]: the hub's sensor and newest reading while the service runs, and every settings change;
 * - [check]: the alarm, boot, app update, time change (from the saved sensor alone: the hub may not know it yet);
 * - [unlocked]: an unlock that brings a reminder forward; [dismissed]: a countdown swiped away (kept away for it).
 */
class SensorEndNotifier(
    private val store: StateStore<SensorEndMemory>,
    private val presenter: SensorEndPresenter,
    private val alarm: SensorEndAlarm,
    private val clock: Clock,
    private val settings: suspend () -> SensorEndSettings,
) {
    private val mutex = Mutex()

    /**
     * The hub's [sensor] and the newest reading's time ([latestS]); [wearer] false (a follower, no role) clears
     * everything: only the phone that wears the sensor reminds.
     */
    suspend fun update(
        wearer: Boolean,
        sensor: SensorState,
        latestS: Long?,
        s: SensorEndSettings,
    ) = mutex.withLock {
        val now = clock.now().epochSecond
        val before = store.data.first()
        if (!wearer || sensor == SensorState.None) return@withLock forget(before)
        val info = sensor.cardInfo()
        val mem =
            when {
                info != null -> {
                    SensorEndPlan.remember(before, info.startS, info.endS, info.warmupEndS, s.config.hashCode(), now)
                }

                // an end before the planned one: the sensor stopped early
                sensor == SensorState.Ended && before.known -> {
                    SensorEndPlan.remember(before, before.start, minOf(before.end, now), before.warmupEnd, s.config.hashCode(), now)
                }

                // Bluetooth off, a missing core, a step for the person: the planned sensor still holds
                before.known -> {
                    SensorEndPlan.remember(before, before.start, before.end, before.warmupEnd, s.config.hashCode(), now)
                }

                else -> {
                    return@withLock
                }
            }
        if (before.known && mem.start != before.start) SensorNotice.entries.forEach(presenter::cancel)
        apply(mem, latestS?.takeIf { it >= mem.start }, s, now)
    }

    /** Re-plans from the saved sensor now (alarm, boot, time change). */
    suspend fun check() =
        mutex.withLock {
            val before = store.data.first()
            if (!before.known) return@withLock alarm.set(null, true)
            val s = settings()
            val now = clock.now().epochSecond
            apply(SensorEndPlan.remember(before, before.start, before.end, before.warmupEnd, s.config.hashCode(), now), null, s, now)
        }

    /** The phone was unlocked: recorded and re-planned only when it brings a reminder forward. */
    suspend fun unlocked() =
        mutex.withLock {
            val before = store.data.first()
            val s = settings()
            val now = clock.now().epochSecond
            if (!SensorEndPlan.unlockWanted(before, s.config, now, clock.zone)) return@withLock
            apply(before.copy(lastUnlock = now), null, s, now)
        }

    /** A countdown was swiped away: not shown again for this sensor ([warmup] = the warm-up one, and no "ready"). */
    suspend fun dismissed(warmup: Boolean) =
        mutex.withLock {
            store.update { if (warmup) it.copy(warmupDismissed = true) else it.copy(countdownDismissed = true) }
            presenter.cancel(if (warmup) SensorNotice.WARMUP else SensorNotice.COUNTDOWN)
        }

    private suspend fun forget(before: SensorEndMemory) {
        SensorNotice.entries.forEach(presenter::cancel)
        if (before != SensorEndMemory()) store.update { SensorEndMemory() }
        alarm.set(null, true)
    }

    private suspend fun apply(
        m0: SensorEndMemory,
        first: Long?,
        s: SensorEndSettings,
        now: Long,
    ) {
        var mem = m0
        val p = SensorEndPlan.plan(s.config, s.countdownHours, now, clock.zone, mem, first)
        if (p.warmup) {
            presenter.warmup(mem.start, mem.warmupEnd, now, p.warmupWaiting)
            mem = mem.copy(warmupShown = true)
        } else {
            // the first reading (or the give-up) ends it, whatever it shows
            presenter.cancel(SensorNotice.WARMUP)
        }
        if (p.ready) {
            presenter.ready()
            mem = mem.copy(readyShown = true)
        }
        // remembered: after a restart the warm-up must not come back before the next reading
        if (first != null && mem.firstReading == 0L) mem = mem.copy(firstReading = first)
        p.remind?.let {
            presenter.remind(mem.end, now, p.remindText)
            mem = mem.copy(lastReminder = it)
        }
        if (p.countdown) {
            presenter.countdown(
                mem.end,
                s.countdownHours,
                now,
                promote = !s.chip,
            )
        } else {
            presenter.cancel(SensorNotice.COUNTDOWN)
        }
        if (p.ended) {
            presenter.cancel(SensorNotice.REMINDER)
            presenter.ended()
            mem = mem.copy(endedShown = true)
        }
        store.update { mem }
        alarm.set(p.next, p.nextExact)
    }
}
