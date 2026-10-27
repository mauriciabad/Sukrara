// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import CoreGraphics
import CoreText
import Foundation

// MARK: - Ring

/// The mark a time-in-range ring wears (TirMark in GlucoseCard.kt; spikes/graph-fixtures/mark), by the share the ring
/// shows: a laurel from 90 %, a laurel with a flower on each branch tip from 95 %, and at 100 % (no reading in the
/// window outside the ring's range) the crown with the flowered laurel, by that share alone (the user, 2026-10-19:
/// lows don't take it away), every mark the report's 70 % of data. The 100 in the % column beside the chart wears the
/// crown on the same rule (`column`).
enum TirMark: String, Comparable {
    case none, laurel, flowers, crown

    static func < (a: TirMark, b: TirMark) -> Bool { allCases.firstIndex(of: a)! < allCases.firstIndex(of: b)! }
    static let allCases: [TirMark] = [.none, .laurel, .flowers, .crown]

    static func of(_ tir: TimeInRange, _ range: TirRange) -> TirMark {
        guard tir.coverage >= Goal.minCoverage else { return .none }
        let x = tir.of(range)
        if x >= 1 { return .crown }
        let shown = (x * 100).rounded()
        return shown >= 95 ? .flowers : shown >= 90 ? .laurel : .none
    }

    static func column(_ tir: TimeInRange) -> Bool { of(tir, .inRange) == .crown }
}

/// The flower a laurel wears from 95 % (Bloom in Blooms.kt; spikes/graph-fixtures/mark): 95 lavender, 96
/// forget-me-not, 97 daisy, 98 cherry blossom, 99 golden rosette; the crowned 100 % keeps the rosette; 99.6 counts as 99.
enum Bloom: String {
    case lavender, forgetMeNot = "forget_me_not", daisy, cherry, rosette

    static func of(_ tir: TimeInRange, _ range: TirRange) -> Bloom? {
        let m = TirMark.of(tir, range)
        guard m >= .flowers else { return nil }
        if m == .crown { return .rosette }
        switch min(99, Int((tir.of(range) * 100).rounded())) {
        case 95: return .lavender
        case 96: return .forgetMeNot
        case 97: return .daisy
        case 98: return .cherry
        default: return .rosette
        }
    }
}

/// The flower drawings (Blooms in Blooms.kt), in units of a ring's radius r: a tip flower just past a laurel stem's end
/// (lavender grows from the stem's end, tangent to it) and the piece shared where two flowered laurels meet. Fixed
/// colours ("reward", not a glucose range); tinted widgets draw them white.
enum Blooms {
    private static func hex(_ v: UInt32) -> CGColor { GraphPalette.hex(v) }
    private static let white = CGColor(gray: 1, alpha: 1)

    static func tip(
        _ ctx: CGContext, _ b: Bloom, cx: CGFloat, cy: CGFloat, r: CGFloat, isLeft: Bool, _ pal: GraphPalette,
        alpha: CGFloat
    ) {
        faded(ctx, alpha) {
            if b == .lavender {
                spike(ctx, at(cx, cy, r * 1.25, isLeft ? 214 : -34), r, isLeft ? -56 : -124, pal)
            } else {
                flower(ctx, b, at(cx, cy, r * 1.25, isLeft ? 219 : -39), unit(r, 1), pal)
            }
        }
    }

    /// Where two flowered laurels meet (rings at `lx`, `rx`): lavender grows a spike from each meeting branch, in its
    /// own ring's fade; the others draw one centre piece between the two inner tips.
    static func center(
        _ ctx: CGContext, _ b: Bloom, lx: CGFloat, rx: CGFloat, cy: CGFloat, r: CGFloat, _ pal: GraphPalette,
        la: CGFloat, ra: CGFloat
    ) {
        if b == .lavender {
            tip(ctx, b, cx: lx, cy: cy, r: r, isLeft: false, pal, alpha: la)
            tip(ctx, b, cx: rx, cy: cy, r: r, isLeft: true, pal, alpha: ra)
            return
        }
        let p1 = at(lx, cy, r * 1.25, -39), p2 = at(rx, cy, r * 1.25, 219)
        faded(ctx, max(la, ra)) { centerPiece(ctx, b, CGPoint(x: (p1.x + p2.x) / 2, y: p1.y), unit(r, 1.3), pal) }
    }

