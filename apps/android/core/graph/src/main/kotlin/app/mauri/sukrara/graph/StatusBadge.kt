// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.graph

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.PathParser

/**
 * The pill under the glucose value, from [CardStatus]: nothing in range; "Eat carbs" / "Take insulin" filled in the
 * colour of what to log; tonal with the "paused" bell and the time left while a treatment or this device's mute
 * covers it ("45m left", "Muted" without an end); the neutral "Muted · 1h 20m" with the "off" bell for Mute all.
 * The same pill says "Replace sensor" on an ended sensor and "Now" over a scrolled chart.
 */
object StatusBadge {
    /** [kind] = the treatment kind whose colour and marker the pill uses ("muted", "sensor", "now": neutral). */
    data class Look(
        val kind: String,
        val text: String,
        val short: String,
        val tonal: Boolean,
    )

    /** Kind of the Mute all pill (neutral colour, the "notifications off" bell). */
    const val MUTED = "muted"

    /** The pill for [s] at [now]; null for none, and for a mute that has already ended (never "0m left"). */
    fun look(
        s: CardStatus,
        now: Long,
        w: CardWords,
    ): Look? =
        when (s) {
            is CardStatus.Taken if s.until <= now -> {
                null
            }

            is CardStatus.Paused if s.until <= now -> {
                null
            }

            is CardStatus.Muted if s.until <= now -> {
                null
            }

            CardStatus.None -> {
                null
            }

            is CardStatus.Needed -> {
                if (s.lowSide) Look("carbs", w.eatCarbs, w.carbsShort, false) else Look("rapid", w.takeInsulin, w.insulinShort, false)
            }

            is CardStatus.Taken -> {
                left(s.treatment.id, s.until, now, w)
            }

            is CardStatus.Paused -> {
                val kind = if (s.lowSide) "carbs" else "rapid"
                if (s.until >= CardStatus.NEVER) Look(kind, w.muted, w.muted, true) else left(kind, s.until, now, w)
            }

            is CardStatus.Muted -> {
                if (s.until >= CardStatus.NEVER) {
                    Look(MUTED, w.muted, w.muted, true)
                } else {
                    Look(MUTED, w.mutedFor(leftMin(s.until, now)), w.muted, true)
                }
            }
        }

    /** The action pill on an ended sensor. */
    fun replaceSensor(w: CardWords) = Look("sensor", w.replaceSensor, w.replaceSensorShort, false)

    /** The pill over a scrolled chart. */
    fun backToNow(w: CardWords) = Look("now", w.backToNow, w.backToNow, false)

    /** For screen readers: the pill's full words (the alert runtime adds its own mute lines where it has them). */
    fun describe(
        s: CardStatus,
        now: Long,
        w: CardWords,
    ): String? = look(s, now, w)?.text

    /** Minutes left of a mute ending at [until]: partial minutes round up ("1m" to the end). */
    private fun leftMin(
        until: Long,
        now: Long,
    ) = ((until - now).coerceAtLeast(0) + 59) / 60

    private fun left(
        kind: String,
        until: Long,
        now: Long,
        w: CardWords,
    ) = Look(kind, w.timeLeft(leftMin(until, now)), w.shortDuration(until - now, up = true), true)

    /** Material Symbols "notifications_paused" and "notifications_off" (Apache 2.0). */
    private val bellPaused: Path by lazy {
        PathParser.createPathFromPathData(
            "M370-320h220v-72H480l110-136v-72H370v72h110L370-392v72ZM160-200v-80h80v-280q0-83 50-147.5T420-792v-28q0-25 " +
                "17.5-42.5T480-880q25 0 42.5 17.5T540-820v28q80 20 130 84.5T720-560v280h80v80H160Zm320-300Zm0 420q-33 0-56.5-" +
                "23.5T400-160h160q0 33-23.5 56.5T480-80ZM320-280h320v-280q0-66-47-113t-113-47q-66 0-113 47t-47 113v280Z",
        )
    }
    private val bellOff: Path by lazy {
        PathParser.createPathFromPathData(
            "M160-200v-80h80v-280q0-33 8.5-65t25.5-61l60 60q-7 16-10.5 32.5T320-560v280h248L56-792l56-56 736 736-56 56-146-" +
                "144H160Zm560-154-80-80v-126q0-66-47-113t-113-47q-26 0-50 8t-44 24l-58-58q20-16 43-28t49-18v-28q0-25 17.5-" +
                "42.5T480-880q25 0 42.5 17.5T540-820v28q80 20 130 84.5T720-560v206Zm-276-50Zm36 324q-33 0-56.5-23.5T400-160h160" +
                "q0 33-23.5 56.5T480-80Zm33-481Z",
        )
    }

    private fun bell(
        cv: Canvas,
        x: Float,
        y: Float,
        size: Float,
        color: Int,
        off: Boolean,
    ) {
        val p = Path(if (off) bellOff else bellPaused)
        p.transform(
            Matrix().apply {
                setTranslate(0f, 960f)
                postScale(size / 960f, size / 960f)
                postTranslate(x, y)
            },
        )
        cv.drawPath(p, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
    }

    private class Paints(
        val bg: Int,
        val fg: Int,
        val text: Paint,
    )

    private fun paints(
        look: Look,
        h: Float,
        dark: Boolean,
    ): Paints {
        val c = TreatmentMarks.color(TreatmentMarks.palette(dark), look.kind)
        val bg = if (look.tonal) ColorUtils.setAlphaComponent(c, if (dark) 0x3D else 0x2E) else c
        val fg = if (look.tonal) c else TreatmentMarks.onColor(dark)
        val text =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = fg
                textSize = h * 0.56f
                typeface = Typeface.create(Typeface.DEFAULT, 600, false)
            }
        return Paints(bg, fg, text)
    }

    /** Width of the pill [h] px tall with its full ([short] = short) words. */
    internal fun width(
        look: Look,
        h: Float,
        dark: Boolean,
        short: Boolean = false,
    ): Float = h * 0.4f + h * 0.62f + h * 0.25f + paints(look, h, dark).text.measureText(if (short) look.short else look.text) + h * 0.45f

    /**
     * Draw the pill [h] px tall at ([x], [y]), at most [maxW] wide: full words, short words, or only the icon in a
     * circle. Returns the width used, 0 if not even the circle fits.
     */
    internal fun draw(
        cv: Canvas,
        x: Float,
        y: Float,
        h: Float,
        maxW: Float,
        look: Look,
        dark: Boolean,
    ): Float {
        val p = paints(look, h, dark)
        val words =
            when {
                width(look, h, dark) <= maxW -> look.text
                width(look, h, dark, short = true) <= maxW -> look.short
                h <= maxW -> null
                else -> return 0f
            }
        val icon = h * 0.62f
        val w = if (words == null) h else h * 0.4f + icon + h * 0.25f + p.text.measureText(words) + h * 0.45f
        cv.drawRoundRect(RectF(x, y, x + w, y + h), h / 2, h / 2, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = p.bg })
        val ix = if (words == null) x + (h - icon) / 2 else x + h * 0.4f
        if (look.tonal) {
            bell(cv, ix, y + (h - icon) / 2, icon, p.fg, off = look.kind == MUTED)
        } else {
            TreatmentMarks.marker(cv, look.kind, ix + icon / 2, y + h / 2, icon * 0.42f, p.fg)
        }
        if (words != null) {
            val fm = p.text.fontMetrics
            cv.drawText(words, ix + icon + h * 0.25f, y + h / 2 - (fm.ascent + fm.descent) / 2, p.text)
        }
        return w
    }
}
