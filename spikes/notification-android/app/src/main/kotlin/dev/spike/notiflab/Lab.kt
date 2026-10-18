// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.spike.notiflab

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Log
import android.widget.RemoteViews
import kotlin.math.roundToInt

/**
 * The candidate glucose notifications (investigation 19). Each variant is one notification with its own id, so they
 * can be compared in the shade side by side; `*_c` variants are colorized, which only a foreground service's
 * notification may be, so [CardService] posts those (one at a time).
 */
object Lab {
    const val CHANNEL = "lab"
    const val TAG = "NotifLab"
    val VARIANTS = listOf("baseline", "bands", "bands_c", "tir", "timeline", "live", "picture", "picture_c", "metric")

    fun id(v: String) = 100 + VARIANTS.indexOf(v)

    /** The timeline variant's window (`--ei hours`), to find how many segments the bar keeps. */
    var hours = 3

    fun channel(c: Context) = c.getSystemService(NotificationManager::class.java).createNotificationChannel(
        NotificationChannel(CHANNEL, "Glucose lab", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })

    /** Posts [v] (colorized ones through the service). */
    fun post(c: Context, v: String, sgv: Int, rate: Float) {
        if (v.endsWith("_c") || v == "baseline") {
            c.startForegroundService(Intent(c, CardService::class.java).putExtra("v", v).putExtra("sgv", sgv).putExtra("rate", rate))
            return
        }
        val n = build(c, v, sgv, rate)
        c.getSystemService(NotificationManager::class.java).notify(id(v), n)
        report(c, v, n)
    }

    /** What the system made of it: promotion eligibility (API 36.1+) and the flags it kept. */
    fun report(c: Context, v: String, n: Notification) {
        val nm = c.getSystemService(NotificationManager::class.java)
        val canPromote = runCatching { nm.canPostPromotedNotifications() }.getOrElse { "n/a (${it.javaClass.simpleName})" }
        val promotable = runCatching { n.hasPromotableCharacteristics() }.getOrElse { "n/a (${it.javaClass.simpleName})" }
        Log.i(TAG, "posted $v sdk=${Build.VERSION.SDK_INT} canPostPromoted=$canPromote promotable=$promotable " +
            "style=${n.extras.getString(Notification.EXTRA_TEMPLATE)} colorized=${n.extras.getBoolean(Notification.EXTRA_COLORIZED)}")
    }