    private static func faded(_ ctx: CGContext, _ a: CGFloat, _ block: () -> Void) {
        ctx.saveGState(); ctx.setAlpha(a); ctx.beginTransparencyLayer(auxiliaryInfo: nil)
        block()
        ctx.endTransparencyLayer(); ctx.restoreGState()
    }
    private static func at(_ x: CGFloat, _ y: CGFloat, _ d: CGFloat, _ deg: CGFloat) -> CGPoint {
        CGPoint(x: x + d * cos(deg * .pi / 180), y: y + d * sin(deg * .pi / 180))
    }
    private static func unit(_ r: CGFloat, _ k: CGFloat) -> CGFloat { r * 0.14 * k }
    private static func c(_ pal: GraphPalette, _ v: UInt32) -> CGColor { pal.knockout ? white : hex(v) }
    /// Tinted widgets keep only the opacity: there the flowers are white with their details cut out (`cut`) or half
    /// see-through, so they read as flowers rather than one solid shape.
    private static func half(_ pal: GraphPalette, _ v: UInt32) -> CGColor {
        pal.knockout ? CGColor(gray: 1, alpha: 0.5) : hex(v)
    }
    private static func cut(_ ctx: CGContext, _ pal: GraphPalette, _ block: () -> Void) {
        guard pal.knockout else { return }
        ctx.saveGState(); ctx.setBlendMode(.clear); block(); ctx.restoreGState()
    }
    private static func edge(_ pal: GraphPalette) -> CGColor {
        pal.dark ? (pal.bg.alpha > 0 ? pal.bg : CGColor(gray: 0, alpha: 1)) : hex(0xC9D3CB)
    }
    private static func circle(_ ctx: CGContext, _ p: CGPoint, _ rad: CGFloat, _ color: CGColor) {
        ctx.setFillColor(color)
        ctx.fillEllipse(in: CGRect(x: p.x - rad, y: p.y - rad, width: 2 * rad, height: 2 * rad))
    }
    private static func ellipse(
        _ ctx: CGContext, _ p: CGPoint, _ rx: CGFloat, _ ry: CGFloat, _ rot: CGFloat,
        fill: CGColor, stroke: CGColor? = nil, lineW: CGFloat = 0
    ) {
        ctx.saveGState()
        ctx.translateBy(x: p.x, y: p.y); ctx.rotate(by: rot * .pi / 180)
        let rect = CGRect(x: -rx, y: -ry, width: 2 * rx, height: 2 * ry)
        ctx.setFillColor(fill); ctx.fillEllipse(in: rect)
        if let stroke { ctx.setStrokeColor(stroke); ctx.setLineWidth(lineW); ctx.strokeEllipse(in: rect) }
        ctx.restoreGState()
    }
    private static func leafPair(_ ctx: CGContext, _ p: CGPoint, _ u: CGFloat, _ pal: GraphPalette) {
        for s: CGFloat in [-1, 1] {
            ellipse(
                ctx, CGPoint(x: p.x + s * u * 1.55, y: p.y + u * 0.95), u * 1.3, u * 0.5, s * 28,
                fill: pal.knockout ? CGColor(gray: 1, alpha: 0.5) : pal.tight)
        }
    }

    private static func flower(_ ctx: CGContext, _ b: Bloom, _ p: CGPoint, _ u: CGFloat, _ pal: GraphPalette) {
        switch b {
        case .forgetMeNot: forgetMeNot(ctx, p, u, pal)
        case .daisy: daisy(ctx, p, u, double: false, pal)
        case .cherry: blossom(ctx, p, u, 0, pal)
        case .rosette: rosette(ctx, p, u, pal)
        case .lavender: spike(ctx, p, u / 0.14, -90, pal)
        }
    }

