// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import CoreGraphics
import CoreText
import Foundation
import SukraraCore

// MARK: - Card

/// Value + trend arrow + rings + chart in one drawing (GlucoseCard.kt), adapting to the size.
public enum GlucoseCard {
    static let minCoverage = 0.25

    /// Draw `part` of the card `st` for `surface` and return what was laid out. `metricsOf` gives a slot's numbers; by
    /// default computed from `readings` (enough for windows they cover). `alerts` = what the alert rules decided (the
    /// badge, the last-treatment slot, the mute lines), computed by the caller with the alert engine's rules.
    @discardableResult
    static func draw(
        _ ctx: CGContext, size: CGSize, readings: [GraphReading], marks: [GraphMark], now: TimeInterval,
        _ st: CardStyle, _ pal: GraphPalette, d: CGFloat = 1, surface: CardSurface, part: CardPart,
        metricsOf: ((RingSpec, Bands) -> GlucoseMetrics)? = nil, alerts: CardAlerts = .none,
        view: ChartView? = nil
    ) -> CardLayout {
        let w = size.width, h = size.height
        let rec = CardLayout(surface, part, st, w: w, h: h, d: d)
        let latest = readings.max { $0.t < $1.t }
        let status = alerts.status
        let last = st.last != .off ? alerts.last : nil
        let fr = Freshness.of(age: latest.map { now - $0.t })
        let sl = SensorBadge.look(st.sensor, now: now, latest: latest?.t)
        // a zoomed chart: the same style with the window's length the surface zoomed to
        var graph = fr.showGraph ? st.graph : nil
        if let s = view?.span { graph?.window = s }
        let bands = st.bands.sane()
        let metrics = metricsOf ?? { r, b in GlucoseMetrics.of(readings, from: now - r.window, to: now, bands: b) }
        let spot = sensorSpot(st, sl, fr)
        let countdown = SensorBadge.ring(sl, st.sensor, now: now, hours: st.countdownHours)
        if part == .slots {
            _ = drawRings(
                ctx, CGRect(x: 0, y: 0, width: w, height: h), st, last, now, bands, pal, d: d, metrics,
                countdown: countdown,
                rec: rec, dedicated: true)
            return rec
        }
        // one part in a box of its own: that box is the part's whole area
        let sideBySide = part == .all && graph != nil && st.value && h < 100 * d && w > 2.6 * h
        // A chart card with no chart (no reading for 3 h, none yet): the value's face takes the chart's place; a card tall
        // enough for a chart keeps the slots in the header's row and puts the face where the chart was (GlucoseCard.kt).
        let noChart = st.graph != nil && graph == nil && part == .all
        let rowH = min(max(h * 0.34, 30 * d), 80 * d)
        let stacked = noChart && st.value && h - rowH - 6 * d >= 48 * d
        let headerH: CGFloat =
            (part == .value || part == .header)
            ? h
            : (part == .graph || !st.value)
                ? 0
                : stacked ? rowH : (graph == nil || sideBySide) ? h : rowH
        let valueRight = sideBySide ? min(w * 0.32, 120 * d) : w

        let chart =
            sideBySide
            ? CGRect(x: valueRight + 6 * d, y: 0, width: w - valueRight - 6 * d, height: h)
            : part == .graph
                ? CGRect(x: 0, y: 0, width: w, height: h)
                : CGRect(
                    x: 0, y: headerH + (st.value ? 6 * d : 0), width: w, height: h - headerH - (st.value ? 6 * d : 0))
        if part == .all || part == .graph, let graph {
            GlucoseGraph.draw(
                ctx, chart, readings: readings, marks: marks, now: now, graph, pal, d: d, alerts: alerts,
                sensor: spot == .graph ? sl : nil, rec: rec, view: view, latestT: latest?.t)
        }
        // a graph-only card without its chart: the value's face alone
        if noChart && !st.value {
            drawValue(
                ctx, chart, latest, fr, now, bands, pal, d: d, look: nil, sensor: sl, unit: st.unit,
                rec: rec)
        }
        if st.value && part != .graph {
            let box = CGRect(x: 0, y: 0, width: valueRight, height: sideBySide ? h : headerH)
            let ringsW =
                sideBySide || part == .value
                ? 0
                : drawRings(
                    ctx, box, st, last, now, bands, pal, d: d, metrics, countdown: countdown, rec: rec, dedicated: false
                )
            let look = st.badge ? StatusBadge.look(status, now: now) : nil
            drawValue(
                ctx, stacked ? chart : CGRect(x: box.minX, y: box.minY, width: box.width - ringsW, height: box.height),
                latest, fr, now,
                bands, pal, d: d, look: look, sensor: sl, unit: st.unit, rec: rec)
        }
        return rec
    }

