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

/// `Sukrara -menuPreview out.png`: the dropdown as `out-light.png`, `out-dark.png` and, with Increase Contrast,
/// `out-light-hc.png` / `out-dark-hc.png`, from synthetic data (a realistic 15 days for the rings, insulin / carbs /
/// notes, entries in every v2 delivery state). Uses the same CardView, InfoView, StatusController.describe and
/// TreatmentPaths code as the live menu; a temp store, no network. The menu's material is simulated over a vivid
/// wallpaper (a 72 % tint, like a menu over a bright desktop): the live material can't be captured offscreen.
/// `-menuPreviewPlatter NO` draws the card as it was before the platter (round 19's "before").
enum MenuPreview {
    @MainActor static func render(to path: String) {
        // 22:14 local today: the evening of the mock-up, with the day's meals in the 12 h window.
        var dc = Calendar.current.dateComponents([.year, .month, .day], from: Date())
        dc.hour = 22; dc.minute = 14
        let end = Calendar.current.date(from: dc)!
        let all = RealisticSynthetic.readings(until: end.timeIntervalSince1970, days: 15)
        let cfg = GraphConfig()
        let now = Int64(end.timeIntervalSince1970)
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("sukrara-preview-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: dir) }
        let store = TreatmentStore(directory: dir)
        func at(_ min: Int64) -> Int64 { now - min * 60 }
        let rows = [
            Treatment(k: "rapid", a: 4.5, t: at(165), o: "phone"),
            Treatment(k: "carbs", a: 30, t: at(160), o: "phone"),
            Treatment(k: "long", a: 12, t: at(110), o: "phone"),
            Treatment(k: "note", a: 0, t: at(75), n: "walk", o: "follower"),
            Treatment(k: "rapid", a: 1.5, t: at(40), o: "mac"),
            Treatment(k: "carbs", a: 15, t: at(22), o: "mac"),
            Treatment(k: "note", a: 0, t: at(4), n: "sensor ok", o: "mac"),
        ]
        for t in rows {
            if t.o == "mac" { store.upsertLocal(t) } else { store.receive(t) }
        }
        store.setV2(rows[4].id, u: rows[4].u, .accepted, via: "ble:Tartaruga")
        store.setV2(rows[5].id, u: rows[5].u, .sent, via: "relay:nos.lol")
        store.setV2(rows[6].id, u: rows[6].u, .sending)
        let platter = UserDefaults.standard.object(forKey: "menuPreviewPlatter") == nil || UserDefaults.standard.bool(forKey: "menuPreviewPlatter")

        let w: CGFloat = 368
        let base = path.hasSuffix(".png") ? String(path.dropLast(4)) : path
        for (dark, hc) in [(false, false), (true, false), (false, true), (true, true)] {
            let stack = NSStackView()
            stack.orientation = .vertical
            stack.alignment = .leading
            stack.spacing = 0
            stack.edgeInsets = NSEdgeInsets(top: 5, left: 0, bottom: 5, right: 0)
            func item(_ s: String, _ c: NSColor = .labelColor, key: String? = nil, header: Bool = false, chevron: Bool = false) {
                let f = NSTextField(labelWithString: s)
                f.font = header ? .systemFont(ofSize: 11, weight: .semibold) : .menuFont(ofSize: 0)
                f.textColor = c
                let row = NSStackView(views: [f])
                row.edgeInsets = NSEdgeInsets(top: 3, left: 14, bottom: 3, right: 14)
                row.translatesAutoresizingMaskIntoConstraints = false
                row.widthAnchor.constraint(equalToConstant: w).isActive = true
                if let key {
                    row.addView(NSView(), in: .trailing)
                    let k = NSTextField(labelWithString: key)
                    k.font = .menuFont(ofSize: 0)
                    k.textColor = .tertiaryLabelColor
                    row.addView(k, in: .trailing)
                }
                if chevron {
                    let k = NSTextField(labelWithString: "›")
                    k.font = .menuFont(ofSize: 0)
                    k.textColor = .secondaryLabelColor
                    row.addView(k, in: .trailing)
                }
                stack.addArrangedSubview(row)
            }
            let chart = CardView(frame: NSRect(x: 0, y: 0, width: w, height: platter ? 212 : 200))
            if !platter { chart.platterInset = nil; chart.inset = NSEdgeInsets(top: 6, left: 14, bottom: 4, right: 14) }
            chart.increaseContrast = hc
            chart.reduceTransparency = false
            chart.readings = all.filter { $0.t > end.timeIntervalSince1970 - 12 * 3600 - 120 }
            chart.marks = store.rows.filter { !$0.del }.map { GraphMark(t: TimeInterval($0.t), k: $0.k, a: $0.a, n: $0.n) }
            chart.style = cfg.cardStyle(graph: cfg.graphStyle())
            chart.alerts = AlertSettings()
            chart.metricsOf = { spec, b in GlucoseMetrics.of(all, from: end.timeIntervalSince1970 - spec.window, to: end.timeIntervalSince1970, bands: b) }
            chart.now = end.addingTimeInterval(30)
            chart.translatesAutoresizingMaskIntoConstraints = false
            chart.widthAnchor.constraint(equalToConstant: w).isActive = true
            chart.heightAnchor.constraint(equalToConstant: chart.frame.height).isActive = true
            stack.addArrangedSubview(chart)
            let info = InfoView()
            info.set([("Source: Bluetooth · Tartaruga", .secondaryLabelColor), ("Following Ana · key epoch 2 · 4 relays", .secondaryLabelColor)],
                     image: Avatar.image(nil, name: "Ana", size: 18))
            info.translatesAutoresizingMaskIntoConstraints = false
            info.widthAnchor.constraint(equalToConstant: w).isActive = true
            info.heightAnchor.constraint(equalToConstant: info.frame.height).isActive = true
            stack.addArrangedSubview(info)
            stack.addArrangedSubview(separator(w))
            item("Treatments (24 h)", .secondaryLabelColor, header: true)
            for t in store.rows.sorted(by: { $0.t > $1.t }).prefix(StatusController.maxTreatmentRows) {
                let st = TreatmentPaths.status(of: t, store: store)
                item(StatusController.describe(t) + (st.isEmpty ? "" : "   " + st), chevron: true)
            }
            item("Log Treatment…", key: "⌘L")
            stack.addArrangedSubview(separator(w))
            item("Glucose Report…")
            item("Settings…", key: "⌘,")
            stack.addArrangedSubview(separator(w))
            item("Quit Sukrara", key: "⌘Q")

            let box = MaterialBackdrop(frame: NSRect(x: 0, y: 0, width: w, height: 10))
            box.appearance = NSAppearance(named: hc ? (dark ? .accessibilityHighContrastDarkAqua : .accessibilityHighContrastAqua) : (dark ? .darkAqua : .aqua))
            box.dark = dark
            stack.translatesAutoresizingMaskIntoConstraints = false
            box.addSubview(stack)
            NSLayoutConstraint.activate([stack.leadingAnchor.constraint(equalTo: box.leadingAnchor),
                                         stack.topAnchor.constraint(equalTo: box.topAnchor),
                                         stack.widthAnchor.constraint(equalToConstant: w)])
            box.layoutSubtreeIfNeeded()
            box.setFrameSize(NSSize(width: w, height: stack.fittingSize.height))
            box.layoutSubtreeIfNeeded()
            let scale: CGFloat = 2
            let rep = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: Int(box.bounds.width * scale), pixelsHigh: Int(box.bounds.height * scale),
                                       bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true, isPlanar: false,
                                       colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0)!
            rep.size = box.bounds.size
            box.cacheDisplay(in: box.bounds, to: rep)
            let out = "\(base)-\(dark ? "dark" : "light")\(hc ? "-hc" : "").png"
            try! rep.representation(using: .png, properties: [:])!.write(to: URL(fileURLWithPath: out))
            print("wrote", out)
        }
    }

