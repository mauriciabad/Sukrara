// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.graph

import app.mauri.sukrara.core.Words
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.PathParser
import app.mauri.sukrara.alerts.AlertStatus
import app.mauri.sukrara.alerts.Kind
import app.mauri.sukrara.treatments.LastTreatment
import app.mauri.sukrara.treatments.TreatmentMarks
import kotlin.math.min

/**
 * The badge under the glucose value (2026-10-18 round), from [AlertStatus]:
 * - None: nothing.
 * - Needed: a filled pill in the colour of what to log, with its marker: "Eat carbs" (low side), "Take insulin"
 *   (high side).
 * - Taken: the same pill, tonal (the colour at low alpha, text in the colour), with the "notifications paused" bell and
 *   the time left of the mute ("45m left"): alerts wait while the treatment works.
 *
 * One look on every surface: the widgets and the lock screen draw it here, the app's panel with Compose
 * (`ui/Widgets.kt`, the same colours and words from [look]).
 */
object StatusBadge {
    /** [kind] = the treatment kind whose colour and marker the badge uses; [tonal] = Taken. */
    data class Look(val kind: String, val text: String, val short: String, val tonal: Boolean)

    fun look(s: AlertStatus, now: Long): Look? = when (s) {
        AlertStatus.None -> null
        is AlertStatus.Needed ->
            Words.current.let { w ->
                if (s.kind in Kind.LOW_SIDE) Look("carbs", w.eatCarbs, w.carbsShort, false) else Look("rapid", w.takeInsulin, w.insulinShort, false)
            }
        is AlertStatus.Taken -> TreatmentMarks.duration(s.until - now, up = true).let { Look(s.treatment.k, Words.current.timeLeft.format(it), it, true) }
    }

    /** The action pill on an ended sensor (round sensor-life): the same pill, kind "sensor" (neutral colour, sensor disc). */
    fun replaceSensor(w: Words = Words.current) = Look("sensor", w.replaceSensor, w.replaceSensorShort, false)

    /** For screen readers (widget descriptions). */
    fun describe(s: AlertStatus, now: Long): String? = when (s) {
        AlertStatus.None -> null
        is AlertStatus.Needed -> look(s, now)?.text
        is AlertStatus.Taken -> Words.current.let { w -> w.alertsPaused.format(w.kind(s.kind), TreatmentMarks.duration(s.until - now, up = true)) }
    }

    /** Material Symbols "notifications_paused" (Apache 2.0), viewBox 0 -960 960 960; also `ic_sym_notifications_paused`. */
    private const val BELL_PAUSED = "M370-320h220v-72H480l110-136v-72H370v72h110L370-392v72ZM160-200v-80h80v-280q0-83 50-147.5" +
        "T420-792v-28q0-25 17.5-42.5T480-880q25 0 42.5 17.5T540-820v28q80 20 130 84.5T720-560v280h80v80H160Zm320-300Zm0 420q-33 " +
        "0-56.5-23.5T400-160h160q0 33-23.5 56.5T480-80ZM320-280h320v-280q0-66-47-113t-113-47q-66 0-113 47t-47 113v280Z"
    private val bellPath: Path by lazy { PathParser.createPathFromPathData(BELL_PAUSED) }

    /** The bell, [size] px square, top-left at ([x], [y]). */
    fun bell(cv: Canvas, x: Float, y: Float, size: Float, color: Int) {
        val p = Path(bellPath)
        p.transform(Matrix().apply { setTranslate(0f, 960f); postScale(size / 960f, size / 960f); postTranslate(x, y) })
        cv.drawPath(p, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
    }

    private fun paints(look: Look, h: Float, dark: Boolean): Triple<Int, Int, Paint> {
        val c = TreatmentMarks.color(TreatmentMarks.palette(dark), look.kind)
        val bg = if (look.tonal) ColorUtils.setAlphaComponent(c, if (dark) 0x3D else 0x2E) else c
        val fg = if (look.tonal) c else TreatmentMarks.onColor(dark)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = fg; textSize = h * 0.56f; typeface = Typeface.create(Typeface.DEFAULT, 600, false)
        }
        return Triple(bg, fg, text)
    }

    /** Width of the full badge [h] px tall; [short] = the shorter words. */
    fun width(look: Look, h: Float, dark: Boolean, short: Boolean = false): Float =
        h * 0.4f + h * 0.62f + h * 0.25f + paints(look, h, dark).third.measureText(if (short) look.short else look.text) + h * 0.45f