    /// The y range (mg/dL) the chart of `st` draws for the window ending at `end` (`GlucoseGraph.yRange`): a scrolling
    /// surface animates `ChartView.y` towards it, so the scale never jumps (GlucoseCard.kt `yRange`).
    static func yRange(_ readings: [GraphReading], end: TimeInterval, _ st: GraphStyle) -> ClosedRange<Double> {
        GlucoseGraph.yRange(GlucoseStats.window(readings, from: end - st.window - 60, to: end), bands: st.bands.sane())
    }

    /// The value's decision for the newest reading `latest` (GlucoseCard.face on Android): a warming-up or ended
    /// sensor says so first, then the reading's `Freshness` (struck when stale, gone when old).
    static func face(
        _ latest: GraphReading?, now: TimeInterval, sensor: SensorBadge.Look?,
        format: (Int) -> String = { "\($0)" }
    ) -> GlucoseLook {
        Freshness.look(
            sgv: latest?.sgv, t: latest?.t, rate: latest?.rate, now: now, ended: sensor?.ended == true,
            warmup: sensor?.warmup, format: format)
    }

    /// Where the sensor's time left goes on a card `st` (the WHOLE card, also when its parts are drawn in separate boxes):
    /// the one decision every surface uses (GlucoseCard.kt `sensorSpot`, both checked by spikes/graph-fixtures/place).
    /// In the chart's hour-label row whenever the card shows a chart with hour labels (with or without the % column)
    /// nowhere else: a card without a labelled chart shows just the number and its trend (the user, 2026-10-21: beside the
    /// arrow it turned "120 ○" into "120°"), nor when the place's setting hides it, nothing is known, or the sensor warms
    /// up / has ended (the value's place shows those states).
    static func sensorSpot(_ st: CardStyle, _ look: SensorBadge.Look?, _ fr: Freshness) -> SensorSpot {
        guard let look, st.sensorTime, look.warmup == nil, !look.ended else { return .none }
        if let g = st.graph, fr.showGraph, g.axisLabels { return .graph }
        return .none
    }

