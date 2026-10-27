// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.graph

import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.PathParser
import app.mauri.sukrara.core.model.Treatment
import app.mauri.sukrara.settings.SettingsSpec
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Which parts of each kind's marks the chart draws (settings `graph.marks`): per kind the point, the mute line, the
 * amount and the time label, only those the kind has ([parts]). Defaults from the schema (carbs: the point only).
 */
class MarkShow private constructor(
    private val on: Map<String, Set<String>>,
) {
    fun has(
        k: String,
        part: String,
    ) = part in on[k].orEmpty()

    fun any(k: String) = on[k]?.isNotEmpty() == true

    /** A label part (amount or time) of [k] shows. */
    fun label(k: String) = has(k, AMOUNT) || has(k, TIME)

    /** The same without any label (a chart longer than [GlucoseGraph.LABELS_MAX_S]). */
    fun withoutLabels(): MarkShow = KINDS.fold(this) { m, k -> m.with(k, AMOUNT, false).with(k, TIME, false) }

    fun with(
        k: String,
        part: String,
        v: Boolean,
    ) = of(on + (k to on[k].orEmpty().let { if (v) it + part else it - part }))

    /** "rapid:point+line+amount+time,long:point+amount+time,carbs:point,note:point" (the stored and wire form). */
    val text: String get() = KINDS.joinToString(",") { k -> "$k:" + parts(k).filter { has(k, it) }.joinToString("+") }

    override fun equals(other: Any?) = other is MarkShow && other.text == text

    override fun hashCode() = text.hashCode()

    override fun toString() = text

    companion object {
        const val POINT = "point"
        const val LINE = "line"
        const val AMOUNT = "amount"
        const val TIME = "time"
        val KINDS: List<String> = SettingsSpec.OPTIONS.getValue("markKind")

        fun parts(k: String): List<String> = SettingsSpec.OPTIONS["markParts.$k"].orEmpty()

        private fun defaults(k: String) = SettingsSpec.OPTIONS["markDefault.$k"].orEmpty().toSet()

        private fun of(m: Map<String, Set<String>>) =
            MarkShow(KINDS.associateWith { k -> parts(k).filter { it in m[k].orEmpty() }.toSet() })

        val DEFAULT = of(KINDS.associateWith(::defaults))
        val ALL = of(KINDS.associateWith { parts(it).toSet() })

        /** [text] back; a kind missing from it (or null) keeps its default, unknown parts are dropped. */
        fun parse(text: String?): MarkShow {
            if (text.isNullOrBlank()) return DEFAULT
            val got =
                text
                    .split(",")
                    .mapNotNull { e -> e.split(":", limit = 2).takeIf { it.size == 2 }?.let { it[0].trim() to it[1] } }
                    .associate { (k, v) ->
                        k to
                            v
                                .split("+")
                                .map { it.trim() }
                                .filter { it.isNotEmpty() }
                                .toSet()
                    }
            return of(KINDS.associateWith { k -> got[k] ?: defaults(k) })
        }
    }
}

/**
 * How every chart draws treatments: a lane along the bottom of the plot with one marker per entry (rapid = filled
 * blue drop, long = outlined violet drop, carbs = amber dot, note = grey diamond), a line along the lane to where the
 * entry's alert mute ends (a tick when over, a chevron while it lasts), and labels ("4.5u 12m") under the markers in
 * the hour labels' row and, when needed, a second row (contracts/graph/labels; same rules on the Mac).
 */
object TreatmentMarks {
    data class Palette(
        val rapid: Int,
        val long: Int,
        val carbs: Int,
        val note: Int,
    )

    fun palette(dark: Boolean) =
        if (dark) {
            Palette(rapid = 0xFF8AB4FF.toInt(), long = 0xFFC9B0FF.toInt(), carbs = 0xFFFFB74D.toInt(), note = 0xFFB4B4B4.toInt())
        } else {
            Palette(rapid = 0xFF1A62D6.toInt(), long = 0xFF6D45C4.toInt(), carbs = 0xFFA65A00.toInt(), note = 0xFF707070.toInt())
        }

    fun color(
        p: Palette,
        k: String,
    ) = when (k) {
        "rapid" -> p.rapid
        "long" -> p.long
        "carbs" -> p.carbs
        else -> p.note
    }

    /** Text on a filled chip of a kind's colour. */
    fun onColor(dark: Boolean) = if (dark) 0xFF1C1C1E.toInt() else 0xFFFFFFFF.toInt()

