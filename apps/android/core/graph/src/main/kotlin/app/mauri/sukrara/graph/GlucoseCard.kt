// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.graph

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.core.graphics.ColorUtils
import app.mauri.sukrara.graph.stats.Bands
import app.mauri.sukrara.graph.stats.GlucosePoint
import app.mauri.sukrara.graph.stats.GlucoseStats
import kotlin.math.min

/**
 * THE card renderer ("One graph everywhere"): value + trend arrow + badges + slots + chart in one drawing. Every card
 * surface ([CardSurface]) draws through [draw] (or [image]), choosing only its size, its place's [CardStyle] and the
 * [CardPart]; every glyph surface takes the value's decision from [face]. The layout adapts to the size: slots that
 * don't fit are dropped (last first), short wide cards put the value left of the chart.
 */
object GlucoseCard {
    /** A slot is only drawn when its window has at least this much data. */
    const val MIN_COVERAGE = 0.25

    /** Tests: every [CardLayout] drawn and every [GlucoseLook] decided, as it happens (null in the app). */
    @Volatile var observer: ((CardLayout) -> Unit)? = null

    @Volatile var faceObserver: ((CardSurface, GlucoseLook) -> Unit)? = null

    /**
     * Draw [part] of the card [model] for [surface] into ([w] × [h]) px at the canvas origin ([d] = px per dp) and
     * return what was laid out. [motion] = the app panel's value animation (paint only); [view] = a scrolled / zoomed
     * chart.
     */
    fun draw(
        cv: Canvas,
        w: Float,
        h: Float,
        model: CardModel,
        st: CardStyle,
        pal: GraphPalette,
        d: Float,
        surface: CardSurface,
        part: CardPart,
        motion: CardMotion? = null,
        view: ChartView? = null,
    ): CardLayout {
        val rec = CardLayout(surface, part, st, w, h, d)
        val nowS = model.nowS
        val latest = model.latest
        val last = if (st.last != LastPlace.OFF) LastTreatment.of(model.treatments, model.muteRule, model.status, nowS) else null
        val fr = Freshness.of(latest?.let { nowS - it.t })
        val sl = SensorBadge.look(model.sensor, nowS, latest?.t, model.words)
        val graph = st.graph?.takeIf { fr.showGraph }?.let { g -> view?.spanS?.let { g.copy(windowS = it) } ?: g }
        val bands = st.bands.sane()
        val spot = sensorSpot(st, sl, fr)
        val countdown = SensorBadge.ring(sl, model.sensor, nowS, model.countdownHours)
        val value = ValueFace(model, fr, bands, pal, d, sl, rec, motion)
        if (part == CardPart.SLOTS) {
            drawSlots(cv, RectF(0f, 0f, w, h), st, last, model, bands, pal, d, countdown, rec, dedicated = true)
            return rec.also { observer?.invoke(it) }
        }
        val sideBySide = part == CardPart.ALL && graph != null && st.value && h < 100 * d && w > 2.6f * h
        // a chart card whose chart is gone (3 h+ / none yet): the value's face takes the chart's place, the slots stay
        // in the header's row when the card is tall enough
        val noChart = st.graph != null && graph == null && part == CardPart.ALL
        val rowH = (h * 0.34f).coerceIn(30 * d, 80 * d)
        val stacked = noChart && st.value && h - rowH - 6 * d >= 48 * d
        val headerH =
            when {
                part == CardPart.VALUE || part == CardPart.HEADER -> h
                part == CardPart.GRAPH || !st.value -> 0f
                stacked -> rowH
                graph == null || sideBySide -> h
                else -> rowH
            }
        val valueRight = if (sideBySide) min(w * 0.32f, 120 * d) else w
        val chart =
            when {
                sideBySide -> RectF(valueRight + 6 * d, 0f, w, h)
                part == CardPart.GRAPH -> RectF(0f, 0f, w, h)
                else -> RectF(0f, headerH + if (st.value) 6 * d else 0f, w, h)
            }
        // a card without a value still shows how old its data is (Freshness.AgeMark)
        val age = if (!st.value && part == CardPart.ALL && graph != null) fr.ageMark else Freshness.AgeMark.NONE
        if ((part == CardPart.ALL || part == CardPart.GRAPH) && graph != null) {
            val dimmed = if (age.dims) cv.saveLayerAlpha(null, (255 * Freshness.AgeMark.DIM_ALPHA).toInt()) else null
            val input =
                GlucoseGraph.Input(
                    model.readings,
                    model.treatments,
                    nowS,
                    model.muteRule?.let { r ->
                        r::muteUntil
                    },
                    model.words,
                    latest?.t,
                )
            GlucoseGraph.draw(cv, chart, input, graph, pal, d, sl.takeIf { spot == SensorSpot.GRAPH }, rec, view)
            dimmed?.let(cv::restoreToCount)
        }
        if (age != Freshness.AgeMark.NONE && latest != null) drawAgeLabel(cv, chart, latest.t, nowS, age, pal, d, rec)
        // a graph-only card without its chart: the value's face alone (a graph card has no slots or badge)
        if (noChart && !st.value) value.draw(cv, chart, null, null)
        if (st.value && part != CardPart.GRAPH) {
            val box = RectF(0f, 0f, valueRight, if (sideBySide) h else headerH)
            val slotsW =
                if (sideBySide ||
                    part == CardPart.VALUE
                ) {
                    0f
                } else {
                    drawSlots(cv, box, st, last, model, bands, pal, d, countdown, rec, dedicated = false)
                }
            val look = if (st.badge) StatusBadge.look(model.status, nowS, model.words) else null
            value.draw(cv, if (stacked) chart else RectF(box.left, box.top, box.right - slotsW, box.bottom), look, st.valueInk)
        }
        return rec.also { observer?.invoke(it) }
    }

