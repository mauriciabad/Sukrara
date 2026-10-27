// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.spike.glucostatus

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.Icon
import kotlin.math.min

/**
 * The foreground service's (only) notification. Approaches 1 and 2 are both properties of it:
 *  - chip:        setRequestPromotedOngoing + setShortCriticalText → Live Update chip in the status bar
 *  - dynamicIcon: the small icon is the number itself, rendered to a bitmap (also used as the chip's icon)
 */
object GlucoseNotification {
    const val ID = 1
    private const val CHANNEL = "glucose"

    fun createChannel(c: Context) {
        // LOW = no sound, no heads-up. Its icon would normally be hidden from the status bar as "silent", but a
        // promoted or a *colorized foreground-service* notification is sorted into the Ongoing section instead,
        // which is shown — and shown first.
        val ch = NotificationChannel(CHANNEL, "Glucose", NotificationManager.IMPORTANCE_LOW).apply {
            setShowBadge(false)
        }
        c.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    fun build(c: Context, cfg: Config, r: Reading?, promote: Boolean): Notification {
        val open = PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val b = Notification.Builder(c, CHANNEL)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .setCategory(Notification.CATEGORY_STATUS)
        if (r == null) {
            return b.setSmallIcon(R.drawable.ic_stat_glucose).setContentTitle("Waiting for glucose…").build()
        }

        val title = "${r.mgdl} mg/dL ${Trend.arrow(r.rate)}"
        val rate = r.rate?.let { "%+.1f mg/dL/min".format(it) } ?: "no trend"
        val age = if (r.ageSec < 90) "now" else "${r.ageSec / 60} min ago"
        b.setSmallIcon(if (cfg.dynamicIcon) Icon.createWithBitmap(iconBitmap(r)) else Icon.createWithResource(c, R.drawable.ic_stat_glucose))
            .setContentTitle(title)
            .setContentText("$rate · $age")
            .setWhen(r.epochSec * 1000)
            .setShowWhen(true)

        if (cfg.metricStyle) {
            b.setStyle(Notification.MetricStyle()
                // The semantic style colours the value in the shade / lock screen / AOD card (not the chip).
                .addMetric(Notification.Metric(Notification.Metric.FixedInt(r.mgdl, "mg/dL"), "Glucose", semantic(r)))
                .addMetric(Notification.Metric(Notification.Metric.FixedText(Trend.arrow(r.rate)), "Trend"))
                .setCriticalMetric(0))
        } else {
            b.setStyle(Notification.BigTextStyle().bigText("$rate · $age"))
        }

        if (!promote) {
            // Can't be both: colorized notifications are never promoted.
            b.setColorized(true).setColor(Range.color(r.mgdl) ?: 0xFF2E7D32.toInt())
        } else {
            // Chip text (wins over the MetricStyle critical metric). Max ~74dp; keep it to the number + one arrow.
            b.setRequestPromotedOngoing(true).setShortCriticalText(if (r.stale) "${r.mgdl}?" else r.text())
        }
        return b.build()
    }

    private fun semantic(r: Reading) = when (Range.color(r.mgdl)) {
        null -> Notification.SEMANTIC_STYLE_SAFE
        else -> if (r.mgdl < Range.LOW || r.mgdl > Range.HIGH) Notification.SEMANTIC_STYLE_DANGER else Notification.SEMANTIC_STYLE_CAUTION
    }

    /**
     * Small icons are drawn as an alpha mask (tinted by SystemUI) and fitted into a square, so the number
     * fills the width and the arrow sits underneath. 96 px is downscaled to ~17–24 dp.
     */
    fun iconBitmap(r: Reading, size: Int = 96): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ALPHA_8)
        val cv = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val text = when {
            r.mgdl < 40 -> "LO"
            r.mgdl > 400 -> "HI"
            else -> r.mgdl.toString()
        }
        val numberH = size * 0.62f
        p.textSize = numberH / 0.72f // cap height ≈ 0.72 em
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

    /** Unit arrow from TrendArrow.swift, pointing right, centred on the origin. */
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
