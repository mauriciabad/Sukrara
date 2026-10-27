// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import AppKit
import CoreGraphics
import CoreText
import Foundation

// MARK: - Drawing helpers (y-down CGContext)

enum Ink {
    static func font(_ size: CGFloat, _ weight: NSFont.Weight, rounded: Bool = false) -> CTFont {
        var f = NSFont.systemFont(ofSize: size, weight: weight)
        if rounded, let desc = f.fontDescriptor.withDesign(.rounded) { f = NSFont(descriptor: desc, size: size) ?? f }
        return f as CTFont
    }
    static func line(_ s: String, _ font: CTFont, _ color: CGColor, strike: Bool = false) -> CTLine {
        var attrs: [NSAttributedString.Key: Any] = [.font: font, .foregroundColor: color]
        if strike { attrs[.strikethroughStyle] = NSUnderlineStyle.single.rawValue; attrs[.strikethroughColor] = color }
        return CTLineCreateWithAttributedString(NSAttributedString(string: s, attributes: attrs))
    }
    static func width(_ s: String, _ font: CTFont) -> CGFloat {
        CGFloat(CTLineGetTypographicBounds(line(s, font, GraphPalette.hex(0)), nil, nil, nil))
    }
    /// The outline of `s` (`width` wide, round joins) in the context's blend mode: a label's halo (drawn with
    /// `.destinationOut` to erase what is under the label).
    static func halo(_ ctx: CGContext, _ s: String, x: CGFloat, y: CGFloat, _ font: CTFont, width: CGFloat) {
        let attrs: [NSAttributedString.Key: Any] = [
            .font: font, .strokeColor: GraphPalette.hex(0),
            .strokeWidth: width / CTFontGetSize(font) * 100,
        ]
        let l = CTLineCreateWithAttributedString(NSAttributedString(string: s, attributes: attrs))
        ctx.saveGState()
        ctx.textMatrix = CGAffineTransform(scaleX: 1, y: -1)
        ctx.textPosition = CGPoint(x: x, y: y)
        CTLineDraw(l, ctx)
        ctx.restoreGState()
    }
    /// Draws with the baseline at `y` (y-down context). Strikethrough is drawn by hand (CTLineDraw doesn't).
    static func draw(
        _ ctx: CGContext, _ s: String, x: CGFloat, y: CGFloat, _ font: CTFont, _ color: CGColor,
        align: CGFloat = 0, strike: Bool = false
    ) {
        let l = line(s, font, color)
        let w = CGFloat(CTLineGetTypographicBounds(l, nil, nil, nil))
        let x0 = x - w * align
        ctx.saveGState()
        ctx.textMatrix = CGAffineTransform(scaleX: 1, y: -1)
        ctx.textPosition = CGPoint(x: x0, y: y)
        CTLineDraw(l, ctx)
        ctx.restoreGState()
        if strike {
            let sy = y - CTFontGetXHeight(font) * 0.55
            ctx.setStrokeColor(color)
            ctx.setLineWidth(max(1.5, CTFontGetSize(font) * 0.06))
            ctx.strokeLineSegments(between: [CGPoint(x: x0, y: sy), CGPoint(x: x0 + w, y: sy)])
        }
    }
}

/// A fixed text width model (TextModel.kt), so layout decisions never depend on the font (SF Pro here, Roboto on
/// Android): the treatment label layout and the sensor badge's room use it (the side columns measure their real labels); only the drawing measures real text
/// (centred in the modelled box). Widths in em, at or a little above the wider of SF Pro and Roboto at bold weight and
/// small sizes (SF Pro Text Bold at 9 pt: digits up to 0.71 em); `-graphselftest` checks every fixture label fits.
enum TextModel {
    static func em(_ c: Character) -> CGFloat {
        switch c {
        case "0"..."9": return 0.72
        case ".", ",", "·", ":": return 0.38
        case " ": return 0.3
        case "m": return 0.96
        case "w": return 0.88
        case "i", "l", "j": return 0.32
        case "r", "f", "t": return 0.46
        case "%": return 1.06
        case "a"..."z": return 0.68
        case "A"..."Z": return 0.78
        default: return 0.75
        }
    }
    /// Width of `s` at text size `size` (same unit as `size`).
    static func width(_ s: String, _ size: CGFloat) -> CGFloat { size * s.reduce(0) { $0 + em($1) } }
}

extension CGColor {
    func a(_ alpha: Int) -> CGColor { copy(alpha: CGFloat(alpha) / 255) ?? self }
    func a(_ alpha: CGFloat) -> CGColor { copy(alpha: alpha) ?? self }
}
