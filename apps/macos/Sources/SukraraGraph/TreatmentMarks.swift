// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import CoreGraphics
import CoreText
import Foundation
import SukraraCore

enum TreatmentMarks {
    static func color(_ k: String, dark: Bool) -> CGColor {
        switch k {
        case "rapid": GraphPalette.hex(dark ? 0x8AB4FF : 0x1A62D6)
        case "long": GraphPalette.hex(dark ? 0xC9B0FF : 0x6D45C4)
        case "carbs": GraphPalette.hex(dark ? 0xFFB74D : 0xA65A00)
        default: GraphPalette.hex(dark ? 0xB4B4B4 : 0x707070)
        }
    }

    /// Text on a filled chip of a kind's colour: near-black on the light dark-theme colours, white on the deep light ones.
    static func onColor(dark: Bool) -> CGColor { GraphPalette.hex(dark ? 0x1C1C1E : 0xFFFFFF) }

    /// Words and icon on a tonal (faintly tinted) pill of colour `c`. Light: `c` darkened by a quarter, so the words
    /// reach 4.5:1 on the tint (the bare light colours fail it); dark: `c` itself. Android: `TreatmentMarks.tonalInk`.
    static func tonalInk(_ c: CGColor, dark: Bool) -> CGColor {
        guard !dark, let k = c.components, k.count >= 3 else { return c }
        let keep = 1 - tonalDarken
        return CGColor(srgbRed: k[0] * keep, green: k[1] * keep, blue: k[2] * keep, alpha: 1)
    }

    /// A tonal pill's tint: `c` at 0x3D (dark) / 0x2E (light) alpha over the card.
    static func tonalTint(_ c: CGColor, dark: Bool) -> CGColor { c.copy(alpha: (dark ? 0x3D : 0x2E) / 255.0)! }

    private static let tonalDarken: CGFloat = 0.25

    /// "4.5u", "12u", "30g" (lowercase, no space: the user, 2026-10-18); nil for notes.
    static func amount(k: String, a: Double) -> String? {
        switch k {
        case "rapid", "long": (a == a.rounded() ? String(Int(a)) : String(format: "%.1f", a)) + "u"
        case "carbs": "\(Int(a.rounded()))g"
        default: nil
        }
    }
    static func amount(_ m: GraphMark) -> String? { amount(k: m.k, a: m.a) }

    /// A short duration: "now" under a minute, "12m", "1h 5m", "3h" (whole hours drop the minutes; from 6 h only
    /// hours), "2d" from two days. `up` rounds partial minutes up (time left: "1m" until it is over).
    static func duration(_ s: TimeInterval, up: Bool = false) -> String {
        let sec = Int64(max(0, s))
        let m = up ? (sec + 59) / 60 : sec / 60
        return m < 1 ? "now" : Durations.compact(Int(m))
    }

    private static let order = ["rapid": 0, "long": 1, "carbs": 2, "note": 3]

    /// One marker of kind `k` centred on (`x`, `y`), `r` = its half-size (4.5 on the chart). `hole` fills the
    /// outlined long-acting drop (the background colour, or nil for none).
    static func marker(
        _ ctx: CGContext, _ k: String, x: CGFloat, y: CGFloat, r: CGFloat, color c: CGColor, hole: CGColor? = nil
    ) {
        ctx.saveGState()
        defer { ctx.restoreGState() }
        switch k {
        case "rapid", "long":
            let p = CGMutablePath()
            p.move(to: CGPoint(x: x - r, y: y - r * 0.78)); p.addLine(to: CGPoint(x: x + r, y: y - r * 0.78))
            p.addLine(to: CGPoint(x: x, y: y + r * 0.89)); p.closeSubpath()
            if k == "rapid" { ctx.addPath(p); ctx.setFillColor(c); ctx.fillPath(); return }
            if let hole { ctx.addPath(p); ctx.setFillColor(hole); ctx.fillPath() }
            ctx.addPath(p); ctx.setStrokeColor(c); ctx.setLineWidth(r / 3); ctx.setLineJoin(.round); ctx.strokePath()
        case "carbs":
            ctx.setFillColor(c)
            ctx.fillEllipse(in: CGRect(x: x - r * 0.8, y: y - r * 0.8, width: r * 1.6, height: r * 1.6))
        // the "Replace sensor" pill (StatusBadge.replaceSensor): the Libre sensor disc, a ring with a centre dot
        case "sensor":
            ctx.setStrokeColor(c); ctx.setLineWidth(r * 0.26)
            ctx.strokeEllipse(in: CGRect(x: x - r * 0.8, y: y - r * 0.8, width: r * 1.6, height: r * 1.6))
            ctx.setFillColor(c)
            ctx.fillEllipse(in: CGRect(x: x - r * 0.3, y: y - r * 0.3, width: r * 0.6, height: r * 0.6))
        // the "Now" pill over a scrolled chart (StatusBadge.backToNow): SF Symbol arrow.right (Android: Material arrow_forward)
        case "now":
            let size = r * 2.2
            _ = SensorBadge.symbol(ctx, "arrow.right", x: x - size / 2, y: y - size / 2, size: size, color: c)
        default:
            let p = CGMutablePath(), q = r * 0.67
            p.move(to: CGPoint(x: x, y: y - q)); p.addLine(to: CGPoint(x: x + q, y: y))
            p.addLine(to: CGPoint(x: x, y: y + q)); p.addLine(to: CGPoint(x: x - q, y: y)); p.closeSubpath()
            ctx.addPath(p); ctx.setFillColor(c); ctx.fillPath()
        }
    }