    /// Slots (`Slots.arrange`) right-aligned in `box` (main rightmost); returns the width used. Beside the value
    /// (`dedicated` false) they take at most 55 % of the row; in a box of their own (`.slots`) as many as fit whole.
    private static func drawRings(
        _ ctx: CGContext, _ box: CGRect, _ st: CardStyle, _ last: LastTreatment.Info?, _ now: TimeInterval,
        _ bands: Bands, _ pal: GraphPalette, d: CGFloat,
        _ metricsOf: (RingSpec, Bands) -> GlucoseMetrics, countdown: Double? = nil,
        rec: CardLayout, dedicated: Bool
    ) -> CGFloat {
        let extra = st.last != .off && (last != nil || st.lastElse != nil)
        guard !st.rings.isEmpty || extra else { return 0 }
        let size = min(box.height * 0.96, 84 * d)
        guard size >= 30 * d else { return 0 }
        let gap = size * 0.14
        let fit = min(Int((box.width * (dedicated ? 1 : 0.55) + gap) / (size + gap)), 4)
        guard fit >= 1 else { return 0 }
        let rings = st.rings.prefix(3).enumerated().compactMap { (i, spec) -> (Int, RingSpec, GlucoseMetrics)? in
            let m = metricsOf(spec, bands)
            return m.tir.coverage >= minCoverage ? (i, spec, m) : nil
        }
        let fallback: (RingSpec, GlucoseMetrics)? =
            st.last != .off && last == nil
            ? st.lastElse.flatMap { s in
                let m = metricsOf(s, bands); return m.tir.coverage >= minCoverage ? (s, m) : nil
            } : nil
        let x = box.maxX
        var items = Slots.arrange(rings, place: st.last, last: last, fallback: fallback, max: fit)
        // in a box of its own every slot fits whole, the laurels' extra room included
        if dedicated {
            while !items.isEmpty
                && CGFloat(items.count) * size + CGFloat(items.count - 1) * gap + Slots.extraWidth(items, r: size / 2)
                    > box.width + 0.5
            {
                items = Slots.arrange(rings, place: st.last, last: last, fallback: fallback, max: items.count - 1)
            }
        }
        guard !items.isEmpty else { return 0 }
        let main = Slots.mainIndex(items)
        let cx = Slots.centers(items, right: x, size: size, gap: gap)
        Slots.drawUnder(ctx, items, cx: cx, cy: box.midY, radius: size / 2, pal)
        for (i, item) in items.enumerated() {
            Slots.draw(
                ctx, item, center: CGPoint(x: cx[i], y: box.midY), radius: size / 2, now: now, pal, d: d,
                countdown: i == main ? countdown : nil)
            rec.add(
                "slot", CGRect(x: cx[i] - size / 2, y: box.midY - size / 2, width: size, height: size),
                Slots.describe(item, main: i == main, countdown: i == main ? countdown : nil))
        }
        rec.add(
            "slots", CGRect(x: cx.last! - size / 2, y: box.minY, width: x - (cx.last! - size / 2), height: box.height),
            "\(items.count)")
        rec.add("slots.box", box)
        // a laurel on the leftmost slot reaches past its ring: what is left of the slots keeps clear of it too
        return cx.last.map { x - ($0 - size / 2) + gap + Slots.leftInset(items, r: size / 2) } ?? 0
    }

