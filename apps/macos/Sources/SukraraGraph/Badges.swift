// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import CoreGraphics
import CoreText
import Foundation
import SukraraCore

// MARK: - Status badge and last-treatment slot (CardStatus.kt)

/// The badge under the glucose value, from `AlertStatus`: none → nothing; needed → a filled pill in the colour of what
/// to log, with its marker ("Eat carbs" / "Take insulin"); taken → the same pill tonal, with the "notifications
/// paused" bell and the time left of the mute ("45m left").
enum StatusBadge {
    /// `kind` = the treatment kind whose colour and marker the badge uses; `tonal` = taken.
    struct Look: Equatable { var kind: String; var text: String; var short: String; var tonal: Bool }

    /// The action pill on an ended sensor (round sensor-life): the same pill, kind "sensor" (neutral colour, sensor disc).
    static let replaceSensor = Look(kind: "sensor", text: "Replace sensor", short: "Sensor", tonal: false)
    /// The pill over a scrolled chart (GlucoseGraph, `ChartView.now`): the same pill, kind "now" (neutral colour, arrow).
    static let backToNow = Look(kind: "now", text: "Now", short: "Now", tonal: false)

    static func look(_ s: AlertStatus, now: TimeInterval) -> Look? {
        switch s.current(at: now) {
        case .none: return nil
        case .needed(let k):
            return k == .low
                ? Look(kind: "carbs", text: "Eat carbs", short: "Carbs", tonal: false)
                : Look(kind: "rapid", text: "Take insulin", short: "Insulin", tonal: false)
        case .taken(_, let tr, let until):
            let d = TreatmentMarks.duration(until - now, up: true)
            return Look(kind: tr.k, text: "\(d) left", short: d, tonal: true)
        case .paused(let k, let until):  // muted on this device: the colour of what answers it
            if until >= AlertStatus.never {
                return Look(kind: k == .low ? "carbs" : "rapid", text: "Muted", short: "Muted", tonal: true)
            }
            let d = TreatmentMarks.duration(until - now, up: true)
            return Look(kind: k == .low ? "carbs" : "rapid", text: "\(d) left", short: d, tonal: true)
        case .muted(let until):  // Mute all in range: neutral, the "notifications off" bell
            if until >= AlertStatus.never {
                return Look(kind: "muted", text: BadgeCopy.pillForever, short: "Muted", tonal: true)
            }
            return Look(
                kind: "muted", text: BadgeCopy.pill(leftMin: BadgeCopy.leftMin(until - now)), short: "Muted",
                tonal: true)
        }
    }

    /// For screen readers, in `w`'s language (the mute lines read like MuteCopy's: "Low alerts muted · 12 min left").
    static func describe(_ s: AlertStatus, now: TimeInterval, words w: CardWords) -> String? {
        func left(_ until: TimeInterval) -> String { Durations.text(BadgeCopy.leftMin(until - now)) }
        switch s.current(at: now) {
        case .none: return nil
        case .needed(let k): return k == .low ? w.eatCarbs : w.takeInsulin
        // only Mute all has no end
        case .paused(_, let until) where until >= AlertStatus.never: return w.mutedForever
        case .paused(let k, let until): return w.sideMuted(k == .low, left(until))
        case .taken(let k, let tr, let until): return w.sideAfter(k == .low, tr.k == "carbs", left(until))
        case .muted(let until):
            return until > now && until < AlertStatus.never ? w.mutedLeft(left(until)) : w.mutedForever
        }
    }