    // MARK: label layout: docs/16-graph.md "Treatment labels" (the same rules in TreatmentMarks.kt)

    /// Label text size; least space between two labels in a lane; entries this close in time (s) or on the chart (a
    /// marker's width) share a label; chart height from which a second lane may be used, and its height; how far a
    /// marker stays inside its label when the label slides (all dp).
    static let labelSize: CGFloat = 9, labelGap: CGFloat = 4, mergeS: TimeInterval = 180, mergeDp: CGFloat = 9
    static let twoLanesH: CGFloat = 90, laneH: CGFloat = 11, inset: CGFloat = 3
    static let sep = " · "

    /// One label: `members` in label order (kind, then time), the entries with a label part shown (`MarkShow.label`)
    /// `shownAmounts` = those whose amount shows; `time` = since `timeOf`, the newest member whose time shows (nil = none
    /// wants it, or left out for room); `lane` 0 (the axis row) or 1 (under it), `left` + `w` = its modelled box (TextModel).
    struct Label {
        var members: [GraphMark]; var shownAmounts: [GraphMark]; var timeOf: GraphMark?; var time: String?
        var lane: Int; var left: CGFloat; var w: CGFloat
        var amounts: [String] { shownAmounts.compactMap { TreatmentMarks.amount($0) } }
        var text: String { TreatmentMarks.text(amounts, time) }
    }

    /// "4u · 40g 1h 4m", "40g", "1h 4m": the amounts joined by " · ", then the time.
    static func text(_ amounts: [String], _ time: String?) -> String {
        [amounts.isEmpty ? nil : amounts.joined(separator: sep), time].compactMap { $0 }.joined(separator: " ")
    }

    /// Where the labels go; `dropped` = the groups left without a label (their markers stay).
    struct Layout {
        var labels: [Label]; var dropped: [[GraphMark]]
        var twoLanes: Bool { labels.contains { $0.lane == 1 } }
        /// Whether `a`…`b` touches a lane-0 label: an hour label there gives way.
        func blocks(_ a: CGFloat, _ b: CGFloat) -> Bool {
            labels.contains { $0.lane == 0 && $0.left < b && a < $0.left + $0.w }
        }
    }

