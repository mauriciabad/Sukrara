// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import AppKit
import CryptoKit
import SukraraShare
import SwiftUI

/// What a generated picture draws, in unit coordinates (y down), before any pixels: the background and white marks
/// with their alpha (PROTOCOL-v2 "Profiles"; the same numbers as Android's `AvatarDrawing`). Pure, so the geometry is
/// tested against `contracts/protocol/identity/avatar-v1.json`; `image(size:)` paints it.
///
/// Every renderer version this app ever shipped stays here unchanged (`of` dispatches on `v`); a version this build
/// doesn't know, or no picture, draws the fallback: grey with the name's initial.
struct AvatarDrawing: Equatable {
    struct Point: Equatable {
        var x, y: Double
    }

    enum Background: Equatable {
        /// Linear gradient from `from` (colour `a`) to `to` (colour `b`); colours 0xAARRGGBB.
        case gradient(a: UInt32, b: UInt32, from: Point, to: Point)
        case solid(UInt32)
    }

    /// A white mark; `alpha` 0–255.
    enum Mark: Equatable {
        case polygon([Point], alpha: Int)
        case circle(Point, r: Double, alpha: Int)
        /// A stroked circle, `width` in unit coordinates.
        case ring(Point, r: Double, width: Double, alpha: Int)
        /// Bold text at 0.5 of the size, its glyph box centred.
        case letter(String, alpha: Int)
    }

    var background: Background
    var marks: [Mark]

    static let fallbackColor: UInt32 = 0xFF78_909C

    static func of(_ avatar: Avatar?, name: String) -> AvatarDrawing {
        if let avatar, avatar.isKnown, avatar.version == 1 { return V1.draw(avatar.object, name: name) }
        return fallback(name: name)
    }

    static func fallback(name: String) -> AvatarDrawing {
        AvatarDrawing(background: .solid(fallbackColor), marks: [.letter(initial(name), alpha: V1.letterAlpha)])
    }

    /// The name's first letter, upper-cased ("?" for a blank name).
    static func initial(_ name: String) -> String {
        name.trimmingCharacters(in: .whitespacesAndNewlines).first.map { String($0).uppercased() } ?? "?"
    }

    /// Version 1 (2026-10-17). Frozen: never change what it draws; a new look is a new version.
    enum V1 {
        static let palettes: [(UInt32, UInt32)] = [
            (0xFF1B_B5C9, 0xFF0B_2E5E), (0xFFC6_F432, 0xFF2E_7D32), (0xFFFF_8A65, 0xFFBF_360C),
            (0xFFBA_68C8, 0xFF4A_148C), (0xFFFF_D54F, 0xFFE6_5100), (0xFF4F_C3F7, 0xFF01_579B),
            (0xFFF0_6292, 0xFF88_0E4F), (0xFF90_A4AE, 0xFF26_3238),
        ]
        static let letterAlpha = 240
        private static let m = 0.5

        /// Five circles (x, y, r, alpha) from sha256("sukrara/avatar/v1/dots:" + seed): bytes 4k…4k+3 → r, x, y, alpha.
        static func dots(_ seed: Int64) -> [(x: Double, y: Double, r: Double, alpha: Int)] {
            let h = Array(SHA256.hash(data: Data("sukrara/avatar/v1/dots:\(seed)".utf8)))
            func u(_ i: Int) -> Double { Double(h[i]) / 255 }
            return (0..<5).map { k in
                (
                    0.25 + 0.5 * u(4 * k + 1), 0.25 + 0.5 * u(4 * k + 2), 0.08 + 0.12 * u(4 * k),
                    90 + Int(110 * u(4 * k + 3))
                )
            }
        }

        static func draw(_ a: [String: Any], name: String) -> AvatarDrawing {
            let count = palettes.count
            let (c1, c2) = palettes[(((a["c"] as? Int) ?? 0) % count + count) % count]
            let rad = Double((a["a"] as? Int) ?? 0) * .pi / 180
            let dx = cos(rad) / 2
            let dy = sin(rad) / 2
            let background = Background.gradient(
                a: c1, b: c2, from: Point(x: m - dx, y: m - dy), to: Point(x: m + dx, y: m + dy))
            let marks: [Mark]
            switch a["s"] as? String {
            case "cube": marks = cube()
            case "drop": marks = drop()
            case "dots":
                marks = dots(Int64((a["n"] as? Int) ?? 0)).map {
                    .circle(Point(x: $0.x, y: $0.y), r: $0.r, alpha: $0.alpha)
                }
            case "rings":
                marks = (1...4).reversed().map { i in
                    .ring(Point(x: m, y: m), r: 0.09 * Double(i), width: 0.03, alpha: 60 + 40 * i)
                }
            default: marks = [.letter(AvatarDrawing.initial(name), alpha: letterAlpha)]
            }
            return AvatarDrawing(background: background, marks: marks)
        }

