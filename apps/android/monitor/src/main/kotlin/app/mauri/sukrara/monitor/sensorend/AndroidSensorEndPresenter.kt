// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.monitor.sensorend

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import app.mauri.sukrara.core.model.NavTargets
import app.mauri.sukrara.graph.Clock
import app.mauri.sukrara.monitor.AlertCheckReceiver
import app.mauri.sukrara.monitor.R
import app.mauri.sukrara.monitor.notify.Channels
import app.mauri.sukrara.monitor.notify.NotificationGroups
import app.mauri.sukrara.monitor.notify.NotificationIds
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.concurrent.ConcurrentHashMap

/**
 * The sensor notices. Reminders, *Sensor ready* and *Sensor ended* are plain notifications on channel `sensor` in the
 * `info` group (never an alarm, never a Live Update: "upcoming calendar events" are not one). The countdowns are
 * ongoing ProgressStyle notifications on `sensor.countdown`, ungrouped, with the time left as a chronometer counting
 * down (the chip's timer when promoted) and **no buttons**: the way out is a swipe (the delete intent keeps it away
 * for this sensor, as the Live Update guide asks) or Settings › Sensor end. Not `setTimeoutAfter` on them: a timeout
 * sends the delete intent, which would read as "dismissed". The end countdown asks for promotion only while the
 * glucose chip is off: the status bar shows one chip, and the glucose value keeps it.
 */