    private static func centerPiece(_ ctx: CGContext, _ b: Bloom, _ p: CGPoint, _ u: CGFloat, _ pal: GraphPalette) {
        switch b {
        case .forgetMeNot:
            leafPair(ctx, p, u, pal)
            for (a, bb) in [(-1.45, 0.55), (1.45, 0.55), (-0.75, -0.7), (0.75, -0.7), (0, 0.25)] as [(CGFloat, CGFloat)]
            {
                forgetMeNot(ctx, CGPoint(x: p.x + a * u, y: p.y + bb * u), u * 0.6, pal)
            }
        case .daisy:
            leafPair(ctx, p, u, pal)
            for (a, bb, s) in [(-1.85, 0.55, 0.5), (1.85, 0.55, 0.5), (-1.2, -1.25, 0.34), (1.2, -1.25, 0.34)]
                as [(CGFloat, CGFloat, CGFloat)]
            {
                daisy(ctx, CGPoint(x: p.x + a * u, y: p.y + bb * u), u * s, double: false, pal)
            }
            daisy(ctx, p, u * 0.85, double: true, pal)
        case .cherry:
            leafPair(ctx, p, u, pal)
            blossom(ctx, CGPoint(x: p.x - u * 1.6, y: p.y + u * 0.55), u * 0.58, 20, pal)
            blossom(ctx, CGPoint(x: p.x + u * 1.6, y: p.y + u * 0.55), u * 0.58, -20, pal)
            blossom(ctx, CGPoint(x: p.x, y: p.y - u * 0.2), u, 0, pal)
        case .rosette:
            // an award rosette with two ribbon tails hanging into the gap
            for (s, v) in [(CGFloat(1), UInt32(0xC98A12)), (-1, 0xB57A0E)] {
                let pts: [(CGFloat, CGFloat)] = [(-0.25, 0.3), (-1.35, 2.75), (-0.9, 2.45), (-0.62, 3.0), (0.35, 0.55)]
                ctx.beginPath()
                for (i, (a, bb)) in pts.enumerated() {
                    let q = CGPoint(x: p.x + s * a * u, y: p.y + bb * u)
                    if i == 0 { ctx.move(to: q) } else { ctx.addLine(to: q) }
                }
                ctx.closePath(); ctx.setFillColor(s > 0 ? c(pal, v) : half(pal, v)); ctx.fillPath()
            }
            rosette(ctx, p, u * 1.1, pal)
        case .lavender: spike(ctx, p, u / 0.14, -90, pal)
        }
    }

    /// Lavender: a bare stem continuing the branch along `dir`, then five pairs of violet buds.
    private static func spike(_ ctx: CGContext, _ p: CGPoint, _ r: CGFloat, _ dir: CGFloat, _ pal: GraphPalette) {
        let u = unit(r, 1), e = at(p.x, p.y, u * 3.6, dir)
        ctx.saveGState()
        ctx.setStrokeColor(pal.knockout ? white : pal.tight); ctx.setLineWidth(u * 0.16); ctx.setLineCap(.round)
        ctx.move(to: p); ctx.addLine(to: e); ctx.strokePath()
        ctx.restoreGState()
        for i in 0..<5 {
            let bp = at(p.x, p.y, u * (0.9 + CGFloat(i) * 0.68), dir), sz = u * (0.58 - CGFloat(i) * 0.07)
            for side: CGFloat in [-1, 1] {
                ellipse(
                    ctx, at(bp.x, bp.y, sz * 0.55, dir + side * 90), sz, sz * 0.62, dir + side * 35,
                    fill: i % 2 == 1 ? half(pal, 0xA98BE0) : c(pal, 0x8E6CD4))
            }
        }
    }

