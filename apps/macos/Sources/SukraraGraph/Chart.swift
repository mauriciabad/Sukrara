// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import CoreGraphics
import CoreText
import Foundation

// MARK: - Chart

/// The chart with the time-in-range side profile (GlucoseGraph.kt). `d` = points per dp (1 on the Mac). The level
/// lines and the glucose line sit in one transparency layer; the distribution's outline is erased from it
/// (`.destinationOut`), so the lines stop short of each row of dots on any background, transparent widgets included.
enum GlucoseGraph {
    /// Narrowest chart (pt) that still gets the % labels beside it.
    static let pctMinWidth: CGFloat = 240
    /// `alerts` = the rules behind the treatments' mute lines (nil = no lines). `sensor` = the life badge to put in the
    /// hour-label row (`sensorTop`); only `GlucoseCard` decides that (`GlucoseCard.sensorSpot`), so only it calls this
    /// (tools/check `graph-surface`).
    static func draw(
        _ ctx: CGContext, _ area: CGRect, readings: [GraphReading], marks: [GraphMark], now: TimeInterval,
        _ st: GraphStyle, _ pal: GraphPalette, d: CGFloat, alerts: CardAlerts,
        sensor: SensorBadge.Look?, rec: CardLayout, view: ChartView? = nil, latestT: TimeInterval? = nil
    ) {
        // The window drawn: the view's (a scrolled chart) or the last `st.window` up to now. Everything the chart shows
        // (line, distribution, %, y range, treatments, hour labels) is about this window only.
        let end = min(view?.end ?? now, now)
        let from = end - st.window
        let b = st.bands.sane()
        let pts = GlucoseStats.window(readings, from: from - 60, to: end)
        let tir = GlucoseStats.timeInRange(pts, from: from, to: end, bands: b)
        let text = Ink.font(10 * d, .regular)
        let pctBig = Ink.font(13 * d, .bold), pctSmall = Ink.font(8.5 * d, .regular)

        let labels = st.axisLabels
        let p = pct3(tir.below, tir.inRange, tir.above)
        // the % labels as drawn (real font): the column is the widest of them plus `sideGap` on each side
        func pctW(_ v: Int) -> CGFloat { Ink.width("\(v)", pctBig) + Ink.width("%", pctSmall) }
        let pctLabelW = max(pctW(p.0), pctW(p.1), pctW(p.2))
        // Treatment labels go under their markers, in the hour labels' row and, when needed, a second row under it.
        // Only the parts the settings show (`st.marks`): an entry with nothing shown is left out, and the marker lane
        // is there only when a point or a mute line is drawn in it.
        // A window longer than `labelsMaxS` (zoomed far out, a long widget) shows no treatment labels: they would be
        // meaningless crowds (the user, 2026-10-21); points and mute lines follow the settings as always.
        let show = st.window > Self.labelsMaxS ? st.marks.withoutLabels() : st.marks
        let muteUntil: ((GraphMark) -> TimeInterval?)? = alerts.muteUntil
        func mutes(_ m: GraphMark) -> Bool { show.has(m.k, MarkShow.line) && muteUntil?(m) != nil }
        let shownMarks =
            st.treatments
            ? marks.filter { m in
                m.t >= from && m.t <= end && show.any(m.k)
                    && (show.has(m.k, MarkShow.point) || show.label(m.k) && TreatmentMarks.amount(m) != nil || mutes(m))
            } : []
        let lane = shownMarks.contains { show.has($0.k, MarkShow.point) || mutes($0) }
        // The y range of the window (`yRange`); a scrolling surface animates it towards that and passes the current one.
        let own = yRange(pts, bands: b)
        let yMin = view?.y?.lowerBound ?? own.lowerBound, yMax = view?.y?.upperBound ?? own.upperBound
        rec.add("chart.y", nil, "\(Int(own.lowerBound.rounded()))-\(Int(own.upperBound.rounded()))")
        let split = b.tight < b.high
        // The values on the right: low and high always, then very good, very low / very high and the extra lines,
        // each only where it doesn't touch one already written (in the plot from `top` to `plotBottom`).
        let candidates =
            !labels
            ? []
            : ([b.low, b.high] + (split ? [b.tight] : []) + [b.veryLow, b.veryHigh, b.extremelyHigh] + st.lines)
                .filter { Double($0) > yMin && Double($0) < yMax }
        func axisValues(_ top: CGFloat, _ plotBottom: CGFloat) -> [(Int, CGFloat)] {
            var out: [(Int, CGFloat)] = []
            for v in candidates {
                let ly = plotBottom - CGFloat((Double(v) - yMin) / (yMax - yMin)) * (plotBottom - top)
                if !out.contains(where: { abs($0.1 - ly) < 12 * d }) { out.append((v, ly)) }
            }
            return out
        }
        func valuesW(_ vs: [Int]) -> CGFloat { vs.map { Ink.width("\($0)", text) }.max() ?? 0 }
        // The plot's sides: the % column and the values column, each its widest drawn label + `sideGap` on each side.
        // Which values show depends on the plot's height, which depends (second treatment lane) on its width: start from
        // every candidate and settle on the values actually written (GlucoseGraph.kt).
        struct Geo {
            var valW: CGFloat; var left: CGFloat; var right: CGFloat; var layout: TreatmentMarks.Layout?
            var lane1: CGFloat; var top: CGFloat; var bottom: CGFloat; var plotBottom: CGFloat
            var values: [(Int, CGFloat)]
        }
        func geo(_ valW: CGFloat) -> Geo {
            let (left, right) = plotX(
                area.minX, area.maxX, pct: st.side && !tir.empty, labels: labels, d: d, pctW: pctLabelW, valW: valW)
            let layout =
                shownMarks.isEmpty
                ? nil
                : TreatmentMarks.layout(
                    shownMarks, x: { left + CGFloat(($0 - from) / st.window) * (right - left) }, now: now,
                    minX: area.minX, maxX: area.maxX, lanes: lanes(area.height, d: d), d: d, show: show)
            let lane1: CGFloat = layout?.twoLanes == true ? TreatmentMarks.laneH * d : 0
            let textRow = labels || !(layout?.labels.isEmpty ?? true)
            let top = area.minY + 3 * d
            let bottom = area.maxY - (textRow ? 10 * d + 4 * d : 0) - lane1
            let plotBottom = lane ? bottom - 12 * d : bottom
            return Geo(
                valW: valW, left: left, right: right, layout: layout, lane1: lane1, top: top, bottom: bottom,
                plotBottom: plotBottom, values: axisValues(top, plotBottom))
        }
        var g = geo(valuesW(candidates))
        for _ in 0..<3 {
            let w = valuesW(g.values.map(\.0))
            if w == g.valW { break }
            g = geo(w)
        }
        let left = g.left, right = g.right, layout = g.layout, top = g.top
        // A scrolling surface eases the plot's bottom (the treatment lane and the second label row come and go with the
        // marks in the window and the zoom) instead of letting the plot jump: `view.plotInset` is the distance from the
        // area's bottom it draws at; the marker lane and the label rows follow it (GlucoseGraph.kt).
        let plotBottom = view?.plotInset.map { min(max(area.maxY - $0, top + 20 * d), area.maxY) } ?? g.plotBottom
        let eased = view?.plotInset != nil
        let textRow = labels || !(layout?.labels.isEmpty ?? true)
        let lane1: CGFloat =
            !eased
            ? g.lane1
            : min(
                max(area.maxY - (textRow ? 14 * d : 0) - (lane ? 12 * d : 0) - plotBottom, 0), TreatmentMarks.laneH * d)
        let bottom: CGFloat = !eased ? g.bottom : lane ? plotBottom + 12 * d : plotBottom
        let values = plotBottom == g.plotBottom ? g.values : axisValues(top, plotBottom)
        let pctCol = left - area.minX
        func x(_ t: TimeInterval) -> CGFloat { left + CGFloat((t - from) / st.window) * (right - left) }
        rec.add("chart", area, "\(Int(st.window / 3600))h")
        if end < now { rec.add("chart.window", nil, "\(Int((from - now) / 60))m..\(Int((end - now) / 60))m") }
        guard right - left >= 20 * d, plotBottom - top >= 20 * d else {
            rec.add("chart.tooSmall", area)
            if let sensor { rec.add("chart.sensor", nil, "\(sensor.label) (no room)") }
            return
        }
        rec.add("chart.plot", CGRect(x: left, y: top, width: right - left, height: plotBottom - top))
        // where the plot ends without easing (what a scrolling surface eases `view.plotInset` towards)
        rec.add("chart.plot.natural", CGRect(x: left, y: top, width: right - left, height: g.plotBottom - top))
        if lane1 > 0 { rec.add("chart.lane2") }
        // The whole chart in one layer: the treatment labels' halos are erased from it (on any background). A little
        // wider than the area: a marker at the plot's edge may stick out.
        ctx.beginTransparencyLayer(in: area.insetBy(dx: -6 * d, dy: 0), auxiliaryInfo: nil)
        defer { ctx.endTransparencyLayer() }

        func y(_ v: Double) -> CGFloat {
            plotBottom - CGFloat((min(max(v, yMin), yMax) - yMin) / (yMax - yMin)) * (plotBottom - top)
        }
        let sideW: CGFloat = st.side && !tir.empty ? min((right - left) * 0.26, 130 * d) : 0

        // The in-range band across the whole plot; its very good part (low…tight) a little stronger, in its own colour.
        let tightTop = y(Double(split ? b.tight : b.high))
        let bandDecor: CGFloat = pal.decor < 1 ? 0.5 : 1
        ctx.setFillColor(
            pal.tight.a(CGFloat(split ? (pal.dark ? 0x2A : 0x24) : (pal.dark ? 0x1E : 0x18)) / 255 * bandDecor))
        ctx.fill(CGRect(x: left, y: tightTop, width: right - left, height: y(Double(b.low)) - tightTop))
        if split {
            ctx.setFillColor(pal.target.a(CGFloat(pal.dark ? 0x16 : 0x14) / 255 * bandDecor))
            ctx.fill(CGRect(x: left, y: y(Double(b.high)), width: right - left, height: tightTop - y(Double(b.high))))
        }

        // Hour grid + labels: the user's step, or a longer one where labels would come closer than ~44 dp.
        let pxPerHour = (right - left) / CGFloat(st.window / 3600)
        // From a day up (a zoomed-out chart) the lines fall on midnights (every day, 2 or 3 days, a week: Mondays) and the labels
        // are dates; the steps are fixed to the calendar, not to the window, so they don't shift while the chart scrolls.
        let step = Self.gridStep(pxPerHour, gridHours: st.gridHours, d: d)
        let cal = Calendar.current
        var hour = cal.dateInterval(of: .hour, for: Date(timeIntervalSince1970: from))!.start.addingTimeInterval(3600)
        while !Self.onStep(hour, step, cal) { hour.addTimeInterval(3600) }
        ctx.saveGState()
        ctx.setStrokeColor(pal.grid.a(pal.decor))
        ctx.setLineWidth(1 * d)
        ctx.setLineDash(phase: 0, lengths: [2 * d, 3 * d])
        var lastLabelEnd = -CGFloat.infinity
        while hour.timeIntervalSince1970 <= end {
            let gx = x(hour.timeIntervalSince1970)
            if gx > left + sideW * 0.6 {
                if st.gridHours > 0 {
                    ctx.strokeLineSegments(between: [CGPoint(x: gx, y: top), CGPoint(x: gx, y: plotBottom)])
                }
                if labels {
                    let s =
                        step >= 24
                        ? TimeText.dayLabel(hour) : TimeText.hourLabel(cal.component(.hour, from: hour), st.hour24)
                    let tw = Ink.width(s, text)
                    let lx = min(max(gx - tw / 2, left), right - tw)
                    // a label squeezed against the right edge would touch its neighbour; a treatment label wins
                    if lx > lastLabelEnd + 6 * d && !(layout?.blocks(lx - 3 * d, lx + tw + 3 * d) ?? false) {
                        Ink.draw(ctx, s, x: lx, y: area.maxY - 2 * d - lane1, text, pal.inkSoft)
                        rec.add("time.text", nil, s)
                        if rec.first("chart.hours") == nil { rec.add("chart.hours") }
                        lastLabelEnd = lx + tw
                    }
                }
            }
            if step >= 24 {
                repeat { hour = cal.date(byAdding: .day, value: 1, to: hour)! } while !Self.onStep(hour, step, cal)
            } else {
                hour.addTimeInterval(TimeInterval(step * 3600))
            }
        }
        ctx.restoreGState()
        if labels && rec.first("chart.hours") == nil { rec.add("chart.hours", nil, "(no room)") }
        // The values on the right (axisValues), left-aligned `sideGap` after the plot; the widest ends `sideGap` before
        // the area's edge.
        for (v, ly) in values {
            let s = "\(v)", base = ly + 10 * d / 3, w = Ink.width(s, text)
            Ink.draw(ctx, s, x: right + Self.sideGap * d, y: base, text, pal.inkSoft)
            // the drawn label's box (LayoutSelfTest checks the column's gaps against it: layout/rules.json "gaps")
            rec.add(
                "chart.values.label",
                CGRect(x: right + Self.sideGap * d, y: base - 10 * d * 0.72, width: w, height: 10 * d * 0.72), s)
        }
        if !values.isEmpty {
            rec.add(
                "chart.values", CGRect(x: right, y: top, width: area.maxX - right, height: plotBottom - top),
                values.map { String($0.0) }.joined(separator: ",") + " [w" + CardLayout.dp(area.maxX - right, d) + "]")
        }

        // Everything the distribution cuts goes into one layer: the dashed level lines and the glucose line.
        ctx.beginTransparencyLayer(in: area, auxiliaryInfo: nil)
        // Very low / very high / extremely high: faint dashed lines where the y range reaches them. The user's lines (100 by default) a
        // little stronger, so they still show on the in-range band.
        ctx.saveGState()
        ctx.setLineWidth(1 * d)
        ctx.setLineDash(phase: 0, lengths: [4 * d, 4 * d])
        for (vs, color) in [
            ([b.veryLow, b.veryHigh, b.extremelyHigh], pal.grid.a(pal.decor)),
            (st.lines, pal.inkSoft.a(0.4 * pal.decor)),
        ] {
            ctx.setStrokeColor(color)
            for v in vs where Double(v) > yMin && Double(v) < yMax {
                ctx.strokeLineSegments(between: [CGPoint(x: left, y: y(Double(v))), CGPoint(x: right, y: y(Double(v)))])
            }
        }
        ctx.restoreGState()
        let shownLines = st.lines.filter { Double($0) > yMin && Double($0) < yMax }
        if !shownLines.isEmpty { rec.add("chart.lines", nil, shownLines.map(String.init).joined(separator: ",")) }

        // The line: a dot per reading. Zoomed far out (more than `densePerDp` readings per pt: days of 1-minute readings)
        // each 1 pt column draws one stroke from its lowest to its highest reading instead, split where it crosses a
        // band, in the band's colour (GlucoseGraph.kt).
        let spacing = pts.count > 1 ? (right - left) / CGFloat(max(1, pts.count)) : 99
        let r = min(max(spacing * 0.55, 1.3 * d), 2.2 * d)
        ctx.setLineCap(.round)
        let dense = CGFloat(pts.count) > Self.densePerDp * (right - left) / d
        if dense {
            rec.add("chart.line", nil, "columns")
            ctx.setLineWidth(1.2 * d)  // the columns just touch: an envelope, not bars
            let edges = [b.veryLow, b.low, b.tight, b.high, b.veryHigh, b.extremelyHigh]
            var k = pts.firstIndex { $0.t >= from } ?? 0
            while k < pts.count {
                let col = floor((x(pts[k].t) - left) / d)
                var lo = pts[k].sgv, hi = lo, j = k + 1
                while j < pts.count, floor((x(pts[j].t) - left) / d) == col,
                    pts[j].t - pts[j - 1].t <= GlucoseStats.maxGap
                {
                    lo = min(lo, pts[j].sgv); hi = max(hi, pts[j].sgv); j += 1
                }
                let cx = left + (col + 0.5) * d
                var a = lo
                for e in edges where e > a && e <= hi {
                    ctx.setStrokeColor(pal.at(b, Double(a)))
                    ctx.strokeLineSegments(between: [CGPoint(x: cx, y: y(Double(a))), CGPoint(x: cx, y: y(Double(e)))])
                    a = e
                }
                ctx.setStrokeColor(pal.at(b, Double(a)))
                ctx.strokeLineSegments(between: [CGPoint(x: cx, y: y(Double(a))), CGPoint(x: cx, y: y(Double(hi)))])
                k = j
            }
        }
        ctx.setLineWidth(1.6 * d)
        for (i, p) in pts.enumerated() where p.t >= from && (!dense || i == pts.count - 1) {
            let px = x(p.t), py = y(Double(p.sgv))
            let c = pal.at(b, Double(p.sgv))
            if spacing > 5 * d, i > 0, p.t - pts[i - 1].t <= GlucoseStats.maxGap {
                ctx.setStrokeColor(c.a(0.75))
                ctx.strokeLineSegments(between: [
                    CGPoint(x: x(pts[i - 1].t), y: y(Double(pts[i - 1].sgv))), CGPoint(x: px, y: py),
                ])
            }
            if i == pts.count - 1 && p.t >= (latestT ?? -.infinity) {
                // the gap around the latest dot: background-coloured, or (tinted) cut out of the lines and band below
                ctx.saveGState()
                if pal.knockout { ctx.setBlendMode(.destinationOut) }
                ctx.setFillColor(pal.knockout ? GraphPalette.hex(0) : pal.bg)
                ctx.fillEllipse(
                    in: CGRect(
                        x: px - r - 2.6 * d, y: py - r - 2.6 * d, width: 2 * (r + 2.6 * d), height: 2 * (r + 2.6 * d)))
                ctx.restoreGState()
                ctx.setFillColor(c)
                ctx.fillEllipse(
                    in: CGRect(
                        x: px - r - 1.4 * d, y: py - r - 1.4 * d, width: 2 * (r + 1.4 * d), height: 2 * (r + 1.4 * d)))
            } else {
                ctx.setFillColor(c)
                ctx.fillEllipse(in: CGRect(x: px - r, y: py - r, width: 2 * r, height: 2 * r))
            }
        }

        // The distribution: its outline erased from the lines (the widgets are transparent, so no background-colour
        // stroke), then its dots.
        let rows =
            sideW > 0
            ? profileRows(
                GlucoseStats.timeAtLevel(pts, from: from, to: end, bands: b, tir: tir), b, pal,
                left: left, sideW: sideW, top: top, bottom: plotBottom, yMin: yMin, yMax: yMax, d: d) : []
        if !rows.isEmpty {
            ctx.saveGState()
            ctx.setBlendMode(.destinationOut)
            let h = (Self.profileR + Self.profileCut) * d
            for row in rows {
                ctx.addPath(
                    CGPath(
                        roundedRect: CGRect(
                            x: area.minX, y: row.cy - h, width: row.endX + Self.profileCut * d - area.minX,
                            height: 2 * h),
                        cornerWidth: h, cornerHeight: h, transform: nil))
            }
            ctx.fillPath()
            ctx.restoreGState()
            // the outline as erased and the dots it surrounds (LayoutSelfTest checks the margin: layout/rules.json "distribution")
            let top0 = rows.map(\.cy).min()!, bottom0 = rows.map(\.cy).max()!, endX = rows.map(\.endX).max()!
            rec.add(
                "chart.distribution.cut",
                CGRect(
                    x: area.minX, y: top0 - h, width: endX + Self.profileCut * d - area.minX,
                    height: bottom0 - top0 + 2 * h))
            rec.add(
                "chart.distribution.dots",
                CGRect(
                    x: left, y: top0 - Self.profileR * d, width: endX - left,
                    height: bottom0 - top0 + 2 * Self.profileR * d))
        }
        ctx.endTransparencyLayer()
        if !rows.isEmpty {
            rec.add("chart.distribution", CGRect(x: left, y: top, width: sideW, height: plotBottom - top))
        }
        for row in rows {
            ctx.setFillColor(row.color)
            for k in 0..<row.n {
                let cx = left + Self.profilePitch * d * (CGFloat(k) + 0.5), pr = Self.profileR * d
                ctx.fillEllipse(in: CGRect(x: cx - pr, y: row.cy - pr, width: 2 * pr, height: 2 * pr))
            }
        }

        // % per major band, centred on its band; a band too short for its label (often low, sometimes high) pushes
        // it outwards so the three never overlap, and it may hang a little past the plot.
        if pctCol > 0 {
            let lh = 13 * d * 0.95
            var c = [
                (top + y(Double(b.high))) / 2, (y(Double(b.high)) + y(Double(b.low))) / 2,
                (y(Double(b.low)) + plotBottom) / 2,
            ]
            c[0] = min(c[0], c[1] - lh)
            c[2] = max(c[2], c[1] + lh)
            c[0] = max(c[0], area.minY + lh * 0.5)
            c[2] = min(c[2], area.maxY - lh * 0.4)
            for (v, cy) in zip([p.2, p.1, p.0], c) {
                let s = "\(v)"
                let w = pctW(v)
                let x0 = left - Self.sideGap * d - w, base = cy + 13 * d * 0.36
                Ink.draw(ctx, s, x: x0, y: base, pctBig, pal.ink)
                Ink.draw(ctx, "%", x: x0 + Ink.width(s, pctBig), y: base, pctSmall, pal.inkSoft)
                // the drawn label's box (LayoutSelfTest checks the column's gaps against it: layout/rules.json "gaps")
                rec.add("chart.pct.label", CGRect(x: x0, y: base - 13 * d * 0.72, width: w, height: 13 * d * 0.72), s)
            }
            rec.add(
                "chart.pct", CGRect(x: area.minX, y: top, width: pctCol, height: plotBottom - top),
                "\(p.0)/\(p.1)/\(p.2) [w" + CardLayout.dp(pctCol, d) + "]")
            // The crown over a perfect 100 (TirMark.column), where it clears the label above.
            if st.crown, TirMark.column(tir) {
                let numW = Ink.width("100", pctBig), cw = numW * 0.62
                let bottom = c[1] + 13 * d * (0.36 - 0.72) - 1.5 * d
                if bottom - cw * Crown.h >= c[0] + 13 * d * 0.36 + 2 * d {
                    Crown.fill(
                        ctx, x: left - Self.sideGap * d - Ink.width("%", pctSmall) - numW / 2, bottom: bottom, w: cw,
                        pal.knockout ? CGColor(gray: 1, alpha: 1) : pal.tight)
                    rec.add("chart.pct.crown")
                } else {
                    rec.add("chart.pct.crown", nil, "(no room)")
                }
            }
        }

        if !shownMarks.isEmpty {
            rec.add(
                "chart.markers", nil,
                "\(shownMarks.filter { ["rapid", "long", "carbs", "note"].contains($0.k) && show.has($0.k, MarkShow.point) }.count)"
            )
            // what each entry shows (layout/rules.json "marks": the same on every surface)
            rec.add(
                "chart.marks", nil,
                shownMarks.sorted { $0.id < $1.id }.map { m in
                    m.id + ":"
                        + MarkShow.parts(m.k).filter { p in
                            show.has(m.k, p) && (p != MarkShow.line || muteUntil?(m) != nil)
                                && (p != MarkShow.amount && p != MarkShow.time || TreatmentMarks.amount(m) != nil)
                        }.joined(separator: "+")
                }.joined(separator: " "))
            for l in layout?.labels ?? [] {
                let lb = l.lane == 0 ? area.maxY - lane1 : area.maxY
                rec.add(
                    "chart.label", CGRect(x: l.left, y: lb - 14 * d, width: l.w, height: 14 * d),
                    "\(l.text) lane\(l.lane)")
            }
            TreatmentMarks.draw(
                ctx, shownMarks, x: x, left: left, right: right, bottom: bottom, dark: pal.dark, d: d,
                layout: layout, base0: area.maxY - 2 * d - lane1, base1: area.maxY - 2 * d, sep: pal.inkSoft,
                now: now, muteUntil: muteUntil, show: show)
        }
        // The "Now" pill of a scrolled chart (the action pill, StatusBadge): top right in the plot, fading in and out
        // with `view.now`; the surface makes its box (chart.now) clickable.
        if let view, view.now > 0 {
            let ph = Self.nowPill * d, look = StatusBadge.backToNow
            let pw = StatusBadge.width(look, h: ph)
            let px = right - pw - Self.nowInset * d, py = top + Self.nowInset * d
            ctx.saveGState()
            ctx.setAlpha(min(max(view.now, 0), 1))
            ctx.beginTransparencyLayer(in: CGRect(x: px, y: py, width: pw, height: ph), auxiliaryInfo: nil)
            StatusBadge.draw(ctx, x: px, y: py, h: ph, maxW: pw, look, dark: pal.dark, knockout: pal.knockout)
            ctx.endTransparencyLayer()
            ctx.restoreGState()
            rec.add("chart.now", CGRect(x: px, y: py, width: pw, height: ph), look.text)
        }
        // The sensor life badge at the start of the hour labels' row, under the % column and the distribution (the
        // user, 2026-10-19/21), drawn last, over whatever passes under it: its outline is erased (`.destinationOut`,
        // `badgeCut` around it) from the treatment and hour labels, which are laid out as if it weren't there, so it
        // never pushes a label into a second row or makes anything jump while the chart scrolls (the user, 2026-10-22).
        if let sensor, labels {
            let sy = sensorTop(area.maxY, lane1: lane1, d: d)
            let box = CGRect(x: area.minX, y: sy, width: sensorWidth(sensor.label, d: d), height: sensorDp * d)
            let c = Self.badgeCut * d, cut = box.insetBy(dx: -c, dy: -c)
            ctx.saveGState()
            ctx.setBlendMode(.destinationOut)
            ctx.addPath(
                CGPath(roundedRect: cut, cornerWidth: cut.height / 2, cornerHeight: cut.height / 2, transform: nil))
            ctx.fillPath()
            ctx.restoreGState()
            SensorBadge.draw(ctx, x: area.minX, y: sy, h: sensorDp * d, sensor, pal)
            rec.add("chart.sensor", box, sensor.label)
        }
    }

