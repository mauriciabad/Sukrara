// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import CoreGraphics
import Foundation
import Testing

@testable import SukraraGraph

/// The status pill's words against the pill, in both themes, at WCAG 1.4.3's 4.5:1 for normal-size text (UX review
/// F5: the light "Eat carbs" pill was 3.7:1 and its tonal "9m left" 3.0:1, read during a low).
@Suite struct ContrastTests {
    private static let kinds = ["rapid", "long", "carbs", "note", "muted"]

    @Test(arguments: [false, true]) func pillWordsAreReadable(dark: Bool) {
        let card = GraphPalette.of(dark: dark).bg
        for kind in Self.kinds {
            for tonal in [false, true] {
                let look = StatusBadge.Look(kind: kind, text: "x", short: "x", tonal: tonal)
                let (bg, fg) = StatusBadge.colors(look, dark: dark, knockout: false)
                let ratio = Self.contrast(fg, Self.over(bg, card))
                #expect(ratio >= 4.5, "\(kind) tonal=\(tonal) dark=\(dark): \(ratio)")
            }
        }
    }

    /// The light carbs colour is the one Android uses (TreatmentMarks.palette(false)).
    @Test func lightCarbsIsTheSharedValue() {
        #expect(TreatmentMarks.color("carbs", dark: false) == GraphPalette.hex(0xA65A00))
    }

    @Test func formulaMatchesKnownPairs() {
        #expect(abs(Self.contrast(GraphPalette.hex(0x000000), GraphPalette.hex(0xFFFFFF)) - 21) < 0.01)
        // the review's measurement of the old colour
        #expect(abs(Self.contrast(GraphPalette.hex(0xFFFFFF), GraphPalette.hex(0xC86E00)) - 3.69) < 0.02)
    }

    /// `top` (with its alpha) composited over the opaque `base`.
    static func over(_ top: CGColor, _ base: CGColor) -> CGColor {
        let t = rgba(top), b = rgba(base)
        func mix(_ i: Int) -> CGFloat { t[i] * t[3] + b[i] * (1 - t[3]) }
        return CGColor(srgbRed: mix(0), green: mix(1), blue: mix(2), alpha: 1)
    }

    /// WCAG 2.x contrast ratio of two opaque sRGB colours.
    static func contrast(_ a: CGColor, _ b: CGColor) -> Double {
        func luminance(_ c: CGColor) -> Double {
            let v = rgba(c).prefix(3).map { x -> Double in
                let x = Double(x)
                return x <= 0.04045 ? x / 12.92 : pow((x + 0.055) / 1.055, 2.4)
            }
            return 0.2126 * v[0] + 0.7152 * v[1] + 0.0722 * v[2]
        }
        let (la, lb) = (luminance(a), luminance(b))
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    private static func rgba(_ c: CGColor) -> [CGFloat] {
        let srgb = c.converted(to: CGColorSpace(name: CGColorSpace.sRGB)!, intent: .defaultIntent, options: nil)!
        return srgb.components!
    }
}