class AndroidSensorEndPresenter(
    private val ctx: Context,
) : SensorEndPresenter {
    private val nm = ctx.getSystemService(NotificationManager::class.java)

    /** What each notice last posted: an equal post is skipped (the hub updates every minute). */
    private val posted = ConcurrentHashMap<SensorNotice, List<Any?>>()

    override fun remind(
        end: Long,
        now: Long,
        text: ReminderText,
    ) {
        val title =
            if (text == ReminderText.TONIGHT) ctx.getString(R.string.sensor_end_tonight, Clock.time(end)) else endsText(end, now)
        val body =
            when (text) {
                ReminderText.ENDS -> R.string.sensor_end_reminder_text
                ReminderText.SPARE -> R.string.sensor_end_spare_text
                ReminderText.TONIGHT -> R.string.sensor_end_tonight_text
            }
        info(
            SensorNotice.REMINDER,
            listOf(end, now, text),
            builder(Channels.SENSOR)
                .setContentTitle(title)
                .setContentText(ctx.getString(body))
                .setCategory(Notification.CATEGORY_REMINDER)
                .setContentIntent(openApp())
                .setAutoCancel(true),
        )
    }

    override fun countdown(
        end: Long,
        hours: Int,
        now: Long,
        promote: Boolean,
    ) {
        val bar = SensorEndPlan.leftPerMille(end, hours, now)
        live(
            SensorNotice.COUNTDOWN,
            listOf(end, bar / 10, promote),
            ctx.getString(R.string.sensor_countdown_title, Clock.time(end)),
            ctx.getString(R.string.sensor_countdown_text),
            until = end,
            perMille = bar,
            promote = promote,
        )
    }

    override fun warmup(
        start: Long,
        warmupEnd: Long,
        now: Long,
        waiting: Boolean,
    ) {
        if (waiting) {
            // countdown → "Almost ready": a fresh post, so a dropped update can't leave a timer running below zero
            if (posted[SensorNotice.WARMUP]?.firstOrNull() == false) nm.cancel(id(SensorNotice.WARMUP))
            live(
                SensorNotice.WARMUP,
                listOf(true),
                ctx.getString(R.string.sensor_almost_ready),
                ctx.getString(R.string.sensor_waiting_first_reading),
                until = null,
                perMille = 1000,
                promote = true,
            )
        } else {
            val bar = SensorEndPlan.warmupPerMille(start, warmupEnd, now)
            live(
                SensorNotice.WARMUP,
                listOf(false, warmupEnd, bar / 20),
                ctx.getString(R.string.sensor_warmup_title),
                ctx.getString(R.string.sensor_warmup_text, Clock.time(warmupEnd)),
                until = warmupEnd,
                perMille = bar,
                promote = true,
            )
        }
    }

    override fun ready() =
        info(
            SensorNotice.READY,
            listOf(System.nanoTime()),
            // good news, the warm-up countdown's follow-up: on its quiet channel, never a sound
            builder(Channels.SENSOR_COUNTDOWN)
                .setContentTitle(ctx.getString(R.string.sensor_ready_title))
                .setContentText(ctx.getString(R.string.sensor_ready_text))
                .setCategory(Notification.CATEGORY_STATUS)
                .setContentIntent(openApp())
                .setTimeoutAfter(READY_SHOWN_MS)
                .setAutoCancel(true),
        )

    override fun ended() {
        val replace = sensorSetup()
        info(
            SensorNotice.ENDED,
            listOf(System.nanoTime()),
            builder(Channels.SENSOR)
                .setContentTitle(ctx.getString(R.string.sensor_ended_title))
                .setContentText(ctx.getString(R.string.sensor_ended_text))
                .setCategory(Notification.CATEGORY_REMINDER)
                .setContentIntent(replace)
                .addAction(Notification.Action.Builder(null, ctx.getString(R.string.monitor_action_replace_sensor), replace).build())
                .setAutoCancel(true),
        )
    }

    override fun cancel(notice: SensorNotice) {
        // nothing posted by this process: a notification from before a restart may still show
        val had = posted.remove(notice) != null
        if (!had && runCatching { nm.activeNotifications.none { it.id == id(notice) } }.getOrDefault(false)) return
        when (notice) {
            SensorNotice.COUNTDOWN, SensorNotice.WARMUP -> nm.cancel(id(notice))
            else -> NotificationGroups.cancel(ctx, id(notice), NotificationGroups.Group.INFO)
        }
    }

    /** "Sensor ends today at 14:30" / "tomorrow at 14:30" / "on Wednesday at 14:30". */
    private fun endsText(
        end: Long,
        now: Long,
    ): String {
        val zone = ZoneId.systemDefault()
        val at = Clock.time(end)
        val locale = ctx.resources.configuration.locales[0]
        return when (val d = SensorEndPlan.dayOffset(end, now, zone)) {
            0L -> {
                ctx.getString(R.string.sensor_end_today, at)
            }

            1L -> {
                ctx.getString(R.string.sensor_end_tomorrow, at)
            }

            else -> {
                val day = Instant.ofEpochSecond(end).atZone(zone)
                val name =
                    if (d < 7) {
                        day.dayOfWeek.getDisplayName(TextStyle.FULL, locale)
                    } else {
                        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(day)
                    }
                ctx.getString(R.string.sensor_end_on_day, name, at)
            }
        }
    }

    private fun live(
        notice: SensorNotice,
        key: List<Any?>,
        title: String,
        text: String,
        until: Long?,
        perMille: Int,
        promote: Boolean,
    ) {
        if (posted[notice] == key) return
        val style =
            Notification
                .ProgressStyle()
                .setProgressSegments(listOf(Notification.ProgressStyle.Segment(1000)))
                .setProgress(perMille)
        val b =
            builder(Channels.SENSOR_COUNTDOWN)
                .setSmallIcon(R.drawable.monitor_ic_timer)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(style)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_PROGRESS)
                .setContentIntent(openApp())
                .setDeleteIntent(dismissed(notice == SensorNotice.WARMUP))
        // until == null: no timer at all (a countdown is never left running past its end)
        if (until != null) {
            b
                .setWhen(until * 1000)
                .setShowWhen(true)
                .setUsesChronometer(true)
                .setChronometerCountDown(true)
        } else {
            b.setShowWhen(false)
        }
        if (promote && canPromote()) runCatching { b.setRequestPromotedOngoing(true) }
        runCatching { nm.notify(id(notice), b.build()) }
            .onSuccess { posted[notice] = key }
            .onFailure { Log.w(TAG, "post $notice: ${it.javaClass.simpleName}") }
    }

    private fun info(
        notice: SensorNotice,
        key: List<Any?>,
        b: Notification.Builder,
    ) {
        runCatching {
            NotificationGroups.post(
                ctx,
                id(notice),
                b.setGroup(NotificationGroups.Group.INFO.key).build(),
                NotificationGroups.Group.INFO,
                Channels.SENSOR,
            )
        }.onSuccess { posted[notice] = key }
            .onFailure { Log.w(TAG, "post $notice: ${it.javaClass.simpleName}") }
    }

    @Volatile private var channelsReady = false

    private fun builder(channel: String): Notification.Builder {
        if (!channelsReady) {
            Channels.createCore(ctx) // idempotent; the alarm receiver may post before the app made them
            channelsReady = true
        }
        return Notification.Builder(ctx, channel).setSmallIcon(R.drawable.monitor_ic_app)
    }

    private fun canPromote() =
        Build.VERSION.SDK_INT_FULL >= Build.VERSION_CODES_FULL.BAKLAVA_1 &&
            runCatching { nm.canPostPromotedNotifications() }.getOrDefault(false)

    private fun openApp(): PendingIntent {
        val launch = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName) ?: Intent().setPackage(ctx.packageName)
        return PendingIntent.getActivity(ctx, REQUEST_OPEN, launch, PendingIntent.FLAG_IMMUTABLE)
    }

    private fun sensorSetup(): PendingIntent {
        val setup = Intent(NavTargets.ACTION_SENSOR_SETUP).setPackage(ctx.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val intent =
            if (setup.resolveActivity(ctx.packageManager) != null) {
                setup
            } else {
                ctx.packageManager.getLaunchIntentForPackage(ctx.packageName) ?: Intent().setPackage(ctx.packageName)
            }
        return PendingIntent.getActivity(ctx, REQUEST_SETUP, intent, PendingIntent.FLAG_IMMUTABLE)
    }

    private fun dismissed(warmup: Boolean): PendingIntent =
        PendingIntent.getBroadcast(
            ctx,
            if (warmup) REQUEST_WARMUP_DISMISSED else REQUEST_COUNTDOWN_DISMISSED,
            Intent(ctx, AlertCheckReceiver::class.java).setAction(
                if (warmup) AlertCheckReceiver.ACTION_WARMUP_DISMISSED else AlertCheckReceiver.ACTION_COUNTDOWN_DISMISSED,
            ),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    companion object {
        private const val TAG = "Sukrara.sensorEnd"
        private const val READY_SHOWN_MS = 10 * 60_000L
        private const val REQUEST_OPEN = 10
        private const val REQUEST_SETUP = 11
        private const val REQUEST_COUNTDOWN_DISMISSED = 12
        private const val REQUEST_WARMUP_DISMISSED = 13

        fun id(notice: SensorNotice): Int =
            when (notice) {
                SensorNotice.REMINDER -> NotificationIds.SENSOR
                SensorNotice.COUNTDOWN -> NotificationIds.SENSOR_COUNTDOWN
                SensorNotice.ENDED -> NotificationIds.SENSOR_ENDED
                SensorNotice.WARMUP -> NotificationIds.SENSOR_WARMUP
                SensorNotice.READY -> NotificationIds.SENSOR_READY
            }
    }
}
