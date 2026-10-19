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
import app.mauri.sukrara.graph.CardStyle
import app.mauri.sukrara.graph.Freshness
import app.mauri.sukrara.graph.GlucoseCard
import app.mauri.sukrara.graph.GraphPlace
import app.mauri.sukrara.graph.GraphSettings
import app.mauri.sukrara.graph.Slots
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
 *  - chip off (or Live Updates revoked / unavailable) → a colorized foreground-service card with our own layout:
 *    big value, arrow, the slots beside the value (the home widgets' time-in-range rings / report numbers / last
 *    treatment, [GraphConfig.slots]) and the graph; the small icon is the number itself ([iconBitmap], `icon`).
 *    Android 12+ puts custom content right of the icon column and clips it there (a −52 dp margin was cut off on
 *    the emulator), so the card can't draw under the icon: it fills its column instead (slots on the value's row, the
 *    graph at the column's full width, the follower line in the header).
 */
object GlucoseNotification {
    const val ID = 1
    const val CHANNEL = "glucose"
    private const val GRAPH_H_DP = 96f
    /** Slot discs: the expanded row's height; the collapsed row (48 dp) scales them down. */
    private const val SLOT_DP = 52f

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

    fun build(c: Context, r: Reading?, chip: Boolean, icon: Boolean, detail: String): Notification {
        // a tap (card or Live Update chip) opens the quick treatment dialog over any app; it opens the app itself when
        // this phone can't add entries
        val open = PendingIntent.getActivity(c, 0, Intent(c, QuickTreatmentActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val b = Notification.Builder(c, CHANNEL)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .setCategory(Notification.CATEGORY_STATUS)
        val now = System.currentTimeMillis() / 1000
        val fresh = r?.let { Freshness.of(now - it.t) }
        // round sensor-life: an ended or warming-up sensor says so instead of "waiting" / "No reading since"
        val sensor = app.mauri.sukrara.graph.SensorBadge.look(app.mauri.sukrara.core.SensorLife.current.value, now, r?.t)
        // ended: as soon as it has ended (not an error, and no value to show any more); warming: while no value shows
        sensor?.takeIf { it.ended }?.let {
            // "Sensor ended" + the "Replace sensor" action (the card's pill) for the phone that wears it
            b.setSmallIcon(R.drawable.ic_stat_sensor_ended).setContentTitle(c.getString(R.string.card_sensor_ended))
            if (detail.isNotEmpty()) b.setContentText(detail)
            if (app.mauri.sukrara.App.role(c) == "owner") {
                val scan = app.mauri.sukrara.ui.MainActivity.sensorPendingIntent(c)
                b.setContentIntent(scan).addAction(Notification.Action.Builder(null, c.getString(R.string.badge_replace_sensor), scan).build())
            }
            return b.build()
        }
        if (r == null || fresh?.showValue != true) sensor?.takeIf { it.warmup != null }?.let { sl ->
            return b.setSmallIcon(R.drawable.ic_stat_sukrara)
                .setContentTitle(if (sl.waiting(now)) c.getString(R.string.card_almost_ready)
                    else c.getString(R.string.card_ready_at, Freshness.timeText(sl.readyAt, now)))
                .setContentText(detail).build()
        }
        if (r == null || fresh == null) {
            return b.setSmallIcon(R.drawable.ic_stat_sukrara).setContentTitle(c.getString(R.string.notif_waiting_for_glucose))
                .setContentText(detail).build()
        }
        val stale = fresh.strike || !fresh.showValue
        // Investigation 16: no rate and no "time ago"; the reading's time only once it's late (Freshness).
        if (!fresh.showValue) {
            return b.setSmallIcon(R.drawable.ic_stat_sukrara).setContentTitle(c.getString(R.string.notif_no_reading_since, Freshness.timeText(r.t, now)))
                .setContentText(detail).build()
        }
        val ageText = if (fresh.showTime) c.getString(R.string.notif_reading_at, Freshness.timeText(r.t, now)) else ""
        val line = listOf(ageText, detail).filter { it.isNotEmpty() }.joinToString(" · ")
        b.setContentTitle("${r.sgv} mg/dL ${Trend.arrow(r.rate)}")
            .setContentText(line)
            .setWhen(r.t * 1000)
            .setShowWhen(true)
        // Promoted ongoing is missing on some API 36 images (emulator: NoSuchMethodError) and the user can revoke it.
        val live = chip && runCatching { c.getSystemService(NotificationManager::class.java).canPostPromotedNotifications() }
            .getOrDefault(false) && runCatching {
                b.setRequestPromotedOngoing(true).setShortCriticalText(if (stale) "${r.sgv}?" else "${r.sgv}")
            }.isSuccess
        if (live) {
            b.setSmallIcon(Icon.createWithBitmap(arrowIcon(r.rate, stale)))
            if (Build.VERSION.SDK_INT >= 37) {
                // The expanded promoted card uses the title as its header line (no app name), so it's not the value.
                b.setContentTitle(Trend.word(r.rate).replaceFirstChar { it.uppercase() } + " · " + detail)
                b.setStyle(Notification.MetricStyle()
                    // Labels are a third of the card width ("Glucose (mg/dL)" got ellipsized on the phone): the unit
                    // goes in the label, the values stay short. The reading's time only once it's late.
                    .addMetric(Notification.Metric(Notification.Metric.FixedInt(r.sgv), "mg/dL", semantic(r.sgv)))
                    .apply {
                        // The slots beside the value, like the home widgets (the trend is already the chip's arrow and
                        // the title); three metrics in all, the reading's time taking the last one once it's late.
                        val items = runCatching {
                            appSlots(c, RecentHistory.readings(c), app.mauri.sukrara.treatments.TreatmentFeed.items.value, now,
                                if (fresh.showTime) 1 else 2)
                        }.getOrDefault(emptyList())
                        for (item in items) metric(item, now)?.let { (v, label) ->
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
            val bg = Range.color(r.sgv) ?: 0xFF1BB5C9.toInt()
            val art = art(c, r)
            b.setSmallIcon(if (icon) Icon.createWithBitmap(iconBitmap(r)) else Icon.createWithResource(c, R.drawable.ic_stat_sukrara))
                .setColorized(true).setColor(bg)
                .setShowWhen(false)   // the age is in the card; the header keeps only the icon and app name (system-drawn)
                // expanded: the follower line goes in the header beside "Sukrara" instead of a row of its own
                .setSubText(detail.ifEmpty { null })
                .setStyle(Notification.DecoratedCustomViewStyle())
                .setCustomContentView(card(c, R.layout.notif_glucose_collapsed, r, stale, bg, ageText, art?.slots))
                .setCustomBigContentView(card(c, R.layout.notif_glucose_expanded, r, stale, bg, ageText, art?.slots).apply {
                    art?.graph?.let { setImageViewBitmap(R.id.graph, it) }
                })
        }
        return b.build()
    }

    /** Black or white, whichever reads better on the colorized background (the system does the same for the header). */
    private fun ink(bg: Int) =
        if (ColorUtils.calculateContrast(Color.BLACK, bg or 0xFF000000.toInt()) >= ColorUtils.calculateContrast(Color.WHITE, bg or 0xFF000000.toInt()))
            Color.BLACK else Color.WHITE

    private fun card(c: Context, layout: Int, r: Reading, stale: Boolean, bg: Int, info: String, slots: Bitmap?): RemoteViews {
        val fg = ink(bg)
        return RemoteViews(c.packageName, layout).apply {
            val value = if (stale) "${r.sgv}?" else "${r.sgv}"
            val sp = if (layout == R.layout.notif_glucose_expanded) 44f else 30f
            setImageViewBitmap(R.id.value, valueBitmap(value, fg,
                android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, sp, c.resources.displayMetrics)))
            setContentDescription(R.id.value, value)
            setImageViewBitmap(R.id.arrow, arrowBitmap(r.rate, stale, fg, (34 * c.resources.displayMetrics.density).toInt()))
            setTextViewText(R.id.info, info)
            setTextColor(R.id.info, fg)
            if (slots != null) setImageViewBitmap(R.id.slots, slots)
            setViewVisibility(R.id.slots, if (slots != null) android.view.View.VISIBLE else android.view.View.GONE)
        }
    }

    // ============================================================================================ art (chip off)

    /** The custom card's pictures: the graph panel and the slot discs (null = no slots to show). */
    private class Art(val graph: Bitmap?, val slots: Bitmap?)

    private var artCache: Art? = null
    private var artKey = ""

    /**
     * The graph ([GlucoseCard], investigation 16; window = the notification's setting) over [RecentHistory] (the
     * role's own store) on a rounded surface, and the slots ([appSlots], like the home widgets) as discs of the same
     * surface, so both read on any range colour. Re-drawn only for a new reading / theme / 5-min tick / settings, and
     * not while the screen is off (the screen-on re-post brings fresh ones). Drawn at ≤ 2× density at the content
     * column's width (the layout scales them uniformly if the guess is off): graph ~560×190 px, slots ≤ 104 px tall.
     */
    @Synchronized
    private fun art(c: Context, r: Reading): Art? = runCatching {
        val now = System.currentTimeMillis() / 1000
        val night = c.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        val treatments = app.mauri.sukrara.treatments.TreatmentFeed.items.value
        val key = "${r.t}|${now / 300}|$night|${java.util.Locale.getDefault()}|${treatments.hashCode()}|${GraphSettings.value.hashCode()}|" +
            "${app.mauri.sukrara.alerts.Alerts.settings.value.hashCode()}"
        val old = artCache
        if (old != null && (key == artKey || !c.getSystemService(PowerManager::class.java).isInteractive)) return@runCatching old
        val dm = c.resources.displayMetrics
        val gc = if (dm.density <= 2f) c else c.createConfigurationContext(Configuration(c.resources.configuration).apply { densityDpi = 320 })
        val d = gc.resources.displayMetrics.density
        val dark = night == Configuration.UI_MODE_NIGHT_YES
        val pal = systemPalette(gc, dark)
        val surface = c.getColor(if (dark) android.R.color.system_surface_container_high_dark
            else android.R.color.system_surface_container_high_light)
        val history = RecentHistory.readings(c).let { h0 ->
            if (h0.lastOrNull()?.let { r.t - it.t < 30 } == true) h0 else h0 + r
        }
        // The content column: the shade's 16 dp sides, the icon column (72 dp in Android 17's notification templates,
        // 52 dp before) and the 16 dp end margin.
        val wDp = min(dm.widthPixels, dm.heightPixels) / dm.density - 32f - (if (Build.VERSION.SDK_INT >= 37) 72f else 52f) - 16f
        val cfg = GraphSettings.value
        val graph = run {
            val w = (wDp * d).toInt()
            val h = (GRAPH_H_DP * d).toInt()
            val pad = (6 * d).toInt()
            val plot = createBitmap(w - 2 * pad, h - 2 * pad)
            GlucoseCard.draw(Canvas(plot), plot.width.toFloat(), plot.height.toFloat(), history,
                app.mauri.sukrara.treatments.TreatmentFeed.between(now - cfg.hours(GraphPlace.NOTIFICATION) * 3600L, now), now,
                CardStyle(graph = cfg.graphStyle(cfg.hours(GraphPlace.NOTIFICATION), android.text.format.DateFormat.is24HourFormat(c)),
                    rings = emptyList(), value = false),
                pal, d)
            createBitmap(w, h).also { out ->
                Canvas(out).apply {
                    drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), 12 * d, 12 * d, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = surface })
                    drawBitmap(plot, pad.toFloat(), pad.toFloat(), null)
                }
                plot.recycle()
            }
        }
        // Slots: at most what fits beside the value (measured, at least as wide as "388"), the arrow (6 + 34 dp), the
        // info's 12 dp margin and the slots' 8 dp one, so the row never has to squeeze a disc; 3 + the last treatment.
        val size = SLOT_DP * d
        val gap = 6 * d
        val vp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = app.mauri.sukrara.graph.RoundedFont.typeface(700)
            textSize = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, 44f, dm)
        }
        val valueDp = (maxOf(vp.measureText("${r.sgv}?"), vp.measureText(app.mauri.sukrara.graph.ValueFit.REFERENCE)) + 2) / d
        val room = (wDp - valueDp - 6f - 34f - 12f - 8f) * d
        val max = ((room + gap) / (size + gap)).toInt().coerceIn(0, 4)
        val items = appSlots(c, history, treatments, now, max)
        val slots = if (items.isEmpty()) null else createBitmap((items.size * size + (items.size - 1) * gap).toInt(), size.toInt()).also { bmp ->
            val cv = Canvas(bmp)
            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = surface }
            var x = bmp.width.toFloat()
            val main = Slots.mainIndex(items)
            val countdown = app.mauri.sukrara.graph.SensorBadge.countdownNow(now)
            Slots.drawUnder(cv, items, items.indices.map { x - size / 2 - it * (size + gap) }, size / 2, size / 2 - 3 * d, pal)
            for ((i, item) in items.withIndex()) {
                cv.drawCircle(x - size / 2, size / 2, size / 2, fill)
                Slots.draw(cv, item, x - size / 2, size / 2, size / 2 - 3 * d, now, pal, d, countdown.takeIf { i == main })
                x -= size + gap
            }
        }
        Art(graph, slots).also { artCache = it; artKey = key }   // the previous ones may still be parcelled: the GC takes them
    }.onFailure { Log.w("Sukrara.notif", "art: $it") }.getOrNull()

    /** A slot as a Live Update metric: value and a short label ("86%" · "in range 1d", "4.5u" · "12m ago"). */
    private fun metric(item: app.mauri.sukrara.graph.SlotItem, now: Long): Pair<String, String>? = when (item) {
        is app.mauri.sukrara.graph.SlotItem.Metric -> if (item.m.empty) null else when (item.spec.kind) {
            app.mauri.sukrara.graph.SlotKind.TIR -> "${app.mauri.sukrara.graph.TirRing.text(item.m.tir, item.spec)}%" to
                "${Words.current.let { if (item.spec.range == app.mauri.sukrara.graph.TirRange.TIGHT) it.tirVeryGood else it.tirInRange }} ${item.spec.period}"
            else -> app.mauri.sukrara.graph.MetricGauge.text(item.spec.kind, item.m).let { (v, u) -> "$v$u" } to
                "${item.spec.kind.short} ${item.spec.period}"
        }
        is app.mauri.sukrara.graph.SlotItem.Last -> (app.mauri.sukrara.treatments.TreatmentMarks.label(item.info.tr, 8) ?: item.info.tr.k) to
            Words.current.ago.format(app.mauri.sukrara.treatments.TreatmentMarks.duration(now - item.info.tr.t))
    }

    private fun semantic(mgdl: Int) = when {
        Range.color(mgdl) == null -> Notification.SEMANTIC_STYLE_SAFE
        mgdl < Range.LOW -> Notification.SEMANTIC_STYLE_DANGER
        else -> Notification.SEMANTIC_STYLE_CAUTION
    }

    /** Alpha-mask small icon: the number fills the width, the trend arrow sits underneath. */
    fun iconBitmap(r: Reading, size: Int = 96): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ALPHA_8)
        val cv = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val text = when {
            r.sgv < 40 -> "LO"
            r.sgv > 400 -> "HI"
            else -> r.sgv.toString()
        }
        val numberH = size * 0.62f
        p.textSize = numberH / 0.72f
        p.textSize *= min(1f, size / p.measureText(text))
        cv.drawText(text, size / 2f, numberH, p)
        r.rate?.let { rate ->
            val arrowBox = size - numberH - size * 0.04f
            cv.save()
            cv.translate(size / 2f, size - arrowBox / 2)
            cv.rotate(Trend.angleDeg(rate))
            drawArrow(cv, arrowBox * 1.15f, p, double = Trend.isDouble(rate))
            cv.restore()
        }
        return bmp
    }

    /**
     * The Live Update chip's icon (and the card's header icon): only the trend arrow, at the Mac's continuous angle,
     * so the chip shows one number (its text). A dot when there's no trend or the reading is stale.
     */
    fun arrowIcon(rate: Double?, stale: Boolean, size: Int = 96): Bitmap =
        Bitmap.createBitmap(size, size, Bitmap.Config.ALPHA_8).also { drawTrend(Canvas(it), rate, stale, size, Paint(Paint.ANTI_ALIAS_FLAG)) }

    /** The same arrow in a colour, for the card's layout. */
    /**
     * The value drawn in the card's rounded face ([app.mauri.sukrara.graph.RoundedFont], proportional figures like SF Pro Rounded) at [px] text
     * size, cropped to the digits' height (like a TextView without font padding).
     */
    private fun valueBitmap(s: String, color: Int, px: Float): Bitmap {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color; textSize = px; typeface = app.mauri.sukrara.graph.RoundedFont.typeface(700)
        }
        val b = android.graphics.Rect().also { p.getTextBounds("0123456789", 0, 10, it) }
        val pad = (px * 0.06f).toInt() + 1
        return Bitmap.createBitmap(kotlin.math.ceil(p.measureText(s)).toInt() + 2, b.height() + 2 * pad, Bitmap.Config.ARGB_8888).also {
            Canvas(it).drawText(s, 0f, (pad - b.top).toFloat(), p)
        }
    }

    private fun arrowBitmap(rate: Double?, stale: Boolean, color: Int, size: Int): Bitmap =
        Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also {
            drawTrend(Canvas(it), rate, stale, size, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
        }

    private fun drawTrend(cv: Canvas, rate: Double?, stale: Boolean, size: Int, p: Paint) {
        if (rate == null || stale) {
            cv.drawCircle(size / 2f, size / 2f, size * 0.16f, p)
            return
        }
        cv.save()
        cv.translate(size / 2f, size / 2f)
        cv.rotate(Trend.angleDeg(rate))
        drawArrow(cv, size * 1.02f, p, double = Trend.isDouble(rate))
        cv.restore()
    }

    /** Also used by the SystemUI hook (hook/SystemUiHook.kt) for its arrow. */
    fun drawArrow(cv: Canvas, scale: Float, p: Paint, double: Boolean) {
        fun head(tip: Float) = Path().apply {
            moveTo(tip * scale, 0f); lineTo((tip - .40f) * scale, .30f * scale)
            lineTo((tip - .30f) * scale, 0f); lineTo((tip - .40f) * scale, -.30f * scale); close()
        }
        val fill = Paint(p).apply { style = Paint.Style.FILL }
        cv.drawRect(-.42f * scale, -.07f * scale, .25f * scale, .07f * scale, fill)
        cv.drawPath(head(.46f), fill)
        if (double) cv.drawPath(head(.12f), fill)
    }
}
