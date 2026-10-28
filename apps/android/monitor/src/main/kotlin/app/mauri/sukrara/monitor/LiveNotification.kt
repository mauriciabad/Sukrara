// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.monitor

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.text.SpannableString
import android.text.style.StrikethroughSpan
import android.util.Log
import app.mauri.sukrara.core.model.GlucoseState
import app.mauri.sukrara.core.model.NavTargets
import app.mauri.sukrara.core.model.SensorState
import app.mauri.sukrara.graph.CardSurface
import app.mauri.sukrara.graph.Freshness
import app.mauri.sukrara.graph.GlucoseCard
import app.mauri.sukrara.graph.GlucoseLook
import app.mauri.sukrara.graph.SensorBadge
import app.mauri.sukrara.graph.ValueGlyphs
import app.mauri.sukrara.graph.stats.toPoint
import app.mauri.sukrara.monitor.notify.Channels
import app.mauri.sukrara.monitor.notify.LiveNotificationContent
import app.mauri.sukrara.monitor.notify.LiveNotificationContent.Icon
import app.mauri.sukrara.monitor.notify.LiveNotificationContent.Target
import app.mauri.sukrara.monitor.notify.NotificationIds
import app.mauri.sukrara.monitor.notify.PostGate
import app.mauri.sukrara.monitor.notify.UiText
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The glucose notification (surface `notification-text`: title, Live Update chip and small icon), which is also the
 * monitoring service's foreground notification, so the status bar costs one post per reading.
 *
 * Promoted ongoing **Live Update** when Android allows it (`canPostPromotedNotifications`): the status-bar chip is the
 * small icon (the trend arrow) + `setShortCriticalText` ("138", no text arrow). A Live Update can't be colorized or use custom
 * views, so the card stays a standard BigText notification. When the person turned Live Updates off (or demoted it)
 * the same notification posts unpromoted: title, text and arrow icon in the shade, no chip. The renderer's card image
 * (surface `notification`) joins once `GlucoseCard` lands (OPS issue c10).
 */