    /**
     * Draw the badge [h] px tall with its top-left at ([x], [y]), at most [maxW] wide: the full words, the short ones,
     * or (no room for words) only the icon in a circle. Returns the width used, 0 if not even the circle fits.
     */
    fun draw(cv: Canvas, x: Float, y: Float, h: Float, maxW: Float, look: Look, dark: Boolean): Float {
        val (bg, fg, text) = paints(look, h, dark)
        val words = when {
            width(look, h, dark) <= maxW -> look.text
            width(look, h, dark, short = true) <= maxW -> look.short
            h <= maxW -> null
            else -> return 0f
        }
        val icon = h * 0.62f
        val w = if (words == null) h else h * 0.4f + icon + h * 0.25f + text.measureText(words) + h * 0.45f
        cv.drawRoundRect(RectF(x, y, x + w, y + h), h / 2, h / 2, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bg })
        val ix = if (words == null) x + (h - icon) / 2 else x + h * 0.4f
        if (look.tonal) bell(cv, ix, y + (h - icon) / 2, icon, fg)
        else TreatmentMarks.marker(cv, look.kind, ix + icon / 2, y + h / 2, icon * 0.42f, fg)
        if (words != null) {
            val fm = text.fontMetrics
            cv.drawText(words, ix + icon + h * 0.25f, y + h / 2 - (fm.ascent + fm.descent) / 2, text)
        }
        return w
    }
}

/**
 * Where the last-treatment slot sits among the slots beside the value (2026-10-18 round): one more slot, not a slot
 * kind, so the user's rings stay as they are. [NEAR_VALUE] = leftmost, next to the number; [END] = rightmost.
 */
enum class LastPlace { OFF, NEAR_VALUE, END }

/** One slot beside the value, ready to draw. */
sealed interface SlotItem {
    val emphasis: Float
    data class Metric(val spec: RingSpec, val m: GlucoseMetrics, override val emphasis: Float) : SlotItem
    data class Last(val info: LastTreatment.Info) : SlotItem { override val emphasis = 1f }
}

object Slots {
    private val RING_EMPHASIS = floatArrayOf(1f, 0.55f, 0.35f)
    /** The fallback ring (no recent treatment) when it is faded: like a second slot. */
    private const val FALLBACK_EMPHASIS = 0.55f

    /**
     * What to draw, right to left (main first), at most [max] slots. [rings] = the user's slots (main first) with
     * their metrics, already without the ones lacking data. When there's no room for everything, the main ring stays
     * first, then a recent treatment, then the second ring, then the fallback ring, then the third ring; so on a small
     * widget the treatment pushes the second ring out, and the fallback only fills room the rings leave.
     */
    fun arrange(rings: List<Triple<Int, RingSpec, GlucoseMetrics>>, place: LastPlace, last: LastTreatment.Info?,
                fallback: Pair<RingSpec, GlucoseMetrics>?, max: Int): List<SlotItem> {
        val metric = rings.map { (i, s, m) -> i to SlotItem.Metric(s, m, if (s.faded) RING_EMPHASIS[i.coerceIn(0, 2)] else 1f) }
        val extra: SlotItem? = when {
            place == LastPlace.OFF -> null
            last != null -> SlotItem.Last(last)
            else -> fallback?.let { (s, m) -> SlotItem.Metric(s, m, if (s.faded) FALLBACK_EMPHASIS else 1f) }
        }
        val priority = buildList {
            metric.firstOrNull { it.first == 0 }?.let { add(it.second) }
            if (extra is SlotItem.Last) add(extra)
            metric.firstOrNull { it.first == 1 }?.let { add(it.second) }
            if (extra is SlotItem.Metric) add(extra)
            metric.filter { it.first >= 2 }.forEach { add(it.second) }
        }
        val keep = priority.take(max.coerceAtLeast(0)).toSet()
        val ordered = metric.map { it.second }.filter { it in keep }
        return when {
            extra == null || extra !in keep -> ordered
            place == LastPlace.END -> listOf(extra) + ordered
            else -> ordered + extra
        }
    }

    /**
     * Which of [items] (as [arrange] returns them) is the main ring, the one the sensor countdown ring
     * ([SensorBadge.drawCountdown]) covers: the first ring, else the only slot there is; -1 = none.
     */
    fun mainIndex(items: List<SlotItem>): Int = items.indexOfFirst { it is SlotItem.Metric }.takeIf { it >= 0 } ?: if (items.isEmpty()) -1 else 0