    private static func forgetMeNot(_ ctx: CGContext, _ p: CGPoint, _ u: CGFloat, _ pal: GraphPalette) {
        for i in 0..<5 { circle(ctx, at(p.x, p.y, u, -90 + CGFloat(i) * 72), u * 0.8, c(pal, 0x7DB8F2)) }
        if pal.knockout {
            // the white ring becomes a hole, the yellow dot stays
            cut(ctx, pal) { circle(ctx, p, u * 0.55, white) }
        } else {
            circle(ctx, p, u * 0.55, white)
        }
        circle(ctx, p, u * 0.28, c(pal, 0xF2C230))
    }

    private static func daisy(_ ctx: CGContext, _ p: CGPoint, _ u: CGFloat, double: Bool, _ pal: GraphPalette) {
        let n = double ? 16 : 10
        for i in 0..<n {
            let a = -90 + CGFloat(i) * 360 / CGFloat(n)
            ellipse(
                ctx, at(p.x, p.y, u * 1.15, a), u, u * (double ? 0.3 : 0.38), a, fill: white,
                stroke: pal.knockout ? nil : edge(pal), lineW: u * 0.12)
            if pal.knockout {  // tinted: each petal's edge cut, so the petals stay apart
                ctx.saveGState(); ctx.setBlendMode(.clear)
                ellipse(
                    ctx, at(p.x, p.y, u * 1.15, a), u, u * (double ? 0.3 : 0.38), a, fill: CGColor(gray: 1, alpha: 0),
                    stroke: white, lineW: u * 0.14)
                ctx.restoreGState()
                ellipse(ctx, at(p.x, p.y, u * 1.15, a), u * 0.9, u * (double ? 0.22 : 0.29), a, fill: white)
            }
        }
        if double {
            for i in 0..<10 {
                let a = -72 + CGFloat(i) * 36
                ellipse(
                    ctx, at(p.x, p.y, u * 0.78, a), u * 0.62, u * 0.26, a, fill: pal.knockout ? white : hex(0xFFFDF4),
                    stroke: edge(pal), lineW: u * 0.12)
            }
        }
        // tinted: a gap between the petals and the heart, and the seeds cut out of it
        cut(ctx, pal) { circle(ctx, p, u * 0.8, white) }
        circle(ctx, p, u * 0.62, c(pal, 0xF2C230))
        if double {
            for i in 0..<6 { circle(ctx, at(p.x, p.y, u * 0.3, -90 + CGFloat(i) * 60), u * 0.09, c(pal, 0xC9940F)) }
        }
        cut(ctx, pal) {
            if double { for i in 0..<6 { circle(ctx, at(p.x, p.y, u * 0.3, -90 + CGFloat(i) * 60), u * 0.09, white) } }
        }
    }

    private static func blossom(_ ctx: CGContext, _ p: CGPoint, _ u: CGFloat, _ rot: CGFloat, _ pal: GraphPalette) {
        let l = u * 2, w = u * 0.95
        for i in 0..<5 {
            ctx.saveGState()
            ctx.translateBy(x: p.x, y: p.y); ctx.rotate(by: (CGFloat(i) * 72 + rot) * .pi / 180)
            ctx.beginPath()
            ctx.move(to: .zero)
            ctx.addCurve(
                to: CGPoint(x: w * 0.35, y: -l), control1: CGPoint(x: w, y: -l * 0.25),
                control2: CGPoint(x: w * 1.05, y: -l * 0.9))
            ctx.addLine(to: CGPoint(x: 0, y: -l * 0.82)); ctx.addLine(to: CGPoint(x: -w * 0.35, y: -l))
            ctx.addCurve(
                to: .zero, control1: CGPoint(x: -w * 1.05, y: -l * 0.9), control2: CGPoint(x: -w, y: -l * 0.25))
            ctx.closePath(); ctx.setFillColor(c(pal, 0xF7A9C4)); ctx.fillPath()
            ctx.restoreGState()
        }
        if pal.knockout {
            // tinted: the heart and the stamens cut out of the white petals
            cut(ctx, pal) {
                circle(ctx, p, u * 0.42, white)
                for i in 0..<5 { circle(ctx, at(p.x, p.y, u * 0.55, -90 + rot + CGFloat(i) * 72), u * 0.12, white) }
                for i in 0..<5 {  // a thin line between the petals
                    let a = at(p.x, p.y, u * 2.2, -90 + rot + 36 + CGFloat(i) * 72)
                    ctx.setLineWidth(u * 0.12); ctx.move(to: p); ctx.addLine(to: a); ctx.strokePath()
                }
            }
            circle(ctx, p, u * 0.2, white)
            return
        }
        for i in 0..<5 { circle(ctx, at(p.x, p.y, u * 0.55, -90 + rot + CGFloat(i) * 72), u * 0.1, c(pal, 0xC93F70)) }
        circle(ctx, p, u * 0.42, c(pal, 0xD9487A))
    }