        private static func cube() -> [Mark] {
            let w = 0.22
            func face(_ alpha: Int, _ p: [Double]) -> Mark {
                .polygon(stride(from: 0, to: p.count, by: 2).map { Point(x: p[$0], y: p[$0 + 1]) }, alpha: alpha)
            }
            return [
                face(235, [m, m - w * 1.1, m + w, m - w * 0.55, m, m, m - w, m - w * 0.55]),
                face(170, [m - w, m - w * 0.55, m, m, m, m + w * 1.15, m - w, m + w * 0.6]),
                face(110, [m + w, m - w * 0.55, m, m, m, m + w * 1.15, m + w, m + w * 0.6]),
            ]
        }

        private static func drop() -> [Mark] {
            let r = 0.2
            return [
                .circle(Point(x: m, y: m + r * 0.6), r: r, alpha: 220),
                .polygon(
                    [
                        Point(x: m - r * 0.93, y: m + r * 0.3), Point(x: m, y: m - r * 1.5),
                        Point(x: m + r * 0.93, y: m + r * 0.3),
                    ], alpha: 220),
            ]
        }
    }

    // MARK: painting

    static func color(_ argb: UInt32, alpha: Int = 255) -> CGColor {
        CGColor(
            srgbRed: CGFloat((argb >> 16) & 0xff) / 255, green: CGFloat((argb >> 8) & 0xff) / 255,
            blue: CGFloat(argb & 0xff) / 255, alpha: CGFloat(alpha) / 255)
    }

    /// A circular picture of `size` points.
    func image(size: CGFloat) -> NSImage {
        let drawing = self
        return NSImage(size: NSSize(width: size, height: size), flipped: true) { rect in
            guard let c = NSGraphicsContext.current?.cgContext else { return false }
            c.addEllipse(in: rect)
            c.clip()
            c.scaleBy(x: rect.width, y: rect.height)  // unit coordinates, y down (like Android)
            drawing.paint(c)
            return true
        }
    }

    func paint(_ c: CGContext) {
        switch background {
        case .solid(let argb):
            c.setFillColor(Self.color(argb))
            c.fill(CGRect(x: 0, y: 0, width: 1, height: 1))
        case .gradient(let a, let b, let from, let to):
            if let g = CGGradient(
                colorsSpace: CGColorSpace(name: CGColorSpace.sRGB), colors: [Self.color(a), Self.color(b)] as CFArray,
                locations: [0, 1])
            {
                c.drawLinearGradient(
                    g, start: CGPoint(x: from.x, y: from.y), end: CGPoint(x: to.x, y: to.y),
                    options: [.drawsBeforeStartLocation, .drawsAfterEndLocation])
            }
        }
        for mark in marks {
            switch mark {
            case .polygon(let points, let alpha):
                c.setFillColor(Self.color(0xFFFF_FFFF, alpha: alpha))
                c.beginPath()
                c.addLines(between: points.map { CGPoint(x: $0.x, y: $0.y) })
                c.closePath()
                c.fillPath()
            case .circle(let p, let r, let alpha):
                c.setFillColor(Self.color(0xFFFF_FFFF, alpha: alpha))
                c.fillEllipse(in: CGRect(x: p.x - r, y: p.y - r, width: 2 * r, height: 2 * r))
            case .ring(let p, let r, let width, let alpha):
                c.setStrokeColor(Self.color(0xFFFF_FFFF, alpha: alpha))
                c.setLineWidth(width)
                c.strokeEllipse(in: CGRect(x: p.x - r, y: p.y - r, width: 2 * r, height: 2 * r))
            case .letter(let text, let alpha):
                Self.letter(c, text, alpha: alpha)
            }
        }
    }

    /// White bold letter, its glyph box centred. Laid out at 100 pt and scaled down: CoreText metrics are unreliable
    /// at sub-point sizes.
    private static func letter(_ c: CGContext, _ text: String, alpha: Int) {
        let line = CTLineCreateWithAttributedString(
            NSAttributedString(
                string: text,
                attributes: [
                    .font: NSFont.boldSystemFont(ofSize: 100),
                    .foregroundColor: NSColor(cgColor: color(0xFFFF_FFFF, alpha: alpha)) ?? .white,
                ]))
        c.saveGState()
        c.textMatrix = .identity
        let b = CTLineGetImageBounds(line, c)
        c.translateBy(x: 0.5, y: 0.5)
        c.scaleBy(x: 0.005, y: -0.005)  // 100 pt → 0.5 of the picture; text draws y-up
        c.translateBy(x: -b.midX, y: -b.midY)
        CTLineDraw(line, c)
        c.restoreGState()
    }
}

/// A person's generated picture (decorative: the name next to it says who it is).
struct AvatarView: View {
    let avatar: Avatar?
    let name: String
    var size: CGFloat = 32

    var body: some View {
        Image(nsImage: AvatarDrawing.of(avatar, name: name).image(size: size))
            .frame(width: size, height: size)
            .accessibilityHidden(true)
    }
}