    /**
     * Words and icon on a tonal (faintly tinted) pill of kind [k]. Light theme: a deeper shade of the kind's colour, so
     * the words reach 4.5:1 on the tint (the bare light colours fail it, e.g. carbs 3.8:1); dark theme: the colour
     * itself. The same values as Mac `TreatmentMarks.tonalInk`.
     */
    fun tonalInk(
        k: String,
        dark: Boolean,
    ): Int =
        if (dark) {
            color(palette(true), k)
        } else {
            when (k) {
                "rapid" -> 0xFF1550B4.toInt()
                "long" -> 0xFF5A32A8.toInt()
                "carbs" -> 0xFF8A4A00.toInt()
                else -> 0xFF555555.toInt()
            }
        }

    /** A tonal pill's tint: [c] at 0x3D (dark) / 0x2E (light) alpha over the card. */
    fun tonalTint(
        c: Int,
        dark: Boolean,
    ) = ColorUtils.setAlphaComponent(c, if (dark) 0x3D else 0x2E)

    /** "4.5u", "12u", "30g"; null for notes. */
    fun amount(tr: Treatment): String? =
        when (tr.k) {
            "rapid", "long" -> number(tr.amount) + "u"
            "carbs" -> "${tr.amount.roundToInt()}g"
            else -> null
        }

    /** [amount], or a note's text cut to [noteChars] (0 = no text for notes). */
    fun label(
        tr: Treatment,
        noteChars: Int = 0,
    ): String? =
        amount(tr) ?: tr.note
            .trim()
            .takeIf { noteChars > 0 && it.isNotEmpty() }
            ?.let { if (it.length > noteChars) it.take(noteChars - 1).trimEnd() + "…" else it }

    private fun number(a: Double) = if (a % 1.0 == 0.0) "${a.toLong()}" else String.format(Locale.ROOT, "%.1f", a)

    /** A short duration ("now", "12m", "1h 5m", "3h", "2d"); [up] rounds partial minutes up (time left). */
    fun duration(
        s: Long,
        w: CardWords,
        up: Boolean = false,
    ): String = w.shortDuration(s, up)

    private val arrowBack: Path by lazy {
        PathParser.createPathFromPathData("m313-440 224 224-57 56-320-320 320-320 57 56-224 224h487v80H313Z")
    }

    private val ORDER = mapOf("rapid" to 0, "long" to 1, "carbs" to 2, "note" to 3)

    /** One marker of kind [k] centred on ([x], [y]), [r] = its half-size; [hole] fills the outlined long drop. */
    fun marker(
        cv: Canvas,
        k: String,
        x: Float,
        y: Float,
        r: Float,
        color: Int,
        hole: Int? = null,
    ) {
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        when (k) {
            "rapid", "long" -> {
                val path =
                    Path().apply {
                        moveTo(x - r, y - r * 0.78f)
                        lineTo(x + r, y - r * 0.78f)
                        lineTo(x, y + r * 0.89f)
                        close()
                    }
                if (k == "rapid") {
                    cv.drawPath(path, fill)
                } else {
                    hole?.let { cv.drawPath(path, Paint(fill).apply { this.color = it }) }
                    cv.drawPath(
                        path,
                        Paint(fill).apply {
                            style = Paint.Style.STROKE
                            strokeWidth = r / 3
                            strokeJoin = Paint.Join.ROUND
                        },
                    )
                }
            }

            "carbs" -> {
                cv.drawCircle(x, y, r * 0.8f, fill)
            }

            // the "Replace sensor" pill: the Libre sensor disc
            "sensor" -> {
                cv.drawCircle(
                    x,
                    y,
                    r * 0.8f,
                    Paint(fill).apply {
                        style = Paint.Style.STROKE
                        strokeWidth = r * 0.26f
                    },
                )
                cv.drawCircle(x, y, r * 0.3f, fill)
            }

            // the "Now" pill over a scrolled chart: "arrow_back" mirrored to "arrow_forward"
            "now" -> {
                cv.drawPath(
                    Path(arrowBack).apply {
                        transform(
                            Matrix().apply {
                                setScale(-1f, 1f, 480f, 0f)
                                postTranslate(0f, 960f)
                                postScale(r * 2.2f / 960f, r * 2.2f / 960f)
                                postTranslate(x - r * 1.1f, y - r * 1.1f)
                            },
                        )
                    },
                    fill,
                )
            }

            else -> {
                cv.drawPath(
                    Path().apply {
                        moveTo(x, y - r * 0.67f)
                        lineTo(x + r * 0.67f, y)
                        lineTo(x, y + r * 0.67f)
                        lineTo(x - r * 0.67f, y)
                        close()
                    },
                    fill,
                )
            }
        }
    }