    private static func rosette(_ ctx: CGContext, _ p: CGPoint, _ u: CGFloat, _ pal: GraphPalette) {
        for i in 0..<8 { circle(ctx, at(p.x, p.y, u * 1.1, -90 + CGFloat(i) * 45), u * 0.62, c(pal, 0xE7B53C)) }
        // tinted: the inner row as outlined petals and a ring round the heart
        cut(ctx, pal) {
            for i in 0..<8 { circle(ctx, at(p.x, p.y, u * 0.62, -67.5 + CGFloat(i) * 45), u * 0.48, white) }
        }
        for i in 0..<8 {
            circle(
                ctx, at(p.x, p.y, u * 0.62, -67.5 + CGFloat(i) * 45), u * (pal.knockout ? 0.3 : 0.42), c(pal, 0xF6D576))
        }
        cut(ctx, pal) { circle(ctx, p, u * 0.5, white) }
        circle(ctx, p, u * (pal.knockout ? 0.3 : 0.42), c(pal, 0xC98A12))
    }
}

/// The crown (Crown in GlucoseCard.kt): a 12 × 7.4 outline with rounded joins, `w` wide, bottom centre at (`x`, `bottom`).
enum Crown {
    private static let pts: [(CGFloat, CGFloat)] = [
        (0.7, 7.4), (0, 1.7), (3.3, 4.3), (6, 0), (8.7, 4.3), (12, 1.7), (11.3, 7.4),
    ]
    static let h: CGFloat = 7.4 / 12

    /// `grow` > 0: the outline widened by that much on every side (a cut-out's margin).
    static func fill(_ ctx: CGContext, x: CGFloat, bottom: CGFloat, w: CGFloat, _ color: CGColor, grow: CGFloat = 0) {
        let k = w / 12
        ctx.saveGState()
        ctx.beginPath()
        for (i, p) in pts.enumerated() {
            let q = CGPoint(x: x + (p.0 - 6) * k, y: bottom + (p.1 - 7.4) * k)
            if i == 0 { ctx.move(to: q) } else { ctx.addLine(to: q) }
        }
        ctx.closePath()
        ctx.setLineJoin(.round)
        ctx.setLineWidth(w * 0.8 / 12 + 2 * grow)
        ctx.setFillColor(color); ctx.setStrokeColor(color)
        ctx.drawPath(using: .fillStroke)
        ctx.restoreGState()
    }
}

/// The laurel (Laurel in GlucoseCard.kt): two branches of leaves up the sides of a ring of outer radius `r`, open at
/// the top and bottom, the stems on a circle of 1.25 `r`. Next to a slot without a laurel a side stays whole (the slots
/// move apart, `Slots.extraGap`). Between two laurelled rings the slots sit so their facing stems touch in the middle of
/// the gap, so the wreaths meet there; those sides (`.merged`) keep their stem and inner leaves but leave out the outer
/// leaves that would cross into the other branch. A faded colour fades the whole laurel at once (a layer).
enum Laurel {
    enum Side { case full, merged }