    /// The longest window whose treatment marks get labels (layout/rules.json "treatmentLabels"; GlucoseGraph.kt `LABELS_MAX_S`).
    static let labelsMaxS: TimeInterval = 24 * 3600

    /// Readings per pt of plot from which the line is drawn as columns (GlucoseGraph.kt `DENSE_PER_DP`).
    static let densePerDp: CGFloat = 6

    /// The step (hours) between the vertical lines and their labels: the user's step at least, else the first whose
    /// labels are ≥ 44 pt apart: 1, 2, 3, 4, 6, 12 h, then 1, 2, 3 days, a week (GlucoseGraph.kt `gridStep`).
    static func gridStep(_ pxPerHour: CGFloat, gridHours: Int, d: CGFloat) -> Int {
        [1, 2, 3, 4, 6, 12, 24, 48, 72, 168].filter { $0 >= gridHours }.first { CGFloat($0) * pxPerHour >= 44 * d }
            ?? 168
    }

    /// Is `t` (on the hour) a line of `step`? Hours: the hour divides by it; days: a midnight whose day number divides by
    /// the step in days, a Monday (a week).
    static func onStep(_ t: Date, _ step: Int, _ cal: Calendar) -> Bool {
        let h = cal.component(.hour, from: t)
        if step < 24 { return h % step == 0 }
        guard h == 0 else { return false }
        if step == 168 { return cal.component(.weekday, from: t) == 2 }
        if step > 24 {
            let local = t.timeIntervalSince1970 + TimeInterval(cal.timeZone.secondsFromGMT(for: t))
            return Int(floor(local / 86400)) % (step / 24) == 0
        }
        return true
    }

