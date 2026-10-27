// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.spike.glucowidgets

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Typeface
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.createBitmap
import java.util.Calendar
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Glance has no canvas, so the graph is drawn into a Bitmap at the widget's exact pixel size and shown with an
 * Image. Colours come from the Material 3 dynamic-colour system resources (API 34+), so the bitmap matches the
 * rest of the (themed) widget; the caller re-renders on dark-mode changes.
 */
object GraphRenderer {
    data class Colors(val line: Int, val band: Int, val grid: Int, val label: Int)

    fun colors(c: Context): Colors {
        val dark = (c.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        fun res(light: Int, night: Int) = c.getColor(if (dark) night else light)
        val primary = res(android.R.color.system_primary_light, android.R.color.system_primary_dark)
        return Colors(
            line = primary,
            band = ColorUtils.setAlphaComponent(primary, if (dark) 0x38 else 0x24),
            grid = res(android.R.color.system_outline_variant_light, android.R.color.system_outline_variant_dark),
            label = res(android.R.color.system_on_surface_variant_light,
                android.R.color.system_on_surface_variant_dark),
        )
    }

    /** Hours of history that fit: 3 h on a 4-column phone widget, more as it is resized wider. */
    fun hoursFor(widthDp: Float): Int = when {
        widthDp < 200 -> 2
        widthDp < 400 -> 3
        widthDp < 520 -> 4
        else -> 6
    }

    fun render(
        c: Context, history: List<Reading>, nowSec: Long, widthPx: Int, heightPx: Int, hours: Int,
        showLabels: Boolean = true,
    ): Bitmap {
        val w = widthPx.coerceIn(16, 2000)
        val h = heightPx.coerceIn(16, 1200)
        val bmp = createBitmap(w, h)
        val cv = Canvas(bmp)
        val d = c.resources.displayMetrics.density
        val col = colors(c)

        val span = hours * 3600L
        val t0 = nowSec - span
        val pts = history.filter { it.epochSec >= t0 - 60 }
        val yMin = 40f
        val yMax = max(250f, (pts.maxOfOrNull { it.mgdl } ?: 0) + 15f)

        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = col.label; textSize = 10 * d; typeface = Typeface.DEFAULT
        }
        val bottom = if (showLabels) h - labelPaint.textSize - 3 * d else h.toFloat()
        val right = if (showLabels) w - labelPaint.measureText("250") - 4 * d else w.toFloat()
        val left = 2 * d
        val top = 4 * d
        fun x(t: Long) = left + (t - t0).toFloat() / span * (right - left)
        fun y(v: Float) = bottom - (v.coerceIn(yMin, yMax) - yMin) / (yMax - yMin) * (bottom - top)

        // Target band 70–180.
        cv.drawRect(left, y(Range.TARGET_HIGH.toFloat()), right, y(Range.TARGET_LOW.toFloat()),
            Paint().apply { color = col.band })

        // Hour grid + labels.
        val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = col.grid; strokeWidth = 1 * d; pathEffect = DashPathEffect(floatArrayOf(3 * d, 3 * d), 0f)
        }
        val cal = Calendar.getInstance().apply {
            timeInMillis = t0 * 1000; set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            add(Calendar.HOUR_OF_DAY, 1)
        }
        while (cal.timeInMillis / 1000 <= nowSec) {
            val gx = x(cal.timeInMillis / 1000)
            cv.drawLine(gx, top, gx, bottom, grid)
            if (showLabels) {
                val s = "%d:00".format(cal.get(Calendar.HOUR_OF_DAY))
                val tw = labelPaint.measureText(s)
                cv.drawText(s, (gx - tw / 2).coerceIn(0f, right - tw), h - 2 * d, labelPaint)
            }
            cal.add(Calendar.HOUR_OF_DAY, 1)
        }
        if (showLabels) for (v in listOf(Range.TARGET_LOW, Range.TARGET_HIGH)) {
            cv.drawText("$v", right + 3 * d, y(v.toFloat()) + labelPaint.textSize / 3, labelPaint)
        }

        // Line, coloured per segment by range; broken where readings are > 6 min apart. Dots when sparse.
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            strokeWidth = 2.2f * d; strokeCap = Paint.Cap.ROUND; style = Paint.Style.STROKE
        }
        val dot = Paint(Paint.ANTI_ALIAS_FLAG)
        val sparse = pts.size < 2 || (right - left) / pts.size > 6 * d
        for (i in pts.indices) {
            val r = pts[i]
            val colr = Range.color(r.mgdl) ?: col.line
            if (i > 0 && r.epochSec - pts[i - 1].epochSec <= 360) {
                val p = pts[i - 1]
                stroke.color = colr
                cv.drawLine(x(p.epochSec), y(p.mgdl.toFloat()), x(r.epochSec), y(r.mgdl.toFloat()), stroke)
            }
            if (sparse || i == pts.lastIndex) {
                dot.color = colr
                cv.drawCircle(x(r.epochSec), y(r.mgdl.toFloat()), if (i == pts.lastIndex) 3.5f * d else 2f * d, dot)
            }
        }
        return bmp
    }

    /** Pixel size for a dp size, capped so all sizes of one widget stay well under the RemoteViews bitmap limit. */
    fun px(c: Context, dp: Float): Int = min((dp * c.resources.displayMetrics.density).roundToInt(), 1400)
}
