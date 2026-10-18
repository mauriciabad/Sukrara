// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import AppKit

/// The doctor's report under the dropdown's card (investigation 18), compact, in the layout of LibreView's AGP page
/// (and Sukrara for Android's ReportCard.kt): time in the five ranges as a stacked bar with each share and its
/// consensus goal, then average glucose, GMI and glucose variability. A met goal is green with ✓, a missed one
/// orange. The window is `reportDays` (default 14, like the report).
enum GlucoseReport {
    static let width: CGFloat = 340

    /// Draws into a y-down context from y = 0 and returns the height used.
    @discardableResult
    static func draw(_ ctx: CGContext, width w: CGFloat, m: GlucoseMetrics, bands b: Bands, days: Int, _ pal: GraphPalette) -> CGFloat {
        let title = Ink.font(13, .semibold), name = Ink.font(11.5, .regular), strong = Ink.font(11.5, .semibold)
        let small = Ink.font(10, .regular), chip = Ink.font(9.5, .semibold)
        let big = Ink.font(13, .bold), mid = Ink.font(11.5, .semibold)
        var y: CGFloat = 14
        Ink.draw(ctx, "Glucose report (\(days)d)", x: 0, y: y, title, pal.ink)
        if !m.empty {
            Ink.draw(ctx, "sensor active \(ShareText.coverage(m.tir.coverage)) %", x: w, y: y, small, pal.inkSoft, align: 1)
        }
        y += 8
        guard !m.empty else {
            Ink.draw(ctx, "No readings in this window yet", x: 0, y: y + 14, name, pal.inkSoft)
            return y + 22
        }
        if m.tir.coverage < Goal.minCoverage {
            y += 13
            Ink.draw(ctx, "Only \(ShareText.coverage(m.tir.coverage)) % of the window has sensor data; the report needs 70 %.",
                     x: 0, y: y, small, pal.high)
        }
        y += 6

        func goalChip(_ g: Goal, right: CGFloat, mid cy: CGFloat, extra: String? = nil) {
            let met = g.met(m)
            let c = met ? pal.tight : pal.high
            let text = (met ? "✓ " : "") + g.text + (extra.map { " · \($0)" } ?? "")
            let tw = Ink.width(text, chip)
            let r = CGRect(x: right - tw - 10, y: cy - 7.5, width: tw + 10, height: 15)
            ctx.setFillColor(c.copy(alpha: pal.dark ? 0.22 : 0.14)!)
            ctx.addPath(CGPath(roundedRect: r, cornerWidth: 4, cornerHeight: 4, transform: nil))
            ctx.fillPath()
            Ink.draw(ctx, text, x: r.minX + 5, y: cy + 3.5, chip, c)
        }

        // Time in ranges, high on top: a stacked bar, and a row per range or group total.
        struct Row { var label: String; var mg: String; var share: Double; var goal: Goal?; var strong: Bool }
        let rows = [
            Row(label: "Very high", mg: "> \(b.veryHigh)", share: m.tir.veryHigh, goal: .veryHigh, strong: false),
            Row(label: "High", mg: "\(b.high + 1)–\(b.veryHigh)", share: m.tir.high, goal: nil, strong: false),
            Row(label: "Above range", mg: "", share: m.tir.above, goal: .above, strong: true),
            Row(label: "Target", mg: "\(b.low)–\(b.high)", share: m.tir.inRange, goal: .inRange, strong: true),
            Row(label: "Very good", mg: "\(b.low)–\(b.tight)", share: m.tir.tight, goal: nil, strong: false),
            Row(label: "Low", mg: "\(b.veryLow)–\(b.low - 1)", share: m.tir.low, goal: nil, strong: false),
            Row(label: "Very low", mg: "< \(b.veryLow)", share: m.tir.veryLow, goal: .veryLow, strong: false),
            Row(label: "Below range", mg: "", share: m.tir.below, goal: .below, strong: true),
        ]
        let rowH: CGFloat = 17
        let top = y
        let barX: CGFloat = 0, barW: CGFloat = 10, textX: CGFloat = 20, mgX: CGFloat = 104, pctR: CGFloat = 214
        for (i, r) in rows.enumerated() {
            let cy = y + rowH / 2
            Ink.draw(ctx, r.label, x: textX, y: cy + 4, r.strong ? strong : name, r.strong ? pal.ink : pal.inkSoft)
            if !r.mg.isEmpty { Ink.draw(ctx, r.mg, x: mgX, y: cy + 4, small, pal.inkSoft) }
            Ink.draw(ctx, ShareText.of(r.share, r.goal) + "%", x: pctR, y: cy + 4.5, r.strong ? big : mid, r.strong ? pal.ink : pal.inkSoft, align: 1)
            if let g = r.goal { goalChip(g, right: w, mid: cy) }
            y += rowH
            if i == 2 || i == 4 {               // a hairline between above | target | below
                ctx.setFillColor(pal.grid)
                ctx.fill(CGRect(x: textX, y: y + 1, width: w - textX, height: 0.5))
                y += 3
            }
        }
        // the bar: very high … very low, proportional, 2 pt gaps
        let order: [Band] = [.veryHigh, .high, .target, .tight, .low, .veryLow]
        let shares = order.map { m.tir.share($0) }
        let gaps = CGFloat(max(0, shares.filter { $0 > 0 }.count - 1)) * 2
        var by = top + 2
        let usable = y - top - 4 - gaps
        for (band, sh) in zip(order, shares) where sh > 0 {
            let h = max(1, CGFloat(sh) * usable)
            ctx.setFillColor(pal.of(band))
            ctx.fill(CGRect(x: barX, y: by, width: barW, height: h))
            by += h + 2
        }

        // Average glucose, GMI, variability.
        y += 4
        ctx.setFillColor(pal.grid)
        ctx.fill(CGRect(x: 0, y: y, width: w, height: 0.5))
        y += 4
        let metrics: [(String, String, Goal, String?)] = [
            ("Average glucose", "\(Int(m.mean.rounded())) mg/dL", .average, nil),
            ("GMI", String(format: "%.1f %%  ·  %d mmol/mol", m.gmi, Int(m.gmiMmolMol.rounded())), .gmi, "< 53"),
            ("Variability (CV)", String(format: "%.1f %%", m.cv), .cv, nil),
        ]
        for (label, value, goal, extra) in metrics {
            let cy = y + 9
            Ink.draw(ctx, label, x: 0, y: cy + 4, strong, pal.ink)
            Ink.draw(ctx, value, x: mgX, y: cy + 4, mid, pal.ink)
            goalChip(goal, right: w, mid: cy, extra: extra)
            y += 19
        }
        y += 2
        Ink.draw(ctx, "Goals for type 1 and type 2 diabetes (international consensus).", x: 0, y: y + 8, small, pal.inkSoft)
        return y + 13
    }
}