    static func draw(
        _ ctx: CGContext, center c: CGPoint, r: CGFloat, _ color: CGColor, left: Side = .full, right: Side = .full
    ) {
        ctx.saveGState()
        ctx.setAlpha(color.alpha)
        ctx.beginTransparencyLayer(auxiliaryInfo: nil)
        let col = color.copy(alpha: 1) ?? color
        branch(ctx, c, r, col, isLeft: true, left)
        branch(ctx, c, r, col, isLeft: false, right)
        ctx.endTransparencyLayer()
        ctx.restoreGState()
    }

    /// One side's branch: climbing from below the middle (135° / 45°) to above it (210° / -30°), an outer and an inner
    /// leaf per step leaning up it, the inner ones clear of the ring; a merged side keeps only its two lowest and its
    /// highest outer leaves. The flowers are `Blooms`'.
    private static func branch(
        _ ctx: CGContext, _ c: CGPoint, _ r: CGFloat, _ color: CGColor, isLeft: Bool, _ side: Side
    ) {
        ctx.saveGState()
        ctx.setStrokeColor(color); ctx.setFillColor(color)
        ctx.setLineWidth(r * 0.032); ctx.setLineCap(.round)
        let a0: CGFloat = isLeft ? 131 : -34
        ctx.addArc(
            center: c, radius: r * 1.25, startAngle: a0 * .pi / 180, endAngle: (a0 + 83) * .pi / 180, clockwise: false)
        ctx.strokePath()
        func at(_ rad: CGFloat, _ deg: CGFloat, _ rot: CGFloat) {
            let a = deg * .pi / 180
            ctx.saveGState()
            ctx.translateBy(x: c.x + rad * cos(a), y: c.y + rad * sin(a))
            ctx.rotate(by: rot * .pi / 180)
            ctx.fillEllipse(in: CGRect(x: -r * 0.135, y: -r * 0.056, width: r * 0.27, height: r * 0.112))
            ctx.restoreGState()
        }
        let s: CGFloat = isLeft ? 1 : -1
        for k in 0..<6 {
            let a = isLeft ? 135 + 15 * CGFloat(k) : 45 - 15 * CGFloat(k)
            if side == .full || k <= 1 || k == 5 { at(r * 1.35, a, a + s * 90 - s * 32) }
            at(r * 1.17, a + s * 5, a + s * 90 + s * 32)
        }
        ctx.restoreGState()
    }
}