    /// The number + arrow, and under it the late reading's time and/or the status badge (`look`). The badge takes a
    /// row under the number when the box is tall enough to keep the number at least 26 pt; otherwise it shrinks to its
    /// icon beside the arrow, or is left out when there's no room there either.
    private static func drawValue(
        _ ctx: CGContext, _ box: CGRect, _ r: GraphReading?, _ fr: Freshness, _ now: TimeInterval,
        _ bands: Bands, _ pal: GraphPalette, d: CGFloat, look: StatusBadge.Look?,
        sensor: SensorBadge.Look?, unit: GlucoseUnit, rec: CardLayout
    ) {
        let small = Ink.font(max(11 * d, box.height * 0.17), .regular)
        let face = face(r, now: now, sensor: sensor, format: unit.format)
        if face.state == .warmingUp, let sensor {
            return drawWarmup(ctx, box, sensor, small, now, pal, d: d, rec: rec)
        }
        // an ended sensor says so as soon as it has ended, instead of a value or "No reading since" (not an error)
        if face.state == .ended { return drawEnded(ctx, box, pal, d: d, rec: rec) }
        guard let r, face.shown, let s = face.text else {
            // Fit the longer line into the space left of the rings; no readings for 20 min: the menubar's "no readings"
            // symbol before the words, as tall as both lines.
            let lines =
                r.map { ["No reading since", Freshness.timeText($0.t, now: now)] } ?? [
                    "No readings yet"
                ]
            var size = min(box.height * 0.26, 20 * d)
            let iconW: (CGFloat) -> CGFloat = { r == nil ? 0 : $0 * 2.1 + $0 * 0.35 }
            let widest = lines.map { Ink.width($0, Ink.font(size, .semibold)) }.max() ?? 0
            if widest + iconW(size) > box.width - 8 * d, widest > 0 {
                size *= max(0.4, (box.width - 8 * d) / (widest + iconW(size)))
            }
            let big = Ink.font(size, .semibold)
            let y = box.midY - (lines.count == 1 ? -size / 3 : size * 0.15)
            if r != nil {
                _ = SensorBadge.symbol(
                    ctx, GlucoseLook.noReadingsSymbol, x: box.minX, y: y - size * 0.95, size: size * 2.1,
                    color: pal.inkSoft)
            }
            let x = box.minX + iconW(size)
            Ink.draw(ctx, lines[0], x: x, y: y, big, pal.inkSoft)
            if lines.count > 1 {
                Ink.draw(ctx, lines[1], x: x, y: y + size * 1.25, big, pal.inkSoft); rec.add("time.text", nil, lines[1])
            }
            rec.add(
                "value.none",
                CGRect(x: box.minX, y: y - size, width: box.width, height: size * CGFloat(lines.count) * 1.25),
                face.state.layoutName)
            return
        }
        let color = fr.strike ? pal.inkSoft : pal.at(bands, Double(r.sgv))
        // which colour, by name, for the cross-surface test (layout rules "value-color")
        rec.add(
            "value.color", nil,
            fr.strike ? "grey" : r.sgv >= bands.extremelyHigh ? "EXTREMELY_HIGH" : bands.of(Double(r.sgv)).name)
        // A late reading's time goes under the number (the user: beside it, it took the rings' room).
        let time = fr.showTime ? Freshness.timeText(r.t, now: now) : ""
        let smallH = fr.showTime ? CTFontGetSize(small) * 1.25 : 0
        let badgeH = min(max(box.height * 0.24, 14 * d), 24 * d)
        let badgeRow = look != nil && box.height - badgeH * 1.25 - smallH >= 26 * d
        // the late time shares the badge's row (after the badge) when there is one
        let timeH = badgeRow ? badgeH * 1.25 : smallH
        let numBox = CGRect(x: box.minX, y: box.minY, width: box.width, height: box.height - timeH)
        var numSize = numBox.height * 1.18
        let arrowFactor: CGFloat = 0.62
        func need() -> CGFloat { Ink.width(s, Ink.font(numSize, .bold, rounded: true)) + numSize * arrowFactor * 1.1 }
        for _ in 0..<3 { let n = need(); if n > box.width { numSize *= box.width / n * 0.995 } }
        let num = Ink.font(numSize, .bold, rounded: true)
        // no row for the badge: its icon goes beside the arrow if there's room
        let iconH = min(numBox.height * 0.5, 24 * d)
        let iconBadge =
            look != nil && !badgeRow && box.width - (Ink.width(s, num) + numSize * arrowFactor * 1.2) >= iconH
        let capH = CTFontGetCapHeight(num)
        let baseY = numBox.midY + capH / 2
        Ink.draw(ctx, s, x: box.minX - numSize * 0.04, y: baseY, num, color, strike: fr.strike)
        rec.add(
            "value.number", CGRect(x: box.minX, y: numBox.midY - capH / 2, width: Ink.width(s, num), height: capH),
            fr.strike ? "\(s) struck" : s)
        let x = box.minX + Ink.width(s, num) + numSize * 0.08
        let arrowSize = numSize * arrowFactor
        if face.trend == .arrow, let rate = r.rate {
            ctx.saveGState()
            ctx.translateBy(x: x + arrowSize / 2, y: numBox.midY)
            ctx.rotate(by: CGFloat(-max(-1, min(1, rate / 3))) * .pi / 2)
            drawArrow(ctx, scale: arrowSize * 1.05, color: color, double: abs(rate) >= 3)
            ctx.restoreGState()
            rec.add(
                "value.trend", CGRect(x: x, y: numBox.midY - arrowSize / 2, width: arrowSize, height: arrowSize),
                abs(rate) >= 3 ? "arrow double" : "arrow single")
        } else {
            // no trend (no rate, or the value is struck through): the hollow ring in the arrow's place
            ctx.setStrokeColor(color)
            ctx.setLineWidth(TrendMark.ringStroke(arrowSize))
            let rr = TrendMark.ringRadius(arrowSize)
            ctx.strokeEllipse(in: CGRect(x: x + arrowSize / 2 - rr, y: numBox.midY - rr, width: 2 * rr, height: 2 * rr))
            rec.add(
                "value.trend", CGRect(x: x, y: numBox.midY - arrowSize / 2, width: arrowSize, height: arrowSize), "ring"
            )
        }
        if badgeRow, let look {
            let top = numBox.maxY + badgeH * 0.25
            let bw = StatusBadge.draw(
                ctx, x: box.minX, y: top, h: badgeH, maxW: box.width, look, dark: pal.dark, knockout: pal.knockout)
            rec.add("value.status", CGRect(x: box.minX, y: top, width: bw, height: badgeH), "\(look.kind) [row]")
            if fr.showTime {
                let t = Ink.font(badgeH * 0.56, .regular)
                let tx = box.minX + bw + badgeH * 0.35
                if tx + Ink.width(time, t) <= box.maxX {
                    Ink.draw(ctx, time, x: tx, y: top + badgeH / 2 + badgeH * 0.56 * 0.36, t, pal.inkSoft)
                    rec.add(
                        "value.time", CGRect(x: tx, y: top, width: Ink.width(time, t), height: badgeH), "[badge row]")
                    rec.add("time.text", nil, time)
                } else {
                    rec.add("value.time", nil, "[badge row] (no room)")
                }
            }
        } else {
            if iconBadge, let look {
                StatusBadge.draw(
                    ctx, x: x + arrowSize * 1.15, y: numBox.midY - iconH / 2, h: iconH, maxW: iconH, look,
                    dark: pal.dark,
                    knockout: pal.knockout)
                rec.add(
                    "value.status",
                    CGRect(x: x + arrowSize * 1.15, y: numBox.midY - iconH / 2, width: iconH, height: iconH),
                    "\(look.kind) [icon]")
            } else if let look {
                rec.add("value.status", nil, "\(look.kind) (no room)")
            }
            if fr.showTime {
                Ink.draw(ctx, time, x: box.minX, y: baseY + CTFontGetSize(small) * 1.25, small, pal.inkSoft)
                rec.add(
                    "value.time",
                    CGRect(x: box.minX, y: baseY, width: Ink.width(time, small), height: CTFontGetSize(small) * 1.25),
                    "[own row]")
                rec.add("time.text", nil, time)
            }
        }
    }

