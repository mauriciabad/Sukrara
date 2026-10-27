// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.monitor.alerts

import app.mauri.sukrara.alerts.AlertEvent
import app.mauri.sukrara.alerts.AlertGroup
import app.mauri.sukrara.alerts.AlertKind
import app.mauri.sukrara.alerts.AlertSettings
import app.mauri.sukrara.alerts.Firing
import app.mauri.sukrara.alerts.Side
import app.mauri.sukrara.core.model.AlertDeliveryProblem

/*
 * What the alert runtime drives. The Android implementations are AlertNotifier, AndroidAlarms.alertCheck and the module's
 * wiring; tests use fakes, so no test ever posts a real notification or sets a real alarm.
 */

/** Shows and removes alert notifications: one slot per [AlertGroup] (an alert, or its side's muted card). */
interface AlertPresenter {
    /**
     * Posts [firing] ([silent]: shown again after a restart, it already rang). [who] = the followed person, or null;
     * [relaxedMin] > 0: the side reminds less (offer *Remind as usual*).
     */
    fun show(firing: Firing, settings: AlertSettings, silent: Boolean, who: String?, relaxedMin: Int)

    /** Replaces the group's slot with "[side] alerts muted · until [until]" (count-down, Unmute, Change time). */
    fun showMuted(group: AlertGroup, side: Side, kind: AlertKind, until: Long, settings: AlertSettings, now: Long)

    fun clear(group: AlertGroup)

    /** What stops an alert from reaching the person now (checked on every publish; shown on home). */
    fun deliveryProblems(alarmKinds: Set<AlertKind>): Set<AlertDeliveryProblem>
}

/** The one exact wake-up of the alert runtime: [AlertRuntime.tick] at [at] (epoch s); null = none needed. */
fun interface AlertAlarm {
    fun set(at: Long?)
}

/** Sends the wearer's `alert` events to the followers (chunk 13's publisher; a no-op until it is wired). */
fun interface AlertEventSink {
    fun publish(event: AlertEvent)
}

/**
 * Restarts the monitoring service after a check found it dead (chunk 10's `MonitorStarter`; it surfaces a denial).
 * Suspends until the start was tried, so the alarm's broadcast (and its foreground-service start exemption) is still
 * alive when it happens.
 */
fun interface MonitorRestarter {
    suspend fun restart()
}