    /// Material Symbols "notifications_paused" (Apache 2.0), viewBox 0 -960 960 960.
    static let bellPaused =
        "M370-320h220v-72H480l110-136v-72H370v72h110L370-392v72ZM160-200v-80h80v-280q0-83 50-147.5"
        + "T420-792v-28q0-25 17.5-42.5T480-880q25 0 42.5 17.5T540-820v28q80 20 130 84.5T720-560v280h80v80H160Zm320-300Zm0 420q-33 "
        + "0-56.5-23.5T400-160h160q0 33-23.5 56.5T480-80ZM320-280h320v-280q0-66-47-113t-113-47q-66 0-113 47t-47 113v280Z"
    /// Immutable once built (CGPath has no mutating API), so sharing it across threads is safe.
    nonisolated(unsafe) private static let bellPath: CGPath = SVGPath.parse(bellPaused)
    /// Material Symbols "notifications_off" (Apache 2.0; Android ic_sym_notifications_off): the Mute all pill.
    static let bellOff =
        "M160-200v-80h80v-280q0-33 8.5-65t25.5-61l60 60q-7 16-10.5 32.5T320-560v280h248L56-792l56-56 736 736-56 56-146-144H160Zm560-154"
        + "-80-80v-126q0-66-47-113t-113-47q-26 0-50 8t-44 24l-58-58q20-16 43-28t49-18v-28q0-25 17.5-42.5T480-880q25 0 42.5 17.5T540-820v28q80 20 "
        + "130 84.5T720-560v206Zm-276-50Zm36 324q-33 0-56.5-23.5T400-160h160q0 33-23.5 56.5T480-80Zm33-481Z"
    /// Immutable, as `bellPath`.
    nonisolated(unsafe) private static let bellOffPath: CGPath = SVGPath.parse(bellOff)

    /// The bell, `size` square, top-left at (`x`, `y`); `off` = the "notifications off" one (Mute all).
    static func bell(_ ctx: CGContext, x: CGFloat, y: CGFloat, size: CGFloat, color: CGColor, off: Bool = false) {
        var t = CGAffineTransform(translationX: x, y: y).scaledBy(x: size / 960, y: size / 960).translatedBy(
            x: 0, y: 960)
        guard let p = (off ? bellOffPath : bellPath).copy(using: &t) else { return }
        ctx.saveGState(); ctx.addPath(p); ctx.setFillColor(color); ctx.fillPath(); ctx.restoreGState()
    }

    static func colors(_ look: Look, dark: Bool, knockout: Bool) -> (bg: CGColor, fg: CGColor) {
        // Tinted: white (full opacity in either tinted mode); needed = solid pill with the words cut out, taken = a
        // faint pill under solid words, so the two stay apart as in full colour.
        if knockout {
            return look.tonal
                ? (CGColor(gray: 1, alpha: 0.22), CGColor(gray: 1, alpha: 1))
                : (CGColor(gray: 1, alpha: 1), CGColor(gray: 0, alpha: 1))
        }
        let c = TreatmentMarks.color(look.kind, dark: dark)
        return look.tonal
            ? (c.copy(alpha: (dark ? 0x3D : 0x2E) / 255.0)!, TreatmentMarks.tonalInk(look.kind, dark: dark))
            : (c, TreatmentMarks.onColor(dark: dark))
    }
    private static func font(_ h: CGFloat) -> CTFont { Ink.font(h * 0.56, .semibold) }

    /// Width of the full badge `h` tall; `short` = the shorter words.
    static func width(_ look: Look, h: CGFloat, short: Bool = false) -> CGFloat {
        h * 0.4 + h * 0.62 + h * 0.25 + Ink.width(short ? look.short : look.text, font(h)) + h * 0.45
    }