    /// The outline erased around the sensor badge (pt; GlucoseGraph.kt `BADGE_CUT_DP`).
    static let badgeCut: CGFloat = 2

    /// The Now pill's height and its distance from the plot's top-right corner (pt; GlucoseGraph.kt `NOW_PILL_DP`, `NOW_INSET_DP`).
    static let nowPill: CGFloat = 22, nowInset: CGFloat = 4

    /// The y range (mg/dL) of a window with the readings `pts` in it: it always reaches the very low / very high limits,
    /// so their lines always show, and every reading with a margin. The user's own lines don't change it: one outside
    /// the range isn't drawn. GlucoseGraph.kt `yRange`.
    static func yRange(_ pts: [GraphReading], bands b: Bands) -> ClosedRange<Double> {
        let dMin = pts.map(\.sgv).min() ?? b.low, dMax = pts.map(\.sgv).max() ?? b.high
        return max(
            Double(GlucoseStats.minMg), Double(min(b.low - 22, b.veryLow - 6, dMin - 6)))...min(
                Double(GlucoseStats.maxMg), Double(max(b.high + 40, b.veryHigh + 8, dMax + 8)))
    }

    /// Height of the sensor badge in the hour-label row (GlucoseGraph.kt `SENSOR_DP`).
    static let sensorDp: CGFloat = 10
    /// The top of the sensor badge (at the chart's left edge, `sensorDp` tall) in a chart ending at `bottom` with `lane1`
    /// of second label lane: centred on the hour labels' x-height (GlucoseGraph.kt `sensorTop`; fixtures graph-fixtures/place).
    static func sensorTop(_ bottom: CGFloat, lane1: CGFloat, d: CGFloat) -> CGFloat {
        bottom - 2 * d - lane1 - 3.6 * d - sensorDp * d / 2
    }
    /// The badge's width in the `TextModel`: the room kept free of treatment labels (GlucoseGraph.kt `sensorWidth`).
    static func sensorWidth(_ label: String, d: CGFloat) -> CGFloat {
        sensorDp * d * 1.3 + TextModel.width(label, sensorDp * d * 0.95)
    }

