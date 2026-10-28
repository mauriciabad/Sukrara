// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import CoreGraphics
import Foundation

/// Which side of the user's range the newest value is on: the menu bar colours its number by it (below red, above
/// orange, in range the menu bar's own ink).
public enum RangeSide: Sendable, Equatable {
    case below, inRange, above
}

extension GlucoseCard {
    /// The side of `config`'s ranges the newest reading of `data` is on; nil without a reading. Below = very low +
    /// low, above = high + very high (the time-in-range groups).
    public static func rangeSide(_ data: CardData, config: GraphConfig) -> RangeSide? {
        guard let v = data.latest?.v else { return nil }
        switch config.bands.sane().of(v).group {
        case 0: return .below
        case 2: return .above
        default: return .inRange
        }
    }

    /// The SF Symbol a text place shows instead of a value for no readings for 20 min.
    public static let noReadingsSymbol = GlucoseLook.noReadingsSymbol

    /// The trend mark as a picture for text places that can show one (the menu bar): THE card's arrow at
    /// `TrendAngle.deg`, doubled from ±3 mg/dL/min, or the "no trend" ring for `rate` nil. `size` points square,
    /// `scale` pixels per point, drawn in `color`.
    public static func trendImage(rate: Double?, size: CGFloat, scale: CGFloat = 2, color: CGColor) -> CGImage? {
        let px = max(4, Int(size * scale))
        guard
            let ctx = CGContext(
                data: nil, width: px, height: px, bitsPerComponent: 8, bytesPerRow: 0,
                space: CGColorSpace(name: CGColorSpace.sRGB)!,
                bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)
        else { return nil }
        // y-down points, like the card
        ctx.translateBy(x: 0, y: CGFloat(px))
        ctx.scaleBy(x: scale, y: -scale)
        ctx.translateBy(x: size / 2, y: size / 2)
        if let rate {
            ctx.rotate(by: CGFloat(TrendAngle.deg(rate)) * .pi / 180)
            drawArrow(ctx, scale: size, color: color, double: TrendAngle.isDouble(rate))
        } else {
            let r = TrendMark.ringRadius(size)
            ctx.setStrokeColor(color)
            ctx.setLineWidth(TrendMark.ringStroke(size))
            ctx.strokeEllipse(in: CGRect(x: -r, y: -r, width: 2 * r, height: 2 * r))
        }
        return ctx.makeImage()
    }
}