    /// Draw the badge `h` tall with its top-left at (`x`, `y`), at most `maxW` wide: the full words, the short ones, or
    /// (no room for words) only the icon in a circle. Returns the width used, 0 if not even the circle fits.
    /// `knockout` (tinted widgets): see `GraphPalette.knockout`.
    @discardableResult
    static func draw(
        _ ctx: CGContext, x: CGFloat, y: CGFloat, h: CGFloat, maxW: CGFloat, _ look: Look, dark: Bool,
        knockout: Bool = false
    ) -> CGFloat {
        let (bg, fg) = colors(look, dark: dark, knockout: knockout)
        let cut = knockout && !look.tonal
        let words: String?
        if width(look, h: h) <= maxW {
            words = look.text
        } else if width(look, h: h, short: true) <= maxW {
            words = look.short
        } else if h <= maxW {
            words = nil
        } else {
            return 0
        }
        let icon = h * 0.62, f = font(h)
        let pad: CGFloat = h * 0.4 + h * 0.25 + h * 0.45  // split up: CI's compiler times out on one expression
        let w: CGFloat = words.map { (s: String) -> CGFloat in pad + icon + Ink.width(s, f) } ?? h
        if cut { ctx.beginTransparencyLayer(in: CGRect(x: x, y: y, width: w, height: h), auxiliaryInfo: nil) }
        defer { if cut { ctx.endTransparencyLayer() } }
        ctx.saveGState()
        ctx.addPath(
            CGPath(
                roundedRect: CGRect(x: x, y: y, width: w, height: h), cornerWidth: h / 2, cornerHeight: h / 2,
                transform: nil))
        ctx.setFillColor(bg); ctx.fillPath()
        ctx.restoreGState()
        ctx.saveGState()
        defer { ctx.restoreGState() }
        if cut { ctx.setBlendMode(.destinationOut) }
        let ix = words == nil ? x + (h - icon) / 2 : x + h * 0.4
        if look.tonal {
            bell(ctx, x: ix, y: y + (h - icon) / 2, size: icon, color: fg, off: look.kind == "muted")
        } else {
            TreatmentMarks.marker(ctx, look.kind, x: ix + icon / 2, y: y + h / 2, r: icon * 0.42, color: fg)
        }
        if let words {
            Ink.draw(
                ctx, words, x: ix + icon + h * 0.25, y: y + h / 2 + (CTFontGetAscent(f) - CTFontGetDescent(f)) / 2, f,
                fg)
        }
        return w
    }
}

/// Where the last-treatment slot sits among the slots beside the value: one more slot, not a slot kind, so the user's
/// rings stay as they are. `nearValue` = leftmost, next to the number; `end` = rightmost. Raw values = Android's.
public enum LastPlace: String, Codable, CaseIterable, Sendable {
    case off = "OFF", nearValue = "NEAR_VALUE", end = "END"
    var label: String {
        switch self {
        case .off: "Off"
        case .nearValue: "Next to the number"
        case .end: "At the right end"
        }
    }
}

/// One slot beside the value, ready to draw.
enum SlotItem: Equatable {
    case metric(RingSpec, GlucoseMetrics, emphasis: CGFloat)
    case last(LastTreatment.Info)
}

enum Slots {
    private static let ringEmphasis: [CGFloat] = [1, 0.55, 0.35]
    /// The fallback ring (no recent treatment) when it is faded: like a second slot.
    private static let fallbackEmphasis: CGFloat = 0.55

    /// What to draw, right to left (main first), at most `max` slots. `rings` = the user's slots (index, spec,
    /// metrics; main first) already without the ones lacking data. When room is short: main ring, recent treatment,
    /// second ring, fallback ring, third ring.
    static func arrange(
        _ rings: [(Int, RingSpec, GlucoseMetrics)], place: LastPlace, last: LastTreatment.Info?,
        fallback: (RingSpec, GlucoseMetrics)?, max n: Int
    ) -> [SlotItem] {
        let metric = rings.map { (i, s, m) in
            (i, SlotItem.metric(s, m, emphasis: s.faded ? ringEmphasis[min(max(i, 0), 2)] : 1))
        }
        let extra: SlotItem? =
            place == .off
            ? nil
            : last.map { .last($0) }
                ?? fallback.map { (s, m) in .metric(s, m, emphasis: s.faded ? fallbackEmphasis : 1) }
        var isLast = false
        if case .last = extra { isLast = true }
        var priority: [SlotItem] = []
        if let m = metric.first(where: { $0.0 == 0 }) { priority.append(m.1) }
        if isLast, let extra { priority.append(extra) }
        if let m = metric.first(where: { $0.0 == 1 }) { priority.append(m.1) }
        if !isLast, let extra { priority.append(extra) }
        priority += metric.filter { $0.0 >= 2 }.map(\.1)
        let keep = Array(priority.prefix(Swift.max(n, 0)))
        let ordered = metric.map(\.1).filter { keep.contains($0) }
        guard let extra, keep.contains(extra) else { return ordered }
        return place == .end ? [extra] + ordered : ordered + [extra]
    }

