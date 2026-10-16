// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.status

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
 *    big value, arrow, delta + age and a 3 h graph; the small icon is the number itself ([iconBitmap], `icon`).
 */
object GlucoseNotification {
    const val ID = 1
    const val CHANNEL = "glucose"
    private const val GRAPH_H_DP = 112f

    fun createChannel(c: Context) {
        val ch = NotificationChannel(CHANNEL, "Glucose (status bar)", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) }
        c.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

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
        if (r == null) {
            return b.setSmallIcon(R.drawable.ic_stat_sukrara).setContentTitle("Sukrara · waiting for glucose…")
                .setContentText(detail).build()
        }
        val now = System.currentTimeMillis() / 1000
        val fresh = Freshness.of(now - r.t)
        val stale = fresh.strike || !fresh.showValue
        // Investigation 16: no rate and no "time ago"; the reading's time only once it's late (Freshness).
        if (!fresh.showValue) {
            return b.setSmallIcon(R.drawable.ic_stat_sukrara).setContentTitle("No reading since ${Freshness.timeText(r.t, now)}")
                .setContentText(detail).build()
        }
        val ageText = if (fresh.showTime) "at ${Freshness.timeText(r.t, now)}" else ""
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
                    .addMetric(Notification.Metric(Notification.Metric.FixedText(Trend.arrow(r.rate)), "trend"))
                    .apply {
                        if (fresh.showTime) addMetric(Notification.Metric(Notification.Metric.FixedText(
                            Freshness.timeText(r.t, now)), "reading"))
                    }
                    .setCriticalMetric(0))
            } else {
                b.setStyle(Notification.BigTextStyle().bigText(line))
            }
        } else {
            // Colorized foreground service: keeps the number icon first and shown although the channel is LOW (docs/05).
            val bg = Range.color(r.sgv) ?: 0xFF1BB5C9.toInt()
            b.setSmallIcon(if (icon) Icon.createWithBitmap(iconBitmap(r)) else Icon.createWithResource(c, R.drawable.ic_stat_sukrara))
                .setColorized(true).setColor(bg)
                .setShowWhen(false)   // the age is in the card; the header keeps only the icon and app name (system-drawn)
                .setStyle(Notification.DecoratedCustomViewStyle())
                .setCustomContentView(card(c, R.layout.notif_glucose_collapsed, r, stale, bg, ageText))
                .setCustomBigContentView(card(c, R.layout.notif_glucose_expanded, r, stale, bg, ageText).apply {
                    graph(c, r)?.let { setImageViewBitmap(R.id.graph, it) }
                    setTextViewText(R.id.detail, detail)
                    setTextColor(R.id.detail, ColorUtils.setAlphaComponent(ink(bg), 0xB0))
                })
        }
        return b.build()
    }

    /** Black or white, whichever reads better on the colorized background (the system does the same for the header). */
    private fun ink(bg: Int) =
        if (ColorUtils.calculateContrast(Color.BLACK, bg or 0xFF000000.toInt()) >= ColorUtils.calculateContrast(Color.WHITE, bg or 0xFF000000.toInt()))
            Color.BLACK else Color.WHITE

    private fun card(c: Context, layout: Int, r: Reading, stale: Boolean, bg: Int, info: String): RemoteViews {
        val fg = ink(bg)
        return RemoteViews(c.packageName, layout).apply {
            setTextViewText(R.id.value, if (stale) "${r.sgv}?" else "${r.sgv}")
            setTextColor(R.id.value, fg)
            setImageViewBitmap(R.id.arrow, arrowBitmap(r.rate, stale, fg, (34 * c.resources.displayMetrics.density).toInt()))
            setTextViewText(R.id.info, info)
            setTextColor(R.id.info, fg)
        }
    }

    // ============================================================================================ graph (chip off)

    private var graphBmp: Bitmap? = null
    private var graphKey = ""

    /**
     * The graph card ([GlucoseCard], investigation 16; window = the notification's setting) over [RecentHistory] (the
     * role's own store), on a rounded surface so it reads on any range colour. Re-drawn only for a new reading / theme / 5-min tick, and not while the screen is off (the
     * screen-on re-post brings a fresh one). Drawn at ≤ 2× density: ~600×190 px, ~0.45 MB.
     */
    @Synchronized
    private fun graph(c: Context, r: Reading): Bitmap? = runCatching {
        val now = System.currentTimeMillis() / 1000
        val night = c.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        val key = "${r.t}|${now / 300}|$night|${app.mauri.sukrara.treatments.TreatmentFeed.items.value.hashCode()}|" +
            "${GraphSettings.value.hashCode()}"
        val old = graphBmp
        if (old != null && (key == graphKey || !c.getSystemService(PowerManager::class.java).isInteractive)) return@runCatching old
        val dm = c.resources.displayMetrics
        val gc = if (dm.density <= 2f) c else c.createConfigurationContext(Configuration(c.resources.configuration).apply { densityDpi = 320 })
        val d = gc.resources.displayMetrics.density
        // Expanded card content ≈ screen width − 2 × 16 dp shade margin − 2 × 16 dp notification padding.
        val wDp = min(dm.widthPixels, dm.heightPixels) / dm.density - 64f
        val w = (wDp * d).toInt()
        val h = (GRAPH_H_DP * d).toInt()
        val pad = (6 * d).toInt()
        val history = RecentHistory.readings(c).let { h0 ->
            if (h0.lastOrNull()?.let { r.t - it.t < 30 } == true) h0 else h0 + r
        }
        val cfg = GraphSettings.value
        val plot = createBitmap(w - 2 * pad, h - 2 * pad)
        GlucoseCard.draw(Canvas(plot), plot.width.toFloat(), plot.height.toFloat(), history,
            app.mauri.sukrara.treatments.TreatmentFeed.between(now - cfg.hours(GraphPlace.NOTIFICATION) * 3600L, now), now,
            CardStyle(graph = cfg.graphStyle(cfg.hours(GraphPlace.NOTIFICATION), android.text.format.DateFormat.is24HourFormat(c)),
                rings = emptyList(), value = false),
            systemPalette(gc, night == Configuration.UI_MODE_NIGHT_YES), d)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(out).apply {
            val surface = c.getColor(if (night == Configuration.UI_MODE_NIGHT_YES) android.R.color.system_surface_container_high_dark
                else android.R.color.system_surface_container_high_light)
            drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), 12 * d, 12 * d, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = surface })
            drawBitmap(plot, pad.toFloat(), pad.toFloat(), null)
        }
        plot.recycle()
        graphBmp = out   // the previous one may still be parcelled by an in-flight notify(): let the GC take it
        graphKey = key
        out
    }.onFailure { Log.w("Sukrara.notif", "graph: $it") }.getOrNull()

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