class LiveNotification(
    private val context: Context,
) {
    private val nm = context.getSystemService(NotificationManager::class.java)
    private val poster = Executors.newSingleThreadScheduledExecutor { Thread(it, "Sukrara.notify").apply { isDaemon = true } }
    private val gate =
        PostGate<LiveNotificationContent>(MIN_GAP_MS, SystemClock::elapsedRealtime) { delay, run ->
            poster.schedule(
                { runCatching(run).onFailure { Log.w(TAG, "post failed: ${it.javaClass.simpleName}") } },
                delay,
                TimeUnit.MILLISECONDS,
            )
        }

    /** The content for [state] now. */
    fun content(
        state: GlucoseState,
        now: Instant,
    ) = LiveNotificationContent.from(state, now, ::face)

    /** Posts [content] unless it's what is showing; at most one post per second. [force] = re-post (dismissed). */
    fun post(
        content: LiveNotificationContent,
        now: Instant,
        force: Boolean = false,
    ) {
        gate.submit(content, force) {
            runCatching { nm.notify(NotificationIds.GLUCOSE, build(content, now)) }
                .onFailure { Log.w(TAG, "notify failed: ${it.javaClass.simpleName}") }
        }
    }

    fun build(
        content: LiveNotificationContent,
        now: Instant,
    ): Notification {
        val title = resolve(content.title, now)
        val text = content.text?.let { resolve(it, now) }
        val builder =
            Notification
                .Builder(context, Channels.GLUCOSE)
                .setSmallIcon(smallIcon(content))
                .setContentTitle(if (content.strike) struck(title) else title)
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_STATUS)
                .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
                .setContentIntent(pending(content.tap))
                // Android 14+ lets the person swipe a foreground notification away: put it back (the receiver re-posts)
                .setDeleteIntent(dismissedIntent())
                .setShowWhen(content.readingAt != null)
                .setStyle(Notification.BigTextStyle().bigText(text))
        content.readingAt?.let { builder.setWhen(it.toEpochMilli()) }
        content.actions.forEach { builder.addAction(Notification.Action.Builder(null, resolve(it.label, now), pending(it.target)).build()) }
        val chip = content.chip
        if (chip != null && Build.VERSION.SDK_INT_FULL >= Build.VERSION_CODES_FULL.BAKLAVA_1 && promotionAllowed()) {
            builder.setRequestPromotedOngoing(true).setShortCriticalText(resolve(chip, now))
        }
        return builder.build()
    }

    /** Drops a post still waiting in the gate (the service is going away). */
    fun cancelPending() = gate.cancelPending()

    /** The trend arrow as the renderer draws it (its continuous angle), or the state's icon. */
    private fun smallIcon(content: LiveNotificationContent): android.graphics.drawable.Icon =
        if (content.icon == Icon.TREND) {
            android.graphics.drawable.Icon
                .createWithBitmap(ValueGlyphs.trendIcon(content.trendRate))
        } else {
            android.graphics.drawable.Icon
                .createWithResource(context, iconRes(content.icon))
        }

    /** Whether Android lets the app post Live Updates now (the person can turn them off per app). */
    fun promotionAllowed(): Boolean =
        Build.VERSION.SDK_INT_FULL >= Build.VERSION_CODES_FULL.BAKLAVA_1 &&
            runCatching { nm.canPostPromotedNotifications() }.getOrDefault(false)

    private fun resolve(
        text: UiText,
        now: Instant,
    ): String {
        val args = text.args.map { if (it is Instant) Freshness.timeText(it.epochSecond, now.epochSecond) else it }
        return context.getString(text.res, *args.toTypedArray())
    }

    private fun struck(text: String): CharSequence =
        SpannableString(text).apply { setSpan(StrikethroughSpan(), 0, text.indexOf(' ').takeIf { it > 0 } ?: text.length, 0) }

    private fun pending(target: Target): PendingIntent =
        when (target) {
            is Target.QuickTreatment -> {
                val quick =
                    Intent(NavTargets.ACTION_QUICK_TREATMENT)
                        .setPackage(context.packageName)
                        .putExtra(NavTargets.EXTRA_FOCUS_CARBS, target.focusCarbs)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                // until the quick treatment dialog is in this build, the tap opens the app
                val intent = if (hasQuickTreatment) quick else launchIntent()
                PendingIntent.getActivity(context, REQUEST_QUICK + if (target.focusCarbs) 1 else 0, intent, IMMUTABLE)
            }

            Target.OpenApp -> {
                PendingIntent.getActivity(context, REQUEST_OPEN, launchIntent(), IMMUTABLE)
            }

            Target.SensorSetup -> {
                val setup = Intent(NavTargets.ACTION_SENSOR_SETUP).setPackage(context.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                PendingIntent.getActivity(context, REQUEST_SENSOR, if (hasSensorSetup) setup else launchIntent(), IMMUTABLE)
            }
        }

    // the app's activities are fixed at build time: resolved once, not on every post
    private val hasQuickTreatment by lazy { resolves(NavTargets.ACTION_QUICK_TREATMENT) }
    private val hasSensorSetup by lazy { resolves(NavTargets.ACTION_SENSOR_SETUP) }

    private fun resolves(action: String) = Intent(action).setPackage(context.packageName).resolveActivity(context.packageManager) != null

    private fun launchIntent(): Intent =
        context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: Intent(Intent.ACTION_MAIN).setPackage(context.packageName)

    private fun dismissedIntent(): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            REQUEST_DISMISSED,
            Intent(context, HealthCheckReceiver::class.java).setAction(MonitorReceiver.ACTION_GLUCOSE_DISMISSED),
            IMMUTABLE,
        )

    companion object {
        private const val TAG = "Sukrara.notify"
        private const val MIN_GAP_MS = 1_000L
        private const val IMMUTABLE = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        private const val REQUEST_QUICK = 10
        private const val REQUEST_OPEN = 20
        private const val REQUEST_SENSOR = 30
        private const val REQUEST_DISMISSED = 40

        /** The card's face for a hub state: value, trend mark and freshness. */
        fun face(
            state: GlucoseState,
            now: Instant,
        ): GlucoseLook =
            GlucoseCard.face(
                state.latest?.toPoint(),
                now.epochSecond,
                sensorLook(state.sensor, now),
                CardSurface.NOTIFICATION_TEXT,
            )

        /**
         * The sensor states the face decides on (ended; warming up with its ready time where the number goes). The
         * badge's label and life ring aren't used by the face, so they stay empty here.
         */
        private fun sensorLook(
            sensor: SensorState,
            now: Instant,
        ): SensorBadge.Look? =
            when (sensor) {
                SensorState.Ended -> {
                    SensorBadge.Look(label = "", life = 0f, ended = true)
                }

                is SensorState.WarmingUp -> {
                    SensorBadge.Look(
                        label = "",
                        life = 0f,
                        ended = false,
                        warmup = Freshness.timeText(sensor.readyAt.epochSecond, now.epochSecond),
                        readyAt = sensor.readyAt.epochSecond,
                    )
                }

                else -> {
                    null
                }
            }

        /** A state's icon resource ([Icon.TREND] is drawn by the renderer instead). */
        fun iconRes(icon: Icon): Int =
            when (icon) {
                Icon.TREND, Icon.NO_TREND -> R.drawable.monitor_ic_no_trend
                Icon.NO_READINGS -> R.drawable.monitor_ic_no_readings
                Icon.SENSOR_ENDED -> R.drawable.monitor_ic_sensor_ended
                Icon.APP -> R.drawable.monitor_ic_app
            }
    }
}
