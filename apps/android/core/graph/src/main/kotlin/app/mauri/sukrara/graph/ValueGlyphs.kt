// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.graph

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.Typeface
import kotlin.math.ceil
import kotlin.math.min

/**
 * The value's glyphs for surfaces the system lays out ([CardSurface.Kind.GLYPH]: the notification's header and small
 * icon, the Live Update chip), drawn from [GlucoseCard.face]'s decision: the number in the card's rounded face, the
 * card's arrow, the "no trend" ring.
 */
object ValueGlyphs {
    /** The value is sized for at least this wide a number, so a strip beside it doesn't change from 99 to 188. */
    const val REFERENCE = "388"

    /** The value's geometry in units of the number's text size: cap height, gap to the mark, mark box, arrow in box. */
    const val CAP = 0.72f
    const val MARK_GAP = 0.08f
    const val MARK_BOX = 0.62f
    const val ARROW_IN_BOX = 1.05f

    /** The unit arrow's outline pointing +x: the shaft's corners, then the head's (tip at 0.46). */
    private val ARROW_SHAFT = floatArrayOf(-.42f, -.07f, .25f, .07f)
    private val ARROW_HEAD = listOf(.46f to 0f, .06f to .30f, .16f to 0f, .06f to -.30f)

    /** The unit arrow pointing +x, [scale] px long, at the Mac's geometry; [double] = a double arrow. */
    fun arrow(
        cv: Canvas,
        scale: Float,
        p: Paint,
        double: Boolean,
    ) {
        fun head(shift: Float) =
            Path().apply {
                ARROW_HEAD.forEachIndexed { i, (x, y) -> if (i == 0) moveTo((x + shift) * scale, y * scale) else lineTo((x + shift) * scale, y * scale) }
                close()
            }
        val fill = Paint(p).apply { style = Paint.Style.FILL }
        cv.drawRect(ARROW_SHAFT[0] * scale, ARROW_SHAFT[1] * scale, ARROW_SHAFT[2] * scale, ARROW_SHAFT[3] * scale, fill)
        cv.drawPath(head(0f), fill)
        if (double) cv.drawPath(head(-.34f), fill)
    }

    private fun trend(
        cv: Canvas,
        rate: Double?,
        size: Int,
        p: Paint,
    ) {
        if (rate == null) {
            cv.drawCircle(
                size / 2f,
                size / 2f,
                TrendMark.ringRadius(size.toFloat()),
                Paint(p).apply { style = Paint.Style.STROKE; strokeWidth = TrendMark.ringStroke(size.toFloat()) },
            )
            return
        }
        cv.save()
        cv.translate(size / 2f, size / 2f)
        cv.rotate(TrendAngle.deg(rate))
        arrow(cv, size * 1.02f, p, TrendAngle.isDouble(rate))
        cv.restore()
    }

    /** The trend alone in a [size] px square (rate only when the look's trend is the arrow); an alpha mask without [color]. */
    fun trendIcon(
        rate: Double?,
        size: Int = 96,
        color: Int? = null,
    ): Bitmap =
        Bitmap.createBitmap(size, size, if (color == null) Bitmap.Config.ALPHA_8 else Bitmap.Config.ARGB_8888).also {
            trend(Canvas(it), rate, size, Paint(Paint.ANTI_ALIAS_FLAG).apply { if (color != null) this.color = color })
        }

    /** The number paint in the card's rounded face at [px]. */
    fun numberPaint(
        color: Int,
        px: Float,
    ) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        textSize = px
        typeface = RoundedFont.typeface(700)
    }

    /** [s] (the look's text) in the rounded face at [px], struck when [strike], cropped to the digits' height. */
    fun number(
        s: String,
        color: Int,
        px: Float,
        strike: Boolean = false,
    ): Bitmap {
        val p = numberPaint(color, px).apply { isStrikeThruText = strike }
        val b = Rect().also { p.getTextBounds("0123456789", 0, 10, it) }
        val pad = (px * 0.06f).toInt() + 1
        return Bitmap.createBitmap(ceil(p.measureText(s)).toInt() + 2, b.height() + 2 * pad, Bitmap.Config.ARGB_8888).also {
            Canvas(it).drawText(s, 0f, (pad - b.top).toFloat(), p)
        }
    }

    /** Alpha-mask small icon: the number fills the width, the arrow (or ring) underneath; struck at half strength. */
    fun icon(
        look: GlucoseLook,
        size: Int = 96,
    ): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ALPHA_8)
        val text = look.text ?: return bmp
        val cv = Canvas(bmp)
        val p =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
                textAlign = Paint.Align.CENTER
            }
        if (look.strike) cv.saveLayerAlpha(null, 128)
        val numberH = size * 0.62f
        p.textSize = numberH / 0.72f
        p.textSize *= min(1f, size / p.measureText(text))
        cv.drawText(text, size / 2f, numberH, p)
        if (look.strike) cv.drawRect(0f, numberH * 0.45f, size.toFloat(), numberH * 0.45f + size * 0.07f, p)
        val arrowBox = size - numberH - size * 0.04f
        cv.save()
        cv.translate(size / 2f, size - arrowBox / 2)
        val rate = look.rate
        if (look.trend == TrendMark.ARROW && rate != null) {
            cv.rotate(TrendAngle.deg(rate))
            arrow(cv, arrowBox * 1.15f, p, TrendAngle.isDouble(rate))
        } else {
            cv.drawCircle(0f, 0f, TrendMark.ringRadius(arrowBox), Paint(p).apply { style = Paint.Style.STROKE; strokeWidth = TrendMark.ringStroke(arrowBox) })
        }
        cv.restore()
        if (look.strike) cv.restore()
        return bmp
    }
}
