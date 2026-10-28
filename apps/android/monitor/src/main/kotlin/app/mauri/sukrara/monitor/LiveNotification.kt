// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.monitor

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.text.SpannableString
import android.text.style.StrikethroughSpan
import android.util.Log
import android.widget.RemoteViews
import androidx.annotation.RequiresApi
import app.mauri.sukrara.core.model.GlucoseState
import app.mauri.sukrara.core.model.NavTargets
import app.mauri.sukrara.core.model.SensorState
import app.mauri.sukrara.graph.CardSurface
import app.mauri.sukrara.graph.CardWords
import app.mauri.sukrara.graph.Freshness
import app.mauri.sukrara.graph.GlucoseCard
import app.mauri.sukrara.graph.GlucoseLook
import app.mauri.sukrara.graph.GraphConfig
import app.mauri.sukrara.graph.SensorBadge
import app.mauri.sukrara.graph.ValueGlyphs
import app.mauri.sukrara.graph.cardModelOf
import app.mauri.sukrara.graph.notificationCardStyle
import app.mauri.sukrara.graph.stats.Bands
import app.mauri.sukrara.graph.stats.toPoint
import app.mauri.sukrara.monitor.notify.CardFrame
import app.mauri.sukrara.monitor.notify.Channels
import app.mauri.sukrara.monitor.notify.LiveNotificationContent
import app.mauri.sukrara.monitor.notify.LiveNotificationContent.Icon
import app.mauri.sukrara.monitor.notify.LiveNotificationContent.Layout
import app.mauri.sukrara.monitor.notify.LiveNotificationContent.Target
import app.mauri.sukrara.monitor.notify.NotificationArt
import app.mauri.sukrara.monitor.notify.NotificationCard
import app.mauri.sukrara.monitor.notify.NotificationIds
import app.mauri.sukrara.monitor.notify.NotificationPrefs
import app.mauri.sukrara.monitor.notify.PostGate
import app.mauri.sukrara.monitor.notify.SharingLine
import app.mauri.sukrara.monitor.notify.UiText
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The glucose notification (surface `notification-text`: title, Live Update chip, MetricStyle value and small icon;
 * its custom card is surface `notification`, [NotificationCard]), which is also the monitoring service's foreground
 * notification, so the status bar costs one post per reading. Android decides what it can look like:
 * - **chip on** and Live Updates allowed → a promoted ongoing Live Update: the chip is the small icon (the trend
 *   arrow) + `setShortCriticalText` ("138"). Promotion forbids custom views and colorizing; on Android 17 the expanded
 *   card is a `MetricStyle` (the value styled by range, the late reading's time), below it BigText.
 * - **chip off** (or Live Updates refused) → a colorized foreground-service card in the range colour (grey once
 *   struck through) with the renderer's header row collapsed and header + chart expanded; the small icon is the
 *   number itself (*Number icon*) or the arrow. Without its pictures (the first post, the screen off with an
 *   out-of-date card, a drawing failure) it posts the same colorized notification as text, never an old picture.
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

    /** The content for [state] now with the status-bar [prefs] (the chip's permission is read here). */
    fun content(
        state: GlucoseState,
        now: Instant,
        prefs: NotificationPrefs = NotificationPrefs(),
    ) = LiveNotificationContent.from(state, now, ::face, prefs.copy(promotionAllowed = promotionAllowed()))

    /**
     * Posts [content] unless it's what is showing; at most one post per second. [force] = re-post (dismissed, screen
     * on). [frame] = what the custom card draws (null: the card posts as text).
     */
    fun post(
        content: LiveNotificationContent,
        now: Instant,
        frame: CardFrame? = null,
        force: Boolean = false,
    ) {
        gate.submit(content, force) {
            // drawn and posted off the caller's (main) thread; the gate keeps posts in order
            poster.execute {
                runCatching { nm.notify(NotificationIds.GLUCOSE, build(content, now, frame)) }
                    .onFailure { Log.w(TAG, "notify failed: ${it.javaClass.simpleName}") }
            }
        }
    }

    /** The notification for [content]; never throws for the card: a picture that fails falls back to text. */
    fun build(
        content: LiveNotificationContent,
        now: Instant,
        frame: CardFrame? = null,
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
        content.sharing?.let { builder.setSubText(sharingText(it)) }
        content.actions.forEach { builder.addAction(Notification.Action.Builder(null, resolve(it.label, now), pending(it.target)).build()) }
        when (content.layout) {
            Layout.LIVE_UPDATE -> liveUpdate(builder, content, now, frame)
            Layout.CARD -> card(builder, content, now, frame)
            Layout.TEXT -> Unit
        }
        return builder.build()
    }

    /**
     * The chip; on Android 17 the expanded card is a MetricStyle (the value by range, the late reading's time, then the
     * card's slots as words).
     */
    private fun liveUpdate(
        b: Notification.Builder,
        content: LiveNotificationContent,
        now: Instant,
        frame: CardFrame?,
    ) {
        val chip = content.chip ?: return
        if (Build.VERSION.SDK_INT_FULL < Build.VERSION_CODES_FULL.BAKLAVA_1) return
        // promoted ongoing is missing on some 36 images (NoSuchMethodError): then it stays a plain notification
        runCatching { b.setRequestPromotedOngoing(true).setShortCriticalText(resolve(chip, now)) }.onFailure { return }
        val look = content.look ?: return
        if (Build.VERSION.SDK_INT >= METRIC_STYLE_SDK) {
            runCatching { b.setStyle(metricStyle(content, look, now, frame)) }
                .onFailure { Log.w(TAG, "metric style: ${it.javaClass.simpleName}") }
        }
    }

    /**
     * MetricStyle: the value as the face writes it (LO / HI included; never a number of its own), styled by its range
     * while fresh and plain once struck through; the reading's time as a second metric once it's late; the rest of the
     * [MAX_METRICS] are the card's slots as the notification card shows them (`GlucoseCard.slotTexts`: time in range,
     * average, the last treatment, over the same windows as home), only while the value is a reading.
     */
    @RequiresApi(METRIC_STYLE_SDK)
    private fun metricStyle(
        content: LiveNotificationContent,
        look: GlucoseLook,
        now: Instant,
        frame: CardFrame?,
    ): Notification.MetricStyle {
        val bands = frame?.config?.bands ?: Bands()
        val unit = context.getString(R.string.monitor_metric_unit)
        val value =
            if (look.strike || content.mgdl == null) {
                Notification.Metric(Notification.Metric.FixedText(look.text!!), unit)
            } else {
                Notification.Metric(Notification.Metric.FixedText(look.text!!), unit, semantic(content.mgdl, bands))
            }
        val style = Notification.MetricStyle().addMetric(value)
        content.readingAt?.let {
            style.addMetric(
                Notification.Metric(
                    Notification.Metric.FixedText(Freshness.timeText(it.epochSecond, now.epochSecond)),
                    context.getString(R.string.monitor_metric_reading),
                ),
            )
        }
        val room = MAX_METRICS - style.metrics.size
        if (frame != null && content.mgdl != null && room > 0) {
            val model = cardModelOf(frame.state, frame.treatments, now, CardWords.of(context.resources), frame.slots(now))
            GlucoseCard.slotTexts(model, notificationCardStyle(frame.config), room).forEach { (value, label) ->
                style.addMetric(Notification.Metric(Notification.Metric.FixedText(value), label))
            }
        }
        return style.setCriticalMetric(0)
    }

    /** The value's style by the person's ranges: danger below the low band, caution above the high one. */
    @RequiresApi(METRIC_STYLE_SDK)
    private fun semantic(
        mgdl: Int,
        bands: Bands,
    ): Int =
        when {
            mgdl < bands.low -> Notification.SEMANTIC_STYLE_DANGER
            mgdl > bands.high -> Notification.SEMANTIC_STYLE_CAUTION
            else -> Notification.SEMANTIC_STYLE_SAFE
        }

    /**
     * The colorized card: the range colour (keeps the icon first and shown although the channel is LOW) and, when
     * [frame] is drawn, the renderer's header row and chart as custom views.
     */
    private fun card(
        b: Notification.Builder,
        content: LiveNotificationContent,
        now: Instant,
        frame: CardFrame?,
    ) {
        val look = content.look ?: return
        val mgdl = content.mgdl ?: return
        val config = frame?.config ?: GraphConfig()
        val bg = NotificationCard.background(look, mgdl, config)
        b.setColorized(true).setColor(bg)
        val art = frame?.let { art(it, content, bg, now) } ?: return
        val description = resolve(content.title, now)
        b
            .setShowWhen(false) // the late time is in the card
            .setStyle(Notification.DecoratedCustomViewStyle())
            .setCustomContentView(views(R.layout.monitor_notif_card_collapsed, art.collapsed, null, description))
            .setCustomBigContentView(views(R.layout.monitor_notif_card_expanded, art.header, art.graph, description))
    }

    private fun views(
        layout: Int,
        header: Bitmap,
        graph: Bitmap?,
        description: String,
    ) = RemoteViews(context.packageName, layout).apply {
        setImageViewBitmap(R.id.monitor_notif_header, header)
        setContentDescription(R.id.monitor_notif_header, description)
        if (graph != null) setImageViewBitmap(R.id.monitor_notif_graph, graph)
    }

    private var artCache: NotificationArt? = null
    private var artKey: Any? = null
    private var graphKey: Any? = null

    /**
     * The card's pictures, redrawn only when what they show changed (the content's card key: reading, minute,
     * treatments, settings, theme), the chart at most every 5 minutes besides a new reading. While the screen is off
     * nothing is drawn: an up-to-date picture is reused, an out-of-date one is dropped (the text shows instead) and the
     * screen-on re-post draws it. A failure is logged and posts text.
     */
    @Synchronized
    private fun art(
        frame: CardFrame,
        content: LiveNotificationContent,
        bg: Int,
        now: Instant,
    ): NotificationArt? {
        val key =
            listOf(content.cardKey, content.look, bg, frame.config, frame.dark, frame.state.latest, frame.state.sensor, frame.stored.size)
        val gKey = listOf(frame.config, frame.dark, frame.state.latest, frame.treatments, now.epochSecond / GRAPH_EVERY_S)
        val cached = artCache
        if (cached != null && key == artKey && gKey == graphKey) return cached
        if (!context.getSystemService(PowerManager::class.java).isInteractive) return null
        return runCatching {
            val model = cardModelOf(frame.state, frame.treatments, now, CardWords.of(context.resources), frame.slots(now))
            val redrawGraph = cached?.graph == null || gKey != graphKey
            val drawn =
                NotificationCard.renderNotificationArt(
                    context,
                    model,
                    frame.config,
                    bg,
                    frame.dark,
                    NotificationCard.columnDp(context),
                    redrawGraph,
                )
            NotificationArt(drawn.header, drawn.collapsed, if (redrawGraph) drawn.graph else cached?.graph)
        }.onSuccess {
            artCache = it
            artKey = key
            graphKey = gKey
        }.onFailure { Log.w(TAG, "card: ${it.javaClass.simpleName}") }
            .getOrNull()
    }

    /** Drops a post still waiting in the gate (the service is going away). */
    fun cancelPending() = gate.cancelPending()

    /** The trend arrow as the renderer draws it (its continuous angle), the number itself, or the state's icon. */
    private fun smallIcon(content: LiveNotificationContent): android.graphics.drawable.Icon =
        when {
            content.icon == Icon.TREND -> {
                android.graphics.drawable.Icon
                    .createWithBitmap(ValueGlyphs.trendIcon(content.trendRate))
            }

            content.icon == Icon.NUMBER && content.look != null -> {
                android.graphics.drawable.Icon
                    .createWithBitmap(ValueGlyphs.icon(content.look))
            }

            else -> {
                android.graphics.drawable.Icon
                    .createWithResource(context, iconRes(content.icon))
            }
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

    /** "2 followers · 1 nearby" (nearby only while a follower device is connected over Bluetooth). */
    private fun sharingText(line: SharingLine): String {
        val r = context.resources
        val followers = r.getQuantityString(R.plurals.monitor_sub_followers, line.followers, line.followers)
        return if (line.nearby > 0) {
            followers + " · " + r.getQuantityString(R.plurals.monitor_sub_nearby, line.nearby, line.nearby)
        } else {
            followers
        }
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
                // the app's own activity with the flow's action: no exported filter for it (any app could open it)
                val setup = launchIntent().setAction(NavTargets.ACTION_SENSOR_SETUP).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                PendingIntent.getActivity(context, REQUEST_SENSOR, setup, IMMUTABLE)
            }
        }

    // the app's activities are fixed at build time: resolved once, not on every post
    private val hasQuickTreatment by lazy { resolves(NavTargets.ACTION_QUICK_TREATMENT) }

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

        /** Android 17: `Notification.MetricStyle`. */
        private const val METRIC_STYLE_SDK = 37

        /** MetricStyle shows up to three metrics. */
        private const val MAX_METRICS = 3

        /** The chart is redrawn at most this often without a new reading. */
        private const val GRAPH_EVERY_S = 300L

        /** The card's face for a hub state: value, trend mark and freshness. */
        fun face(
            state: GlucoseState,
            now: Instant,
        ): GlucoseLook = GlucoseCard.face(state, now.epochSecond, CardSurface.NOTIFICATION_TEXT)

        /** A state's icon resource ([Icon.TREND] is drawn by the renderer instead). */
        fun iconRes(icon: Icon): Int =
            when (icon) {
                Icon.TREND, Icon.NO_TREND, Icon.NUMBER -> R.drawable.monitor_ic_no_trend
                Icon.NO_READINGS -> R.drawable.monitor_ic_no_readings
                Icon.SENSOR_ENDED -> R.drawable.monitor_ic_sensor_ended
                Icon.APP -> R.drawable.monitor_ic_app
            }
    }
}
