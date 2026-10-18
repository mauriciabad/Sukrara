// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.treatments

import app.mauri.sukrara.core.Words
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import androidx.core.graphics.ColorUtils
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * How every chart draws treatments: the widgets, the notification and the app's home chart (all through
 * `GlucoseGraph`), so they all look the same.
 *
 * A lane along the bottom of the plot (the 40–60 mg/dL strip, almost always empty), one marker per entry at its
 * time, the amount and the time since it above it ("4.5u 12m"): rapid insulin = filled blue drop (triangle pointing
 * down), long-acting = outlined violet drop, carbs = amber dot, note = small grey diamond (its text only where the
 * caller allows it). A faint vertical line ties the marker to the curve. From the marker, a line along the lane runs to
 * where the entry's alert mute ends ([muteUntil]); it stops with a tick when the mute is over and runs off the right
 * edge (a chevron) while it still lasts. Entries that mute nothing (long-acting, notes) get no line. A label that would
 * overlap one already placed goes one row up (then without its time); when every row is taken it is dropped (the
 * marker stays). Insulin labels are placed first, then carbs, then notes.
 */
object TreatmentMarks {
    data class Palette(val rapid: Int, val long: Int, val carbs: Int, val note: Int)

    fun palette(dark: Boolean) = if (dark)
        Palette(rapid = 0xFF8AB4FF.toInt(), long = 0xFFC9B0FF.toInt(), carbs = 0xFFFFB74D.toInt(), note = 0xFFB4B4B4.toInt())
    else
        Palette(rapid = 0xFF1A62D6.toInt(), long = 0xFF6D45C4.toInt(), carbs = 0xFFC86E00.toInt(), note = 0xFF707070.toInt())

    fun color(p: Palette, k: String) = when (k) { "rapid" -> p.rapid; "long" -> p.long; "carbs" -> p.carbs; else -> p.note }

    /** Text on a filled [color] chip of that kind: near-black on the light dark-theme colours, white on the deep light-theme ones. */
    fun onColor(dark: Boolean) = if (dark) 0xFF1C1C1E.toInt() else 0xFFFFFFFF.toInt()

    /** "4.5u", "12u", "30g" (lowercase, no space: the user, 2026-10-18); null for notes. */
    fun amount(tr: Treatment): String? = when (tr.k) {
        "rapid", "long" -> number(tr.a) + "u"
        "carbs" -> "${tr.a.roundToInt()}g"
        else -> null
    }

    /** [amount], or a note's text cut to [noteChars] (0 = no text for notes). */
    fun label(tr: Treatment, noteChars: Int = 0): String? = amount(tr) ?: tr.n.trim()
        .takeIf { noteChars > 0 && it.isNotEmpty() }?.let { if (it.length > noteChars) it.take(noteChars - 1).trimEnd() + "…" else it }

    private fun number(a: Double) = if (a % 1.0 == 0.0) "${a.toLong()}" else String.format(Locale.ROOT, "%.1f", a)

    /**
     * A short duration: "now" under a minute, "12m", "1h 5m", "3h" (whole hours drop the minutes; from 6 h only hours),
     * "2d" from two days. [up] rounds partial minutes up (time left: "1m" until it is over).
     */
    fun duration(s: Long, up: Boolean = false): String {
        val m = if (up) (s.coerceAtLeast(0) + 59) / 60 else s.coerceAtLeast(0) / 60
        val w = Words.current
        return when {
            m < 1 -> w.durNow
            m < 60 -> w.durMinutes.format(m)
            m < 6 * 60 -> if (m % 60 == 0L) w.durHours.format(m / 60) else w.durHoursMinutes.format(m / 60, m % 60)
            m < 48 * 60 -> w.durHours.format(m / 60)
            else -> w.durDays.format(m / 1440)
        }
    }

    private val ORDER = mapOf("rapid" to 0, "long" to 1, "carbs" to 2, "note" to 3)

