// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.spike.brandpreview

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * One row per candidate: the launcher icon as this phone draws it (its own mask), a themed-icon rendering of the
 * monochrome layer in the wallpaper's Material You colours, and the notification small icon. "Post notifications"
 * puts every small icon into the real status bar. Round-2 candidates are also launcher entries (activity-alias).
 */
class MainActivity : Activity() {
    private val nm by lazy { getSystemService(NotificationManager::class.java) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        actionBar?.hide()
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        // Default importance (silent): Pixel hides the status-bar icons of low-importance channels.
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Icon preview", NotificationManager.IMPORTANCE_DEFAULT).apply {
            setSound(null, null)
            enableVibration(false)
        })

        val dark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val text = if (dark) Color.WHITE else Color.BLACK
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(40))
        }
        list.addView(label("Icon candidates", 24f, text))
        list.addView(label("Left: the launcher icon as this phone masks it. Middle: themed icon (Material You). " +
            "Right: notification icon. Every round-2 (Sucre…) candidate is also in the app drawer under its own name.", 14f, text, 0.7f))
        for (c in CANDIDATES) list.addView(row(c, dark, text))
        list.addView(Button(this).apply {
            setText("Post notifications (status-bar icons)")
            setOnClickListener { postAll() }
        })
        list.addView(Button(this).apply {
            setText("Clear notifications")
            setOnClickListener { nm.cancelAll() }
        })
        setContentView(ScrollView(this).apply { fitsSystemWindows = true; addView(list) })
    }

    private fun row(c: Candidate, dark: Boolean, text: Int): View {
        // AdaptiveIconDrawable clips itself with the device's own icon mask.
        val icon = getDrawable(c.launcher)!!
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(14), 0, dp(14))
        }
        r.addView(ImageView(this).apply { setImageDrawable(icon) }, LinearLayout.LayoutParams(dp(64), dp(64)))
        (icon as? AdaptiveIconDrawable)?.monochrome?.let { mono ->
            r.addView(ThemedIcon(mono.mutate(), dark), LinearLayout.LayoutParams(dp(64), dp(64)).apply { marginStart = dp(14) })
        }
        r.addView(ImageView(this).apply {
            setImageResource(c.statIcon)
            setColorFilter(if (dark) Color.BLACK else Color.WHITE)
            background = GradientDrawable().apply { cornerRadius = dp(8).toFloat(); setColor(text) }
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginStart = dp(14) })
        r.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(label(c.name, 20f, text))
            addView(label(c.domain, 13f, text, 0.6f))
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(16) })
        return r
    }

    private fun postAll() {
        CANDIDATES.forEachIndexed { i, c ->
            nm.notify(i + 1, Notification.Builder(this, CHANNEL)
                .setSmallIcon(c.statIcon)
                .setColor(c.top)
                .setContentTitle("102 mg/dL →")
                .setContentText("${c.name} · ${c.domain}")
                .setOnlyAlertOnce(true)
                .build())
        }
    }

    /** Pixel-style themed icon: the monochrome layer tinted with the wallpaper's accent palette on a circle. */
    private inner class ThemedIcon(private val mono: Drawable, dark: Boolean) : View(this@MainActivity) {
        private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = getColor(if (dark) android.R.color.system_neutral1_800 else android.R.color.system_accent1_100)
        }
        init { mono.setTint(getColor(if (dark) android.R.color.system_accent1_200 else android.R.color.system_accent1_700)) }

        override fun onDraw(canvas: Canvas) {
            val s = width.toFloat()
            canvas.drawCircle(s / 2, s / 2, s / 2, bg)
            // The layer is 108 units of which the middle 72 are visible: overscan by 18/72 on each side.
            val o = (s * 18 / 72).toInt()
            mono.setBounds(-o, -o, width + o, height + o)
            mono.draw(canvas)
        }
    }

    private fun label(s: String, sp: Float, color: Int, alpha: Float = 1f) = TextView(this).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(color)
        this.alpha = alpha
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        const val CHANNEL = "preview-default"
    }
}
