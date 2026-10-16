// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import AppKit
import CryptoKit

/// Generated profile pictures, the same drawing as Android's `core/Avatar.kt` (PROTOCOL-v2 "Profiles"). The wire form
/// is a few numbers with a renderer version `v`; every shipped version keeps its renderer, and a version this build
/// doesn't know is drawn as the grey initial until the app is updated.
enum Avatar {
    static let current = 1

    /// A new random v1 picture (any shape but "letter").
    static func random() -> [String: Any] {
        ["v": 1, "c": Int.random(in: 0..<V1.palettes.count), "s": V1.shapes[Int.random(in: 0..<(V1.shapes.count - 1))],
         "a": Int.random(in: 0..<24) * 15, "n": Int.random(in: 0...Int(Int32.max))]
    }

    static func initial(_ name: String) -> String {
        name.trimmingCharacters(in: .whitespaces).first.map { String($0).uppercased() } ?? "?"
    }

    /// A circular picture of `size` points. `name` gives the letter for the "letter" shape and the fallback.
    static func image(_ avatar: [String: Any]?, name: String, size: CGFloat) -> NSImage {
        NSImage(size: NSSize(width: size, height: size), flipped: true) { r in
            guard let c = NSGraphicsContext.current?.cgContext else { return false }
            c.addEllipse(in: r)
            c.clip()
            c.scaleBy(x: r.width, y: r.height)         // draw in unit coordinates, y down (like Android)
            switch avatar?["v"] as? Int {
            case 1: V1.draw(c, avatar!, name: name)
            default: fallback(c, name: name)
            }
            return true
        }
    }

    private static func fallback(_ c: CGContext, name: String) {
        c.setFillColor(rgb(0xFF78909C))
        c.fill(CGRect(x: 0, y: 0, width: 1, height: 1))
        V1.letter(c, initial(name))
    }

    static func rgb(_ argb: UInt32, alpha: CGFloat = 1) -> CGColor {
        CGColor(srgbRed: CGFloat((argb >> 16) & 0xff) / 255, green: CGFloat((argb >> 8) & 0xff) / 255,
                blue: CGFloat(argb & 0xff) / 255, alpha: alpha)
    }

    /// Version 1 (2026-10-17). Frozen: change nothing here once released; add a V2 instead.
    enum V1 {
        static let palettes: [(UInt32, UInt32)] = [(0xFF1BB5C9, 0xFF0B2E5E), (0xFFC6F432, 0xFF2E7D32), (0xFFFF8A65, 0xFFBF360C),
            (0xFFBA68C8, 0xFF4A148C), (0xFFFFD54F, 0xFFE65100), (0xFF4FC3F7, 0xFF01579B), (0xFFF06292, 0xFF880E4F),
            (0xFF90A4AE, 0xFF263238)]
        static let shapes = ["cube", "drop", "dots", "rings", "letter"]

        /// Five circles (x, y, r, alpha 0–255) from sha256("sukrara/avatar/v1/dots:" + seed): bytes 4k…4k+3 → r, x, y, alpha.
        static func dots(_ seed: Int) -> [(CGFloat, CGFloat, CGFloat, Int)] {
            let h = Array(SHA256.hash(data: Data("sukrara/avatar/v1/dots:\(seed)".utf8)))
            func u(_ i: Int) -> CGFloat { CGFloat(h[i]) / 255 }
            return (0..<5).map { k in (0.25 + 0.5 * u(4 * k + 1), 0.25 + 0.5 * u(4 * k + 2), 0.08 + 0.12 * u(4 * k), 90 + Int(110 * u(4 * k + 3))) }
        }

        static func draw(_ c: CGContext, _ a: [String: Any], name: String) {
            let (c1, c2) = palettes[(((a["c"] as? Int) ?? 0) % palettes.count + palettes.count) % palettes.count]
            let rad = CGFloat((a["a"] as? Int) ?? 0) * .pi / 180
            let dx = cos(rad) / 2, dy = sin(rad) / 2
            if let g = CGGradient(colorsSpace: CGColorSpace(name: CGColorSpace.sRGB), colors: [rgb(c1), rgb(c2)] as CFArray, locations: [0, 1]) {
                c.drawLinearGradient(g, start: CGPoint(x: 0.5 - dx, y: 0.5 - dy), end: CGPoint(x: 0.5 + dx, y: 0.5 + dy),
                                     options: [.drawsBeforeStartLocation, .drawsAfterEndLocation])
            }
            func white(_ al: Int) { c.setFillColor(rgb(0xFFFFFFFF, alpha: CGFloat(al) / 255)); c.setStrokeColor(rgb(0xFFFFFFFF, alpha: CGFloat(al) / 255)) }
            func poly(_ pts: [CGFloat]) {
                c.beginPath()
                c.move(to: CGPoint(x: pts[0], y: pts[1]))
                for i in stride(from: 2, to: pts.count, by: 2) { c.addLine(to: CGPoint(x: pts[i], y: pts[i + 1])) }
                c.closePath()
                c.fillPath()
            }
            let m: CGFloat = 0.5
            switch a["s"] as? String {
            case "cube":
                let w: CGFloat = 0.22
                white(235); poly([m, m - w * 1.1, m + w, m - w * 0.55, m, m, m - w, m - w * 0.55])
                white(170); poly([m - w, m - w * 0.55, m, m, m, m + w * 1.15, m - w, m + w * 0.6])
                white(110); poly([m + w, m - w * 0.55, m, m, m, m + w * 1.15, m + w, m + w * 0.6])
            case "drop":
                let r: CGFloat = 0.2
                white(220)
                c.fillEllipse(in: CGRect(x: m - r, y: m + r * 0.6 - r, width: 2 * r, height: 2 * r))
                poly([m - r * 0.93, m + r * 0.3, m, m - r * 1.5, m + r * 0.93, m + r * 0.3])
            case "dots":
                for (x, y, r, al) in dots((a["n"] as? Int) ?? 0) {
                    white(al)
                    c.fillEllipse(in: CGRect(x: x - r, y: y - r, width: 2 * r, height: 2 * r))
                }
            case "rings":
                c.setLineWidth(0.03)
                for i in stride(from: 4, through: 1, by: -1) {
                    white(60 + 40 * i)
                    let r = 0.09 * CGFloat(i)
                    c.strokeEllipse(in: CGRect(x: m - r, y: m - r, width: 2 * r, height: 2 * r))
                }
            default:
                letter(c, Avatar.initial(name))
            }
        }

        /// White bold letter, its glyph box centred (unit coordinates, y down).
        static func letter(_ c: CGContext, _ text: String) {
            // laid out at 100 pt and scaled down: CoreText metrics are unreliable at sub-point sizes
            let font = NSFont.boldSystemFont(ofSize: 100)
            let line = CTLineCreateWithAttributedString(NSAttributedString(string: text, attributes: [
                .font: font, .foregroundColor: NSColor(cgColor: rgb(0xFFFFFFFF, alpha: 240.0 / 255))!]))
            c.saveGState()
            c.textMatrix = .identity
            let b = CTLineGetImageBounds(line, c)
            c.translateBy(x: 0.5, y: 0.5)
            c.scaleBy(x: 0.005, y: -0.005)               // 100 pt → 0.5 of the picture; text draws y-up
            c.translateBy(x: -b.midX, y: -b.midY)
            CTLineDraw(line, c)
            c.restoreGState()
        }
    }
}
