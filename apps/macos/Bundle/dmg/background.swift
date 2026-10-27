// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Draws the Sukrara installer window background (640×400 pt) at 1× and 2×:
//   swift dmg/background.swift <out-dir>   → background.png, background@2x.png
// Light on purpose: Finder draws the icon labels in black on a light window.
import AppKit

let out = CommandLine.arguments.dropFirst().first ?? "."
let size = NSSize(width: 640, height: 400)
func hex(_ s: String) -> NSColor {
    let v = Int(s.dropFirst(), radix: 16)!
    return NSColor(srgbRed: CGFloat((v >> 16) & 255) / 255, green: CGFloat((v >> 8) & 255) / 255, blue: CGFloat(v & 255) / 255, alpha: 1)
}
let top = hex("#1BB5C9"), bottom = hex("#0B2E5E")   // brand.json

func render(scale: CGFloat, to path: String) {
    let rep = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: Int(size.width * scale), pixelsHigh: Int(size.height * scale),
                               bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true, isPlanar: false,
                               colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0)!
    rep.size = size
    NSGraphicsContext.saveGraphicsState()
    NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: rep)

    // Soft wash: white at the top, a hint of the brand teal at the bottom.
    NSGradient(starting: .white, ending: top.blended(withFraction: 0.88, of: .white)!)!.draw(in: NSRect(origin: .zero, size: size), angle: -90)

    // Title (y is from the bottom in AppKit).
    let para = NSMutableParagraphStyle(); para.alignment = .center
    func text(_ s: String, y: CGFloat, size fs: CGFloat, weight: NSFont.Weight, color: NSColor) {
        let a: [NSAttributedString.Key: Any] = [.font: NSFont.systemFont(ofSize: fs, weight: weight), .foregroundColor: color, .paragraphStyle: para]
        (s as NSString).draw(in: NSRect(x: 0, y: y, width: size.width, height: fs * 1.5), withAttributes: a)
    }
    text("Install Sukrara", y: 338, size: 22, weight: .semibold, color: bottom)
    text("Drag the icon onto the Applications folder", y: 312, size: 13, weight: .regular, color: bottom.withAlphaComponent(0.75))

    // Arrow between the two icons (icon centres at x 170 and 470, y 200 from the top → 200 from the bottom).
    let y: CGFloat = 200, x0: CGFloat = 262, x1: CGFloat = 378
    let shaft = NSBezierPath()
    shaft.move(to: NSPoint(x: x0, y: y)); shaft.line(to: NSPoint(x: x1 - 14, y: y))
    shaft.lineWidth = 6; shaft.lineCapStyle = .round
    top.setStroke(); shaft.stroke()
    let head = NSBezierPath()
    head.move(to: NSPoint(x: x1, y: y)); head.line(to: NSPoint(x: x1 - 22, y: y + 15)); head.line(to: NSPoint(x: x1 - 22, y: y - 15)); head.close()
    top.setFill(); head.fill()

    // First-launch help, for builds Apple hasn't notarized.
    text("First launch: if macOS blocks Sukrara, open System Settings → Privacy & Security", y: 58, size: 11.5, weight: .regular, color: bottom.withAlphaComponent(0.7))
    text("and click “Open Anyway”. You only do this once.", y: 40, size: 11.5, weight: .regular, color: bottom.withAlphaComponent(0.7))

    NSGraphicsContext.restoreGraphicsState()
    try! rep.representation(using: .png, properties: [:])!.write(to: URL(fileURLWithPath: path))
}
render(scale: 1, to: "\(out)/background.png")
render(scale: 2, to: "\(out)/background@2x.png")