    /// The label layout (TreatmentMarks.kt `layout`, same steps): group (≤ `mergeS` after the group's last entry,
    /// chained, or < `mergeDp` from the group's first marker), label = amounts in kind order joined by " · " + the time since the newest,
    /// anchored mid-group; newest group first, each in the first of lane 0 / lane 1 with the time, lane 0 / lane 1
    /// without it that has a free place (`place`), else no label.
    /// Nothing else takes room in the lanes: the sensor badge is drawn over the row, masking what passes under it.
    static func layout(
        _ marks: [GraphMark], x: (TimeInterval) -> CGFloat, now: TimeInterval?, minX: CGFloat, maxX: CGFloat,
        lanes: Int, d: CGFloat, show: MarkShow = .defaults
    ) -> Layout {
        let items = marks.filter { amount($0) != nil && order[$0.k] != nil && show.label($0.k) }
            .sorted { ($0.t, order[$0.k]!, $0.a, $0.id) < ($1.t, order[$1.k]!, $1.a, $1.id) }
        var groups: [[GraphMark]] = []
        for m in items {
            if let g = groups.last, m.t - g.last!.t <= mergeS || x(m.t) - x(g.first!.t) < mergeDp * d {
                groups[groups.count - 1].append(m)
            } else {
                groups.append([m])
            }
        }
        let size = labelSize * d, gap = labelGap * d
        var taken: [[(CGFloat, CGFloat)]] = [[], []]
        var labels: [Label] = [], dropped: [[GraphMark]] = []
        for g in groups.sorted(by: { $0.last!.t > $1.last!.t }) {
            let members = g.sorted { (order[$0.k]!, $0.t, $0.a, $0.id) < (order[$1.k]!, $1.t, $1.a, $1.id) }
            let shownAmounts = members.filter { show.has($0.k, MarkShow.amount) }
            let amounts = shownAmounts.compactMap { amount($0) }
            // the time once, since the newest member that wants it
            let timeOf = g.last { show.has($0.k, MarkShow.time) }
            let time = timeOf.flatMap { m in now.map { duration($0 - m.t) } }
            let cx = (x(g.first!.t) + x(g.last!.t)) / 2
            var placed: Label?
            var tries: [(Int, String?)] = []
            for (lane, t) in [(0, time), (1, time), (0, nil), (1, nil)]
            where lane < lanes && (!amounts.isEmpty || t != nil) {
                if !tries.contains(where: { $0.0 == lane && $0.1 == t }) { tries.append((lane, t)) }
            }
            for (lane, t) in tries {
                let w = TextModel.width(text(amounts, t), size)
                if let left = place(cx, w, taken[lane], minX: minX, maxX: maxX, gap: gap, inset: inset * d) {
                    taken[lane].append((left - gap / 2, left + w + gap / 2))
                    placed = Label(
                        members: members, shownAmounts: shownAmounts, timeOf: timeOf, time: t, lane: lane, left: left,
                        w: w)
                    break
                }
            }
            if let placed { labels.append(placed) } else { dropped.append(members) }
        }
        return Layout(labels: labels, dropped: dropped)
    }

    /// The left edge for a box `w` wide anchored at `cx` in a lane holding `taken`, or nil (TreatmentMarks.kt `place`):
    /// centred if free, else slid while the anchor stays `inset` inside the box; candidates in order: preferred, the
    /// slide's two ends, then right of / left of each taken box; the free one nearest the preferred wins (first on a tie).
    static func place(
        _ cx: CGFloat, _ w: CGFloat, _ taken: [(CGFloat, CGFloat)], minX: CGFloat, maxX: CGFloat,
        gap: CGFloat, inset: CGFloat
    ) -> CGFloat? {
        if w > maxX - minX { return nil }
        let pref = min(max(cx - w / 2, minX), max(maxX - w, minX))
        var lo = max(minX, cx - w + inset), hi = min(maxX - w, cx - inset)
        if lo > hi { lo = pref; hi = pref }
        func free(_ l: CGFloat) -> Bool {
            !taken.contains { l - gap / 2 < $0.1 - 0.01 && $0.0 + 0.01 < l + w + gap / 2 }
        }
        let cands = [min(max(pref, lo), hi), lo, hi] + taken.flatMap { [$0.1 + gap / 2, $0.0 - w - gap / 2] }
        var best: CGFloat?
        for c in cands where c >= lo && c <= hi && free(c) {
            if best == nil || abs(c - pref) < abs(best! - pref) { best = c }
        }
        return best
    }

