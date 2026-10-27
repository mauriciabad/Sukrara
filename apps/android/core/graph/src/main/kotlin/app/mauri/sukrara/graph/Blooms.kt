// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.graph

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import app.mauri.sukrara.graph.stats.TimeInRange
import app.mauri.sukrara.graph.stats.TirRange
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The flower a laurel wears from 95 %: 95 lavender, 96 forget-me-not, 97 daisy, 98 cherry blossom, 99 golden rosette;
 * the crowned 100 % keeps the rosette. contracts/graph/mark (`flower`).
 */
enum class Bloom {
    LAVENDER,
    FORGET_ME_NOT,
    DAISY,
    CHERRY,
    ROSETTE,
    ;

    companion object {
        fun of(
            tir: TimeInRange,
            range: TirRange,
        ): Bloom? {
            val m = TirMark.of(tir, range)
            if (m < TirMark.FLOWERS) return null
            if (m == TirMark.CROWN) return ROSETTE
            return when (min(99, (tir.of(range) * 100).roundToInt())) {
                95 -> LAVENDER
                96 -> FORGET_ME_NOT
                97 -> DAISY
                98 -> CHERRY
                else -> ROSETTE
            }
        }
    }
}

/**
 * Draws the [Bloom]s in units of a ring's radius r: a small flower at a laurel's tip (1.25 r at 219° / -39°;
 * lavender grows from the stem's end) and the piece shared where two flowered laurels meet. Fixed colours: they mean
 * "reward", not a glucose range.
 */
internal object Blooms {
    private const val LAVENDER_DARK = 0xFF8E6CD4.toInt()
    private const val LAVENDER_LIGHT = 0xFFA98BE0.toInt()
    private const val FMN = 0xFF7DB8F2.toInt()
    private const val YELLOW = 0xFFF2C230.toInt()
    private const val WHITE = 0xFFFFFFFF.toInt()
    private const val CREAM = 0xFFFFFDF4.toInt()
    private const val SEEDS = 0xFFC9940F.toInt()
    private const val PINK = 0xFFF7A9C4.toInt()
    private const val PINK_DOT = 0xFFC93F70.toInt()
    private const val PINK_HEART = 0xFFD9487A.toInt()
    private const val GOLD = 0xFFE7B53C.toInt()
    private const val GOLD_LIGHT = 0xFFF6D576.toInt()
    private const val GOLD_DARK = 0xFFC98A12.toInt()
    private const val RIBBON = 0xFFB57A0E.toInt()

    /** A laurel side's tip flower (`left` = the ring's left side), faded by [alpha] (0–255) as one layer. */
    fun tip(
        cv: Canvas,
        b: Bloom,
        cx: Float,
        cy: Float,
        r: Float,
        left: Boolean,
        pal: GraphPalette,
        alpha: Int,
    ) = faded(cv, alpha) {
        if (b == Bloom.LAVENDER) {
            val (x, y) = at(cx, cy, r * 1.25f, if (left) 214f else -34f)
            spike(cv, x, y, r, if (left) -56f else -124f, pal)
        } else {
            val (x, y) = at(cx, cy, r * 1.25f, if (left) 219f else -39f)
            flower(cv, b, x, y, unit(r, 1f), pal)
        }
    }

    /** The piece where two flowered laurels meet (rings at [lx] and [rx]); lavender grows a spike from each branch. */
    fun center(
        cv: Canvas,
        b: Bloom,
        lx: Float,
        rx: Float,
        cy: Float,
        r: Float,
        pal: GraphPalette,
        la: Int,
        ra: Int,
    ) {
        if (b == Bloom.LAVENDER) {
            tip(cv, b, lx, cy, r, false, pal, la)
            tip(cv, b, rx, cy, r, true, pal, ra)
            return
        }
        val (x1, y) = at(lx, cy, r * 1.25f, -39f)
        val (x2, _) = at(rx, cy, r * 1.25f, 219f)
        faded(cv, maxOf(la, ra)) { centerPiece(cv, b, (x1 + x2) / 2, y, unit(r, 1.3f), pal) }
    }

    private inline fun faded(
        cv: Canvas,
        alpha: Int,
        block: () -> Unit,
    ) {
        val layer = if (alpha < 255) cv.saveLayerAlpha(null, alpha) else -1
        block()
        if (layer >= 0) cv.restoreToCount(layer)
    }

