// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.monitor.sensorend

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import app.mauri.sukrara.core.model.Clock
import app.mauri.sukrara.core.model.SensorState
import app.mauri.sukrara.core.model.storage.StateStore
import app.mauri.sukrara.monitor.R
import app.mauri.sukrara.monitor.alerts.AlertAlarm
import app.mauri.sukrara.monitor.notify.Channels
import app.mauri.sukrara.monitor.notify.NotificationGroups
import app.mauri.sukrara.monitor.notify.NotificationIds
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What the sensor-end notifier shows (Android: [AndroidSensorEndPresenter]; tests: a fake). */
interface SensorEndPresenter {
    fun warn(endsAt: Long, now: Long)

    fun ended()

    fun clear()
}

/**
 * Warns before the sensor ends and says when it has (CHANGES P7): driven by the hub's sensor state while the service
 * runs ([onSensor]) and by its own exact alarm, boot and clock changes ([check], from `AlertsModule.check`).
 */
class SensorEndNotifier(
    private val store: StateStore<SensorEndMemory>,
    private val presenter: SensorEndPresenter,
    private val alarm: AlertAlarm,
    private val clock: Clock,
) {
    private val mutex = Mutex()

    /** The hub's sensor: an active sensor's end plans the notices; no sensor clears them. */
    suspend fun onSensor(sensor: SensorState) {
        when (sensor) {
            is SensorState.Active -> sensor.endsAt?.let { apply(it.epochSecond) }
            SensorState.None -> apply(null)
            else -> Unit // warming up, ended, Bluetooth off, …: the planned end still holds
        }
    }

    /** Re-evaluates the planned end now (alarm, boot, time change). */
    suspend fun check() {
        apply(store.data.first().endsAt.takeIf { it > 0 })
    }

    private suspend fun apply(endsAt: Long?) = mutex.withLock {
        val now = clock.now().epochSecond
        val before = store.data.first()
        val d = SensorEndPlan.decide(endsAt, now, before)
        if (d.clear) presenter.clear()
        if (d.warn && endsAt != null) presenter.warn(endsAt, now)
        if (d.ended) presenter.ended()
        store.update { d.memory }
        alarm.set(d.next)
    }
}

/** The sensor notifications on channel `sensor`, in the `info` group (never bundled with alerts). */
class AndroidSensorEndPresenter(private val ctx: Context) : SensorEndPresenter {
    override fun warn(endsAt: Long, now: Long) {
        val hours = ((endsAt - now + 1799) / 3600).coerceAtLeast(1)
        val left = ctx.getString(app.mauri.sukrara.core.graph.R.string.duration_text_hours, hours)
        post(
            builder()
                .setContentTitle(ctx.getString(R.string.sensor_end_soon_title, left))
                .setContentText(ctx.getString(R.string.sensor_end_soon_text))
                .setWhen(endsAt * 1000)
                .setShowWhen(true)
                .setUsesChronometer(true)
                .setChronometerCountDown(true)
                .setTimeoutAfter(((endsAt - now) * 1000).coerceAtLeast(1_000))
                .build(),
        )
    }

    override fun ended() {
        val pair = Notification.Action.Builder(null, ctx.getString(R.string.sensor_action_pair), openApp(ACTION_PAIR_SENSOR)).build()
        post(
            builder()
                .setContentTitle(ctx.getString(R.string.sensor_ended_title))
                .setContentText(ctx.getString(R.string.sensor_ended_text))
                .addAction(pair)
                .build(),
        )
    }

    override fun clear() = NotificationGroups.cancel(ctx, ID, NotificationGroups.Group.INFO)

    @Volatile private var channelsReady = false

    private fun builder(): Notification.Builder {
        if (!channelsReady) {
            Channels.create(ctx) // idempotent; the alarm receiver may post before the app made them
            channelsReady = true
        }
        return Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(ctx.applicationInfo.icon)
            .setCategory(Notification.CATEGORY_REMINDER)
            .setContentIntent(openApp(null))
            .setAutoCancel(true)
            .setGroup(NotificationGroups.Group.INFO.key)
    }

    private fun post(n: Notification) = NotificationGroups.post(ctx, ID, n, NotificationGroups.Group.INFO, CHANNEL)


    /** The app at its start screen, or with [action] (the sensor flow of chunk 18 handles [ACTION_PAIR_SENSOR]). */
    private fun openApp(action: String?): PendingIntent {
        val launch = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName) ?: Intent().setPackage(ctx.packageName)
        action?.let { launch.setAction(it) }
        return PendingIntent.getActivity(ctx, if (action == null) 10 else 11, launch, PendingIntent.FLAG_IMMUTABLE)
    }

    companion object {
        const val CHANNEL = Channels.SENSOR
        const val ID = NotificationIds.SENSOR
        const val ACTION_PAIR_SENSOR = "app.mauri.sukrara.action.PAIR_SENSOR"
    }
}
