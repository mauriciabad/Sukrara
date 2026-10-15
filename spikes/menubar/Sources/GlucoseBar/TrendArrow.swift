// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import AppKit

/// Draws a trend arrow whose angle is continuous in the rate of change, instead of the
/// 5 fixed Libre buckets (↓ ↘ → ↗ ↑).
///
/// Mapping: 0 mg/dL/min -> pointing right, ±3 mg/dL/min -> straight up/down, linear in between.
/// That keeps Libre's bucket centres where users expect them (±1.5 -> 45°), but 0.4 vs 0.9 is now visible.
/// Beyond ±3 a second chevron is added (the Libre "double arrow" zone).
enum TrendArrow {
    static let fullScaleRate = 3.0   // mg/dL/min that maps to ±90°

    static func angle(forRate rate: Double) -> CGFloat {
        CGFloat(max(-1, min(1, rate / fullScaleRate)) * (.pi / 2))
    }

    /// - Parameter color: nil -> monochrome template image that follows the menubar appearance.
    static func image(rate: Double?, size: CGFloat = 16, color: NSColor? = nil) -> NSImage {
        let img = NSImage(size: NSSize(width: size, height: size), flipped: false) { rect in
            guard let rate else {
                // Unknown trend: a small dot.
                let d = rect.width * 0.22
                (color ?? .black).setFill()
                NSBezierPath(ovalIn: NSRect(x: rect.midX - d / 2, y: rect.midY - d / 2, width: d, height: d)).fill()
                return true
            }
            // Transform the context (not the paths) so stroke widths scale with the arrow.
            let t = NSAffineTransform()
            t.translateX(by: rect.midX, yBy: rect.midY)
            t.rotate(byRadians: angle(forRate: rate))
            t.scale(by: rect.width)
            t.concat()

            // Unit arrow pointing to +x, centred on origin; the shaft runs into the head.
            let shaft = NSBezierPath()
            shaft.move(to: NSPoint(x: -0.42, y: 0))
            shaft.line(to: NSPoint(x: 0.25, y: 0))
            shaft.lineWidth = 0.14
            shaft.lineCapStyle = .round

            (color ?? .black).set()
            shaft.stroke()
            chevronHead(tipX: 0.46).fill()
            if abs(rate) >= fullScaleRate {
                chevronHead(tipX: 0.12).fill()
            }
            return true
        }
        img.isTemplate = (color == nil)
        return img
    }

    private static func chevronHead(tipX: CGFloat) -> NSBezierPath {
        let p = NSBezierPath()
        p.move(to: NSPoint(x: tipX, y: 0))
        p.line(to: NSPoint(x: tipX - 0.40, y: 0.30))
        p.line(to: NSPoint(x: tipX - 0.30, y: 0))
        p.line(to: NSPoint(x: tipX - 0.40, y: -0.30))
        p.close()
        return p
    }
}