/// The segmented time-in-range ring (TirRing in GlucoseCard.kt), with the sensor `countdown` ring over it. A ring with
/// a `TirMark` leaves the ring its full size and may overflow the slot: the laurel up its sides (from 95 %, also at
/// 100 %; `Slots.drawUnder`, drawn for every slot before the rings, so what is next to it covers it), and at 100 % the crown
/// in the text colour set into 12 o'clock like a cap, cut out of the ring and the countdown.
enum TirRing {
    static func draw(
        _ ctx: CGContext, center c: CGPoint, radius r: CGFloat, tir: TimeInRange, spec: RingSpec,
        _ pal: GraphPalette, emphasis: CGFloat, countdown: Double? = nil
    ) {
        let mark = mark(tir, spec)
        let sw = r * 0.2
        let rr = r - sw / 2
        let crownW = r * 0.74, crownBottom = c.y - r * 0.72
        if mark == .crown { ctx.beginTransparencyLayer(auxiliaryInfo: nil) }
        ctx.saveGState()
        ctx.setLineWidth(sw)
        ctx.setLineCap(.butt)
        // Clockwise from 12 o'clock in the user's order (`ringOrder`); gaps between below | very good | in range | above.
        let order = Set(spec.order).count == 6 ? spec.order : DEFAULT_RING_ORDER
        let shares = order.map { ($0, tir.share($0)) }.filter { $0.1 >= 0.004 }
        func gapAfter(_ i: Int) -> Bool {
            guard shares.count > 1 else { return false }
            let (x, y) = (shares[i].0, shares[(i + 1) % shares.count].0)
            // also between very good and the rest of in range (the user, 0.11.9); the two lows / two highs touch
            return x.group != y.group || Set([x, y]) == Set([Band.tight, Band.target])
        }
        if shares.isEmpty {
            ctx.setStrokeColor(pal.grid.a(emphasis))
            ctx.strokeEllipse(in: CGRect(x: c.x - rr, y: c.y - rr, width: 2 * rr, height: 2 * rr))
        } else {
            let gap: CGFloat = 7
            let gaps = CGFloat(shares.indices.filter(gapAfter).count) * gap
            var a: CGFloat = -90
            for (i, s) in shares.enumerated() {
                let sweep = max(1.5, (360 - gaps) * CGFloat(s.1))
                ctx.setStrokeColor(pal.of(s.0).a(emphasis))
                // y-down: increasing angle runs clockwise on screen, like Android's drawArc.
                ctx.addArc(
                    center: c, radius: rr, startAngle: a * .pi / 180, endAngle: (a + sweep) * .pi / 180,
                    clockwise: false)
                ctx.strokePath()
                a += sweep + (gapAfter(i) ? gap : 0)
            }
        }
        ctx.restoreGState()
        SensorBadge.drawCountdown(ctx, center: c, radius: r, countdown, pal)
        if mark == .crown {
            ctx.saveGState()
            ctx.setBlendMode(.destinationOut)
            Crown.fill(ctx, x: c.x, bottom: crownBottom, w: crownW, CGColor(gray: 0, alpha: 1), grow: r * 0.06)
            ctx.restoreGState()
            ctx.endTransparencyLayer()
            // faded as one layer: fill and outline overlapping at partial alpha would show a darker rim
            ctx.saveGState()
            ctx.setAlpha(emphasis)
            ctx.beginTransparencyLayer(auxiliaryInfo: nil)
            Crown.fill(ctx, x: c.x, bottom: crownBottom, w: crownW, pal.knockout ? CGColor(gray: 1, alpha: 1) : pal.ink)
            ctx.endTransparencyLayer()
            ctx.restoreGState()
        }
        let num = Ink.font(r * 0.78, .heavy, rounded: true)
        let sub = Ink.font(r * 0.34, .semibold, rounded: true)
        let v = tir.empty ? "–" : text(tir, spec)
        let base = c.y + r * 0.78 * 0.28
        // "69.7", "<0.1": the decimals fit inside the ring (TirRing.draw on Android)
        var size = v.count > 3 ? r * 0.78 * 0.7 : r * 0.78
        // "100" too
        size = min(size, size * (r - sw) * 1.5 / max(Ink.width(v, Ink.font(size, .heavy, rounded: true)), 1))
        Ink.draw(
            ctx, v, x: c.x, y: base, size == r * 0.78 ? num : Ink.font(size, .heavy, rounded: true),
            pal.ink.a(emphasis), align: 0.5)
        Ink.draw(
            ctx, spec.period, x: c.x, y: base + r * 0.34 * 1.1, sub,
            (spec.range == .tight ? pal.tight : pal.inkSoft).a(emphasis), align: 0.5)
    }
}

extension TirRing {
    /// The ring's `TirMark` (none without data).
    static func mark(_ tir: TimeInRange, _ spec: RingSpec) -> TirMark {
        tir.empty || !spec.marks ? .none : TirMark.of(tir, spec.range)
    }
    /// The number in the middle: in range against its > 70 % goal, very good on its own (TirRing.text).
    static func text(_ tir: TimeInRange, _ spec: RingSpec) -> String {
        ShareText.of(tir.of(spec.range), spec.range == .inRange ? .inRange : nil)
    }
}