    /// Which of `items` is the main ring, the one the sensor countdown ring covers: the first ring, else the only
    /// slot there is; -1 = none (Slots.mainIndex in CardStatus.kt).
    static func mainIndex(_ items: [SlotItem]) -> Int {
        if let i = items.firstIndex(where: { if case .metric = $0 { true } else { false } }) { return i }
        return items.isEmpty ? -1 : 0
    }

    private static func mark(_ item: SlotItem) -> TirMark {
        if case let .metric(spec, m, _) = item, spec.kind == .tir { return TirRing.mark(m.tir, spec) }
        return .none
    }
    private static func laurel(_ item: SlotItem) -> Bool { mark(item) != .none }

    /// Extra room between `items[i]` and the next one to its left when either wears a laurel (`r` = a slot's radius
    /// Slots.extraGap in CardStatus.kt): 0.22 r, so the gap is 0.5 r and two laurels' facing stems touch in its middle.
    static func extraGap(_ items: [SlotItem], _ i: Int, r: CGFloat) -> CGFloat {
        i + 1 < items.count && (laurel(items[i]) || laurel(items[i + 1])) ? r * 0.22 : 0
    }

    /// Room kept left of the leftmost item (the last one) when it wears a laurel (Slots.leftInset on Android): its branch
    /// reaches about half a radius past the ring, so the value's words beside it don't run into the leaves.
    static func leftInset(_ items: [SlotItem], r: CGFloat) -> CGFloat {
        items.last.map { laurel($0) } == true ? r * 0.3 : 0
    }
    /// Room kept at the card's edge (right of the first item) when it wears a laurel: 0.3 r (Slots.edgeInset).
    static func edgeInset(_ items: [SlotItem], r: CGFloat) -> CGFloat {
        items.first.map { laurel($0) } == true ? r * 0.3 : 0
    }

    /// All the `extraGap`s of `items` and the `edgeInset` together (Slots.extraWidth).
    static func extraWidth(_ items: [SlotItem], r: CGFloat) -> CGFloat {
        items.indices.reduce(0) { $0 + extraGap(items, $1, r: r) } + edgeInset(items, r: r)
    }

    /// What a slot shows, for the layout report (Slots.describe in CardStatus.kt, the same words): kind, window, range,
    /// the number in it, its mark, and whether it is the main one (and carries the sensor countdown ring).
    static func describe(_ item: SlotItem, main: Bool, countdown: Double?) -> String {
        var out: String
        switch item {
        case let .metric(spec, m, _):
            out = "\(spec.kind.rawValue) \(Int(spec.window))"
            if spec.kind == .tir { out += " \(spec.range.rawValue)" }
            out +=
                " " + (m.empty ? "-" : spec.kind == .tir ? TirRing.text(m.tir, spec) : MetricGauge.text(spec.kind, m).0)
            let mk = mark(item)
            if mk != .none { out += " \(mk.rawValue)" }
        case let .last(info):
            out = "LAST \(info.tr.k)" + (info.carbs.map { "+\($0.info.tr.k)" } ?? "")
        }
        if main { out += " [main]" }
        if countdown != nil { out += " [countdown]" }
        return out
    }

    /// The slots' centres, right to left from `right` (the card's edge), `edgeInset` in, `gap` + `extraGap` apart.
    static func centers(_ items: [SlotItem], right: CGFloat, size: CGFloat, gap: CGFloat) -> [CGFloat] {
        var out: [CGFloat] = [], x = right - edgeInset(items, r: size / 2) - size / 2
        for i in items.indices { out.append(x); x -= size + gap + extraGap(items, i, r: size / 2) }
        return out
    }

