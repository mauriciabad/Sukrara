// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import AppKit

/// `Sukrara -preview out.png` renders what the menubar item looks like for a range of rates,
/// on light and dark backgrounds, so the arrow can be eyeballed without a live source.
enum Preview {
    static func render(to path: String) {
        let rates: [Double] = [-4, -3, -2.5, -2, -1.5, -1, -0.5, -0.2, 0, 0.2, 0.5, 1, 1.5, 2, 2.5, 3, 4]
        let values: [Double] = [58, 72, 88, 104, 110, 120, 126, 131, 133, 135, 140, 150, 168, 190, 214, 240, 270]
        let cell = NSSize(width: 64, height: 24), scale: CGFloat = 3
        let size = NSSize(width: cell.width * CGFloat(rates.count), height: cell.height * 2 + 14)
        let rep = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: Int(size.width * scale), pixelsHigh: Int(size.height * scale),
                                   bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true, isPlanar: false,
                                   colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0)!
        rep.size = size
        NSGraphicsContext.saveGraphicsState()
        NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: rep)
        let bands = Bands()
        for (row, dark) in [false, true].enumerated() {
            let fg: NSColor = dark ? .white : .black
            let y = size.height - CGFloat(row + 1) * cell.height
            (dark ? NSColor(white: 0.12, alpha: 1) : NSColor(white: 0.93, alpha: 1)).setFill()
            NSRect(x: 0, y: y, width: size.width, height: cell.height).fill()
            for (i, rate) in rates.enumerated() {
                let x = CGFloat(i) * cell.width + 6
                let color = bands.color(for: values[i]) ?? fg
                let s = NSAttributedString(string: GlucoseUnit.mgdl.format(values[i]), attributes: [
                    .font: NSFont.monospacedDigitSystemFont(ofSize: NSFont.systemFontSize, weight: .medium),
                    .foregroundColor: color,
                ])
                s.draw(at: NSPoint(x: x, y: y + 4))
                TrendArrow.image(rate: rate, size: 15, color: color)
                    .draw(in: NSRect(x: x + s.size().width + 2, y: y + 4.5, width: 15, height: 15))
            }
        }
        for (i, rate) in rates.enumerated() {
            NSAttributedString(string: String(format: "%+.1f", rate), attributes: [.font: NSFont.systemFont(ofSize: 9)])
                .draw(at: NSPoint(x: CGFloat(i) * cell.width + 14, y: 1))
        }
        NSGraphicsContext.restoreGraphicsState()
        try! rep.representation(using: .png, properties: [:])!.write(to: URL(fileURLWithPath: path))
        print("wrote", path)
    }
}