    const val LABEL_DP = 9f
    const val LABEL_GAP_DP = 4f
    const val MERGE_S = 180L
    const val MERGE_DP = 9f
    const val TWO_LANES_DP = 90f
    const val LANE_DP = 11f
    const val INSET_DP = 3f
    private const val SEP = " · "

    /**
     * One label: [members] in label order, [shownAmounts] those whose amount shows, [time] the time since [timeOf];
     * [lane] 0 (axis row) or 1 (under it), [left] + [w] = its modelled box ([TextModel], px).
     */
    class Label(
        val members: List<Treatment>,
        val shownAmounts: List<Treatment>,
        val timeOf: Treatment?,
        val time: String?,
        val lane: Int,
        val left: Float,
        val w: Float,
    ) {
        val amounts: List<String> get() = shownAmounts.mapNotNull { amount(it) }
        val text: String get() = text(amounts, time)
    }

    /** "4u · 40g 1h 4m": the amounts joined by " · ", then the time. */
    fun text(
        amounts: List<String>,
        time: String?,
    ) = listOfNotNull(amounts.joinToString(SEP).ifEmpty { null }, time).joinToString(" ")

    /** Where the labels go; [dropped] = the groups left without a label (their markers stay). */
    class Layout(
        val labels: List<Label>,
        val dropped: List<List<Treatment>>,
    ) {
        val twoLanes: Boolean get() = labels.any { it.lane == 1 }

        /** Whether [a]..[b] (px) touches a lane-0 label: an hour label there gives way. */
        fun blocks(
            a: Float,
            b: Float,
        ) = labels.any { it.lane == 0 && it.left < b && a < it.left + it.w }
    }

    /**
     * The label layout (docs/FEATURES.md "Treatment labels"): entries with a shown label are grouped (≤ [MERGE_S]
     * after the group's last, or markers < [MERGE_DP] from its first), each group's label is its shown amounts plus the
     * time since its newest timed member, and groups are placed newest first in the first free of: lane 0 with time,
     * lane 1 with time, lane 0 without, lane 1 without ([place]). A group with none gets no label.
     */
    fun layout(
        list: List<Treatment>,
        x: (Long) -> Float,
        nowS: Long?,
        minX: Float,
        maxX: Float,
        lanes: Int,
        d: Float,
        w: CardWords,
        show: MarkShow = MarkShow.DEFAULT,
    ): Layout {
        val items =
            list
                .filter { !it.deleted && amount(it) != null && show.label(it.k) }
                .sortedWith(compareBy<Treatment>({ it.t }, { ORDER.getValue(it.k) }, { it.amount }, { it.id }))
        val groups = ArrayList<MutableList<Treatment>>()
        for (tr in items) {
            val g = groups.lastOrNull()
            if (g != null &&
                (tr.t - g.last().t <= MERGE_S || x(tr.t) - x(g.first().t) < MERGE_DP * d)
            ) {
                g += tr
            } else {
                groups += mutableListOf(tr)
            }
        }
        val size = LABEL_DP * d
        val gap = LABEL_GAP_DP * d
        val taken = List(2) { ArrayList<Pair<Float, Float>>() }
        val labels = ArrayList<Label>()
        val dropped = ArrayList<List<Treatment>>()
        for (g in groups.sortedByDescending { it.last().t }) {
            val members = g.sortedWith(compareBy<Treatment>({ ORDER.getValue(it.k) }, { it.t }, { it.amount }, { it.id }))
            val shownAmounts = members.filter { show.has(it.k, MarkShow.AMOUNT) }
            val amounts = shownAmounts.mapNotNull { amount(it) }
            val timeOf = g.lastOrNull { show.has(it.k, MarkShow.TIME) }
            val time = if (timeOf != null) nowS?.let { duration(it - timeOf.t, w) } else null
            val cx = (x(g.first().t) + x(g.last().t)) / 2
            val tries =
                listOf(0 to time, 1 to time, 0 to null, 1 to null)
                    .filter { it.first < lanes && (amounts.isNotEmpty() || it.second != null) }
                    .distinct()
            val label =
                tries.firstNotNullOfOrNull { (lane, t) ->
                    val lw = TextModel.width(text(amounts, t), size)
                    place(cx, lw, taken[lane], minX, maxX, gap, INSET_DP * d)?.let { left ->
                        taken[lane] += left - gap / 2 to left + lw + gap / 2
                        Label(members, shownAmounts, timeOf, t, lane, left, lw)
                    }
                }
            if (label != null) labels += label else dropped += members
        }
        return Layout(labels, dropped)
    }