    /// The sensor has ended: "Sensor ended" in the secondary colour (a state, not an error) and under it the action pill
    /// "Replace sensor" (StatusBadge.replaceSensor, the same pill as "Eat carbs": full, short or icon only as room
    /// allows); a box too short for the pill's row gets the icon-only pill beside the words (GlucoseCard.drawEnded).
    private static func drawEnded(_ ctx: CGContext, _ box: CGRect, _ pal: GraphPalette, d: CGFloat, rec: CardLayout) {
        let words = "Sensor ended"
        let badgeH = min(max(box.height * 0.24, 14 * d), 24 * d)
        let row = box.height - badgeH * 1.25 >= 20 * d
        let textBox = row ? CGRect(x: box.minX, y: box.minY, width: box.width, height: box.height - badgeH * 1.25) : box
        var size = min(textBox.height * 0.5, 20 * d)
        let iconRoom: CGFloat = row ? 0 : badgeH * 1.3
        // the checkered flag before the words: the menubar's "sensor ended" glyph, so the two read as one thing
        let tw = Ink.width(words, Ink.font(size, .semibold)) + size * 1.25
        if tw > box.width - iconRoom, tw > 0 { size *= max(0.45, (box.width - iconRoom) / tw) }
        let f = Ink.font(size, .semibold)
        let base = textBox.midY + size * 0.36, fs = size * 1.05, cap = CTFontGetCapHeight(f)
        SensorBadge.flag(ctx, x: box.minX, y: base - cap - (fs - cap) / 2, size: fs, color: pal.inkSoft)
        Ink.draw(ctx, words, x: box.minX + size * 1.25, y: base, f, pal.inkSoft)
        rec.add(
            "value.ended",
            CGRect(x: box.minX, y: textBox.minY, width: size * 1.25 + Ink.width(words, f), height: textBox.height))
        if row {
            let pw = StatusBadge.draw(
                ctx, x: box.minX, y: textBox.maxY + badgeH * 0.25, h: badgeH, maxW: box.width,
                StatusBadge.replaceSensor,
                dark: pal.dark, knockout: pal.knockout)
            rec.add(
                "value.pill", CGRect(x: box.minX, y: textBox.maxY + badgeH * 0.25, width: pw, height: badgeH), "[row]")
        } else {
            let x = box.minX + size * 1.25 + Ink.width(words, f) + badgeH * 0.3
            if x + badgeH <= box.maxX {
                _ = StatusBadge.draw(
                    ctx, x: x, y: box.midY - badgeH / 2, h: badgeH, maxW: badgeH, StatusBadge.replaceSensor,
                    dark: pal.dark, knockout: pal.knockout)
                rec.add("value.pill", CGRect(x: x, y: box.midY - badgeH / 2, width: badgeH, height: badgeH), "[icon]")
            } else {
                rec.add("value.pill", nil, "(no room)")
            }
        }
    }

