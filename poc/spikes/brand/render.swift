// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Rasterizer for brand.py: runs a JSON list of jobs in one process.
//   svg      SVG file -> PNG at an exact pixel size (AppKit's SVG renderer)
//   menubar  fake macOS menubar strip with glucose readings, drawn with the real TrendArrow + system fonts
//   sysicon  the icon macOS actually shows for an .app (reveals the Tahoe+ "squircle jail" for legacy icons)
// Built by brand.py together with ../menubar/Sources/GlucoseBar/TrendArrow.swift.
import AppKit

struct Item: Decodable { let text: String; let rate: Double?; let color: String?; let stale: Bool? }
struct Job: Decodable {
    let kind: String
    let out: String
    var `in`: String?
    var w: Int?, h: Int?
    var dark: Bool?
    var font: String?
    var items: [Item]?
    var app: String?
}

func writePNG(_ rep: NSBitmapImageRep, _ path: String) {
    try? FileManager.default.createDirectory(atPath: (path as NSString).deletingLastPathComponent,
                                             withIntermediateDirectories: true)
    try! rep.representation(using: .png, properties: [:])!.write(to: URL(fileURLWithPath: path))
}

func bitmap(_ w: Int, _ h: Int) -> NSBitmapImageRep {
    NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: w, pixelsHigh: h, bitsPerSample: 8, samplesPerPixel: 4,
                     hasAlpha: true, isPlanar: false, colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0)!
}

func draw(into rep: NSBitmapImageRep, _ body: () -> Void) {
    NSGraphicsContext.saveGraphicsState()
    let ctx = NSGraphicsContext(bitmapImageRep: rep)!
    ctx.imageInterpolation = .high
    NSGraphicsContext.current = ctx
    body()
    NSGraphicsContext.restoreGraphicsState()
}

func color(_ s: String?) -> NSColor? {
    guard let s else { return nil }
    switch s {
    case "label": return .labelColor
    case "secondary": return .secondaryLabelColor
    case "systemRed": return .systemRed
    case "systemOrange": return .systemOrange
    case "systemYellow": return .systemYellow
    case "systemGreen": return .systemGreen
    default:
        var v: UInt64 = 0
        Scanner(string: String(s.dropFirst())).scanHexInt64(&v)
        return NSColor(srgbRed: CGFloat((v >> 16) & 0xff) / 255, green: CGFloat((v >> 8) & 0xff) / 255,
                       blue: CGFloat(v & 0xff) / 255, alpha: 1)
    }
}

func numberFont(_ name: String?, size: CGFloat) -> NSFont {
    switch name {
    case "rounded":
        let base = NSFont.monospacedDigitSystemFont(ofSize: size, weight: .semibold)
        return NSFont(descriptor: base.fontDescriptor.withDesign(.rounded)!, size: size)!
    case "mono": return .monospacedSystemFont(ofSize: size, weight: .medium)
    case "bold": return .monospacedDigitSystemFont(ofSize: size, weight: .bold)
    default: return .monospacedDigitSystemFont(ofSize: size, weight: .medium)  // what GlucoseBar uses today
    }
}

func menubar(_ job: Job) {
    let scale: CGFloat = 2, height: CGFloat = 24, gap: CGFloat = 22, pad: CGFloat = 12
    let dark = job.dark ?? false
    let appearance = NSAppearance(named: dark ? .darkAqua : .aqua)!
    var segments: [(NSAttributedString, NSImage)] = []
    var width = pad
    appearance.performAsCurrentDrawingAppearance {
        for it in job.items ?? [] {
            let stale = it.stale ?? false
            let c = stale ? NSColor.secondaryLabelColor : (color(it.color) ?? .labelColor)
            var attrs: [NSAttributedString.Key: Any] = [.font: numberFont(job.font, size: NSFont.systemFontSize),
                                                        .foregroundColor: c]
            if stale { attrs[.strikethroughStyle] = NSUnderlineStyle.single.rawValue }
            let s = NSAttributedString(string: it.text, attributes: attrs)
            let arrow = TrendArrow.image(rate: stale ? nil : it.rate, size: 15, color: c)
            segments.append((s, arrow))
            width += s.size().width + 2 + 15 + gap
        }
    }
    width += pad - gap
    let rep = bitmap(Int(width * scale), Int(height * scale))
    rep.size = NSSize(width: width, height: height)
    draw(into: rep) {
        appearance.performAsCurrentDrawingAppearance {
            (dark ? NSColor(white: 0.13, alpha: 1) : NSColor(white: 0.95, alpha: 1)).setFill()
            NSRect(x: 0, y: 0, width: width, height: height).fill()
            var x = pad
            for (s, arrow) in segments {
                let sz = s.size()
                s.draw(at: NSPoint(x: x, y: (height - sz.height) / 2))
                x += sz.width + 2
                arrow.draw(in: NSRect(x: x, y: (height - 15) / 2, width: 15, height: 15))
                x += 15 + gap
            }
        }
    }
    writePNG(rep, job.out)
}

func svg(_ job: Job) {
    guard let img = NSImage(contentsOf: URL(fileURLWithPath: job.in!)) else { fatalError("cannot load \(job.in!)") }
    let rep = bitmap(job.w!, job.h ?? job.w!)
    draw(into: rep) { img.draw(in: NSRect(x: 0, y: 0, width: job.w!, height: job.h ?? job.w!)) }
    writePNG(rep, job.out)
}

func sysicon(_ job: Job) {
    let s = job.w ?? 256
    let img = NSWorkspace.shared.icon(forFile: job.app!)
    let rep = bitmap(s, s)
    draw(into: rep) { img.draw(in: NSRect(x: 0, y: 0, width: s, height: s)) }
    writePNG(rep, job.out)
}

@main enum Render {
    static func main() {
        let url = URL(fileURLWithPath: CommandLine.arguments[1])
        for job in try! JSONDecoder().decode([Job].self, from: Data(contentsOf: url)) {
            switch job.kind {
            case "svg": svg(job)
            case "menubar": menubar(job)
            case "sysicon": sysicon(job)
            default: fatalError("unknown job kind \(job.kind)")
            }
        }
    }
}
