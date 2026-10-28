// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import CoreGraphics
import Foundation
import Testing

@testable import SukraraGraph

/// WCAG 1.4.3: the words on the card's pills reach 4.5:1 in both themes (Android: PillContrastTest). The person reads
/// "Eat carbs" during a low.
struct PillContrastTests {
    private func rgb(_ c: CGColor) -> [CGFloat] { Array((c.components ?? [0, 0, 0]).prefix(3)) }

    private func luminance(_ c: [CGFloat]) -> CGFloat {
        let lin = c.map { $0 <= 0.03928 ? $0 / 12.92 : pow(($0 + 0.055) / 1.055, 2.4) }
        return 0.2126 * lin[0] + 0.7152 * lin[1] + 0.0722 * lin[2]
    }

    private func ratio(_ a: [CGFloat], _ b: [CGFloat]) -> CGFloat {
        let (x, y) = (luminance(a) + 0.05, luminance(b) + 0.05)
        return max(x, y) / min(x, y)
    }

    /// `top` (with its alpha) over the opaque `ground`.
    private func over(_ top: CGColor, _ ground: [CGFloat]) -> [CGFloat] {
        let a = top.alpha
        return zip(rgb(top), ground).map { $0 * a + $1 * (1 - a) }
    }

    @Test(arguments: [false, true])
    func pillWordsReachAA(dark: Bool) {
        let card = rgb(dark ? GraphPalette.darkPalette.bg : GraphPalette.lightPalette.bg)
        for k in ["rapid", "long", "carbs", "note"] {
            let c = TreatmentMarks.color(k, dark: dark)
            let filled = ratio(rgb(TreatmentMarks.onColor(dark: dark)), rgb(c))
            #expect(filled >= 4.5, "\(k) filled pill dark=\(dark): \(filled)")
            let tint = over(c.copy(alpha: (dark ? 0x3D : 0x2E) / 255.0)!, card)
            let tonal = ratio(rgb(TreatmentMarks.tonalInk(k, dark: dark)), tint)
            #expect(tonal >= 4.5, "\(k) tonal pill dark=\(dark): \(tonal)")
        }
    }
}