    /// A new sensor warming up: the time left ("42m") where the number goes, in the secondary colour, the sensor disc
    /// beside it filling up, and "Ready at 19:45" under it (GlucoseCard.drawWarmup on Android).
    private static func drawWarmup(
        _ ctx: CGContext, _ box: CGRect, _ look: SensorBadge.Look, _ small: CTFont, _ now: TimeInterval,
        _ pal: GraphPalette, d: CGFloat, rec: CardLayout
    ) {
        guard let s = look.warmup else { return }
        let line = look.warmupLine(now: now)
        let numBox = CGRect(
            x: box.minX, y: box.minY, width: box.width, height: box.height - CTFontGetSize(small) * 1.25)
        var numSize = numBox.height * 1.18
        let need = Ink.width(s, Ink.font(numSize, .bold, rounded: true)) + numSize * 0.6
        if need > box.width { numSize *= box.width / need }
        let num = Ink.font(numSize, .bold, rounded: true)
        let capH = CTFontGetCapHeight(num)
        let baseY = numBox.midY + capH / 2
        Ink.draw(ctx, s, x: box.minX - numSize * 0.04, y: baseY, num, pal.inkSoft)
        let dh = capH * 0.5
        SensorBadge.draw(
            ctx, x: box.minX + Ink.width(s, num) + numSize * 0.12, y: numBox.midY - dh / 2, h: dh, look, pal,
            label: false)
        var t = small
        let lw = Ink.width(line, t)
        if lw > box.width, lw > 0 { t = Ink.font(CTFontGetSize(small) * box.width / lw, .regular) }
        Ink.draw(ctx, line, x: box.minX, y: baseY + CTFontGetSize(small) * 1.25, t, pal.inkSoft)
        if !look.waiting(now: now) { rec.add("time.text", nil, line) }
        rec.add("value.warmup", CGRect(x: box.minX, y: numBox.midY - capH / 2, width: need, height: capH), s)
    }