    /// What goes under all the slots, before any `draw` (Slots.drawUnder in CardStatus.kt): the rings' laurels. `cx` =
    /// each item's centre (same order). Between two laurelled rings the facing sides meet (`Laurel.Side.merged`); every
    /// other side is the whole branch, which the neighbour covers where it still reaches it.
    static func drawUnder(
        _ ctx: CGContext, _ items: [SlotItem], cx: [CGFloat], cy: CGFloat, radius: CGFloat, _ pal: GraphPalette
    ) {
        let colors: [CGColor?] = items.map { item in
            guard laurel(item), case let .metric(_, _, e) = item else { return nil }
            return (pal.knockout ? CGColor(gray: 1, alpha: 1) : pal.tight).a(e)
        }
        let order = items.indices.sorted { cx[$0] < cx[$1] }  // left to right
        func side(_ n: Int?) -> Laurel.Side { n.flatMap { colors[$0] } != nil ? .merged : .full }
        for (p, i) in order.enumerated() {
            guard let c = colors[i] else { continue }
            let l = p > 0 ? order[p - 1] : nil, r = p + 1 < order.count ? order[p + 1] : nil
            Laurel.draw(ctx, center: CGPoint(x: cx[i], y: cy), r: radius, c, left: side(l), right: side(r))
        }
        // The flowers: a tip flower on each side, except where two flowered laurels meet: one shared piece there, the
        // flower of the ring that isn't faded, or of the higher number when both are faded or neither is.
        let blooms: [Bloom?] = items.map { item in
            if case let .metric(spec, m, _) = item, spec.kind == .tir, spec.marks { return Bloom.of(m.tir, spec.range) }
            return nil
        }
        func fade(_ i: Int) -> CGFloat { if case let .metric(_, _, e) = items[i] { return e }; return 1 }
        func share(_ i: Int) -> Double {
            if case let .metric(spec, m, _) = items[i] { return m.tir.of(spec.range) }; return 0
        }
        for (p, i) in order.enumerated() {
            guard let b = blooms[i] else { continue }
            let l = p > 0 ? order[p - 1] : nil, r = p + 1 < order.count ? order[p + 1] : nil
            if l.flatMap({ blooms[$0] }) == nil {
                Blooms.tip(ctx, b, cx: cx[i], cy: cy, r: radius, isLeft: true, pal, alpha: fade(i))
            }
            if let r, let rb = blooms[r] {
                let first = fade(i) != fade(r) ? (fade(i) > fade(r) ? b : rb) : (share(i) >= share(r) ? b : rb)
                Blooms.center(ctx, first, lx: cx[i], rx: cx[r], cy: cy, r: radius, pal, la: fade(i), ra: fade(r))
            } else {
                Blooms.tip(ctx, b, cx: cx[i], cy: cy, r: radius, isLeft: false, pal, alpha: fade(i))
            }
        }
    }

    /// Draw `item` as a circle of `radius` around `c`, with the sensor `countdown` ring over it (the main slot's; a
    /// time-in-range ring draws it under its crown).
    static func draw(
        _ ctx: CGContext, _ item: SlotItem, center c: CGPoint, radius: CGFloat, now: TimeInterval, _ pal: GraphPalette,
        d: CGFloat,
        countdown: Double? = nil
    ) {
        switch item {
        case let .metric(spec, m, e) where spec.kind == .tir:
            TirRing.draw(ctx, center: c, radius: radius, tir: m.tir, spec: spec, pal, emphasis: e, countdown: countdown)
            return
        case let .metric(spec, m, e):
            MetricGauge.draw(ctx, center: c, radius: radius, m: m, spec: spec, pal, emphasis: e, d: d)
        case let .last(info): LastSlot.draw(ctx, center: c, radius: radius, info: info, now: now, pal, d: d)
        }
        SensorBadge.drawCountdown(ctx, center: c, radius: radius, countdown, pal)
    }
}

/// The last-treatment slot: the kind's marker, the amount ("4.5u", "30g") in the kind's colour and the time since it
/// ("12m"); around it a ring in the kind's colour for the share of the alert mute still left, shrinking back to 12
/// o'clock. When the mute is over (or the kind mutes nothing) only the grey track is left.
enum LastSlot {
    /// Smallest slot radius (pt) that draws insulin + carbs concentric; smaller slots show the insulin alone (PAIR_MIN_DP).
    static let pairMin: CGFloat = 20