    /// The plot's left and right edge in `areaLeft`…`areaRight` (GlucoseGraph.kt `plotX`): the % column (`pct`: time
    /// in range shown and with data; only from `pctMinWidth`) and the values on the right (`labels`). Each column is its
    /// widest label actually drawn (`pctW`, `valW`: measured with the fonts that draw them) plus `sideGap` on each side,
    /// whatever the surface, size or font (the user, 2026-10-21: "it should depend on the longest text width"; the
    /// TextModel's worst-case widths had left extra margin beside the labels).
    static func plotX(
        _ areaLeft: CGFloat, _ areaRight: CGFloat, pct: Bool, labels: Bool, d: CGFloat,
        pctW: CGFloat, valW: CGFloat
    ) -> (CGFloat, CGFloat) {
        let pctCol = pct && labels && areaRight - areaLeft >= pctMinWidth * d ? pctW + 2 * sideGap * d : 0
        return (areaLeft + pctCol, areaRight - (labels ? valW + 2 * sideGap * d : 0))
    }

    /// The side columns' margin (pt) on each side of their widest label: one constant (layout/rules.json "gaps"
    /// GlucoseGraph.kt `SIDE_GAP_DP`).
    static let sideGap: CGFloat = 3

    /// Treatment label lanes for a chart `h` tall: a second one from `TreatmentMarks.twoLanesH`.
    static func lanes(_ h: CGFloat, d: CGFloat) -> Int { h >= TreatmentMarks.twoLanesH * d ? 2 : 1 }