    /// Draw `marks` (live entries) into the plot `left`…`right` × …`bottom`: markers, mute lines (`muteUntil`, nil =
    /// none; `now` = the plot's right edge), then `layout`'s labels with baselines `base0` / `base1`. Each label's
    /// outline (3 dp) is first erased (`.destinationOut`) from what is under it, so it reads over anything on any
    /// background (the caller draws the chart in its own transparency layer).
    static func draw(
        _ ctx: CGContext, _ marks: [GraphMark], x: (TimeInterval) -> CGFloat, left: CGFloat, right: CGFloat,
        bottom: CGFloat, dark: Bool, d: CGFloat, layout: Layout?, base0: CGFloat, base1: CGFloat, sep sepColor: CGColor,
        now: TimeInterval? = nil, muteUntil: ((GraphMark) -> TimeInterval?)? = nil, show: MarkShow = .defaults
    ) {
        let items = marks.filter { order[$0.k] != nil }.map { ($0, x($0.t)) }.filter {
            $0.1 >= left - 1 && $0.1 <= right + 1
        }
        guard !items.isEmpty else { return }
        let cy = bottom - 5 * d
        let back = items.sorted { order[$0.0.k]! > order[$1.0.k]! }  // notes at the back, insulin in front

        if let muteUntil {
            ctx.saveGState()
            ctx.setLineWidth(2 * d); ctx.setLineCap(.round); ctx.setLineJoin(.round)
            // Insulin's line just above the lane's middle, carbs' just below: a meal's two lines lie side by side.
            for (m, px) in back where show.has(m.k, MarkShow.line) {
                guard let until = muteUntil(m) else { continue }
                let ly = cy + (m.k == "carbs" ? 1.5 : -1.5) * d
                let running = now.map { until > $0 } ?? false
                let ex = running ? right : min(x(until), right)
                if ex <= px + d { continue }
                ctx.setStrokeColor(color(m.k, dark: dark).copy(alpha: 0xB0 / 255.0)!)
                ctx.strokeLineSegments(between: [CGPoint(x: px, y: ly), CGPoint(x: ex, y: ly)])
                if running {
                    if ex - px > 8 * d {  // still muting: a chevron at the edge
                        ctx.move(to: CGPoint(x: ex - 3 * d, y: ly - 3 * d)); ctx.addLine(to: CGPoint(x: ex, y: ly))
                        ctx.addLine(to: CGPoint(x: ex - 3 * d, y: ly + 3 * d)); ctx.strokePath()
                    }
                } else {
                    // over: a tick
                    ctx.strokeLineSegments(between: [CGPoint(x: ex, y: ly - 3 * d), CGPoint(x: ex, y: ly + 3 * d)])
                }
            }
            ctx.restoreGState()
        }
        for (m, px) in back where show.has(m.k, MarkShow.point) {
            marker(ctx, m.k, x: px, y: cy, r: 4.5 * d, color: color(m.k, dark: dark))
        }

        guard let layout, !layout.labels.isEmpty else { return }
        let bold = Ink.font(labelSize * d, .bold), medium = Ink.font(labelSize * d, .medium)
        // pieces (text, font, colour) and the start of the real text, centred in its modelled box
        let drawn: [(Label, [(String, CTFont, CGColor)], CGFloat)] = layout.labels.map { l in
            var pieces: [(String, CTFont, CGColor)] = []
            for (i, m) in l.shownAmounts.filter({ amount($0) != nil }).enumerated() {
                if i > 0 { pieces.append((sep, medium, sepColor.copy(alpha: 0xC0 / 255.0)!)) }
                pieces.append((amount(m)!, bold, color(m.k, dark: dark)))
            }
            if let t = l.time, let of = l.timeOf {
                pieces.append(
                    ((pieces.isEmpty ? "" : " ") + t, medium, color(of.k, dark: dark).copy(alpha: 0xC0 / 255.0)!))
            }
            let w = pieces.reduce(0) { $0 + Ink.width($1.0, $1.1) }
            return (l, pieces, l.left + (l.w - w) / 2)
        }
        func each(_ f: (String, CTFont, CGColor, CGFloat, CGFloat) -> Void) {
            for (l, pieces, x0) in drawn {
                var px = x0
                for (s, font, c) in pieces { f(s, font, c, px, l.lane == 0 ? base0 : base1); px += Ink.width(s, font) }
            }
        }
        ctx.saveGState()
        ctx.setBlendMode(.destinationOut)
        ctx.setLineJoin(.round)
        each { s, font, _, px, base in Ink.halo(ctx, s, x: px, y: base, font, width: 3 * d) }
        ctx.restoreGState()
        each { s, font, c, px, base in Ink.draw(ctx, s, x: px, y: base, font, c) }
    }
}