    static func draw(
        _ ctx: CGContext, center c: CGPoint, radius: CGFloat, info: LastTreatment.Info, now: TimeInterval,
        _ pal: GraphPalette,
        d: CGFloat
    ) {
        if let carbs = info.carbs, radius >= pairMin * d {
            return drawPair(ctx, center: c, radius: radius, info, carbs.info, now: now, pal)
        }
        let sw = radius * 0.2, rr = radius - sw / 2
        ctx.saveGState()
        ctx.setLineWidth(sw); ctx.setLineCap(.round)
        // Tinted: hue is lost, so the grey track would be as solid as the coloured arc; a faint white track, white arc.
        ctx.setStrokeColor(pal.knockout ? CGColor(gray: 1, alpha: 0.25) : pal.grid)
        ctx.strokeEllipse(in: CGRect(x: c.x - rr, y: c.y - rr, width: 2 * rr, height: 2 * rr))
        let col = pal.knockout ? CGColor(gray: 1, alpha: 1) : TreatmentMarks.color(info.tr.k, dark: pal.dark)
        let left = info.left(now)
        if left > 0 {
            ctx.setStrokeColor(col)
            let sweep = max(2, 360 * CGFloat(left)) * .pi / 180
            ctx.addArc(center: c, radius: rr, startAngle: -.pi / 2, endAngle: -.pi / 2 + sweep, clockwise: false)
            ctx.strokePath()
        }
        ctx.restoreGState()
        TreatmentMarks.marker(
            ctx, info.tr.k, x: c.x, y: c.y - radius * 0.4, r: radius * 0.14, color: col,
            hole: pal.knockout ? nil : pal.bg.alpha > 0 ? pal.bg : nil)
        let inner = (radius - sw) * 2 * 0.86
        let s = TreatmentMarks.amount(info.tr) ?? ""
        var size = radius * 0.5
        size = min(size, size * inner / max(Ink.width(s, Ink.font(size, .heavy)), 1))
        Ink.draw(ctx, s, x: c.x, y: c.y + radius * 0.17, Ink.font(size, .heavy), col, align: 0.5)
        let sub = radius * 0.3
        Ink.draw(
            ctx, TreatmentMarks.duration(now - info.tr.t), x: c.x, y: c.y + radius * 0.17 + sub * 1.25,
            Ink.font(sub, .semibold), pal.inkSoft, align: 0.5)
    }

    /// Insulin with carbs logged while it works (the user, 2026-10-21; CardStatus.kt `LastSlot.drawPair`): two
    /// concentric rings, the outer one the insulin's mute left, the inner one the carbs', and the two amounts stacked
    /// inside in their colours ("4.5u" over "45g"); no markers or time.
    private static func drawPair(
        _ ctx: CGContext, center c: CGPoint, radius: CGFloat, _ ins: LastTreatment.Info, _ carbs: LastTreatment.Info,
        now: TimeInterval, _ pal: GraphPalette
    ) {
        let outerSw = radius * 0.16, innerSw = radius * 0.13
        let outerR = radius - outerSw / 2
        let innerR = radius - outerSw - radius * 0.05 - innerSw / 2
        func color(_ k: String) -> CGColor {
            pal.knockout ? CGColor(gray: 1, alpha: 1) : TreatmentMarks.color(k, dark: pal.dark)
        }
        ctx.saveGState()
        ctx.setLineCap(.round)
        for (info, r, sw) in [(ins, outerR, outerSw), (carbs, innerR, innerSw)] {
            ctx.setLineWidth(sw)
            ctx.setStrokeColor(pal.knockout ? CGColor(gray: 1, alpha: 0.25) : pal.grid)
            ctx.strokeEllipse(in: CGRect(x: c.x - r, y: c.y - r, width: 2 * r, height: 2 * r))
            let left = info.left(now)
            if left > 0 {
                ctx.setStrokeColor(color(info.tr.k))
                let sweep = max(2, 360 * CGFloat(left)) * .pi / 180
                ctx.addArc(center: c, radius: r, startAngle: -.pi / 2, endAngle: -.pi / 2 + sweep, clockwise: false)
                ctx.strokePath()
            }
        }
        ctx.restoreGState()
        // the two amounts in the inner circle, one size so they read as a pair
        let room = (innerR - innerSw / 2) * 2 * 0.84
        let a = TreatmentMarks.amount(ins.tr) ?? "", b = TreatmentMarks.amount(carbs.tr) ?? ""
        var size = radius * 0.36
        let widest = max(Ink.width(a, Ink.font(size, .heavy)), Ink.width(b, Ink.font(size, .heavy)), 1)
        size = min(size, size * room / widest)
        let font = Ink.font(size, .heavy)
        let cap = size * 0.72, gap = size * 0.28
        Ink.draw(ctx, a, x: c.x, y: c.y - gap / 2, font, color(ins.tr.k), align: 0.5)
        Ink.draw(ctx, b, x: c.x, y: c.y + gap / 2 + cap, font, color(carbs.tr.k), align: 0.5)
    }
}