    private static func separator(_ width: CGFloat) -> NSView {
        let b = NSBox()
        b.boxType = .separator
        let wrap = NSStackView(views: [b])
        wrap.edgeInsets = NSEdgeInsets(top: 5, left: 14, bottom: 5, right: 14)
        wrap.translatesAutoresizingMaskIntoConstraints = false
        wrap.widthAnchor.constraint(equalToConstant: width).isActive = true
        return wrap
    }
}

/// A stand-in for the menu's material: a vivid "wallpaper" (blurred, so smooth) under the menu tint.
private final class MaterialBackdrop: NSView {
    var dark = false
    override var isFlipped: Bool { true }
    override func draw(_ dirtyRect: NSRect) {
        guard let ctx = NSGraphicsContext.current?.cgContext else { return }
        let cs = CGColorSpaceCreateDeviceRGB()
        let colors = [GraphPalette.hex(0x2F6BFF), GraphPalette.hex(0xF26B3A), GraphPalette.hex(0xFFD23F), GraphPalette.hex(0x19B37D)] as CFArray
        let g = CGGradient(colorsSpace: cs, colors: colors, locations: [0, 0.4, 0.7, 1])!
        ctx.drawLinearGradient(g, start: .zero, end: CGPoint(x: bounds.width, y: bounds.height), options: [])
        ctx.setFillColor(dark ? CGColor(srgbRed: 0.12, green: 0.12, blue: 0.13, alpha: 0.72) : CGColor(srgbRed: 0.96, green: 0.96, blue: 0.97, alpha: 0.72))
        ctx.fill(bounds)
    }
}

private extension NSFont {
    func withWeight(_ w: NSFont.Weight) -> NSFont { NSFont.systemFont(ofSize: pointSize, weight: w) }
}