/// The report as a view (flipped, sized to its content): the Glucose Report window (ReportWindow.swift).
final class ReportView: NSView {
    var metrics: GlucoseMetrics? { didSet { needsDisplay = true } }
    var bands = Bands()
    var days = 14
    var inset = NSEdgeInsets(top: 2, left: 14, bottom: 6, right: 14)

    override var isFlipped: Bool { true }

    /// The height the current content needs (measured by drawing into a scratch context).
    func fittingHeight() -> CGFloat {
        guard let m = metrics, let ctx = CGContext(data: nil, width: 1, height: 1, bitsPerComponent: 8, bytesPerRow: 0,
                                                   space: CGColorSpaceCreateDeviceRGB(),
                                                   bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return 40 }
        return GlucoseReport.draw(ctx, width: GlucoseReport.width, m: m, bands: bands, days: days, .darkPalette) + inset.top + inset.bottom
    }

    override func draw(_ dirtyRect: NSRect) {
        guard let ctx = NSGraphicsContext.current?.cgContext, let m = metrics else { return }
        let dark = effectiveAppearance.bestMatch(from: [.darkAqua, .aqua]) == .darkAqua
        ctx.saveGState()
        ctx.translateBy(x: inset.left, y: inset.top)
        GlucoseReport.draw(ctx, width: bounds.width - inset.left - inset.right, m: m, bands: bands, days: days, MacSurface.palette(dark: dark))
        ctx.restoreGState()
    }
}