    /// Same geometry as GlucoseNotification.drawArrow / the menubar TrendArrow: unit arrow pointing +x.
    static func drawArrow(_ ctx: CGContext, scale s: CGFloat, color: CGColor, double: Bool) {
        ctx.setFillColor(color)
        ctx.fill(CGRect(x: -0.42 * s, y: -0.07 * s, width: 0.67 * s, height: 0.14 * s))
        func head(_ tip: CGFloat) {
            let p = CGMutablePath()
            p.move(to: CGPoint(x: tip * s, y: 0)); p.addLine(to: CGPoint(x: (tip - 0.40) * s, y: 0.30 * s))
            p.addLine(to: CGPoint(x: (tip - 0.30) * s, y: 0)); p.addLine(to: CGPoint(x: (tip - 0.40) * s, y: -0.30 * s))
            p.closeSubpath()
            ctx.addPath(p); ctx.fillPath()
        }
        head(0.46)
        if double { head(0.12) }
    }

    /// The card as a bitmap (widgets, PNG evidence): `scale` px per point, y-down.
    /// `overflow`: room around the card in the picture (it is `overflow` larger on every side) for what overflows the
    /// card, such as a ring's laurel or crown; the card itself keeps `size`.
    static func image(
        size: CGSize, scale: CGFloat = 2, readings: [GraphReading], marks: [GraphMark], now: TimeInterval,
        _ st: CardStyle, _ pal: GraphPalette, background: CGColor? = nil, surface: CardSurface,
        metricsOf: ((RingSpec, Bands) -> GlucoseMetrics)? = nil, alerts: CardAlerts = .none,
        overflow: CGFloat = 0
    ) -> CGImage? {
        let pw = max(16, Int((size.width + 2 * overflow) * scale)),
            ph = max(16, Int((size.height + 2 * overflow) * scale))
        guard
            let ctx = CGContext(
                data: nil, width: pw, height: ph, bitsPerComponent: 8, bytesPerRow: 0,
                space: CGColorSpace(name: CGColorSpace.sRGB)!,
                bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)
        else { return nil }
        ctx.translateBy(x: 0, y: CGFloat(ph))
        ctx.scaleBy(x: scale, y: -scale)
        ctx.translateBy(x: overflow, y: overflow)
        if let background { ctx.setFillColor(background); ctx.fill(CGRect(origin: .zero, size: size)) }
        draw(
            ctx, size: size, readings: readings, marks: marks, now: now, st, pal, surface: surface, part: .all,
            metricsOf: metricsOf,
            alerts: alerts)
        return ctx.makeImage()
    }
}

/// A believable history (meals with bolus, dawn rise, an afternoon dip, noise), seeded: the same picture every run.
/// Port of the Android gallery's Synthetic (GraphGallery.kt), for widget previews and evidence renders.

// MARK: - Public entry points (every Mac surface draws and words the card only through these)

/// What a card shows at `now`: the history (oldest first is not required), the treatments, the sensor and what the
/// alert rules decided. Never made-up data on a release build: the app fills it from its stores only.
public struct CardData: Sendable, Equatable, Codable {
    public var readings: [GraphReading]
    public var marks: [GraphMark]
    public var sensor: SensorInfo?
    public var alerts: CardAlerts
    public var now: TimeInterval

    public init(
        readings: [GraphReading], marks: [GraphMark] = [], sensor: SensorInfo? = nil, alerts: CardAlerts = .none,
        now: TimeInterval
    ) {
        self.readings = readings
        self.marks = marks
        self.sensor = sensor
        self.alerts = alerts
        self.now = now
    }

    /// The newest reading, or nil.
    public var latest: GraphReading? { readings.max { $0.t < $1.t } }
}

extension GlucoseCard {
    /// What a surface shows while there is no reading to show (never a made-up value).
    public static let noValueText = "—"