    /**
     * Draw [item] as a circle of [radius] around ([cx], [cy]), with the sensor [countdown] ring over it (the main slot's;
     * a time-in-range ring draws it under its crown).
     */
    fun draw(cv: Canvas, item: SlotItem, cx: Float, cy: Float, radius: Float, nowS: Long, pal: GraphPalette, d: Float,
             countdown: Double? = null) {
        if (item is SlotItem.Metric && item.spec.kind == SlotKind.TIR) {
            TirRing.draw(cv, cx, cy, radius, item.m.tir, item.spec, pal, item.emphasis, d, countdown)
            return
        }
        when (item) {
            is SlotItem.Metric -> MetricGauge.draw(cv, cx, cy, radius, item.m, item.spec, pal, item.emphasis, d)
            is SlotItem.Last -> LastSlot.draw(cv, cx, cy, radius, item.info, nowS, pal, d)
        }
        SensorBadge.drawCountdown(cv, cx, cy, radius, countdown, pal)
    }
}

/**
 * The last-treatment slot: the kind's marker, the amount ("4.5u", "30g") in the kind's colour and the time since it
 * ("12m"); around it a ring in the kind's colour for the share of the alert mute still left (a determinate circular
 * indicator), shrinking back to 12 o'clock. When the mute is over (or the kind mutes nothing) only the grey track is
 * left; [LastTreatment.keepS] later the slot gives way to its fallback.
 */
object LastSlot {
    fun draw(cv: Canvas, cx: Float, cy: Float, radius: Float, info: LastTreatment.Info, nowS: Long, pal: GraphPalette, d: Float) {
        val sw = radius * 0.2f
        val oval = RectF(cx - radius + sw / 2, cy - radius + sw / 2, cx + radius - sw / 2, cy + radius - sw / 2)
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = sw; strokeCap = Paint.Cap.ROUND }
        ring.color = pal.grid
        cv.drawOval(oval, ring)
        val c = TreatmentMarks.color(TreatmentMarks.palette(pal.dark), info.tr.k)
        val left = info.left(nowS)
        if (left > 0f) {
            ring.color = c
            cv.drawArc(oval, -90f, (360f * left).coerceAtLeast(2f), false, ring)
        }
        TreatmentMarks.marker(cv, info.tr.k, cx, cy - radius * 0.4f, radius * 0.14f, c, pal.bg.takeIf { it != 0 })
        val inner = (radius - sw) * 2 * 0.86f
        val num = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = c; textAlign = Paint.Align.CENTER; typeface = Typeface.create(Typeface.DEFAULT, 800, false)
            textSize = radius * 0.5f
        }
        val s = TreatmentMarks.amount(info.tr) ?: ""
        num.textSize = min(num.textSize, num.textSize * inner / num.measureText(s).coerceAtLeast(1f))
        cv.drawText(s, cx, cy + radius * 0.17f, num)
        val sub = Paint(num).apply {
            textSize = radius * 0.3f; typeface = Typeface.create(Typeface.DEFAULT, 600, false); color = pal.inkSoft
        }
        cv.drawText(TreatmentMarks.duration(nowS - info.tr.t), cx, cy + radius * 0.17f + sub.textSize * 1.25f, sub)
    }
}

/**
 * The slots beside the value with the app's own settings (rings, last-treatment slot and its fallback) and the
 * role's stored metrics, for surfaces that lay the slots out themselves (the lock screen widget around the lock icon,
 * the notification): the same choice as [GlucoseCard]'s header, at most [max] slots, right to left (main first).
 */
fun appSlots(c: android.content.Context, readings: List<app.mauri.sukrara.core.Reading>,
             treatments: List<app.mauri.sukrara.treatments.Treatment>, nowS: Long, max: Int): List<SlotItem> {
    val cfg = GraphSettings.value
    val bands = cfg.bands.sane()
    val alerts = app.mauri.sukrara.alerts.Alerts.settings.value
    val last = if (cfg.lastPlace == LastPlace.OFF) null
        else LastTreatment.of(treatments, alerts, AlertStatus.of(readings.maxByOrNull { it.t }, alerts, treatments, nowS), nowS)
    fun metrics(s: RingSpec) = GraphData.metrics(c, s.windowS, bands, nowS).takeIf { it.tir.coverage >= GlucoseCard.MIN_COVERAGE }
    val rings = cfg.rings().take(3).mapIndexedNotNull { i, s -> metrics(s)?.let { Triple(i, s, it) } }
    val fallback = if (cfg.lastPlace != LastPlace.OFF && last == null) cfg.lastElseRing()?.let { s -> metrics(s)?.let { s to it } } else null
    return Slots.arrange(rings, cfg.lastPlace, last, fallback, max)
}
