// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.status

import app.mauri.sukrara.core.Words
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Icon
import android.os.Build
import android.os.PowerManager
import android.util.Log
import android.widget.RemoteViews
import androidx.core.graphics.ColorUtils
import app.mauri.sukrara.R
import app.mauri.sukrara.core.Range
import app.mauri.sukrara.core.Reading
import app.mauri.sukrara.core.Trend
import app.mauri.sukrara.ui.QuickTreatmentActivity
import app.mauri.sukrara.graph.CardPart
import app.mauri.sukrara.graph.CardStyle
import app.mauri.sukrara.graph.CardSurface
import app.mauri.sukrara.graph.ValueGlyphs
import app.mauri.sukrara.graph.Freshness
import app.mauri.sukrara.graph.GlucoseLook
import app.mauri.sukrara.graph.TrendMark
import app.mauri.sukrara.graph.GlucoseCard
import app.mauri.sukrara.graph.GraphPlace
import app.mauri.sukrara.graph.GraphSettings
import app.mauri.sukrara.graph.appSlots
import app.mauri.sukrara.graph.systemPalette
import androidx.core.graphics.createBitmap
import app.mauri.sukrara.widgets.RecentHistory
import kotlin.math.abs
import kotlin.math.min

/**
 * The role service's one notification (investigation 05, approaches 1 + 2), so the status bar costs one post per
 * reading. Android 17 decides what it can look like (docs/10 "Live Update chip and card"):
 *  - chip on → a promoted ongoing **Live Update**. The chip is the small icon + the short critical text, and SystemUI
 *    removes that notification's icon from the icon area while its chip shows. So the small icon is only the trend
 *    arrow and the text only the value: the chip reads "↗ 138". Promotion forbids custom views and colorizing, and
 *    MetricStyle hides the large icon → the card is MetricStyle (big value coloured by range, trend, live age), no graph.
 *  - chip off (or Live Updates revoked / unavailable) → a colorized foreground-service card with our own layout: the
 *    card's header row as the renderer draws it on every card ([CardPart.HEADER]: big value, arrow or "no trend"
 *    ring, the late reading's time, the status pill "Eat carbs" / "45m left", the slots beside it) and the graph; the
 *    small icon is the number itself ([ValueGlyphs.icon], `icon`).
 *    Android 12+ puts custom content right of the icon column and clips it there (a −52 dp margin was cut off on
 *    the emulator), so the card can't draw under the icon: it fills its column instead (slots on the value's row, the
 *    graph at the column's full width, the follower line in the header).
 */
object GlucoseNotification {
    const val ID = 1
    const val CHANNEL = "glucose"
    private const val GRAPH_H_DP = 96f
    /** The header row: expanded, and collapsed (Android 12+ gives a collapsed custom view at most 48 dp). */
    private const val HEADER_DP = 64f
    private const val HEADER_COLLAPSED_DP = 48f