    /**
     * The left edge for a box [w] wide anchored at [cx] in a lane holding [taken], or null: centred if free, else the
     * free candidate (slide ends, beside each taken box) nearest the centred place, the anchor staying [inset] inside.
     */
    fun place(
        cx: Float,
        w: Float,
        taken: List<Pair<Float, Float>>,
        minX: Float,
        maxX: Float,
        gap: Float,
        inset: Float,
    ): Float? {
        if (w > maxX - minX) return null
        val pref = (cx - w / 2).coerceIn(minX, (maxX - w).coerceAtLeast(minX))
        var lo = max(minX, cx - w + inset)
        var hi = min(maxX - w, cx - inset)
        if (lo > hi) {
            lo = pref
            hi = pref
        }

        fun free(l: Float) = taken.none { (a0, b0) -> l - gap / 2 < b0 - 0.01f && a0 + 0.01f < l + w + gap / 2 }
        val cands = listOf(pref.coerceIn(lo, hi), lo, hi) + taken.flatMap { (a0, b0) -> listOf(b0 + gap / 2, a0 - w - gap / 2) }
        var best: Float? = null
        for (c in cands) if (c in lo..hi && free(c) && (best == null || abs(c - pref) < abs(best - pref))) best = c
        return best
    }

    /**
     * Draw [list] into the plot [left]..[right] × ..[bottom]: markers in the lane above [bottom], the mute lines
     * ([muteUntil]; [nowS] = the right edge), each where [show] has it, then [layout]'s labels on [base0] / [base1],
     * each with a 3 dp halo erased (DST_OUT) from what is under it (the caller draws the chart in its own layer).
     */
    internal fun draw(
        cv: Canvas,
        list: List<Treatment>,
        x: (Long) -> Float,
        left: Float,
        right: Float,
        bottom: Float,
        d: Float,
        dark: Boolean,
        layout: Layout?,
        base0: Float,
        base1: Float,
        sep: Int,
        nowS: Long?,
        muteUntil: ((Treatment) -> Long?)?,
        show: MarkShow,
    ) {
        val pal = palette(dark)
        val items = list.filter { !it.deleted && it.k in ORDER }.map { it to x(it.t) }.filter { (_, px) -> px in (left - 1)..(right + 1) }
        if (items.isEmpty()) return
        val cy = bottom - 5 * d
        val back = items.sortedByDescending { ORDER.getValue(it.first.k) }
        if (muteUntil != null) {
            val line =
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    strokeWidth = 2 * d
                    strokeCap = Paint.Cap.ROUND
                    style = Paint.Style.STROKE
                }
            // insulin's line just above the lane's middle, carbs' just below: a meal's two lines lie side by side
            for ((tr, px) in back) {
                if (!show.has(tr.k, MarkShow.LINE)) continue
                val until = muteUntil(tr) ?: continue
                val ly = cy + if (tr.k == "carbs") 1.5f * d else -1.5f * d
                val running = nowS != null && until > nowS
                val ex = if (running) right else x(until).coerceAtMost(right)
                if (ex <= px + 1 * d) continue
                line.color = ColorUtils.setAlphaComponent(color(pal, tr.k), 0xB0)
                cv.drawLine(px, ly, ex, ly, line)
                if (running) {
                    if (ex - px > 8 * d) {
                        cv.drawPath(
                            Path().apply {
                                moveTo(ex - 3 * d, ly - 3 * d)
                                lineTo(ex, ly)
                                lineTo(ex - 3 * d, ly + 3 * d)
                            },
                            line,
                        )
                    }
                } else {
                    cv.drawLine(ex, ly - 3 * d, ex, ly + 3 * d, line)
                }
            }
        }
        for ((tr, px) in back) if (show.has(tr.k, MarkShow.POINT)) marker(cv, tr.k, px, cy, 4.5f * d, color(pal, tr.k))
        if (layout == null || layout.labels.isEmpty()) return
        drawLabels(cv, layout, pal, d, base0, base1, sep)
    }

    private class Piece(
        val s: String,
        val paint: Paint,
        val color: Int,
    )

    private fun drawLabels(
        cv: Canvas,
        layout: Layout,
        pal: Palette,
        d: Float,
        base0: Float,
        base1: Float,
        sep: Int,
    ) {
        val bold =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = LABEL_DP * d
                typeface = Typeface.DEFAULT_BOLD
            }
        val medium = Paint(bold).apply { typeface = Typeface.create(Typeface.DEFAULT, 500, false) }
        val drawn =
            layout.labels.map { l ->
                val pieces = ArrayList<Piece>()
                l.shownAmounts.forEachIndexed { i, tr ->
                    if (i > 0) pieces += Piece(SEP, medium, ColorUtils.setAlphaComponent(sep, 0xC0))
                    pieces += Piece(amount(tr).orEmpty(), bold, color(pal, tr.k))
                }
                val timeOf = l.timeOf
                if (l.time != null && timeOf != null) {
                    pieces +=
                        Piece(
                            if (pieces.isEmpty()) l.time else " ${l.time}",
                            medium,
                            ColorUtils.setAlphaComponent(color(pal, timeOf.k), 0xC0),
                        )
                }
                val w = pieces.sumOf { it.paint.measureText(it.s).toDouble() }.toFloat()
                Triple(l, pieces, l.left + (l.w - w) / 2) // the real text, centred in its modelled box
            }

        fun each(f: (Piece, Float, Float) -> Unit) =
            drawn.forEach { (l, pieces, x0) ->
                var px = x0
                for (p in pieces) {
                    f(p, px, if (l.lane == 0) base0 else base1)
                    px += p.paint.measureText(p.s)
                }
            }
        val cut =
            Paint(bold).apply {
                style = Paint.Style.STROKE
                strokeWidth = 3 * d
                strokeJoin = Paint.Join.ROUND
                blendMode = BlendMode.DST_OUT
            }
        each { p, px, base -> cv.drawText(p.s, px, base, cut.apply { typeface = p.paint.typeface }) }
        each { p, px, base -> cv.drawText(p.s, px, base, p.paint.apply { color = p.color }) }
    }
}