    private fun at(
        x: Float,
        y: Float,
        dist: Float,
        deg: Float,
    ): Pair<Float, Float> {
        val a = Math.toRadians(deg.toDouble())
        return Pair(x + dist * cos(a).toFloat(), y + dist * sin(a).toFloat())
    }

    private fun fill(c: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = c }

    /** White petals need an edge on light cards; on dark ones the card itself. */
    private fun edge(
        pal: GraphPalette,
        w: Float,
    ) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = w
        color = if (pal.dark) (if (pal.bg ushr 24 == 0) 0xFF000000.toInt() else pal.bg) else 0xFFC9D3CB.toInt()
    }

    private fun circle(
        cv: Canvas,
        x: Float,
        y: Float,
        rad: Float,
        color: Int,
    ) = cv.drawCircle(x, y, rad, fill(color))

    private fun ellipse(
        cv: Canvas,
        x: Float,
        y: Float,
        rx: Float,
        ry: Float,
        rot: Float,
        paint: Paint,
    ) {
        cv.save()
        cv.translate(x, y)
        cv.rotate(rot)
        cv.drawOval(RectF(-rx, -ry, rx, ry), paint)
        cv.restore()
    }

    /** A flower's unit at size [k]: 0.14 r. */
    private fun unit(
        r: Float,
        k: Float,
    ) = r * 0.14f * k

    private fun flower(
        cv: Canvas,
        b: Bloom,
        x: Float,
        y: Float,
        u: Float,
        pal: GraphPalette,
    ) {
        when (b) {
            Bloom.FORGET_ME_NOT -> forgetMeNot(cv, x, y, u)
            Bloom.DAISY -> daisy(cv, x, y, u, false, pal)
            Bloom.CHERRY -> blossom(cv, x, y, u, 0f)
            Bloom.ROSETTE -> rosette(cv, x, y, u)
            Bloom.LAVENDER -> spike(cv, x, y, u / 0.14f, -90f, pal)
        }
    }

    /** Two leaves under a centre piece, one reaching towards each laurel. */
    private fun leafPair(
        cv: Canvas,
        x: Float,
        y: Float,
        u: Float,
        pal: GraphPalette,
    ) {
        for (s in intArrayOf(-1, 1)) ellipse(cv, x + s * u * 1.55f, y + u * 0.95f, u * 1.3f, u * 0.5f, s * 28f, fill(pal.tight))
    }

    private fun centerPiece(
        cv: Canvas,
        b: Bloom,
        x: Float,
        y: Float,
        u: Float,
        pal: GraphPalette,
    ) {
        when (b) {
            Bloom.FORGET_ME_NOT -> {
                leafPair(cv, x, y, u, pal)
                for ((a, bb) in listOf(-1.45f to .55f, 1.45f to .55f, -.75f to -.7f, .75f to -.7f, 0f to .25f)) {
                    forgetMeNot(cv, x + a * u, y + bb * u, u * 0.6f)
                }
            }

            Bloom.DAISY -> {
                leafPair(cv, x, y, u, pal)
                for ((a, bb, s) in listOf(
                    Triple(-1.85f, .55f, .5f),
                    Triple(1.85f, .55f, .5f),
                    Triple(-1.2f, -1.25f, .34f),
                    Triple(1.2f, -1.25f, .34f),
                )) {
                    daisy(cv, x + a * u, y + bb * u, u * s, false, pal)
                }
                daisy(cv, x, y, u * 0.85f, true, pal)
            }

            Bloom.CHERRY -> {
                leafPair(cv, x, y, u, pal)
                blossom(cv, x - u * 1.6f, y + u * .55f, u * .58f, 20f)
                blossom(cv, x + u * 1.6f, y + u * .55f, u * .58f, -20f)
                blossom(cv, x, y - u * .2f, u, 0f)
            }

            Bloom.ROSETTE -> {
                // an award rosette with two ribbon tails hanging into the gap
                val pts = listOf(-.25f to .3f, -1.35f to 2.75f, -.9f to 2.45f, -.62f to 3.0f, .35f to .55f)
                for ((s, color) in listOf(1 to GOLD_DARK, -1 to RIBBON)) {
                    val p =
                        Path().apply {
                            pts.forEachIndexed { i, (a, bb) ->
                                if (i ==
                                    0
                                ) {
                                    moveTo(x + s * a * u, y + bb * u)
                                } else {
                                    lineTo(x + s * a * u, y + bb * u)
                                }
                            }
                            close()
                        }
                    cv.drawPath(p, fill(color))
                }
                rosette(cv, x, y, u * 1.1f)
            }

            Bloom.LAVENDER -> {
                spike(cv, x, y, u / 0.14f, -90f, pal)
            }
        }
    }

    /** Lavender: a bare stem continuing the branch along [dir], then five pairs of violet buds. */
    private fun spike(
        cv: Canvas,
        x: Float,
        y: Float,
        r: Float,
        dir: Float,
        pal: GraphPalette,
    ) {
        val u = unit(r, 1f)
        val (ex, ey) = at(x, y, u * 3.6f, dir)
        cv.drawLine(
            x,
            y,
            ex,
            ey,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = u * .16f
                color = pal.tight
                strokeCap = Paint.Cap.ROUND
            },
        )
        for (i in 0 until 5) {
            val (bx, by) = at(x, y, u * (.9f + i * .68f), dir)
            val sz = u * (.58f - i * .07f)
            for (side in intArrayOf(-1, 1)) {
                val (sx, sy) = at(bx, by, sz * .55f, dir + side * 90)
                ellipse(cv, sx, sy, sz, sz * .62f, dir + side * 35, fill(if (i % 2 == 1) LAVENDER_LIGHT else LAVENDER_DARK))
            }
        }
    }

    private fun forgetMeNot(
        cv: Canvas,
        x: Float,
        y: Float,
        u: Float,
    ) {
        for (i in 0 until 5) {
            val (px, py) = at(x, y, u, -90f + i * 72)
            circle(cv, px, py, u * .8f, FMN)
        }
        circle(cv, x, y, u * .55f, WHITE)
        circle(cv, x, y, u * .28f, YELLOW)
    }

    private fun daisy(
        cv: Canvas,
        x: Float,
        y: Float,
        u: Float,
        double: Boolean,
        pal: GraphPalette,
    ) {
        val n = if (double) 16 else 10
        val petal = fill(WHITE)
        val line = edge(pal, u * .12f)
        val ry = u * (if (double) .3f else .38f)
        for (i in 0 until n) {
            val a = -90f + i * 360f / n
            val (px, py) = at(x, y, u * 1.15f, a)
            ellipse(cv, px, py, u, ry, a, petal)
            ellipse(cv, px, py, u, ry, a, line)
        }
        if (double) {
            for (i in 0 until 10) {
                val a = -72f + i * 36f
                val (px, py) = at(x, y, u * .78f, a)
                ellipse(cv, px, py, u * .62f, u * .26f, a, fill(CREAM))
                ellipse(cv, px, py, u * .62f, u * .26f, a, line)
            }
        }
        circle(cv, x, y, u * .62f, YELLOW)
        if (double) {
            for (i in 0 until 6) {
                val (px, py) = at(x, y, u * .3f, -90f + i * 60)
                circle(cv, px, py, u * .09f, SEEDS)
            }
        }
    }

    /** A notched petal pointing up from the origin, [l] long and [w] wide. */
    private fun notched(
        l: Float,
        w: Float,
    ) = Path().apply {
        moveTo(0f, 0f)
        cubicTo(w, -l * .25f, w * 1.05f, -l * .9f, w * .35f, -l)
        lineTo(0f, -l * .82f)
        lineTo(-w * .35f, -l)
        cubicTo(-w * 1.05f, -l * .9f, -w, -l * .25f, 0f, 0f)
        close()
    }

    private fun blossom(
        cv: Canvas,
        x: Float,
        y: Float,
        u: Float,
        rot: Float,
    ) {
        val p = notched(u * 2f, u * .95f)
        val paint = fill(PINK)
        for (i in 0 until 5) {
            cv.save()
            cv.translate(x, y)
            cv.rotate(i * 72f + rot)
            cv.drawPath(p, paint)
            cv.restore()
        }
        for (i in 0 until 5) {
            val (px, py) = at(x, y, u * .55f, -90f + rot + i * 72)
            circle(cv, px, py, u * .1f, PINK_DOT)
        }
        circle(cv, x, y, u * .42f, PINK_HEART)
    }

    private fun rosette(
        cv: Canvas,
        x: Float,
        y: Float,
        u: Float,
    ) {
        for (i in 0 until 8) {
            val (px, py) = at(x, y, u * 1.1f, -90f + i * 45)
            circle(cv, px, py, u * .62f, GOLD)
        }
        for (i in 0 until 8) {
            val (px, py) = at(x, y, u * .62f, -67.5f + i * 45)
            circle(cv, px, py, u * .42f, GOLD_LIGHT)
        }
        circle(cv, x, y, u * .42f, GOLD_DARK)
    }
}