    fun createChannel(c: Context) {
        val ch = NotificationChannel(CHANNEL, c.getString(R.string.channel_glucose), NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) }
        c.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    /** Delayed (conflated) posts run here, off the main thread. */
    private val poster by lazy {
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor { Thread(it, "Sukrara.notify").apply { isDaemon = true } }
    }
    private val gate = PostGate(1_000, System::currentTimeMillis) { d, run ->
        poster.schedule({ runCatching(run).onFailure { Log.w("Sukrara.notify", "post: $it") } }, d, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    /**
     * The only way the roles post [ID] after `startForeground`: skipped when [key] (see [contentKey]) is what was
     * posted last, at most one post per second, a burst conflated into its latest content ([PostGate]).
     * [build] runs when the post happens, so a delayed post shows the newest state.
     */
    fun post(c: Context, key: Any?, force: Boolean = false, build: () -> Notification, onPosted: () -> Unit = {}) {
        val app = c.applicationContext
        gate.submit(key, force) {
            runCatching { app.getSystemService(NotificationManager::class.java).notify(ID, build()) }
                .onSuccess { onPosted() }.onFailure { Log.w("Sukrara.notify", "notify: $it") }
        }
    }

    /** Re-post the current notification unchanged (SystemUI rebuilds its icon list on a notification change). */
    fun repost(c: Context) {
        val nm = c.applicationContext.getSystemService(NotificationManager::class.java)
        val cur = runCatching { nm.activeNotifications.firstOrNull { it.id == ID }?.notification }.getOrNull() ?: return
        post(c, Any(), force = true, build = { cur })
    }

    /**
     * Everything [build] reads, so equal keys mean the same card: the inputs, the minute (age text, freshness steps,
     * sensor countdown), the sensor's life, the treatments, graph / alert settings, theme, locale, Live Update grant.
     */
    fun contentKey(c: Context, r: Reading?, chip: Boolean, icon: Boolean, detail: String): List<Any?> = listOf(
        r?.t, r?.sgv, r?.rate, chip, icon, detail, System.currentTimeMillis() / 60_000,
        app.mauri.sukrara.core.SensorLife.current.value,
        app.mauri.sukrara.treatments.TreatmentFeed.items.value.hashCode(),
        GraphSettings.value.hashCode(), app.mauri.sukrara.alerts.Alerts.settings.value.hashCode(),
        c.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK, java.util.Locale.getDefault(),
        chip && runCatching { c.getSystemService(NotificationManager::class.java).canPostPromotedNotifications() }.getOrDefault(false),
    )

    fun build(c: Context, r: Reading?, chip: Boolean, icon: Boolean, detail: String,
              now: Long = System.currentTimeMillis() / 1000): Notification {
        // a tap (card or Live Update chip) opens the quick treatment dialog over any app; it opens the app itself when
        // this phone can't add entries
        val open = PendingIntent.getActivity(c, 0, Intent(c, QuickTreatmentActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val b = Notification.Builder(c, CHANNEL)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .setCategory(Notification.CATEGORY_STATUS)
        // round sensor-life: an ended or warming-up sensor says so instead of "waiting" / "No reading since"
        val sensor = app.mauri.sukrara.graph.SensorBadge.look(app.mauri.sukrara.core.SensorLife.current.value, now, r?.t)
        // what shows where the value goes: the card's own decision (freshness steps, warm-up, ended), as on every surface
        val look = GlucoseCard.face(r, now, sensor, CardSurface.NOTIFICATION_TEXT)
        val fresh = look.fresh
        // ended: as soon as it has ended (not an error, and no value to show any more); warming: while no value shows
        sensor?.takeIf { look.state == GlucoseLook.State.ENDED }?.let {
            // "Sensor ended" + the "Replace sensor" action (the card's pill) for the phone that wears it
            b.setSmallIcon(R.drawable.ic_stat_sensor_ended).setContentTitle(c.getString(R.string.card_sensor_ended))
            if (detail.isNotEmpty()) b.setContentText(detail)
            if (app.mauri.sukrara.App.role(c) == "owner") {
                val scan = app.mauri.sukrara.ui.MainActivity.sensorPendingIntent(c)
                b.setContentIntent(scan).addAction(Notification.Action.Builder(null, c.getString(R.string.badge_replace_sensor), scan).build())
            }
            return b.build()
        }
        sensor?.takeIf { look.state == GlucoseLook.State.WARMING_UP }?.let { sl ->
            return b.setSmallIcon(R.drawable.ic_stat_sukrara)
                .setContentTitle(if (sl.waiting(now)) c.getString(R.string.card_almost_ready)
                    else c.getString(R.string.card_ready_at, Freshness.timeText(sl.readyAt, now)))
                .setContentText(detail).build()
        }
        if (r == null || look.state == GlucoseLook.State.NONE) {
            return b.setSmallIcon(R.drawable.ic_stat_sukrara).setContentTitle(c.getString(R.string.notif_waiting_for_glucose))
                .setContentText(detail).build()
        }
        // Investigation 16: no rate and no "time ago"; the reading's time only once it's late (Freshness).
        // From 20 min without a reading: no value, the "no readings" icon (status bar) and since when.
        if (look.state == GlucoseLook.State.NO_READINGS) {
            return b.setSmallIcon(smallIcon(look)).setContentTitle(c.getString(R.string.notif_no_reading_since, Freshness.timeText(r.t, now)))
                .setContentText(detail).build()
        }
        val stale = look.strike
        val ageText = if (fresh.showTime) c.getString(R.string.notif_reading_at, Freshness.timeText(r.t, now)) else ""
        val line = listOf(ageText, detail).filter { it.isNotEmpty() }.joinToString(" · ")
        b.setContentTitle(title(look, r.rate))
            .setContentText(line)
            .setWhen(r.t * 1000)
            .setShowWhen(true)
        // Promoted ongoing is missing on some API 36 images (emulator: NoSuchMethodError) and the user can revoke it.
        val live = chip && runCatching { c.getSystemService(NotificationManager::class.java).canPostPromotedNotifications() }
            .getOrDefault(false) && runCatching {
                b.setRequestPromotedOngoing(true).setShortCriticalText(look.plain)
            }.isSuccess
        if (live) {
            b.setSmallIcon(Icon.createWithBitmap(ValueGlyphs.trendIcon(if (look.trend == TrendMark.ARROW) r.rate else null)))
            if (Build.VERSION.SDK_INT >= 37) {
                // The expanded promoted card uses the title as its header line (no app name), so it's not the value.
                b.setContentTitle(Trend.word(r.rate).replaceFirstChar { it.uppercase() } + " · " + detail)
                b.setStyle(Notification.MetricStyle()
                    // Labels are a third of the card width ("Glucose (mg/dL)" got ellipsized on the phone): the unit
                    // goes in the label, the values stay short. The reading's time only once it's late.
                    .addMetric(if (stale || r.sgv !in 40..400) Notification.Metric(Notification.Metric.FixedText(look.plain!!), "mg/dL")
                        else Notification.Metric(Notification.Metric.FixedInt(r.sgv), "mg/dL", semantic(r.sgv)))
                    .apply {
                        // The slots beside the value, like the home widgets (the trend is already the chip's arrow and
                        // the title); three metrics in all, the reading's time taking the last one once it's late.
                        val items = runCatching {
                            appSlots(c, RecentHistory.readings(c), app.mauri.sukrara.treatments.TreatmentFeed.items.value, now,
                                if (fresh.showTime) 1 else 2)
                        }.getOrDefault(emptyList())
                        for (item in items) app.mauri.sukrara.graph.Slots.text(item, now)?.let { (v, label) ->
                            addMetric(Notification.Metric(Notification.Metric.FixedText(v), label))
                        }
                        if (fresh.showTime) addMetric(Notification.Metric(Notification.Metric.FixedText(
                            Freshness.timeText(r.t, now)), c.getString(R.string.notif_metric_reading)))
                    }
                    .setCriticalMetric(0))
            } else {
                b.setStyle(Notification.BigTextStyle().bigText(line))
            }
        } else {
            // Colorized foreground service: keeps the number icon first and shown although the channel is LOW (docs/05).
            // struck through (5–20 min): grey instead of the range colour
            val bg = if (stale) STALE_BG else Range.color(r.sgv) ?: 0xFF1BB5C9.toInt()
            val art = art(c, r, ink(bg), now)
            b.setSmallIcon(if (icon) Icon.createWithBitmap(ValueGlyphs.icon(look, r.rate)) else Icon.createWithResource(c, smallIcon(look)))
                .setColorized(true).setColor(bg)
                .setShowWhen(false)   // the age is in the card; the header keeps only the icon and app name (system-drawn)
                // expanded: the follower line goes in the header beside "Sukrara" instead of a row of its own
                .setSubText(detail.ifEmpty { null })
                .setStyle(Notification.DecoratedCustomViewStyle())
                .setCustomContentView(card(c, R.layout.notif_glucose_collapsed, look, art?.collapsed))
                .setCustomBigContentView(card(c, R.layout.notif_glucose_expanded, look, art?.header).apply {
                    art?.graph?.let { setImageViewBitmap(R.id.graph, it) }
                })
        }
        return b.build()
    }

    /** Black or white, whichever reads better on the colorized background (the system does the same for the header). */
    private fun ink(bg: Int) =
        if (ColorUtils.calculateContrast(Color.BLACK, bg or 0xFF000000.toInt()) >= ColorUtils.calculateContrast(Color.WHITE, bg or 0xFF000000.toInt()))
            Color.BLACK else Color.WHITE

    /** The custom card: the [header] row as the renderer drew it (the expanded one gets the graph below it too). */
    private fun card(c: Context, layout: Int, look: GlucoseLook, header: Bitmap?): RemoteViews =
        RemoteViews(c.packageName, layout).apply {
            if (header != null) setImageViewBitmap(R.id.header, header)
            val value = look.text!!
            setContentDescription(R.id.header, if (look.strike) c.getString(R.string.notif_value_old, value) else value)
        }

    // ============================================================================================ art (chip off)

    /** The custom card's pictures: the graph panel, and the header row expanded and collapsed. */
    internal class Art(val graph: Bitmap?, val header: Bitmap?, val collapsed: Bitmap?)

    private var graphCache: Bitmap? = null
    private var graphKey = ""
    private var headerCache: Art? = null
    private var headerKey = ""

    /**
     * The graph ([GlucoseCard], investigation 16; window = the notification's setting) over [RecentHistory] (the
     * role's own store) on a rounded surface, and the header row ([paintArt]). The graph is re-drawn only for a new
     * reading / theme / 5-min tick / settings, the header also every minute (the status pill's "45m left") and for a
     * mute; neither while the screen is off (the screen-on re-post brings fresh ones). Drawn at ≤ 2× density at the
     * content column's width (the layout scales them uniformly if the guess is off). [ink] = black or white, whichever
     * reads on the card's colour.
     */
    @Synchronized
    private fun art(c: Context, r: Reading, ink: Int, now: Long): Art? = runCatching {
        val night = c.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        val treatments = app.mauri.sukrara.treatments.TreatmentFeed.items.value
        val common = "${r.t}|$night|${java.util.Locale.getDefault()}|${treatments.hashCode()}|${GraphSettings.value.hashCode()}|" +
            "${app.mauri.sukrara.alerts.Alerts.settings.value.hashCode()}"
        val gKey = "$common|${now / 300}"
        val hKey = "$common|${now / 60}|$ink|${app.mauri.sukrara.alerts.Alerts.paused.value.hashCode()}"
        val interactive = c.getSystemService(PowerManager::class.java).isInteractive
        val oldH = headerCache
        if (oldH != null && graphCache != null && (gKey == graphKey && hKey == headerKey || !interactive)) return@runCatching oldH
        val dm = c.resources.displayMetrics
        val gc = if (dm.density <= 2f) c else c.createConfigurationContext(Configuration(c.resources.configuration).apply { densityDpi = 320 })
        val dark = night == Configuration.UI_MODE_NIGHT_YES
        val surface = c.getColor(if (dark) android.R.color.system_surface_container_high_dark
            else android.R.color.system_surface_container_high_light)
        val history = RecentHistory.readings(c).let { h0 ->
            if (h0.lastOrNull()?.let { r.t - it.t < 30 } == true) h0 else h0 + r
        }
        // The content column: the shade's 16 dp sides, the icon column (72 dp in Android 17's notification templates,
        // 52 dp before) and the 16 dp end margin.
        val wDp = min(dm.widthPixels, dm.heightPixels) / dm.density - 32f - (if (Build.VERSION.SDK_INT >= 37) 72f else 52f) - 16f
        val redrawGraph = gKey != graphKey || graphCache == null
        paintArt(gc, dark, surface, c.getColor(android.R.color.system_surface_container_high_light), history, treatments, now, wDp,
            ink, graph = redrawGraph)
            .let { Art(if (redrawGraph) it.graph else graphCache, it.header, it.collapsed) }
            .also { graphCache = it.graph; graphKey = gKey; headerCache = it; headerKey = hKey }   // old ones may still be parcelled: the GC takes them
    }.onFailure { Log.w("Sukrara.notif", "art: $it") }.getOrNull()

    /**
     * The custom card's card parts (the notification's one drawing entry, also the tests'), one whole-card style for
     * all: the graph ([CardPart.GRAPH], window = the notification's setting) on a rounded [surface] of the theme, and the
     * header row ([CardPart.HEADER], expanded [HEADER_DP] and collapsed [HEADER_COLLAPSED_DP] tall): the same value,
     * trend, late time, status pill and slots as every other card, drawn on the card's colour. On colour the header looks
     * the same in light and dark (the card's colour doesn't follow the theme either): the value in [ink], the slots on
     * discs of the light [discs] surface with the light palette (2026-10-21: in dark theme the dark discs looked heavy on
     * the bright card). [wDp] = the content column's width. [metricsOf] null = the slots from the stored history.
     */
    internal fun paintArt(gc: Context, dark: Boolean, surface: Int, discs: Int, history: List<Reading>,
                          treatments: List<app.mauri.sukrara.treatments.Treatment>, now: Long, wDp: Float, ink: Int,
                          metricsOf: ((app.mauri.sukrara.graph.RingSpec, app.mauri.sukrara.graph.Bands) -> app.mauri.sukrara.graph.GlucoseMetrics)? = null,
                          graph: Boolean = true): Art {
        val d = gc.resources.displayMetrics.density
        val cfg = GraphSettings.value
        val card = CardStyle(graph = cfg.graphStyle(cfg.hours(GraphPlace.NOTIFICATION)),
            rings = cfg.rings(), last = cfg.lastPlace, lastElse = cfg.lastElseRing(),
            sensorTime = cfg.sensorTime(GraphPlace.NOTIFICATION), slotDisc = discs, valueInk = ink)
        val metrics = metricsOf ?: { s, b -> app.mauri.sukrara.graph.GraphData.metrics(gc, s.windowS, b, now) }
        val chart = if (!graph) null else {
            val w = (wDp * d).toInt()
            val h = (GRAPH_H_DP * d).toInt()
            val pad = (6 * d).toInt()
            val plot = createBitmap(w - 2 * pad, h - 2 * pad)
            GlucoseCard.draw(Canvas(plot), plot.width.toFloat(), plot.height.toFloat(), history, treatments, now, card,
                systemPalette(gc, dark), d, CardSurface.NOTIFICATION, CardPart.GRAPH, metricsOf = metrics)
            createBitmap(w, h).also { out ->
                Canvas(out).apply {
                    drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), 12 * d, 12 * d, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = surface })
                    drawBitmap(plot, pad.toFloat(), pad.toFloat(), null)
                }
                plot.recycle()
            }
        }
        fun header(hDp: Float) = createBitmap((wDp * d).toInt(), (hDp * d).toInt()).also {
            GlucoseCard.draw(Canvas(it), it.width.toFloat(), it.height.toFloat(), history, treatments, now, card,
                systemPalette(gc, false), d, CardSurface.NOTIFICATION, CardPart.HEADER, metricsOf = metrics)
        }
        return Art(chart, header(HEADER_DP), header(HEADER_COLLAPSED_DP))
    }

    private fun semantic(mgdl: Int) = when {
        Range.color(mgdl) == null -> Notification.SEMANTIC_STYLE_SAFE
        mgdl < Range.LOW -> Notification.SEMANTIC_STYLE_DANGER
        else -> Notification.SEMANTIC_STYLE_CAUTION
    }

    /** Struck-through values (5–20 min) colour the card grey instead of their range. */
    private const val STALE_BG = 0xFF8E9099.toInt()

    /**
     * The notification's (and the Live Update chip's) look for a [look] ([GlucoseCard.face]): its small icon resource
     * when it isn't the number ([ValueGlyphs.icon]) or the arrow ([ValueGlyphs.trendIcon]): the "no readings" icon (sensors_off) from 20
     * min, the flag once the sensor has ended, else Sukrara's.
     */
    fun smallIcon(look: GlucoseLook): Int = when (look.state) {
        GlucoseLook.State.NO_READINGS -> R.drawable.ic_stat_no_readings
        GlucoseLook.State.ENDED -> R.drawable.ic_stat_sensor_ended
        else -> R.drawable.ic_stat_sukrara
    }

    /** The title for a value: `123 mg/dL ↗`, struck through (a span) with the "no trend" ring from 5 min. */
    fun title(look: GlucoseLook, rate: Double?): CharSequence {
        val mark = if (look.trend == TrendMark.ARROW) Trend.arrow(rate) else TrendMark.RING
        val s = android.text.SpannableString("${look.text} mg/dL $mark")
        if (look.strike) s.setSpan(android.text.style.StrikethroughSpan(), 0, look.text!!.length, 0)
        return s
    }
}