/// `Sukrara -panelPreview <dir>`: the real glance panel (PanelView on the menu material, the same view the status
/// item opens) over a bright and a dark test wallpaper, in light, dark and Increase Contrast, from synthetic data (a
/// realistic 15 days, insulin / carbs / notes). Each one is an on-screen window whose material blends with the
/// wallpaper drawn inside it (`.withinWindow`; the live panel blends with the desktop, `.behindWindow`), captured
/// with `screencapture -l` → `<dir>/panel-<appearance>-<wallpaper>.png`. Needs Screen Recording for the capture.
enum PanelPreview {
    @MainActor static func run(dir: String) {
        var dc = Calendar.current.dateComponents([.year, .month, .day], from: Date())
        dc.hour = 22; dc.minute = 14
        let end = Calendar.current.date(from: dc)!
        let all = RealisticSynthetic.readings(until: end.timeIntervalSince1970, days: 15)
        let cfg = GraphConfig()
        let now = Int64(end.timeIntervalSince1970)
        func at(_ min: Int64) -> Int64 { now - min * 60 }
        let rows = [
            Treatment(k: "rapid", a: 4.5, t: at(165)), Treatment(k: "carbs", a: 30, t: at(160)),
            Treatment(k: "long", a: 12, t: at(110)), Treatment(k: "note", a: 0, t: at(75), n: "walk"),
            Treatment(k: "rapid", a: 1.5, t: at(40)), Treatment(k: "carbs", a: 15, t: at(22)),
        ]
        func model(entry: String, logged: Bool) -> PanelModel {
            let m = PanelModel()
            m.readings = all.filter { $0.t > end.timeIntervalSince1970 - 12 * 3600 - 120 }
            m.marks = rows.map { GraphMark(t: TimeInterval($0.t), k: $0.k, a: $0.a, n: $0.n) }
            m.style = cfg.cardStyle(graph: cfg.graphStyle())
            m.alerts = AlertSettings()
            m.metricsOf = { spec, b in GlucoseMetrics.of(all, from: end.timeIntervalSince1970 - spec.window, to: end.timeIntervalSince1970, bands: b) }
            m.now = end.addingTimeInterval(30)
            m.hasData = true
            m.following = true
            m.kind = "rapid"
            m.entry = entry
            if logged { m.logged = ("15g carbs", "x") }
            return m
        }
        typealias Shot = (name: String, appearance: NSAppearance.Name, bright: Bool, entry: String, logged: Bool)
        let shots: [Shot] = [
            ("light-bright", .aqua, true, "4.5u", false), ("light-dark", .aqua, false, "", true),
            ("dark-bright", .darkAqua, true, "", true), ("dark-dark", .darkAqua, false, "30g pasta", false),
        ]
        var windows: [NSWindow] = []
        for (i, shot) in shots.enumerated() {
            let m = model(entry: shot.entry, logged: shot.logged)
            let panel = GlancePanel.content(m, blending: .withinWindow)
            panel.appearance = NSAppearance(named: shot.appearance)
            panel.layoutSubtreeIfNeeded()
            let size = panel.fittingSize
            let pad: CGFloat = 36
            let frame = NSRect(x: 0, y: 0, width: size.width + 2 * pad, height: size.height + 2 * pad)
            let root = Wallpaper(frame: frame)
            root.bright = shot.bright
            panel.frame = NSRect(x: pad, y: pad, width: size.width, height: size.height)
            root.addSubview(panel)
            let w = NSWindow(contentRect: frame.offsetBy(dx: 40 + CGFloat(i) * 30, dy: 80), styleMask: [.borderless], backing: .buffered, defer: false)
            w.contentView = root
            w.appearance = NSAppearance(named: shot.appearance)
            w.orderFrontRegardless()
            windows.append(w)
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) {
            for (w, shot) in zip(windows, shots) {
                let out = "\(dir)/panel-\(shot.name).png"
                let p = Process()
                p.executableURL = URL(fileURLWithPath: "/usr/sbin/screencapture")
                p.arguments = ["-x", "-o", "-l\(w.windowNumber)", out]
                try? p.run()
                p.waitUntilExit()
                print(FileManager.default.fileExists(atPath: out) ? "wrote \(out)" : "FAILED \(out) (Screen Recording permission?)")
            }
            exit(0)
        }
        NSApplication.shared.setActivationPolicy(.accessory)
        NSApplication.shared.run()
    }
}

/// Test wallpapers: a bright pastel one (white, yellow, pink, sky) and a dark one (navy, plum, black, teal).
private final class Wallpaper: NSView {
    var bright = true
    override func draw(_ dirtyRect: NSRect) {
        guard let ctx = NSGraphicsContext.current?.cgContext else { return }
        let hexes: [UInt32] = bright ? [0xFFF4C2, 0xFF9DB0, 0xFFFFFF, 0x8FD8FF] : [0x07102E, 0x3A0F4F, 0x000000, 0x003A44]
        let g = CGGradient(colorsSpace: CGColorSpaceCreateDeviceRGB(), colors: hexes.map(GraphPalette.hex) as CFArray, locations: [0, 0.35, 0.65, 1])!
        ctx.drawLinearGradient(g, start: .zero, end: CGPoint(x: bounds.width, y: bounds.height), options: [])
    }
}

private extension NSFont {
    func withWeight(_ w: NSFont.Weight) -> NSFont { NSFont.systemFont(ofSize: pointSize, weight: w) }
}