    /// The distribution's dots: rows `profilePitch` apart, dots of radius `profileR`; the outline erased around them is
    /// `profileCut` wider (the user, 2026-10-21: "more outline so it masks out more of the content behind"; was 1.6).
    /// The same numbers as layout/rules.json "distribution" (LayoutSelfTest, Android CardLayoutTest).
    static let profilePitch: CGFloat = 3.6, profileR: CGFloat = 1.35, profileCut: CGFloat = 3

    /// One row of the distribution: `n` dots centred on `cy`, the last one ending at `endX`.
    private struct ProfileRow { var cy: CGFloat; var n: Int; var color: CGColor; var endX: CGFloat }

    private static func profileRows(
        _ prof: [Double], _ b: Bands, _ pal: GraphPalette, left: CGFloat, sideW: CGFloat,
        top: CGFloat, bottom: CGFloat, yMin: Double, yMax: Double, d: CGFloat
    ) -> [ProfileRow] {
        let pxPerMg = Double(bottom - top) / (yMax - yMin)
        func massBetween(_ g0: Double, _ g1: Double) -> Double {
            var s = 0.0
            var g = Int(floor(g0))
            while Double(g) < g1 {
                let k = g - GlucoseStats.minMg
                let overlap = min(Double(g + 1), g1) - max(Double(g), g0)
                if k >= 0 && k < prof.count && overlap > 0 { s += prof[k] * overlap }
                g += 1
            }
            return s
        }
        // Rows of dots the size of the line's; dots per row ∝ the row's share of time.
        let pitch = profilePitch * d, r = profileR * d
        let rows = Int((bottom - top) / pitch)
        guard rows > 0 else { return [] }
        let mass = (0..<rows).map { i -> Double in
            let yb = Double(bottom - CGFloat(i) * pitch)
            return massBetween(
                yMin + (Double(bottom) - yb) / pxPerMg, yMin + (Double(bottom) - yb + Double(pitch)) / pxPerMg)
        }
        guard let peak = mass.max(), peak > 0 else { return [] }
        let maxDots = Int(floor(sideW * 0.9 / pitch))
        return (0..<rows).compactMap { i in
            var n = Int((mass[i] / peak * Double(maxDots)).rounded())
            if n == 0 && mass[i] > peak * 0.004 { n = 1 }
            guard n > 0 else { return nil }
            let cy = bottom - CGFloat(i) * pitch - pitch / 2
            let g = yMin + Double(bottom - cy) / pxPerMg
            return ProfileRow(cy: cy, n: n, color: pal.at(b, g), endX: left + pitch / 2 + CGFloat(n - 1) * pitch + r)
        }
    }

