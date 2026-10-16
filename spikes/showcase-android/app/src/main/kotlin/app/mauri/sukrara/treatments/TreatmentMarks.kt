// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.treatments

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import androidx.core.graphics.ColorUtils
import java.util.Locale
import kotlin.math.roundToInt

/**
 * How every chart draws treatments: the widgets and the Live Update graph (through `GraphRenderer`) and the app's
 * home chart (through Compose's native canvas), so they all look the same.
 *
 * A lane along the bottom of the plot (the 40–60 mg/dL strip, almost always empty), one marker per entry at its
 * time, the amount above it: rapid insulin = filled blue drop (triangle pointing down), long-acting = outlined violet
 * drop, carbs = amber dot, note = small grey diamond (its text only where the caller allows it). A faint vertical
 * line ties the marker to the curve. A label that would overlap one already placed goes one row up; when every row
 * is taken it is dropped (the marker stays). Insulin labels are placed first, then carbs, then notes.
 */
object TreatmentMarks {
    data class Palette(val rapid: Int, val long: Int, val carbs: Int, val note: Int)

    fun palette(dark: Boolean) = if (dark)
        Palette(rapid = 0xFF8AB4FF.toInt(), long = 0xFFC9B0FF.toInt(), carbs = 0xFFFFB74D.toInt(), note = 0xFFB4B4B4.toInt())
    else
        Palette(rapid = 0xFF1A62D6.toInt(), long = 0xFF6D45C4.toInt(), carbs = 0xFFC86E00.toInt(), note = 0xFF707070.toInt())

    fun color(p: Palette, k: String) = when (k) { "rapid" -> p.rapid; "long" -> p.long; "carbs" -> p.carbs; else -> p.note }

    /** "4.5U", "12U", "30g"; a note's text cut to [noteChars] (0 = no text for notes). */
    fun label(tr: Treatment, noteChars: Int = 0): String? = when (tr.k) {
        "rapid", "long" -> amount(tr.a) + "U"
        "carbs" -> "${tr.a.roundToInt()}g"
        else -> tr.n.trim().takeIf { noteChars > 0 && it.isNotEmpty() }
            ?.let { if (it.length > noteChars) it.take(noteChars - 1).trimEnd() + "…" else it }
    }

    private fun amount(a: Double) = if (a % 1.0 == 0.0) "${a.toLong()}" else String.format(Locale.ROOT, "%.1f", a)

    private val ORDER = mapOf("rapid" to 0, "long" to 1, "carbs" to 2, "note" to 3)

    /**
     * Draw [list] (live entries; deleted ones are skipped) into the plot [left]..[right] × ..[bottom] of [cv].
     * [x] maps epoch seconds to a pixel column; entries outside the plot are skipped. [density] = px per dp.
     * [top] bounds the faint time line (NaN = no line). [halo], if given, outlines label text in the background
     * colour so it stays legible where it crosses the glucose line.
     */
    fun draw(
        cv: Canvas, list: List<Treatment>, x: (Long) -> Float, left: Float, right: Float, bottom: Float,
        density: Float, dark: Boolean, textDp: Float = 9f, noteChars: Int = 0, rows: Int = 2,
        top: Float = Float.NaN, halo: Int? = null,
    ) {
        val d = density
        val pal = palette(dark)
        val items = list.filter { !it.del && it.k in ORDER }.map { it to x(it.t) }
            .filter { (_, px) -> px >= left - 1 && px <= right + 1 }
        if (items.isEmpty()) return

        val cy = bottom - 5 * d
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f * d }
        val rule = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 1 * d }

        // Time lines first (under everything), then markers: notes at the back, insulin in front.
        if (!top.isNaN()) for ((tr, px) in items) {
            rule.color = ColorUtils.setAlphaComponent(color(pal, tr.k), if (dark) 0x50 else 0x40)
            cv.drawLine(px, top, px, cy - 4 * d, rule)
        }
        for ((tr, px) in items.sortedByDescending { ORDER.getValue(it.first.k) }) {
            val c = color(pal, tr.k)
            when (tr.k) {
                "rapid", "long" -> {
                    val path = Path().apply {
                        moveTo(px - 4.5f * d, cy - 3.5f * d); lineTo(px + 4.5f * d, cy - 3.5f * d)
                        lineTo(px, cy + 4f * d); close()
                    }
                    if (tr.k == "rapid") cv.drawPath(path, fill.apply { color = c })
                    else {
                        halo?.let { cv.drawPath(path, fill.apply { color = it }) }
                        cv.drawPath(path, outline.apply { color = c })
                    }
                }
                "carbs" -> cv.drawCircle(px, cy, 3.6f * d, fill.apply { color = c })
                else -> cv.drawPath(Path().apply {
                    moveTo(px, cy - 3 * d); lineTo(px + 3 * d, cy); lineTo(px, cy + 3 * d); lineTo(px - 3 * d, cy); close()
                }, fill.apply { color = c })
            }
        }

        // Labels, stacked up to [rows] rows above the lane; skipped when no row has room.
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = textDp * d; typeface = Typeface.DEFAULT_BOLD }
        val haloPaint = halo?.let {
            Paint(text).apply { color = it; style = Paint.Style.STROKE; strokeWidth = 3 * d; strokeJoin = Paint.Join.ROUND }
        }
        val fm = text.fontMetrics
        val lineH = fm.descent - fm.ascent
        val placed = ArrayList<RectF>()
        val gap = 2 * d
        val byPriority = items.sortedWith(compareBy<Pair<Treatment, Float>> { ORDER.getValue(it.first.k) }
            .thenByDescending { it.first.t })
        for ((tr, px) in byPriority) {
            val s = label(tr, noteChars) ?: continue
            val w = text.measureText(s)
            val lx = (px - w / 2).coerceIn(left, (right - w).coerceAtLeast(left))
            for (row in 0 until rows) {
                val baseline = cy - 5.5f * d - fm.descent - row * lineH
                val r = RectF(lx - gap / 2, baseline + fm.ascent, lx + w + gap / 2, baseline + fm.descent)
                if (placed.any { RectF.intersects(it, r) }) continue
                placed += r
                haloPaint?.let { cv.drawText(s, lx, baseline, it) }
                cv.drawText(s, lx, baseline, text.apply { color = color(pal, tr.k) })
                break
            }
        }
    }
}
