// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.treatments

import app.mauri.sukrara.core.Durations
import app.mauri.sukrara.core.Words
import app.mauri.sukrara.settings.SettingsSpec
import app.mauri.sukrara.graph.TextModel
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import androidx.core.graphics.ColorUtils
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * How every chart draws treatments: the widgets, the notification and the app's home chart (all through
 * `GlucoseGraph`), so they all look the same.
 *
 * A lane along the bottom of the plot (the 40–60 mg/dL strip, almost always empty), one marker per entry at its
 * time: rapid insulin = filled blue drop (triangle pointing down), long-acting = outlined violet drop, carbs = amber
 * dot, note = small grey diamond. A faint vertical line ties the marker to the curve. From the marker, a line along
 * the lane runs to where the entry's alert mute ends ([muteUntil]); it stops with a tick when the mute is over and
 * runs off the right edge (a chevron) while it still lasts. Entries that mute nothing (long-acting, notes) get no line.
 *
 * The labels (amount and time since, "4.5u 12m") go under the markers, in the time axis' row (its hour labels give
 * way) and, when needed and the chart is tall enough, a second row under it; never over the glucose line. Close
 * entries share a label; see [layout] for the rules (the user, 2026-10-18: "the labels cover the chart data …
 * they can cover the axis labels, which are less important").
 */
/**
 * Which parts of each kind's marks the chart draws (Settings › Graph › Treatments on the graph; schema `graph.marks`,
 * mirrored): per kind ([KINDS]) the point (marker), the mute line, the amount label and the time label ([PARTS]),
 * only those the kind has ([parts]: long-acting mutes nothing, a note has only its marker). The defaults are the
 * schema's `markDefault.*` (carbs: the point only, the user 2026-10-21). Mac `MarkShow`.
 */
class MarkShow private constructor(private val on: Map<String, Set<String>>) {
    fun has(k: String, part: String) = part in (on[k] ?: emptySet())
    /** Anything of [k] shows. */
    fun any(k: String) = on[k]?.isNotEmpty() == true
    /** A label part (amount or time) of [k] shows. */
    fun label(k: String) = has(k, AMOUNT) || has(k, TIME)
    /** The same without any label (a chart too long for them: GlucoseGraph.LABELS_MAX_S). */
    fun withoutLabels(): MarkShow = KINDS.fold(this) { m, k -> m.with(k, AMOUNT, false).with(k, TIME, false) }
    fun with(k: String, part: String, v: Boolean) = of(on + (k to (on[k].orEmpty().let { if (v) it + part else it - part })))
    /** "rapid:point+line+amount+time,long:point+amount+time,carbs:point,note:point" (the stored and wire form). */
    val text: String get() = KINDS.joinToString(",") { k -> "$k:" + parts(k).filter { has(k, it) }.joinToString("+") }
    override fun equals(other: Any?) = other is MarkShow && other.text == text
    override fun hashCode() = text.hashCode()
    override fun toString() = text

    companion object {
        const val POINT = "point"; const val LINE = "line"; const val AMOUNT = "amount"; const val TIME = "time"
        val KINDS: List<String> = SettingsSpec.OPTIONS.getValue("markKind")
        val PARTS: List<String> = SettingsSpec.OPTIONS.getValue("markPart")
        /** The parts kind [k] has, in [PARTS] order. */
        fun parts(k: String): List<String> = SettingsSpec.OPTIONS["markParts.$k"].orEmpty()
        private fun of(m: Map<String, Set<String>>) = MarkShow(KINDS.associateWith { k -> parts(k).filter { it in m[k].orEmpty() }.toSet() })
        val DEFAULT = of(KINDS.associateWith { SettingsSpec.OPTIONS["markDefault.$it"].orEmpty().toSet() })
        /** Every part of every kind (the look before the setting existed). */
        val ALL = of(KINDS.associateWith { parts(it).toSet() })
        /** [text] back; a kind missing from it (or null) keeps its default, unknown parts are dropped. */
        fun parse(text: String?): MarkShow {
            if (text.isNullOrBlank()) return DEFAULT
            val got = text.split(",").mapNotNull { e -> e.split(":", limit = 2).takeIf { it.size == 2 }?.let { it[0].trim() to it[1] } }
                .associate { (k, v) -> k to v.split("+").map { it.trim() }.filter { it.isNotEmpty() }.toSet() }
            return of(KINDS.associateWith { k -> got[k] ?: SettingsSpec.OPTIONS["markDefault.$k"].orEmpty().toSet() })
        }
    }
}

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
        return if (m < 1) Words.current.durNow else Durations.compact(m)
    }

    /** Material Symbols "arrow_back" (Apache 2.0, viewBox 0 -960 960 960; also `ic_sym_arrow_back`), mirrored to "arrow_forward" in [marker]. */
    private val arrowForward: Path by lazy { androidx.core.graphics.PathParser.createPathFromPathData("m313-440 224 224-57 56-320-320 320-320 57 56-224 224h487v80H313Z") }

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
            // the "Replace sensor" pill (graph/StatusBadge): the Libre sensor disc, a ring with a centre dot
            "sensor" -> {
                cv.drawCircle(x, y, r * 0.8f, Paint(fill).apply { style = Paint.Style.STROKE; strokeWidth = r * 0.26f })
                cv.drawCircle(x, y, r * 0.3f, fill)
            }
            // the "Now" pill over a scrolled chart (StatusBadge.backToNow): Material Symbols "arrow_forward"
            "now" -> cv.drawPath(Path(arrowForward).apply {
                transform(android.graphics.Matrix().apply {
                    setScale(-1f, 1f, 480f, 0f); postTranslate(0f, 960f); postScale(r * 2.2f / 960f, r * 2.2f / 960f)
                    postTranslate(x - r * 1.1f, y - r * 1.1f)
                })
            }, fill)
            else -> cv.drawPath(Path().apply {
                moveTo(x, y - r * 0.67f); lineTo(x + r * 0.67f, y); lineTo(x, y + r * 0.67f); lineTo(x - r * 0.67f, y); close()
            }, fill)
        }
    }

    // ---- label layout: docs/16-graph.md "Treatment labels" (the same rules in SukraraGraph.swift) ------------------

    /** Label text size (dp). */
    const val LABEL_DP = 9f
    /** Least space between two labels in a lane (dp). */
    const val LABEL_GAP_DP = 4f
    /** Entries this close in time (s) or on the chart (dp, a marker's width) share one label. */
    const val MERGE_S = 180L
    const val MERGE_DP = 9f
    /** Chart height (dp) from which a second lane may be used, and that lane's height. */
    const val TWO_LANES_DP = 90f
    const val LANE_DP = 11f
    /** A label may slide sideways from its centred place as long as its marker stays this far inside it (dp). */
    const val INSET_DP = 3f

    /**
     * One label under the markers: [members] in label order (kind: insulin, long-acting, carbs; then time), the
     * entries with a label part shown ([MarkShow.label]); [shownAmounts] = those whose amount shows; [time] = the time
     * since [timeOf], the newest member whose time shows (null = none wants it, or left out for room); [lane] 0 (the
     * axis row) or 1 (under it), [left] + [w] = its modelled box ([TextModel], px).
     */
    class Label(val members: List<Treatment>, val shownAmounts: List<Treatment>, val timeOf: Treatment?, val time: String?,
                val lane: Int, val left: Float, val w: Float) {
        val amounts: List<String> get() = shownAmounts.mapNotNull { amount(it) }
        val text: String get() = text(amounts, time)
    }

    /** "4u · 40g 1h 4m", "40g", "1h 4m": the amounts joined by " · ", then the time. */
    fun text(amounts: List<String>, time: String?) = listOfNotNull(amounts.joinToString(SEP).ifEmpty { null }, time).joinToString(" ")

    /** Where the labels go; [dropped] = the groups left without a label (their markers stay). */
    class Layout(val labels: List<Label>, val dropped: List<List<Treatment>>) {
        val twoLanes: Boolean get() = labels.any { it.lane == 1 }

        /** Whether [a]..[b] (px) touches a lane-0 label: an hour label there gives way. */
        fun blocks(a: Float, b: Float) = labels.any { it.lane == 0 && it.left < b && a < it.left + it.w }
    }

    private const val SEP = " · "

    /**
     * The label layout, a pure function of the entries, the time → px mapping [x], the room [minX]..[maxX] (the whole
     * chart width: labels may run under the % column and the values on the right) and [lanes] (1 or 2):
     *
     * 1. Entries with an amount (notes: marker only) whose amount or time label shows ([show]), oldest first, are
     *    grouped: one joins the previous group when it is at most [MERGE_S] after the group's last entry (a meal,
     *    chained) or its marker is less than [MERGE_DP] from the group's first marker (markers that overlap; not
     *    chained, so a run of snacks doesn't become one label). Entries with both labels hidden take no part.
     * 2. A group's label is the amounts that show, in kind order joined by " · ", plus the time since its newest entry
     *    whose time shows ("4.5u · 45g 1h 35m"; once, and none when no member's time shows); its anchor is the middle
     *    of its first and last marker. Widths come from [TextModel].
     * 3. Groups are placed newest first, each in the first of: lane 0 with the time, lane 1 with the time, lane 0
     *    without it, lane 1 without it (only when amounts show), that has a free place ([place]). A group with none
     *    gets no label.
     *
     * Nothing else takes room in the lanes: the sensor badge is drawn over the row, masking what passes under it.
     */
    fun layout(list: List<Treatment>, x: (Long) -> Float, nowS: Long?, minX: Float, maxX: Float, lanes: Int, d: Float,
               show: MarkShow = MarkShow.DEFAULT): Layout {
        val items = list.filter { !it.del && amount(it) != null && show.label(it.k) }
            .sortedWith(compareBy<Treatment>({ it.t }, { ORDER.getValue(it.k) }, { it.a }, { it.id }))
        val groups = ArrayList<MutableList<Treatment>>()
        for (tr in items) {
            val g = groups.lastOrNull()
            if (g != null && (tr.t - g.last().t <= MERGE_S || x(tr.t) - x(g.first().t) < MERGE_DP * d)) g += tr
            else groups += mutableListOf(tr)
        }
        val size = LABEL_DP * d
        val gap = LABEL_GAP_DP * d
        val taken = List(2) { ArrayList<Pair<Float, Float>>() }
        val labels = ArrayList<Label>()
        val dropped = ArrayList<List<Treatment>>()
        for (g in groups.sortedByDescending { it.last().t }) {
            val members = g.sortedWith(compareBy<Treatment>({ ORDER.getValue(it.k) }, { it.t }, { it.a }, { it.id }))
            val shownAmounts = members.filter { show.has(it.k, MarkShow.AMOUNT) }
            val amounts = shownAmounts.mapNotNull { amount(it) }
            // the time once, since the newest member that wants it
            val timeOf = g.lastOrNull { show.has(it.k, MarkShow.TIME) }
            val time = if (timeOf != null) nowS?.let { duration(it - timeOf.t) } else null
            val cx = (x(g.first().t) + x(g.last().t)) / 2
            val tries = listOf(0 to time, 1 to time, 0 to null, 1 to null)
                .filter { it.first < lanes && (amounts.isNotEmpty() || it.second != null) }.distinct()
            val label = tries.firstNotNullOfOrNull { (lane, t) ->
                val w = TextModel.width(text(amounts, t), size)
                place(cx, w, taken[lane], minX, maxX, gap, INSET_DP * d)?.let { left ->
                    taken[lane] += left - gap / 2 to left + w + gap / 2
                    Label(members, shownAmounts, timeOf, t, lane, left, w)
                }
            }
            if (label != null) labels += label else dropped += members
        }
        return Layout(labels, dropped)
    }

    /**
     * The left edge for a box [w] wide anchored at [cx] in a lane holding [taken] (boxes with their half gaps), or null.
     * Preferred: centred on the anchor, kept inside [minX]..[maxX]. It may slide while the anchor stays [inset] inside
     * the box (and the box inside the room; when both can't hold, only the preferred place is tried; a box wider than
     * the room has no place). A place is free
     * when the box with [gap] / 2 on each side overlaps no taken box (by more than 0.01). Candidates, in this order:
     * the preferred place, the two ends of the slide, and for each taken box (in placing order) the place just right of
     * it and just left of it; the free one nearest the preferred place wins, the first on a tie.
     */
    fun place(cx: Float, w: Float, taken: List<Pair<Float, Float>>, minX: Float, maxX: Float, gap: Float, inset: Float): Float? {
        if (w > maxX - minX) return null
        val pref = (cx - w / 2).coerceIn(minX, (maxX - w).coerceAtLeast(minX))
        var lo = max(minX, cx - w + inset)
        var hi = min(maxX - w, cx - inset)
        if (lo > hi) { lo = pref; hi = pref }
        fun free(l: Float) = taken.none { (a0, b0) -> l - gap / 2 < b0 - 0.01f && a0 + 0.01f < l + w + gap / 2 }
        val cands = listOf(pref.coerceIn(lo, hi), lo, hi) + taken.flatMap { (a0, b0) -> listOf(b0 + gap / 2, a0 - w - gap / 2) }
        var best: Float? = null
        for (c in cands) if (c >= lo && c <= hi && free(c) && (best == null || abs(c - pref) < abs(best - pref))) best = c
        return best
    }

    /**
     * Draw [list] (live entries; deleted ones are skipped) into the plot [left]..[right] × ..[bottom] of [cv]: the
     * markers in the lane just above [bottom], the faint time lines up to [top] (NaN = none), the mute lines
     * ([muteUntil], null = none; [nowS] = the plot's right edge), each only where [show] has it, then [layout]'s labels with their baselines at [base0]
     * (lane 0) and [base1] (lane 1). [x] maps epoch seconds to a pixel column; [density] = px per dp; [sep] = the
     * colour of the " · " between merged amounts.
     *
     * Each label gets a halo: its outline, 3 dp wide, is erased (DST_OUT) from what is under it before the text is
     * drawn, so it reads over anything on any background (the caller draws the chart in its own layer, so the erased
     * outline shows the surface behind the chart: card, widget, notification colour or wallpaper).
     */
    fun draw(
        cv: Canvas, list: List<Treatment>, x: (Long) -> Float, left: Float, right: Float, bottom: Float,
        density: Float, dark: Boolean, layout: Layout?, base0: Float, base1: Float, sep: Int,
        top: Float = Float.NaN, nowS: Long? = null, muteUntil: ((Treatment) -> Long?)? = null, show: MarkShow = MarkShow.DEFAULT,
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
                if (!show.has(tr.k, MarkShow.LINE)) continue
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
            if (show.has(tr.k, MarkShow.POINT)) marker(cv, tr.k, px, cy, 4.5f * d, color(pal, tr.k))

        if (layout == null || layout.labels.isEmpty()) return
        val bold = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = LABEL_DP * d; typeface = Typeface.DEFAULT_BOLD }
        val medium = Paint(bold).apply { typeface = Typeface.create(Typeface.DEFAULT, 500, false) }
        class Piece(val s: String, val paint: Paint, val color: Int)
        val drawn = layout.labels.map { l ->
            val pieces = ArrayList<Piece>()
            l.shownAmounts.filter { amount(it) != null }.forEachIndexed { i, tr ->
                if (i > 0) pieces += Piece(SEP, medium, ColorUtils.setAlphaComponent(sep, 0xC0))
                pieces += Piece(amount(tr)!!, bold, color(pal, tr.k))
            }
            val timeOf = l.timeOf
            if (l.time != null && timeOf != null)
                pieces += Piece(if (pieces.isEmpty()) l.time else " ${l.time}", medium, ColorUtils.setAlphaComponent(color(pal, timeOf.k), 0xC0))
            val w = pieces.sumOf { it.paint.measureText(it.s).toDouble() }.toFloat()
            Triple(l, pieces, l.left + (l.w - w) / 2)   // the real text, centred in its modelled box
        }
        fun each(f: (Piece, Float, Float) -> Unit) = drawn.forEach { (l, pieces, x0) ->
            var px = x0
            for (p in pieces) { f(p, px, if (l.lane == 0) base0 else base1); px += p.paint.measureText(p.s) }
        }
        val cut = Paint(bold).apply {
            style = Paint.Style.STROKE; strokeWidth = 3 * d; strokeJoin = Paint.Join.ROUND; blendMode = BlendMode.DST_OUT
        }
        each { p, px, base -> cv.drawText(p.s, px, base, cut.apply { typeface = p.paint.typeface }) }
        each { p, px, base -> cv.drawText(p.s, px, base, p.paint.apply { color = p.color }) }
    }
}
