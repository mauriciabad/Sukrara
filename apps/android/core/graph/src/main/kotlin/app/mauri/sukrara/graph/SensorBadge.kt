// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.graph

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.PathParser
import app.mauri.sukrara.core.model.Durations
import app.mauri.sukrara.settings.SettingsSpec

/**
 * The sensor's remaining life, pure (contracts/graph/sensor/life.json; the Mac decides the same): the badge text, the
 * life arc, the warm-up and the countdown ring.
 */
object SensorLife {
    const val DEFAULT_WARMUP_MIN = 60

    /** The warm-up state lasts until the first reading, at most this long past the warm-up's end. */
    const val WARMUP_GRACE_S = 15 * 60L

    val DEFAULT_COUNTDOWN_HOURS: Int = SettingsSpec.COUNTDOWN_HOURS.default.toInt()

    /** The "sensor ended" glyph: Material Symbols Outlined `sports_score` (Apache 2.0), viewBox 0 -960 960 960. */
    const val FLAG_PATH =
        "M360-720h80v-80h-80v80Zm160 0v-80h80v80h-80ZM360-400v-80h80v80h-80Zm320-160v-80h80v80h-80Zm0 160v-80h80v80h-80Z" +
            "m-160 0v-80h80v80h-80Zm160-320v-80h80v80h-80Zm-240 80v-80h80v80h-80ZM200-160v-640h80v80h80v80h-80v80h80v80h-80v" +
            "320h-80Zm400-320v-80h80v80h-80Zm-160 0v-80h80v80h-80Zm-80-80v-80h80v80h-80Zm160 0v-80h80v80h-80Zm80-80v-80h80v80h-80Z"

    /** Warming up: started, no reading since its start ([latestT], null = none), not more than the grace past its end. */
    fun warming(
        info: SensorInfo,
        latestT: Long?,
        now: Long,
    ): Boolean =
        now >= info.startS && now < info.warmupEndS + WARMUP_GRACE_S && (latestT == null || latestT < info.startS)

    /** The warm-up's time left: whole minutes rounded up, from 60 of them hours; "Almost ready" once it is over. */
    fun warmupLabel(
        info: SensorInfo,
        now: Long,
        w: CardWords,
    ): String =
        if (now >= info.warmupEndS) w.almostReady else Durations.whole((info.warmupEndS - now + 59) / 60, w.durations)

    fun warmupFraction(
        info: SensorInfo,
        now: Long,
    ): Double = ((now - info.startS).toDouble() / (info.warmupEndS - info.startS).coerceAtLeast(1)).coerceIn(0.0, 1.0)

    fun ended(
        endS: Long,
        now: Long,
    ) = now >= endS

    /** The badge text: whole days from 24 h, hours from 1 h, else minutes (floor); "0m" once ended. */
    fun label(
        endS: Long,
        now: Long,
        w: CardWords,
    ): String = Durations.whole((endS - now).coerceAtLeast(0) / 60, w.durations)

    /** The disc's arc: the share of the sensor's life left, 1 → 0. */
    fun lifeFraction(
        info: SensorInfo,
        now: Long,
    ): Double = ((info.endS - now).toDouble() / info.lengthS).coerceIn(0.0, 1.0)

    /** The countdown ring: null outside the last [hours] or after the end, else the share of the window left. */
    fun countdownFraction(
        endS: Long,
        now: Long,
        hours: Int,
    ): Double? {
        val left = endS - now
        val window = hours * 3_600L
        return if (left <= 0 || left > window) null else left.toDouble() / window
    }
}

/**
 * The sensor's remaining life on the card: the badge (a Libre disc whose outline depletes clockwise, then "13d" /
 * "20h" / "45m"; ended: "0m" in the alert colour), the warm-up (its time left where the number goes, the disc filling)
 * and the countdown ring over the main slot in the last hours.
 */
object SensorBadge {
    /**
     * [label]/[life]: time left and share of life left; [ended]: past its end. Warming up: [warmup] holds the figure
     * ("42m") and [fill] the warm-up done (0 → 1).
     */
    data class Look(
        val label: String,
        val life: Float,
        val ended: Boolean,
        val warmup: String? = null,
        val fill: Float = 0f,
        val readyAt: Long = 0,
        val end: Long = 0,
    ) {
        /** Warming up past the warm-up's end: "Almost ready", "Waiting for the first reading". */
        fun waiting(now: Long) = warmup != null && now >= readyAt

        fun warmupLine(
            now: Long,
            w: CardWords,
        ) = if (waiting(now)) w.waitingReading else w.readyAt(Freshness.timeText(readyAt, now))
    }

