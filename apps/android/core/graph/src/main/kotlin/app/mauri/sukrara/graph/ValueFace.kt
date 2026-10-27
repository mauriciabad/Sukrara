// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.graph

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import app.mauri.sukrara.graph.stats.Bands
import kotlin.math.max
import kotlin.math.min

/**
 * The value's place on a card: the number + trend arrow and under it the late time and / or the status pill; or,
 * instead of a value, the warm-up, "Sensor ended" with its "Replace sensor" pill, or "No reading since" with the
 * "no readings" icon. One per [GlucoseCard.draw] call.
 */
internal class ValueFace(
    private val model: CardModel,
    private val fr: Freshness,
    private val bands: Bands,
    private val pal: GraphPalette,
    private val d: Float,
    private val sensor: SensorBadge.Look?,
    private val rec: CardLayout,
    private val motion: CardMotion?,
) {
    private val w = model.words
    private val nowS = model.nowS

    /** Draw into [box] with the status pill [look] (null = none) and the value in [ink] (null = its range colour). */
    fun draw(
        cv: Canvas,
        box: RectF,
        look: StatusBadge.Look?,
        ink: Int?,
    ) {
        val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink ?: pal.inkSoft; textSize = max(11 * d, box.height() * 0.17f) }
        val face = GlucoseCard.face(model.latest, nowS, sensor, rec.surface)
        when (face.state) {
            GlucoseLook.State.WARMING_UP -> drawWarmup(cv, box, checkNotNull(sensor), small)
            // an ended sensor says so as soon as it has ended, instead of a value or "No reading since" (not an error)
            GlucoseLook.State.ENDED -> drawEnded(cv, box)
            GlucoseLook.State.NO_READINGS, GlucoseLook.State.NONE -> drawNone(cv, box, face, small)
            GlucoseLook.State.VALUE -> drawNumber(cv, box, face, look, ink, small)
        }
    }

    /** "Sensor ended" in the secondary colour and under it the "Replace sensor" pill (icon-only beside it when short). */
    private fun drawEnded(
        cv: Canvas,
        box: RectF,
    ) {
        val pill = StatusBadge.replaceSensor(w)
        val badgeH = (box.height() * 0.24f).coerceIn(14 * d, 24 * d)
        val row = box.height() - badgeH * 1.25f >= 20 * d
        val textBox = if (row) RectF(box.left, box.top, box.right, box.bottom - badgeH * 1.25f) else box
        val p =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = pal.inkSoft
                typeface = Typeface.create(Typeface.DEFAULT, 600, false)
                textSize = min(textBox.height() * 0.5f, 20 * d)
            }
        val iconRoom = if (row) 0f else badgeH * 1.3f

        // the checkered flag before the words: the status bar's "sensor ended" glyph
        fun flagW() = p.textSize * 1.25f
        val tw = p.measureText(w.sensorEnded) + flagW()
        if (tw > box.width() - iconRoom) p.textSize *= ((box.width() - iconRoom) / tw).coerceAtLeast(0.45f)
        val base = textBox.centerY() + p.textSize * 0.36f
        val fs = p.textSize * 1.2f
        SensorBadge.flag(cv, box.left - fs * 0.2f, base - p.textSize * 0.72f - (fs - p.textSize * 0.72f) / 2, fs, pal.inkSoft)
        val textW = flagW() + p.measureText(w.sensorEnded)
        cv.drawText(w.sensorEnded, box.left + flagW(), base, p)
        rec.add("value.ended", RectF(box.left, textBox.top, box.left + textW, textBox.bottom))
        if (row) {
            val top = textBox.bottom + badgeH * 0.25f
            val pw = StatusBadge.draw(cv, box.left, top, badgeH, box.width(), pill, pal.dark)
            rec.add("value.pill", RectF(box.left, top, box.left + pw, top + badgeH), "[row]")
        } else {
            val x = box.left + textW + badgeH * 0.3f
            if (x + badgeH <= box.right) {
                StatusBadge.draw(cv, x, box.centerY() - badgeH / 2, badgeH, badgeH, pill, pal.dark)
                rec.add("value.pill", RectF(x, box.centerY() - badgeH / 2, x + badgeH, box.centerY() + badgeH / 2), "[icon]")
            } else {
                rec.add("value.pill", info = "(no room)")
            }
        }
    }

    /** A new sensor warming up: its time left where the number goes, the disc filling beside it, "Ready at …" under it. */
    private fun drawWarmup(
        cv: Canvas,
        box: RectF,
        look: SensorBadge.Look,
        small: Paint,
    ) {
        val s = look.warmup ?: return
        val line = look.warmupLine(nowS, w)
        val numBox = RectF(box.left, box.top, box.right, box.bottom - small.textSize * 1.25f)
        val num = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pal.inkSoft; typeface = RoundedFont.typeface(700); textSize = numBox.height() * 1.18f }
        val need = num.measureText(s) + num.textSize * 0.6f
        if (need > box.width()) num.textSize *= box.width() / need
        val capH = num.textSize * ValueGlyphs.CAP
        val cy = numBox.centerY()
        val baseY = cy + capH / 2
        cv.drawText(s, box.left - num.textSize * 0.04f, baseY, num)
        val dh = capH * 0.5f
        SensorBadge.disc(cv, box.left + num.measureText(s) + num.textSize * 0.12f, cy - dh / 2, dh, look, pal)
        val t = Paint(small)
        if (t.measureText(line) > box.width()) t.textSize *= box.width() / t.measureText(line)
        cv.drawText(line, box.left, baseY + small.textSize * 1.25f, t)
        rec.add("value.warmup", RectF(box.left, cy - capH / 2, box.left + need, cy + capH / 2), s)
        if (!look.waiting(nowS)) rec.add("time.text", info = line)
    }

    /** No reading yet / none for 20 min: the "no readings" icon (only with a last reading) and the words. */
    private fun drawNone(
        cv: Canvas,
        box: RectF,
        face: GlucoseLook,
        small: Paint,
    ) {
        val r = model.latest
        val big = Paint(small).apply { textSize = min(box.height() * 0.26f, 20 * d); typeface = Typeface.create(Typeface.DEFAULT, 600, false) }
        val line1 = if (r == null) w.noReadingsYet else w.noReadingSince
        val line2 = r?.let { Freshness.timeText(it.t, nowS) }

        fun iconW() = if (r != null) big.textSize * (2.1f + 0.35f) else 0f
        val widest = listOfNotNull(line1, line2).maxOf { big.measureText(it) }
        if (widest + iconW() > box.width() - 8 * d && widest > 0) big.textSize *= max(0.4f, (box.width() - 8 * d) / (widest + iconW()))
        val y = box.centerY() - if (line2 == null) -big.textSize / 3 else big.textSize * 0.15f
        val icon = if (r != null) big.textSize * 2.1f else 0f
        if (r != null) Glyphs.drawSymbol(cv, Glyphs.noReadings, box.left, y - big.textSize * 0.95f, icon, pal.inkSoft)
        val x = box.left + if (r != null) icon + big.textSize * 0.35f else 0f
        cv.drawText(line1, x, y, big)
        if (line2 != null) {
            cv.drawText(line2, x, y + big.textSize * 1.25f, big)
            rec.add("time.text", info = line2)
        }
        rec.add("value.none", RectF(box.left, y - big.textSize, box.right, y + (if (line2 != null) big.textSize * 1.25f else 0f)), face.state.name)
    }

    /**
     * The number + arrow (or the "no trend" ring), and under it the pill and / or the late time. The pill takes a row
     * when the number keeps at least 26 dp; else its icon goes beside the arrow, or it is left out.
     */
    private fun drawNumber(
        cv: Canvas,
        box: RectF,
        face: GlucoseLook,
        look: StatusBadge.Look?,
        ink: Int?,
        small: Paint,
    ) {
        val r = checkNotNull(model.latest)
        // an old value is always grey: no surface ink or animation colour can make it look current
        val color = if (fr.strike) pal.inkSoft else ink ?: motion?.color ?: pal.at(bands, r.sgv)
        rec.add(
            "value.color",
            info =
                when {
                    fr.strike -> "grey"
                    r.sgv >= bands.extremelyHigh -> "EXTREMELY_HIGH"
                    else -> bands.of(r.sgv).name
                },
            exact = true,
        )
        val badgeH = (box.height() * 0.24f).coerceIn(14 * d, 24 * d)
        val badgeRow = look != null && box.height() - badgeH * 1.25f - (if (fr.showTime) small.textSize * 1.25f else 0f) >= 26 * d
        val timeH = if (badgeRow) badgeH * 1.25f else if (fr.showTime) small.textSize * 1.25f else 0f
        val numBox = RectF(box.left, box.top, box.right, box.bottom - timeH)
        val num =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color
                typeface = RoundedFont.typeface(700)
                textSize = numBox.height() * 1.18f
                isStrikeThruText = fr.strike
            }
        val s = checkNotNull(face.text)

        // fit the width too: number + arrow stay left of the slots
        fun need() = num.measureText(s) + num.textSize * ValueGlyphs.MARK_BOX * 1.1f
        repeat(3) { need().let { n -> if (n > box.width()) num.textSize *= box.width() / n * 0.995f } }
        val iconBadge =
            look != null && !badgeRow &&
                box.width() - (num.measureText(s) + num.textSize * ValueGlyphs.MARK_BOX * 1.2f) >= min(numBox.height() * 0.5f, 24 * d)
        val capH = num.textSize * ValueGlyphs.CAP
        val cy = numBox.centerY()
        val baseY = cy + capH / 2
        val x0 = box.left - num.textSize * 0.04f
        val prev = motion?.prev
        if (prev != null && prev != s && motion.roll < 1f) rollNumber(cv, s, prev, motion, x0, baseY, capH, num) else cv.drawText(s, x0, baseY, num)
        rec.add("value.number", RectF(box.left, cy - capH / 2, box.left + num.measureText(s), cy + capH / 2), if (fr.strike) "$s struck" else s)
        val x = box.left + num.measureText(s) + num.textSize * ValueGlyphs.MARK_GAP
        val arrowSize = num.textSize * ValueGlyphs.MARK_BOX
        val markBox = RectF(x, cy - arrowSize / 2, x + arrowSize, cy + arrowSize / 2)
        val rate = r.rate
        if (face.trend == TrendMark.ARROW && rate != null) {
            cv.save()
            cv.translate(x + arrowSize / 2, cy)
            cv.rotate(motion?.arrowDeg ?: TrendAngle.deg(rate))
            ValueGlyphs.arrow(cv, arrowSize * ValueGlyphs.ARROW_IN_BOX, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }, TrendAngle.isDouble(rate))
            cv.restore()
            rec.add("value.trend", markBox, if (TrendAngle.isDouble(rate)) "arrow double" else "arrow single")
        } else {
            // no trend (no rate, or struck through): the hollow ring in the arrow's place
            cv.drawCircle(
                x + arrowSize / 2,
                cy,
                TrendMark.ringRadius(arrowSize),
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    this.color = color
                    style = Paint.Style.STROKE
                    strokeWidth = TrendMark.ringStroke(arrowSize)
                },
            )
            rec.add("value.trend", markBox, "ring")
        }
        val time = if (fr.showTime) Freshness.timeText(r.t, nowS) else null
        if (badgeRow && look != null) {
            val top = numBox.bottom + badgeH * 0.25f
            val bw = StatusBadge.draw(cv, box.left, top, badgeH, box.width(), look, pal.dark)
            rec.add("value.status", RectF(box.left, top, box.left + bw, top + badgeH), "${look.kind} [row]")
            if (time != null) {
                // the late time shares the pill's row
                val t = Paint(small).apply { textSize = badgeH * 0.56f }
                val tx = box.left + bw + badgeH * 0.35f
                if (tx + t.measureText(time) <= box.right) {
                    cv.drawText(time, tx, top + badgeH / 2 + t.textSize * 0.36f, t)
                    rec.add("value.time", RectF(tx, top, tx + t.measureText(time), top + badgeH), "[badge row]")
                    rec.add("time.text", info = time)
                } else {
                    rec.add("value.time", info = "[badge row] (no room)")
                }
            }
            return
        }
        if (iconBadge && look != null) {
            val ih = min(numBox.height() * 0.5f, 24 * d)
            val ix = x + arrowSize * 1.15f
            StatusBadge.draw(cv, ix, cy - ih / 2, ih, ih, look, pal.dark)
            rec.add("value.status", RectF(ix, cy - ih / 2, ix + ih, cy + ih / 2), "${look.kind} [icon]")
        } else if (look != null) {
            rec.add("value.status", info = "${look.kind} (no room)")
        }
        if (time != null) {
            cv.drawText(time, box.left, baseY + small.textSize * 1.25f, small)
            rec.add("value.time", RectF(box.left, baseY, box.left + small.measureText(time), baseY + small.textSize * 1.25f), "[own row]")
            rec.add("time.text", info = time)
        }
    }

    /** Each digit that changed slides out and the new one in (up for a rising value), clipped to the number's line. */
    private fun rollNumber(
        cv: Canvas,
        s: String,
        prev: String,
        m: CardMotion,
        x0: Float,
        baseY: Float,
        capH: Float,
        num: Paint,
    ) {
        val lineH = capH * 1.6f
        val dir = if (m.up) 1f else -1f
        val e = m.roll.coerceIn(0f, 1f)
        cv.save()
        cv.clipRect(x0 - capH, baseY - capH * 1.3f, x0 + num.measureText(s) + capH, baseY + capH * 0.3f)
        val old = Paint(num)
        for (i in s.indices) {
            val x = x0 + num.measureText(s, 0, i)
            val o = prev.getOrNull(prev.length - (s.length - i))
            if (o == s[i]) {
                cv.drawText(s[i].toString(), x, baseY, num)
                continue
            }
            cv.drawText(s[i].toString(), x, baseY + (1 - e) * lineH * dir, Paint(num).apply { alpha = (255 * e).toInt() })
            if (o != null) {
                old.alpha = (255 * (1 - e)).toInt()
                cv.drawText(o.toString(), x, baseY - e * lineH * dir, old)
            }
        }
        cv.restore()
    }
}