    /// `surface`'s card at `size` (points) as a bitmap, `scale` px per point, with the app's graph settings `config`
    /// and the placement's `overrides`. `tinted` = a tinted widget rendering mode (accented / vibrant). `overflow`: room
    /// around the card for what overflows it (a ring's laurel or crown).
    public static func image(
        surface: CardSurface, size: CGSize, config: GraphConfig, overrides: PlaceOverrides = PlaceOverrides(),
        data: CardData, unit: GlucoseUnit, dark: Bool, tinted: Bool = false, scale: CGFloat = 2, overflow: CGFloat = 0
    ) -> CGImage? {
        let palette = GraphPalette.of(dark: dark)
        var style = surface.style(config, overrides, sensor: data.sensor)
        style.unit = unit
        return image(
            size: size, scale: scale, readings: data.readings, marks: data.marks, now: data.now, style,
            tinted ? palette.tinted() : palette,
            surface: surface, alerts: data.alerts, overflow: overflow)
    }

    /// Draws `part` of `surface`'s card into `ctx` (y-down, points) at `size`: for surfaces that paint into a view.
    public static func draw(
        surface: CardSurface, in ctx: CGContext, size: CGSize, part: CardPart = .all, config: GraphConfig,
        overrides: PlaceOverrides = PlaceOverrides(), data: CardData, unit: GlucoseUnit, dark: Bool
    ) {
        var style = surface.style(config, overrides, sensor: data.sensor)
        style.unit = unit
        draw(
            ctx, size: size, readings: data.readings, marks: data.marks, now: data.now, style,
            GraphPalette.of(dark: dark), surface: surface,
            part: part, alerts: data.alerts)
    }

    /// The value's decision for text places (the menu bar title, notifications): the state, the value in `unit`, the
    /// strike and the trend mark, from the same rule the card draws with.
    /// `surface` = the registered place asking (contracts/graph/surfaces/surfaces.json; tools/check `card-surface`).
    public static func face(_ data: CardData, unit: GlucoseUnit, surface: CardSurface) -> GlucoseLook {
        let latest = data.latest
        return face(
            latest, now: data.now, sensor: SensorBadge.look(data.sensor, now: data.now, latest: latest?.t),
            format: unit.format)
    }

    /// What VoiceOver says for the card's value: "Glucose 112 milligrams per decilitre, falling, 2 min ago" (the
    /// time once the reading is late), "Glucose 141 milligrams per decilitre (old), trend unknown, at 14:05", "Glucose:
    /// no reading since 14:05", "Glucose: no readings yet", "Warming up", "Sensor ended. Replace sensor"; then the
    /// alert badge's sentence when there is one.
    public static func accessibilityLabel(_ data: CardData, unit: GlucoseUnit, surface: CardSurface) -> String {
        let look = face(data, unit: unit, surface: surface)
        let value: String
        switch look.state {
        case .ended: return "Sensor ended. \(StatusBadge.replaceSensor.text)"
        case .warmingUp: value = "Warming up"
        case .none: value = "Glucose: no readings yet"
        case .noReadings:
            value = "Glucose: no reading since \(data.latest.map { Freshness.timeText($0.t, now: data.now) } ?? "")"
        case .value:
            let number = (look.text ?? noValueText) + (look.strike ? " (old)" : "")
            let trend = Trend.words(rate: look.trend == .arrow ? look.rate : nil)
            let time = look.fresh.showTime ? data.latest.map { ", " + ago($0.t, now: data.now) } ?? "" : ""
            value = "Glucose \(number) \(unit.spoken), \(trend)\(time)"
        }
        // the card draws the badge only under a shown value
        let badge = look.state == .value ? StatusBadge.describe(data.alerts.status, now: data.now) : nil
        return value + (badge.map { ". " + $0 } ?? "")
    }

    /// "2 min ago" within the hour, else the clock time ("at 14:05").
    private static func ago(_ t: TimeInterval, now: TimeInterval) -> String {
        let minutes = Int(max(0, now - t) / 60)
        if minutes < 60 { return "\(Durations.text(minutes)) ago" }
        return "at \(Freshness.timeText(t, now: now))"
    }
}
