// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.spike.notiflab

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/** One glucose band of the graph (investigation 16's 54/70/180/250/300 lines, 40–400 = the sensor's span). */
data class Band(val from: Int, val to: Int, val name: String, val color: Int)

data class Reading(val t: Long, val sgv: Int)

/** Synthetic data and the drawings every variant shares. Self-contained: no Sukrara code. */
object Glucose {
    const val MIN = 40
    const val MAX = 400
    val BANDS = listOf(
        Band(40, 54, "very low", Color.rgb(0xB3, 0x26, 0x1E)),
        Band(54, 70, "low", Color.rgb(0xFF, 0x45, 0x3A)),
        Band(70, 180, "in range", Color.rgb(0x34, 0xA8, 0x53)),
        Band(180, 250, "high", Color.rgb(0xFF, 0x9F, 0x0A)),
        Band(250, 300, "very high", Color.rgb(0xE8, 0x59, 0x0C)),
        Band(300, 400, "extremely high", Color.rgb(0x8E, 0x24, 0xAA)),
    )

    fun band(sgv: Int) = BANDS.firstOrNull { sgv < it.to } ?: BANDS.last()

    /** 24 h every 5 min ending at [sgv]: a slow wave with a meal bump, deterministic. */
    fun history(nowS: Long, sgv: Int): List<Reading> = (0 until 288).map { i ->
        val ago = (287 - i) * 300L
        val h = ago / 3600.0
        val v = 135 + 55 * sin(h / 24 * 2 * PI * 3) + 70 * sin(h / 24 * 2 * PI * 7 + 1) - 20 * sin(h)
        Reading(nowS - ago, if (ago == 0L) sgv else v.roundToInt().coerceIn(MIN, MAX))
    }

    /** Share of [readings] per band, in [BANDS] order. */
    fun tir(readings: List<Reading>) = BANDS.map { b -> readings.count { band(it.sgv) == b }.toFloat() / readings.size }

    fun arrow(rate: Float) = when {
        rate >= 2 -> "⇈"; rate >= 1 -> "↑"; rate >= 0.5 -> "↗"; rate > -0.5 -> "→"; rate > -1 -> "↘"; rate > -2 -> "↓"; else -> "⇊"
    }

    private fun angle(rate: Float) = -(rate.coerceIn(-2f, 2f) / 2f * 90f)

    /** White trend arrow on a disc of the band colour: ProgressStyle's tracker. */
    fun tracker(sgv: Int, rate: Float, size: Int = 96): Bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also {
        val cv = Canvas(it)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = Color.WHITE; cv.drawCircle(size / 2f, size / 2f, size / 2f, p)
        p.color = band(sgv).color; cv.drawCircle(size / 2f, size / 2f, size / 2f - size * 0.08f, p)
        p.color = Color.WHITE
        drawArrow(cv, size / 2f, size / 2f, size * 0.62f, angle(rate), p)
    }

    /** Alpha-only arrow: a small icon (the Live Update chip's icon). */
    fun arrowIcon(rate: Float, size: Int = 96): Bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ALPHA_8).also {
        drawArrow(Canvas(it), size / 2f, size / 2f, size * 1f, angle(rate), Paint(Paint.ANTI_ALIAS_FLAG))
    }

    fun arrowBitmap(rate: Float, color: Int, size: Int): Bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also {
        drawArrow(Canvas(it), size / 2f, size / 2f, size * 1f, angle(rate), Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
    }

    private fun drawArrow(cv: Canvas, cx: Float, cy: Float, s: Float, deg: Float, p: Paint) {
        cv.save(); cv.translate(cx, cy); cv.rotate(deg)
        cv.drawRect(-.42f * s, -.07f * s, .25f * s, .07f * s, p)
        cv.drawPath(Path().apply {
            moveTo(.46f * s, 0f); lineTo(.06f * s, .30f * s); lineTo(.16f * s, 0f); lineTo(.06f * s, -.30f * s); close()
        }, p)
        cv.restore()
    }

    /** The number as an alpha small icon, like Sukrara's status-bar number. */
    fun numberIcon(sgv: Int, size: Int = 96): Bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ALPHA_8).also {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD); textAlign = Paint.Align.CENTER
            textSize = size * 0.8f
        }
        p.textSize *= minOf(1f, size / p.measureText("$sgv"))
        Canvas(it).drawText("$sgv", size / 2f, size * 0.72f, p)
    }

    /** Time-in-range ring (a Sukrara "slot") with the in-range % inside: used as the large icon. */
    fun ring(readings: List<Reading>, size: Int = 192, dark: Boolean): Bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also {
        val cv = Canvas(it)
        val sw = size * 0.12f
        val oval = RectF(sw / 2, sw / 2, size - sw / 2, size - sw / 2)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = sw }
        var start = -90f
        tir(readings).forEachIndexed { i, f ->
            p.color = BANDS[i].color
            cv.drawArc(oval, start, f * 360f, false, p)
            start += f * 360f
        }
        val inRange = (tir(readings)[2] * 100).roundToInt()
        val t = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (dark) Color.WHITE else Color.BLACK; textAlign = Paint.Align.CENTER
            typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD); textSize = size * 0.3f
        }
        cv.drawText("$inRange%", size / 2f, size / 2f + t.textSize * 0.35f, t)
    }

    /** The last [hours] as a graph on a rounded surface: band tints, the 70/180 lines, dots coloured by band. */
    fun graph(readings: List<Reading>, nowS: Long, hours: Int, w: Int, h: Int, surface: Int, dark: Boolean): Bitmap =
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bmp ->
            val cv = Canvas(bmp)
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            p.color = surface; cv.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), h * 0.08f, h * 0.08f, p)
            val pad = h * 0.06f
            val lo = 40f; val hi = 300f
            fun y(v: Int) = pad + (h - 2 * pad) * (1 - (v.coerceIn(40, 300) - lo) / (hi - lo))
            fun x(t: Long) = pad + (w - 2 * pad) * (1 - (nowS - t).toFloat() / (hours * 3600f))
            p.color = Glucose.BANDS[2].color; p.alpha = 40
            cv.drawRect(pad, y(180), w - pad, y(70), p)
            p.color = if (dark) Color.WHITE else Color.BLACK; p.alpha = 70; p.strokeWidth = h * 0.006f
            for (v in listOf(70, 180)) cv.drawLine(pad, y(v), w - pad, y(v), p)
            val r = h * 0.018f
            for (rd in readings) if (nowS - rd.t <= hours * 3600L) {
                p.color = band(rd.sgv).color; p.alpha = 255
                cv.drawCircle(x(rd.t), y(rd.sgv), if (rd.t == nowS) r * 2.2f else r, p)
            }
        }

    /** Runs of consecutive readings in the same band: ProgressStyle segments along time. */
    fun runs(readings: List<Reading>): List<Pair<Band, Int>> {
        val out = mutableListOf<Pair<Band, Int>>()
        for (r in readings) {
            val b = band(r.sgv)
            if (out.lastOrNull()?.first == b) out[out.size - 1] = b to out.last().second + 1 else out += b to 1
        }
        return out
    }
}