    /** [latestT] = the newest reading's time (null = none): a reading since the sensor's start ends the warm-up. */
    fun look(
        info: SensorInfo?,
        now: Long,
        latestT: Long?,
        w: CardWords,
    ): Look? =
        info?.let {
            val warming = SensorLife.warming(it, latestT, now)
            Look(
                label = SensorLife.label(it.endS, now, w),
                life = SensorLife.lifeFraction(it, now).toFloat(),
                ended = SensorLife.ended(it.endS, now),
                warmup = if (warming) SensorLife.warmupLabel(it, now, w) else null,
                fill = SensorLife.warmupFraction(it, now).toFloat(),
                readyAt = it.warmupEndS,
                end = it.endS,
            )
        }

    /** For screen readers: "Sensor: 13d left" / "Sensor ended. Replace sensor" / "Warming up. Ready at 19:45". */
    fun describe(
        look: Look,
        now: Long,
        w: CardWords,
    ): String =
        when {
            look.warmup != null -> (if (look.waiting(now)) w.almostReady else w.warmingUp) + ". " + look.warmupLine(now, w)
            look.ended -> w.sensorEnded + ". " + w.replaceSensor
            else -> w.sensorLeft((look.end - now).coerceAtLeast(0) / 60)
        }

    /** The ring over the main slot: the warm-up filling up, else the end countdown draining (null = none). */
    fun ring(
        look: Look?,
        info: SensorInfo?,
        now: Long,
        hours: Int,
    ): Double? =
        when {
            look == null || info == null -> null
            look.warmup != null -> look.fill.toDouble()
            else -> SensorLife.countdownFraction(info.endS, now, hours)
        }

    private fun text(
        h: Float,
        color: Int,
    ) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        textSize = h * 0.95f
        typeface = RoundedFont.typeface(600)
    }

    private fun color(
        look: Look,
        pal: GraphPalette,
    ) = if (look.ended) pal.veryLow else pal.inkSoft

    private val flagPath by lazy { PathParser.createPathFromPathData(SensorLife.FLAG_PATH) }

    /** The "sensor ended" flag, [size] px square, top-left at ([x], [y]). */
    internal fun flag(
        cv: Canvas,
        x: Float,
        y: Float,
        size: Float,
        color: Int,
    ) = Glyphs.drawSymbol(cv, flagPath, x, y, size, color)

    /** Just the disc (warm-up: the figure beside it says the time). */
    internal fun disc(
        cv: Canvas,
        x: Float,
        y: Float,
        h: Float,
        look: Look,
        pal: GraphPalette,
    ) = draw(cv, x, y, h, look, pal, label = false)

    /** Draws the badge [h] tall with its top-left at ([x], [y]); returns its width. */
    internal fun draw(
        cv: Canvas,
        x: Float,
        y: Float,
        h: Float,
        look: Look,
        pal: GraphPalette,
        label: Boolean = true,
    ): Float {
        val c = color(look, pal)
        val sw = h * 0.14f
        val r = h / 2 - sw / 2
        val cx = x + h / 2
        val cy = y + h / 2
        val oval = RectF(cx - r, cy - r, cx + r, cy + r)
        val stroke =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = sw
                strokeCap = Paint.Cap.ROUND
                color = ColorUtils.setAlphaComponent(c, if (look.ended) 255 else 80)
            }
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
        return h + h * 0.3f + t.measureText(look.label)
    }

    /** The countdown ring over a slot of [radius] at ([cx], [cy]): ink on a background halo; null [fraction] = none. */
    internal fun drawCountdown(
        cv: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        fraction: Double?,
        pal: GraphPalette,
    ) {
        if (fraction == null) return
        val ringSw = radius * 0.2f
        val mid = radius - ringSw / 2
        val oval = RectF(cx - mid, cy - mid, cx + mid, cy + mid)
        val sweep = (360f * fraction.toFloat()).coerceAtLeast(3f)
        val p =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeWidth = ringSw * 0.62f
                // the background, or black / white where it is transparent (the lock screen over the wallpaper)
                color = if (pal.bg ushr 24 == 0) (if (pal.dark) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()) else pal.bg
            }
        cv.drawArc(oval, -90f, sweep, false, p)
        p.strokeWidth = ringSw * 0.3f
        p.color = pal.ink
        cv.drawArc(oval, -90f, sweep, false, p)
    }
}