/**
 * The last treatment that still matters, for the slot beside the value: only rapid insulin and carbs (they act for
 * hours and mute alerts; long-acting would hold the slot all day, notes have no amount).
 */
object LastTreatment {
    /** How long after it was logged an entry stays: rapid 4 h; carbs 3 min per gram between 30 min and 3 h. */
    fun keepS(tr: Treatment): Long? =
        when (tr.k) {
            "rapid" -> 4 * 3600L
            "carbs" -> (tr.amount * 180).toLong().coerceIn(30 * 60L, 3 * 3600L)
            else -> null
        }

    /** [tr], its mute's end ([until]; null = its kind mutes nothing); [carbs] = carbs riding along running insulin. */
    data class Info(
        val tr: Treatment,
        val until: Long?,
        val carbs: Info? = null,
    ) {
        /** Share of the mute window left at [now], 0 when over or none. */
        fun left(now: Long): Float {
            val u = until ?: return 0f
            return ((u - now).toFloat() / (u - tr.t).coerceAtLeast(1)).coerceIn(0f, 1f)
        }
    }

    /**
     * The entry for the slot at [now], or null: running rapid insulin wins; then the treatment covering the current
     * alert ([status] Taken); else the newest rapid / carbs still inside [keepS] (insulin first at the same minute).
     * Running insulin carries the newest carbs inside [keepS] along.
     */
    fun of(
        treatments: List<Treatment>,
        rule: MuteRule?,
        status: CardStatus,
        now: Long,
    ): Info? {
        fun until(t: Treatment) = rule?.muteUntil(t)
        val live = treatments.filter { !it.deleted && it.t <= now }
        val taken = (status as? CardStatus.Taken)?.let { s -> live.lastOrNull { it.kind == s.treatment && it.t == s.at } }
        val tr =
            live.filter { it.k == "rapid" && (until(it) ?: 0) > now }.maxByOrNull { it.t }
                ?: taken
                ?: live
                    .filter { t -> keepS(t)?.let { now - t.t < it } == true }
                    .maxWithOrNull(compareBy<Treatment> { it.t / 60 }.thenBy { if (it.k == "rapid") 1 else 0 })
                ?: return null
        val u = until(tr)
        val carbs =
            if (tr.k == "rapid" && (u ?: 0) > now) {
                live.filter { it.k == "carbs" && keepS(it)?.let { k -> now - it.t < k } == true }.maxByOrNull { it.t }
            } else {
                null
            }
        return Info(tr, u, carbs?.let { Info(it, until(it)) })
    }
}