/// A report number in a slot (MetricGauge in GlucoseCard.kt): the value in the middle, kind + window under it, an arc
/// on the number's scale (green = consensus goal met, orange = not) and a tick at the goal.
enum MetricGauge {
    static func scale(_ k: SlotKind) -> (Double, Double, Double) {
        switch k {
        case .gmi: (5, 10, 7)
        case .average: (70, 250, 154)
        default: (0, 60, 36)
        }
    }
    static func value(_ k: SlotKind, _ m: GlucoseMetrics) -> Double {
        switch k {
        case .gmi: m.gmi
        case .average: m.mean
        default: m.cv
        }
    }
    static func met(_ k: SlotKind, _ m: GlucoseMetrics) -> Bool {
        switch k {
        case .gmi: Goal.gmi.met(m)
        case .average: Goal.average.met(m)
        default: Goal.cv.met(m)
        }
    }
    static func text(_ k: SlotKind, _ m: GlucoseMetrics) -> (String, String) {
        switch k {
        case .gmi: (String(format: "%.1f", m.gmi), "%")
        case .average: ("\(Int(m.mean.rounded()))", "")
        default: ("\(Int(m.cv.rounded()))", "%")
        }
    }

    static func draw(
        _ ctx: CGContext, center c: CGPoint, radius: CGFloat, m: GlucoseMetrics, spec: RingSpec,
        _ pal: GraphPalette, emphasis: CGFloat, d: CGFloat
    ) {
        let sw = radius * 0.2, rr = radius - sw / 2
        ctx.saveGState()
        ctx.setLineWidth(sw)
        ctx.setLineCap(.round)
        ctx.setStrokeColor(pal.grid.a(emphasis))
        ctx.strokeEllipse(in: CGRect(x: c.x - rr, y: c.y - rr, width: 2 * rr, height: 2 * rr))
        let (lo, hi, goal) = scale(spec.kind)
        if !m.empty {
            let f = min(max((value(spec.kind, m) - lo) / (hi - lo), 0.02), 1)
            ctx.setStrokeColor((met(spec.kind, m) ? pal.tight : pal.high).a(emphasis))
            ctx.addArc(
                center: c, radius: rr, startAngle: -.pi / 2, endAngle: -.pi / 2 + 2 * .pi * CGFloat(f), clockwise: false
            )
            ctx.strokePath()
        }
        // goal tick across the track
        let ga = -Double.pi / 2 + 2 * .pi * (goal - lo) / (hi - lo)
        let r0 = radius - sw * 1.15, r1 = radius + sw * 0.15
        ctx.setLineCap(.butt)
        ctx.setLineWidth(1.6 * d)
        ctx.setStrokeColor(pal.ink.a(emphasis))
        ctx.strokeLineSegments(between: [
            CGPoint(x: c.x + r0 * CGFloat(cos(ga)), y: c.y + r0 * CGFloat(sin(ga))),
            CGPoint(x: c.x + r1 * CGFloat(cos(ga)), y: c.y + r1 * CGFloat(sin(ga))),
        ])
        ctx.restoreGState()
        let num = Ink.font(radius * 0.62, .heavy, rounded: true)
        let unit = Ink.font(radius * 0.3, .semibold, rounded: true)
        let (v, u) = m.empty ? ("–", "") : text(spec.kind, m)
        let w = Ink.width(v, num) + Ink.width(u, unit)
        let base = c.y + radius * 0.62 * 0.3
        Ink.draw(ctx, v, x: c.x - w / 2, y: base, num, pal.ink.a(emphasis))
        Ink.draw(ctx, u, x: c.x - w / 2 + Ink.width(v, num), y: base, unit, pal.ink.a(emphasis))
        Ink.draw(
            ctx, "\(spec.kind.short) \(spec.period)", x: c.x, y: base + radius * 0.3 * 1.25, unit,
            pal.inkSoft.a(emphasis), align: 0.5)
    }
}
