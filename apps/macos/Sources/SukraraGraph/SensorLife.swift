// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import AppKit
import CoreGraphics
import CoreText
import Foundation
import SukraraCore

// MARK: - Sensor life (SensorLife.kt, SensorBadge.kt)

/// The wearer's current sensor (unix s), from the live event's `ss`/`se`/`sw` (PROTOCOL-v2 "Sensor life").
struct SensorInfo: Codable, Equatable {
    var start: TimeInterval
    var end: TimeInterval
    var warmupEnd: TimeInterval
}

/// The sensor's remaining life and warm-up; the same rules as Android's core/SensorLife (spikes/graph-fixtures/sensor).
enum SensorLife {
    static let defaultCountdownHours = 8
    static let countdownRange = 1...24
    /// The warm-up state lasts until the first reading, at most this long past the warm-up's end.
    static let warmupGrace: TimeInterval = 15 * 60

    static func ended(end: TimeInterval, now: TimeInterval) -> Bool { now >= end }


    /// ≥ 24 h left → whole days ("13d"), ≥ 1 h → hours ("20h"), else minutes ("45m", "0m" once ended); floor.
    static func label(end: TimeInterval, now: TimeInterval) -> String {
        Durations.whole(Int(max(0, (end - now).rounded(.down))) / 60)
    }

    /// The disc's arc: the share of the sensor's life left, 1 → 0.
    static func lifeFraction(_ s: SensorInfo, now: TimeInterval) -> Double {
        min(1, max(0, (s.end - now) / max(1, s.end - s.start)))
    }

    /// The countdown ring: nil outside the last `hours` (and after the end), else the share of the window left.
    static func countdownFraction(end: TimeInterval, now: TimeInterval, hours: Int) -> Double? {
        let left = end - now, window = TimeInterval(hours * 3600)
        return left <= 0 || left > window ? nil : left / window
    }

    /// Warming up: started, no reading since the start (`latest` = the newest reading's time), ≤ 15 min past its end.
    static func warming(_ s: SensorInfo, latest: TimeInterval?, now: TimeInterval) -> Bool {
        now >= s.start && now < s.warmupEnd + warmupGrace && (latest.map { $0 < s.start } ?? true)
    }

    /// The warm-up's time left as the card's figure: whole minutes rounded up ("42m"), from 60 of them hours ("1h": one
    /// rounding, so 59:15 left is "1h" everywhere, never "60m"). From the warm-up's end until the first reading
    /// "Almost ready": a countdown never shows "0m" or less.
    static func warmupLabel(_ s: SensorInfo, now: TimeInterval) -> String {
        if waitingFirstReading(s, now: now) { return "Almost ready" }
        let min = (Int((s.warmupEnd - now).rounded(.down)) + 59) / 60
        return Durations.whole(min)
    }

    /// While warming up: the warm-up's time is over and the first reading hasn't come yet.
    static func waitingFirstReading(_ s: SensorInfo, now: TimeInterval) -> Bool { now >= s.warmupEnd }

    /// The warm-up ring: fills 0 → 1 from the start to the warm-up's end.
    static func warmupFraction(_ s: SensorInfo, now: TimeInterval) -> Double {
        min(1, max(0, (now - s.start) / max(1, s.warmupEnd - s.start)))
    }
}

/// The sensor badge beside the trend arrow (a Libre sensor disc whose outline depletes, then "13d"), the warm-up
/// figure and the ring over the main ring; the same look as Android's SensorBadge.kt.
enum SensorBadge {
    struct Look: Equatable {
        var label: String
        var life: Double
        var ended: Bool
        /// While warming up: the figure ("42m"); `fill` = the warm-up done.
        var warmup: String?
        var fill: Double
        var readyAt: TimeInterval
        var end: TimeInterval = 0

        /// Warming up past the warm-up's end: `warmup` is "Almost ready", the line under it "Waiting for the first reading".
        func waiting(now: TimeInterval) -> Bool { warmup != nil && now >= readyAt }

        /// The line under the warm-up figure: "Ready at 19:45", or "Waiting for the first reading" once that's past.
        func warmupLine(now: TimeInterval) -> String {
            waiting(now: now) ? "Waiting for the first reading" : "Ready at \(Freshness.timeText(readyAt, now: now))"
        }
    }

    static func look(_ s: SensorInfo?, now: TimeInterval, latest: TimeInterval?) -> Look? {
        guard let s else { return nil }
        let warm = SensorLife.warming(s, latest: latest, now: now)
        return Look(label: SensorLife.label(end: s.end, now: now), life: SensorLife.lifeFraction(s, now: now),
                    ended: SensorLife.ended(end: s.end, now: now), warmup: warm ? SensorLife.warmupLabel(s, now: now) : nil,
                    fill: SensorLife.warmupFraction(s, now: now), readyAt: s.warmupEnd, end: s.end)
    }

    /// The ring over the main ring: the warm-up filling up, else the end countdown draining (nil = none).
    static func ring(_ look: Look?, _ s: SensorInfo?, now: TimeInterval, hours: Int) -> Double? {
        guard let look, let s else { return nil }
        return look.warmup != nil ? look.fill : SensorLife.countdownFraction(end: s.end, now: now, hours: hours)
    }