    /**
     * One marker of kind [k] centred on ([x], [y]), [r] = its half-size (4.5 dp on the chart). [hole] fills the
     * outlined long-acting drop (the background colour, or null for none).
     */
    fun marker(cv: Canvas, k: String, x: Float, y: Float, r: Float, color: Int, hole: Int? = null) {
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; this.color = color }
        when (k) {
            "rapid", "long" -> {
                val path = Path().apply {
                    moveTo(x - r, y - r * 0.78f); lineTo(x + r, y - r * 0.78f); lineTo(x, y + r * 0.89f); close()
                }
                if (k == "rapid") cv.drawPath(path, fill)
                else {
                    hole?.let { cv.drawPath(path, Paint(fill).apply { this.color = it }) }
                    cv.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.STROKE; strokeWidth = r / 3; this.color = color; strokeJoin = Paint.Join.ROUND
                    })
                }
            }
            "carbs" -> cv.drawCircle(x, y, r * 0.8f, fill)
            else -> cv.drawPath(Path().apply {
                moveTo(x, y - r * 0.67f); lineTo(x + r * 0.67f, y); lineTo(x, y + r * 0.67f); lineTo(x - r * 0.67f, y); close()
            }, fill)
        }
    }

    /**
     * Draw [list] (live entries; deleted ones are skipped) into the plot [left]..[right] × ..[bottom] of [cv].
     * [x] maps epoch seconds to a pixel column; entries outside the plot are skipped. [density] = px per dp.
     * [top] bounds the faint time line (NaN = no line). [halo], if given, outlines label text in the background
     * colour so it stays legible where it crosses the glucose line. [nowS] (the plot's right edge) adds the time since
     * each entry to its label; [muteUntil] gives the end of an entry's alert mute (null = it mutes nothing) for its line.
     */
    fun draw(
        cv: Canvas, list: List<Treatment>, x: (Long) -> Float, left: Float, right: Float, bottom: Float,
        density: Float, dark: Boolean, textDp: Float = 9f, noteChars: Int = 0, rows: Int = 2,
        top: Float = Float.NaN, halo: Int? = null, nowS: Long? = null, muteUntil: ((Treatment) -> Long?)? = null,
    ) {
        val d = density
        val pal = palette(dark)
        val items = list.filter { !it.del && it.k in ORDER }.map { it to x(it.t) }
            .filter { (_, px) -> px >= left - 1 && px <= right + 1 }
        if (items.isEmpty()) return

        val cy = bottom - 5 * d
        val rule = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 1 * d }

        // Time lines first (under everything), then the mute lines, then markers: notes at the back, insulin in front.
        if (!top.isNaN()) for ((tr, px) in items) {
            rule.color = ColorUtils.setAlphaComponent(color(pal, tr.k), if (dark) 0x50 else 0x40)
            cv.drawLine(px, top, px, cy - 4 * d, rule)
        }
        if (muteUntil != null) {
            val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 2 * d; strokeCap = Paint.Cap.ROUND; style = Paint.Style.STROKE }
            // Insulin's line just above the lane's middle, carbs' just below: a meal's two lines lie side by side.
            for ((tr, px) in items.sortedByDescending { ORDER.getValue(it.first.k) }) {
                val until = muteUntil(tr) ?: continue
                val ly = cy + if (tr.k == "carbs") 1.5f * d else -1.5f * d
                val running = nowS != null && until > nowS
                val ex = if (running) right else x(until).coerceAtMost(right)
                if (ex <= px + 1 * d) continue
                line.color = ColorUtils.setAlphaComponent(color(pal, tr.k), 0xB0)
                cv.drawLine(px, ly, ex, ly, line)
                if (running) { if (ex - px > 8 * d) cv.drawPath(Path().apply {   // still muting: a chevron at the edge
                    moveTo(ex - 3 * d, ly - 3 * d); lineTo(ex, ly); lineTo(ex - 3 * d, ly + 3 * d)
                }, line) }
                else cv.drawLine(ex, ly - 3 * d, ex, ly + 3 * d, line)   // over: a tick where it ended
            }
        }
        for ((tr, px) in items.sortedByDescending { ORDER.getValue(it.first.k) })
            marker(cv, tr.k, px, cy, 4.5f * d, color(pal, tr.k), halo)

        // Labels, stacked up to [rows] rows above the lane; skipped when no row has room.
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = textDp * d; typeface = Typeface.DEFAULT_BOLD }
        val ago = Paint(text).apply { typeface = Typeface.create(Typeface.DEFAULT, 500, false) }
        val haloPaint = halo?.let {
            Paint(text).apply { color = it; style = Paint.Style.STROKE; strokeWidth = 3 * d; strokeJoin = Paint.Join.ROUND }
        }
        val agoHalo = haloPaint?.let { Paint(it).apply { typeface = ago.typeface } }
        val fm = text.fontMetrics
        val lineH = fm.descent - fm.ascent
        val placed = ArrayList<RectF>()
        val timed = ArrayList<Long>()   // entries whose label carries the time: one per meal (entries ≤ 3 min apart)
        val gap = 2 * d
        val byPriority = items.sortedWith(compareBy<Pair<Treatment, Float>> { ORDER.getValue(it.first.k) }
            .thenByDescending { it.first.t })
        for ((tr, px) in byPriority) {
            val s = label(tr, noteChars) ?: continue
            val t = nowS?.takeIf { timed.none { abs(it - tr.t) <= 180 } }?.let { " " + duration(it - tr.t) }
            // with its time first, then (no room) without it
            fun place(withTime: Boolean): Boolean {
                val tail = if (withTime) t ?: return false else ""
                val w = text.measureText(s) + ago.measureText(tail)
                val lx = (px - w / 2).coerceIn(left, (right - w).coerceAtLeast(left))
                for (row in 0 until rows) {
                    val baseline = cy - 5.5f * d - fm.descent - row * lineH
                    val r = RectF(lx - gap / 2, baseline + fm.ascent, lx + w + gap / 2, baseline + fm.descent)
                    if (placed.any { RectF.intersects(it, r) }) continue
                    placed += r
                    val c = color(pal, tr.k)
                    haloPaint?.let { cv.drawText(s, lx, baseline, it) }
                    cv.drawText(s, lx, baseline, text.apply { color = c })
                    if (tail.isNotEmpty()) {
                        val tx = lx + text.measureText(s)
                        agoHalo?.let { cv.drawText(tail, tx, baseline, it) }
                        cv.drawText(tail, tx, baseline, ago.apply { color = ColorUtils.setAlphaComponent(c, 0xC0) })
                        timed += tr.t
                    }
                    return true
                }
                return false
            }
            if (!place(true)) place(false)
        }
    }
}