    fun build(c: Context, v: String, sgv: Int, rate: Float): Notification {
        val now = System.currentTimeMillis() / 1000
        val history = Glucose.history(now, sgv)
        val dark = c.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val band = Glucose.band(sgv)
        val open = PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val b = Notification.Builder(c, CHANNEL)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setCategory(Notification.CATEGORY_STATUS)
            .setSmallIcon(Icon.createWithBitmap(Glucose.numberIcon(sgv)))
            .setContentTitle("$sgv mg/dL ${Glucose.arrow(rate)}")
            .setContentText("${band.name.replaceFirstChar { it.uppercase() }} · 1 follower")
            .setSubText(v)   // which variant this is, in the header
            .setGroup(v)     // one group each, or the shade bundles them
            .setWhen(now * 1000)
            .setShowWhen(true)
        val action = Notification.Action.Builder(Icon.createWithResource(c, R.drawable.ic_stat), "Log treatment", open).build()
        val surface = c.getColor(if (dark) android.R.color.system_surface_container_high_dark else android.R.color.system_surface_container_high_light)
        when (v.removeSuffix("_c")) {
            "baseline" -> {
                val bg = if (band.name == "in range") 0xFF1BB5C9.toInt() else band.color
                val fg = Color.BLACK
                fun card(layout: Int) = RemoteViews(c.packageName, layout).apply {
                    setTextViewText(R.id.value, "$sgv"); setTextColor(R.id.value, fg)
                    setImageViewBitmap(R.id.arrow, Glucose.arrowBitmap(rate, fg, 96))
                    setTextViewText(R.id.info, ""); setImageViewBitmap(R.id.slots, Glucose.ring(history, 156, dark))
                }
                b.setColorized(true).setColor(bg).setShowWhen(false).setSubText("1 follower")
                    .setStyle(Notification.DecoratedCustomViewStyle())
                    .setCustomContentView(card(R.layout.card_collapsed))
                    .setCustomBigContentView(card(R.layout.card_expanded).apply {
                        setImageViewBitmap(R.id.graph, Glucose.graph(history, now, 12, 840, 290, surface, dark))
                    })
            }
            "bands", "live" -> {
                // The sensor's span 40–400 as one segment per band; the tracker sits at the value.
                b.setStyle(Notification.ProgressStyle()
                    .setStyledByProgress(false)
                    .setProgressSegments(Glucose.BANDS.map { Notification.ProgressStyle.Segment(it.to - it.from).setColor(it.color) })
                    .setProgress(sgv.coerceIn(Glucose.MIN, Glucose.MAX) - Glucose.MIN)
                    .setProgressTrackerIcon(Icon.createWithBitmap(Glucose.tracker(sgv, rate))))
                    .setLargeIcon(Icon.createWithBitmap(Glucose.ring(history, dark = dark)))
                    .addAction(action)
                if (v == "live") {
                    val ok = runCatching { b.setRequestPromotedOngoing(true).setShortCriticalText("$sgv") }
                    b.setSmallIcon(Icon.createWithBitmap(Glucose.arrowIcon(rate)))
                    Log.i(TAG, "requestPromotedOngoing: ${ok.exceptionOrNull() ?: "ok"}")
                }
            }
            "tir" -> {
                // 24 h time in range as proportions (per mille), no tracker.
                val parts = Glucose.tir(history)
                b.setContentText("${(parts[2] * 100).roundToInt()}% in range today · 1 follower")
                b.setStyle(Notification.ProgressStyle()
                    .setStyledByProgress(false)
                    .setProgressSegments(parts.mapIndexedNotNull { i, f ->
                        (f * 1000).roundToInt().takeIf { it > 0 }?.let { Notification.ProgressStyle.Segment(it).setColor(Glucose.BANDS[i].color) }
                    })
                    .setProgress(1000))
                    .addAction(action)
            }
            "timeline" -> {
                // The last [hours] along time: one segment per run of readings in the same band, the tracker = now.
                val recent = history.filter { now - it.t <= hours * 3600 }
                val runs = Glucose.runs(recent)
                Log.i(TAG, "timeline runs=${runs.size}")
                b.setStyle(Notification.ProgressStyle()
                    .setStyledByProgress(false)
                    .setProgressSegments(runs.map { (bd, n) -> Notification.ProgressStyle.Segment(n).setColor(bd.color) })
                    // treatments as points; six of them, to find the points limit
                    .setProgressPoints((1..6).map { Notification.ProgressStyle.Point(recent.size * it / 7).setColor(Color.rgb(0x1E, 0x88, 0xE5)) })
                    .setProgress(recent.size)
                    .setProgressTrackerIcon(Icon.createWithBitmap(Glucose.tracker(sgv, rate))))
                    .addAction(action)
            }
            "picture" -> {
                b.setStyle(Notification.BigPictureStyle()
                    .bigPicture(Glucose.graph(history, now, 12, 1000, 345, surface, dark))
                    .showBigPictureWhenCollapsed(false))
                    .setLargeIcon(Icon.createWithBitmap(Glucose.ring(history, dark = dark)))
                    .addAction(action)
            }
            "metric" -> if (Build.VERSION.SDK_INT >= 37) {
                b.setStyle(Notification.MetricStyle()
                    .addMetric(Notification.Metric(Notification.Metric.FixedInt(sgv), "mg/dL",
                        if (band.name == "in range") Notification.SEMANTIC_STYLE_SAFE else Notification.SEMANTIC_STYLE_CAUTION))
                    .addMetric(Notification.Metric(Notification.Metric.FixedText("${(Glucose.tir(history)[2] * 100).roundToInt()}%"), "in range 1d"))
                    .setCriticalMetric(0))
            } else b.setContentText("MetricStyle needs API 37 (this is ${Build.VERSION.SDK_INT})")
        }
        if (v.endsWith("_c")) b.setColorized(true).setColor(band.color)
        return b.build()
    }
}