    /// Three shares as whole percentages adding up to 100 (largest remainder). The middle one (in range) is 100 only
    /// when the others are nothing: that 100 wears the crown (`TirMark.column`).
    static func pct3(_ a: Double, _ b: Double, _ c: Double) -> (Int, Int, Int) {
        let raw = [a, b, c].map { $0 * 100 }
        let total = raw.reduce(0, +)
        guard total > 0 else { return (0, 0, 0) }
        var base = raw.map { Int(floor($0)) }
        var left = Int(total.rounded()) - base.reduce(0, +)
        for i in raw.indices.sorted(by: { raw[$0] - Double(base[$0]) > raw[$1] - Double(base[$1]) }) where left > 0 {
            base[i] += 1; left -= 1
        }
        if base[1] == 100 && (a > 0 || c > 0) { base[1] -= 1; base[a >= c ? 0 : 2] += 1 }
        return (base[0], base[1], base[2])
    }
}

/// Treatments along the bottom lane, as TreatmentMarks.kt: rapid = filled blue drop, long = outlined violet drop,
/// carbs = amber dot, note = grey diamond; from each rapid / carbs mark a line to the end of its alert mute. The labels
/// (amount and time since, `4.5u 12m`) go under the markers, in the hour labels' row (they give way) and, when needed,
/// a second row under it, never over the glucose line; close entries share one (`layout`).