/// A minimal SVG path-data parser (M L H V Q T Z, absolute and relative) for the Material Symbols icons.
enum SVGPath {
    static func parse(_ d: String) -> CGPath {
        let p = CGMutablePath()
        var cmd: Character = "M"
        var nums: [CGFloat] = []
        var tokens: [(Character, [CGFloat])] = []
        var num = ""
        func flushNum() { if let v = Double(num) { nums.append(CGFloat(v)) }; num = "" }
        for ch in d {
            if ch.isLetter && ch != "e" {
                flushNum(); tokens.append((cmd, nums)); nums = []; cmd = ch
            } else if ch == "-" && !num.isEmpty && num.last != "e" {
                flushNum(); num = "-"
            } else if ch == "." && num.contains(".") {
                flushNum(); num = "."
            } else if ch == " " || ch == "," {
                flushNum()
            } else {
                num.append(ch)
            }
        }
        flushNum(); tokens.append((cmd, nums))
        var cur = CGPoint.zero, start = CGPoint.zero, lastCtrl: CGPoint? = nil
        for (c, a) in tokens {
            let rel = c.isLowercase
            func pt(_ i: Int) -> CGPoint {
                rel ? CGPoint(x: cur.x + a[i], y: cur.y + a[i + 1]) : CGPoint(x: a[i], y: a[i + 1])
            }
            switch c.uppercased() {
            case "M":
                var i = 0
                while i + 1 < a.count {
                    let q = pt(i)
                    if i == 0 { p.move(to: q); start = q } else { p.addLine(to: q) }
                    cur = q; i += 2
                }
                lastCtrl = nil
            case "L":
                var i = 0
                while i + 1 < a.count { let q = pt(i); p.addLine(to: q); cur = q; i += 2 }
                lastCtrl = nil
            case "H":
                for v in a { cur = CGPoint(x: rel ? cur.x + v : v, y: cur.y); p.addLine(to: cur) }
                lastCtrl = nil
            case "V":
                for v in a { cur = CGPoint(x: cur.x, y: rel ? cur.y + v : v); p.addLine(to: cur) }
                lastCtrl = nil
            case "Q":
                var i = 0
                while i + 3 < a.count {
                    let ctrl = pt(i), end = pt(i + 2)
                    p.addQuadCurve(to: end, control: ctrl); lastCtrl = ctrl; cur = end; i += 4
                }
            case "T":
                var i = 0
                while i + 1 < a.count {
                    let ctrl = lastCtrl.map { CGPoint(x: 2 * cur.x - $0.x, y: 2 * cur.y - $0.y) } ?? cur
                    let end = pt(i)
                    p.addQuadCurve(to: end, control: ctrl); lastCtrl = ctrl; cur = end; i += 2
                }
            case "Z":
                p.closeSubpath(); cur = start; lastCtrl = nil
            default: break
            }
        }
        return p
    }
}
