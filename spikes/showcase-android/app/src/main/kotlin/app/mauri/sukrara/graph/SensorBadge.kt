// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.graph

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.core.graphics.ColorUtils
import app.mauri.sukrara.core.SensorInfo
import app.mauri.sukrara.core.SensorLife
import app.mauri.sukrara.core.Words

/**
 * The sensor's remaining life on the value card (round sensor-life), one look on every surface (the Mac draws the
 * same in SukraraGraph.swift):
 * - the badge next to the trend arrow: a small Libre sensor disc (a circle with a centre dot) whose outline is an arc
 *   that depletes clockwise from 12 o'clock over the sensor's life, then the time left ("13d", "20h", "45m"), in the
 *   secondary text colour; ended: "0m", disc and text in the alert colour (very low). Hidden when nothing is known.
 * - warming up ([SensorLife.warming]): the card shows the warm-up's time left ("42m") instead of a value, the disc
 *   alone beside it filling up, "Warming up · ready at 19:45" under it; the ring over the main ring fills up over the
 *   warm-up (the opposite of the end countdown, which drains). The first reading ends it.
 * - the countdown ring: in the last [SensorLife.countdownHours], a thin ring over the main slot's ring (same size,
 *   on top of whatever is there) that drains from full to empty at the sensor's end; ink on a background-coloured
 *   halo, so it reads over every band colour in light and dark.
 */
object SensorBadge {
    /**
     * [label]/[life]: the sensor's time left and share of life left; [ended]: past its end. While warming up
     * ([SensorLife.warming]) [warmup] holds the figure ("42m") and [fill] the warm-up done (0 → 1): the card shows the
     * figure instead of a value and the disc fills instead of depleting.
     */
    data class Look(val label: String, val life: Float, val ended: Boolean, val warmup: String? = null, val fill: Float = 0f,
                    val readyAt: Long = 0, val end: Long = 0)

    /** [latestT] = the newest reading's time (null = none): a reading since the sensor's start ends the warm-up. */
    fun look(info: SensorInfo?, now: Long, latestT: Long? = null, w: Words = Words.current): Look? = info?.let {
        val warming = SensorLife.warming(it, latestT, now)
        Look(SensorLife.label(it.endEpoch, now, w), SensorLife.lifeFraction(it, now).toFloat(), SensorLife.ended(it.endEpoch, now),
            if (warming) SensorLife.warmupLabel(it, now, w) else null, SensorLife.warmupFraction(it, now).toFloat(), it.warmupEndEpoch,
            it.endEpoch)
    }

    /** For screen readers: "Sensor: 13d left" / "Sensor ended" / "Warming up, ready at 19:45". */
    fun describe(look: Look, now: Long, w: Words = Words.current): String = when {
        look.warmup != null -> w.warmingUp + ", " + w.readyAt.format(Freshness.timeText(look.readyAt, now))
        look.ended -> SensorLife.endedText(look.end, now, w)
        else -> w.sensorLeft.format(look.label)
    }

    /** The ring over the main ring: the warm-up filling up, else the end countdown draining (null = none). */
    fun ring(look: Look?, info: SensorInfo?, now: Long, hours: Int): Double? = when {
        look == null || info == null -> null
        look.warmup != null -> look.fill.toDouble()
        else -> SensorLife.countdownFraction(info.endEpoch, now, hours)
    }

    private fun text(h: Float, color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color; textSize = h * 0.95f; typeface = RoundedFont.typeface(600)
    }

    private fun color(look: Look, pal: GraphPalette) = if (look.ended) pal.veryLow else pal.inkSoft

    /** The badge's width at height [h]. */
    fun width(look: Look, h: Float, pal: GraphPalette): Float = h + h * 0.3f + text(h, color(look, pal)).measureText(look.label)

    /** Just the disc (warm-up: no label, the figure beside it says the time). */
    fun disc(cv: Canvas, x: Float, y: Float, h: Float, look: Look, pal: GraphPalette) = draw(cv, x, y, h, look, pal, label = false)

    /** Draws the badge [h] tall with its top-left at ([x], [y]); returns its width. */
    fun draw(cv: Canvas, x: Float, y: Float, h: Float, look: Look, pal: GraphPalette, label: Boolean = true): Float {
        val c = color(look, pal)
        val sw = h * 0.14f
        val r = h / 2 - sw / 2
        val cx = x + h / 2
        val cy = y + h / 2
        val oval = RectF(cx - r, cy - r, cx + r, cy + r)
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = sw; strokeCap = Paint.Cap.ROUND }
        stroke.color = ColorUtils.setAlphaComponent(c, if (look.ended) 255 else 80)
        cv.drawOval(oval, stroke)
        val arc = if (look.warmup != null) look.fill else look.life
        if (!look.ended && arc > 0f) {
            stroke.color = c
            cv.drawArc(oval, -90f, (360f * arc).coerceAtLeast(4f), false, stroke)
        }
        cv.drawCircle(cx, cy, h * 0.15f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = c })
        if (!label) return h
        val t = text(h, c)
        val fm = t.fontMetrics
        cv.drawText(look.label, x + h * 1.3f, cy - (fm.ascent + fm.descent) / 2, t)
        return width(look, h, pal)
    }

    /**
     * The countdown ring over a slot of [radius] at ([cx], [cy]) (the slot rings' stroke is radius × 0.2): [fraction]
     * from [SensorLife.countdownFraction], null = none.
     */
    /** The countdown fraction for the current sensor and window (null = no ring). */
    fun countdownNow(now: Long, info: SensorInfo? = SensorLife.current.value, hours: Int = SensorLife.countdown.value): Double? =
        info?.let { SensorLife.countdownFraction(it.endEpoch, now, hours) }

    fun drawCountdown(cv: Canvas, cx: Float, cy: Float, radius: Float, fraction: Double?, pal: GraphPalette) {
        if (fraction == null) return
        val ringSw = radius * 0.2f
        val mid = radius - ringSw / 2
        val oval = RectF(cx - mid, cy - mid, cx + mid, cy + mid)
        val sweep = (360f * fraction.toFloat()).coerceAtLeast(3f)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
        // the halo: the background, or black / white where it is transparent (the lock screen over the wallpaper)
        p.strokeWidth = ringSw * 0.62f
        p.color = if (pal.bg ushr 24 == 0) (if (pal.dark) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()) else pal.bg
        cv.drawArc(oval, -90f, sweep, false, p)
        p.strokeWidth = ringSw * 0.3f; p.color = pal.ink
        cv.drawArc(oval, -90f, sweep, false, p)
    }
}