    /**
     * A card without a value: the reading's time centred over the top of [chart] on a background pill, after the
     * "no readings" icon from 20 min ([age], freshness.json `graph_only`; GlucoseCard.swift `drawAgeLabel`).
     */
    private fun drawAgeLabel(
        cv: Canvas,
        chart: RectF,
        t: Long,
        nowS: Long,
        age: Freshness.AgeMark,
        pal: GraphPalette,
        d: Float,
        rec: CardLayout,
    ) {
        val text = Freshness.timeText(t, nowS)
        val size = (chart.height() * 0.11f).coerceIn(11 * d, 18 * d)
        val ink =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = pal.inkSoft
                typeface = RoundedFont.typeface(600)
                textSize = size
            }
        val icon = if (age == Freshness.AgeMark.NO_READINGS) size * 1.15f else 0f
        val gap = if (icon > 0) size * 0.3f else 0f
        val padX = size * 0.5f
        val padY = size * 0.25f
        val bw = icon + gap + ink.measureText(text) + 2 * padX
        val bh = size * 1.2f + 2 * padY
        val box = RectF(chart.centerX() - bw / 2, chart.top + 2 * d, chart.centerX() + bw / 2, chart.top + 2 * d + bh)
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ColorUtils.setAlphaComponent(pal.bg, (255 * 0.9f).toInt()) }
        cv.drawRoundRect(box, bh / 2, bh / 2, bg)
        if (icon > 0) Glyphs.drawSymbol(cv, Glyphs.noReadings, box.left + padX, box.centerY() - icon / 2, icon, pal.inkSoft)
        cv.drawText(text, box.left + padX + icon + gap, box.centerY() + size * 0.36f, ink)
        rec.add("graph.age", box, age.key)
        rec.add("time.text", info = text)
    }

    /** [draw] into a new bitmap of [wDp] × [hDp] dp at [d] px per dp (widgets, the notification, the lock screen). */
    fun image(
        wDp: Float,
        hDp: Float,
        d: Float,
        model: CardModel,
        st: CardStyle,
        pal: GraphPalette,
        surface: CardSurface,
        part: CardPart = CardPart.ALL,
    ): Bitmap {
        val bmp = Bitmap.createBitmap((wDp * d).toInt().coerceAtLeast(1), (hDp * d).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        draw(Canvas(bmp), bmp.width.toFloat(), bmp.height.toFloat(), model, st, pal, d, surface, part)
        return bmp
    }

    /** The y range (mg/dL) of [st]'s chart for the window ending at [endS]: a scrolling surface animates towards it. */
    fun yRange(
        readings: List<GlucosePoint>,
        endS: Long,
        st: GraphStyle,
    ): Pair<Float, Float> {
        val from = endS - st.windowS
        return GlucoseGraph.yRange(GlucoseStats.window(readings, from - 60, endS), from, st.bands.sane())
    }

    /**
     * The value's decision for the newest reading [latest] for [surface]: the card's number and every glyph surface
     * take it from here ([Freshness.look]: a warming-up or ended sensor first, then the freshness steps).
     */
    fun face(
        latest: GlucosePoint?,
        nowS: Long,
        sensor: SensorBadge.Look?,
        surface: CardSurface,
    ): GlucoseLook =
        Freshness
            .look(latest?.sgv, latest?.t, latest?.rate, nowS, ended = sensor?.ended == true, warmup = sensor?.warmup)
            .also { f -> faceObserver?.invoke(surface, f) }

    /**
     * Where the sensor's time left goes on a card [st] (the WHOLE card, also when drawn in parts): in the chart's
     * hour-label row whenever the card shows a chart with hour labels; nowhere else, nor when the place hides it,
     * nothing is known, or the sensor warms up / has ended (the value's place shows those). contracts/graph/place.
     */
    fun sensorSpot(
        st: CardStyle,
        look: SensorBadge.Look?,
        fr: Freshness,
    ): SensorSpot =
        when {
            look == null || !st.sensorTime || look.warmup != null || look.ended -> SensorSpot.NONE
            st.graph != null && fr.showGraph && st.graph.axisLabels -> SensorSpot.GRAPH
            else -> SensorSpot.NONE
        }

    /**
     * Slots right-aligned in [box] (main rightmost); returns the width used. Beside the value ([dedicated] false) at
     * most 55 % of the row; in a box of their own as many as fit whole. [CardStyle.slotDisc]: each slot on a disc.
     */
    private fun drawSlots(
        cv: Canvas,
        box: RectF,
        st: CardStyle,
        last: LastTreatment.Info?,
        model: CardModel,
        bands: Bands,
        pal: GraphPalette,
        d: Float,
        countdown: Double?,
        rec: CardLayout,
        dedicated: Boolean,
    ): Float {
        val extra = st.last != LastPlace.OFF && (last != null || st.lastElse != null)
        if (st.rings.isEmpty() && !extra) return 0f
        val size = if (st.slotDisc != null) min(box.height(), 84 * d) else min(box.height() * 0.96f, 84 * d)
        if (size < 30 * d) return 0f
        val gap = if (st.slotDisc != null) 6 * d else size * 0.14f
        val fit = ((box.width() * (if (dedicated) 1f else 0.55f) + gap) / (size + gap)).toInt().coerceAtMost(4)
        if (fit < 1) return 0f
        val rings =
            st.rings.take(3).mapIndexedNotNull { i, spec ->
                model.metrics(spec, bands).takeIf { it.tir.coverage >= MIN_COVERAGE }?.let { Triple(i, spec, it) }
            }
        val fallback =
            if (st.last != LastPlace.OFF && last == null) {
                st.lastElse?.let { s -> model.metrics(s, bands).takeIf { it.tir.coverage >= MIN_COVERAGE }?.let { s to it } }
            } else {
                null
            }
        var items = Slots.arrange(rings, st.last, last, fallback, fit)
        // in a box of their own every slot fits whole, the laurels' extra room included
        if (dedicated) {
            while (items.isNotEmpty() &&
                items.size * size + (items.size - 1) * gap + Slots.extraWidth(items, size / 2) > box.width() + 0.5f
            ) {
                items = Slots.arrange(rings, st.last, last, fallback, items.size - 1)
            }
        }
        if (items.isEmpty()) return 0f
        val main = Slots.mainIndex(items)
        val cx = Slots.centers(items, box.right, size, gap)
        val cy = box.centerY()
        val disc = st.slotDisc
        val marked = items.any(Slots::hasLaurel)
        val ringR =
            if (disc == null) {
                size / 2
            } else if (marked) {
                size / 2 / 1.5f
            } else {
                size / 2 - 3 * d
            }
        Slots.drawUnder(cv, items, cx, cy, ringR, pal)
        for ((i, item) in items.withIndex()) {
            if (disc !=
                null
            ) {
                cv.drawCircle(cx[i], cy, if (marked) ringR + 3 * d else size / 2, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = disc })
            }
            val ring = countdown.takeIf { i == main }
            Slots.draw(cv, item, cx[i], cy, ringR, model.nowS, pal, d, model.words, ring)
            rec.add(
                "slot",
                RectF(cx[i] - ringR, cy - ringR, cx[i] + ringR, cy + ringR),
                Slots.describe(item, i == main, ring),
                exact = true,
            )
        }
        rec.add("slots", RectF(cx.last() - size / 2, box.top, box.right, box.bottom), "${items.size}", exact = true)
        rec.add("slots.box", box, exact = true)
        // a laurel on the leftmost slot reaches past its ring: what is left of the slots keeps clear of it too
        return box.right - (cx.last() - size / 2) + gap + Slots.leftInset(items, ringR)
    }
}