    private static func color(_ look: Look, _ pal: GraphPalette) -> CGColor { look.ended ? pal.veryLow : pal.inkSoft }
    private static func font(_ h: CGFloat) -> CTFont { Ink.font(h * 0.95, .semibold, rounded: true) }

    static func width(_ look: Look, h: CGFloat) -> CGFloat { h * 1.3 + Ink.width(look.label, font(h)) }

    /// The badge `h` tall, top-left at (`x`, `y`); `label: false` = the disc alone (warm-up). Returns its width.
    @discardableResult
    static func draw(_ ctx: CGContext, x: CGFloat, y: CGFloat, h: CGFloat, _ look: Look, _ pal: GraphPalette, label: Bool = true) -> CGFloat {
        let c = color(look, pal), sw = h * 0.14, r = h / 2 - sw / 2
        let center = CGPoint(x: x + h / 2, y: y + h / 2)
        ctx.saveGState()
        ctx.setLineWidth(sw); ctx.setLineCap(.round)
        ctx.setStrokeColor(look.ended ? c : c.copy(alpha: 0.31) ?? c)
        ctx.strokeEllipse(in: CGRect(x: center.x - r, y: center.y - r, width: 2 * r, height: 2 * r))
        let arc = look.warmup != nil ? look.fill : look.life
        if !look.ended, arc > 0 {
            ctx.setStrokeColor(c)
            let sweep = max(4, 360 * CGFloat(arc)) * .pi / 180
            ctx.addArc(center: center, radius: r, startAngle: -.pi / 2, endAngle: -.pi / 2 + sweep, clockwise: false)
            ctx.strokePath()
        }
        ctx.setFillColor(c)
        ctx.fillEllipse(in: CGRect(x: center.x - h * 0.15, y: center.y - h * 0.15, width: h * 0.3, height: h * 0.3))
        ctx.restoreGState()
        guard label else { return h }
        let f = font(h)
        Ink.draw(ctx, look.label, x: x + h * 1.3, y: center.y + CTFontGetCapHeight(f) / 2, f, c)
        return width(look, h: h)
    }

    /// The "sensor ended" flag: SF Symbol `flag.checkered` (Android: Material Symbols `sports_score`), `size` pt tall, its
    /// top-left at (`x`, `y`) in the card's y-down context, tinted `color`. Returns its width (0 if the symbol is missing).
    @discardableResult
    static func flag(_ ctx: CGContext, x: CGFloat, y: CGFloat, size: CGFloat, color: CGColor) -> CGFloat {
        symbol(ctx, "flag.checkered", x: x, y: y, size: size, color: color)
    }

    /// An SF Symbol `name` `size` tall in `color`, top-left at (`x`, `y`) of a y-down context; returns its width.
    static func symbol(_ ctx: CGContext, _ name: String, x: CGFloat, y: CGFloat, size: CGFloat, color: CGColor) -> CGFloat {
        guard let sym = NSImage(systemSymbolName: name, accessibilityDescription: nil)?
                .withSymbolConfiguration(NSImage.SymbolConfiguration(pointSize: size, weight: .semibold)) else { return 0 }
        let k = size / max(sym.size.height, 1)
        let rect = NSRect(x: x, y: y, width: sym.size.width * k, height: size)
        let tinted = NSImage(size: sym.size, flipped: false) { r in
            sym.draw(in: r)
            (NSColor(cgColor: color) ?? .gray).set()
            r.fill(using: .sourceAtop)
            return true
        }
        NSGraphicsContext.saveGraphicsState()
        NSGraphicsContext.current = NSGraphicsContext(cgContext: ctx, flipped: true)
        tinted.draw(in: rect, from: .zero, operation: .sourceOver, fraction: 1, respectFlipped: true, hints: nil)
        NSGraphicsContext.restoreGraphicsState()
        return rect.width
    }

    /// The ring over a slot of `radius` (the slot rings' stroke is radius × 0.2): ink on a background-coloured halo
    /// (black / white where the background is transparent; cut out in tinted widgets).
    static func drawCountdown(_ ctx: CGContext, center c: CGPoint, radius: CGFloat, _ fraction: Double?, _ pal: GraphPalette) {
        guard let fraction else { return }
        let ringSw = radius * 0.2, mid = radius - ringSw / 2
        let sweep = max(3, 360 * CGFloat(fraction)) * .pi / 180
        func arc() { ctx.addArc(center: c, radius: mid, startAngle: -.pi / 2, endAngle: -.pi / 2 + sweep, clockwise: false) }
        ctx.saveGState()
        ctx.setLineCap(.round)
        ctx.setLineWidth(ringSw * 0.62)
        if pal.knockout { ctx.setBlendMode(.clear) }
        ctx.setStrokeColor(pal.bg.alpha > 0 ? pal.bg : CGColor(gray: pal.dark ? 0 : 1, alpha: 1))
        arc(); ctx.strokePath()
        ctx.setBlendMode(.normal)
        ctx.setLineWidth(ringSw * 0.3)
        ctx.setStrokeColor(pal.knockout ? CGColor(gray: 1, alpha: 1) : pal.ink)
        arc(); ctx.strokePath()
        ctx.restoreGState()
    }
}

