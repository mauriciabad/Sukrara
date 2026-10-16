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
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.Icon
import android.os.Build
import app.mauri.sukrara.R
import app.mauri.sukrara.core.Reading
import app.mauri.sukrara.ui.MainActivity
import kotlin.math.abs
import kotlin.math.min

/** Same mapping as the Mac (spikes/menubar TrendArrow.swift): 0 → flat, ±3 mg/dL/min → vertical. */
object Trend {
    const val FULL_SCALE = 3.0
    fun angleDeg(rate: Double): Float = (-(rate / FULL_SCALE).coerceIn(-1.0, 1.0) * 90).toFloat()
    fun isDouble(rate: Double) = abs(rate) >= FULL_SCALE
    fun arrow(rate: Double?): String = when {
        rate == null -> "·"
        rate >= FULL_SCALE -> "⇈"
        rate >= 2.0 -> "↑"
        rate >= 1.0 -> "↗"
        rate > -1.0 -> "→"
        rate > -2.0 -> "↘"
        rate > -FULL_SCALE -> "↓"
        else -> "⇊"
    }
}

/** The user's personal thresholds (same as the Mac app and brand.json). */
object Range {
    const val LOW = 65
    const val SLIGHTLY_HIGH = 145
    const val HIGH = 185
    fun color(mgdl: Int): Int? = when {
        mgdl < LOW || mgdl > HIGH -> Color.rgb(0xFF, 0x45, 0x3A)
        mgdl > SLIGHTLY_HIGH -> Color.rgb(0xFF, 0x9F, 0x0A)
        else -> null
    }
}

/**
 * The wearer service's one notification (investigation 05, approaches 1 + 2), so the status bar costs one post per
 * reading:
 *  - chip: setRequestPromotedOngoing + setShortCriticalText → Live Update chip;
 *  - icon: the small icon is the number itself, rendered to a bitmap (also the chip's icon).
 */
object GlucoseNotification {
    const val ID = 1
    const val CHANNEL = "glucose"
    const val STALE_AFTER_S = 5 * 60

    fun createChannel(c: Context) {
        val ch = NotificationChannel(CHANNEL, "Glucose (status bar)", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) }
        c.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    fun build(c: Context, r: Reading?, chip: Boolean, icon: Boolean, detail: String): Notification {
        val open = PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE)
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
        val age = System.currentTimeMillis() / 1000 - r.t
        val stale = age > STALE_AFTER_S
        val rate = r.rate?.let { "%+.1f mg/dL/min".format(it) } ?: "no trend"
        val ageText = if (age < 90) "now" else "${age / 60} min ago"
        b.setSmallIcon(if (icon) Icon.createWithBitmap(iconBitmap(r)) else Icon.createWithResource(c, R.drawable.ic_stat_sukrara))
            .setContentTitle("${r.sgv} mg/dL ${Trend.arrow(r.rate)}")
            .setContentText("$rate · $ageText · $detail")
            .setWhen(r.t * 1000)
            .setShowWhen(true)
        if (Build.VERSION.SDK_INT >= 37) {
            b.setStyle(Notification.MetricStyle()
                .addMetric(Notification.Metric(Notification.Metric.FixedInt(r.sgv, "mg/dL"), "Glucose", semantic(r.sgv)))
                .addMetric(Notification.Metric(Notification.Metric.FixedText(Trend.arrow(r.rate)), "Trend"))
                .setCriticalMetric(0))
        } else {
            b.setStyle(Notification.BigTextStyle().bigText("$rate · $ageText · $detail"))
        }
        if (chip) {
            b.setRequestPromotedOngoing(true).setShortCriticalText(if (stale) "${r.sgv}?" else "${r.sgv}${Trend.arrow(r.rate)}")
        } else {
            b.setColorized(true).setColor(Range.color(r.sgv) ?: 0xFF1BB5C9.toInt())
        }
        return b.build()
    }

    private fun semantic(mgdl: Int) = when {
        Range.color(mgdl) == null -> Notification.SEMANTIC_STYLE_SAFE
        mgdl < Range.LOW || mgdl > Range.HIGH -> Notification.SEMANTIC_STYLE_DANGER
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

    private fun drawArrow(cv: Canvas, scale: Float, p: Paint, double: Boolean) {
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
