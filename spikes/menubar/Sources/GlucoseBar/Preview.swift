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

/// `Sukrara -menuPreview out.png`: the dropdown's glucose card and treatment section as `out-light.png` and
/// `out-dark.png`, from synthetic data (a realistic 15 days for the rings, insulin / carbs / notes, entries in every
/// v2 delivery state). Uses the same CardView, StatusController.describe and TreatmentPaths code as the live menu; a
/// temp store, no network.
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

        let paths = [
            TreatmentPaths(route: .v2, v2: "→ wearer over Sukrara sharing (v2) · allowed"),
            TreatmentPaths(route: .none, v2: "✕ Sukrara sharing (v2): the wearer hasn't allowed this Mac to add entries"),
        ]
        let w: CGFloat = 470
        var panels: [NSView] = []
        for (i, dark) in [false, true].enumerated() {
            let p = paths[i]
            let stack = NSStackView()
            stack.orientation = .vertical
            stack.alignment = .leading
            stack.spacing = 3
            stack.edgeInsets = NSEdgeInsets(top: 10, left: 14, bottom: 10, right: 14)
            func line(_ s: String, _ c: NSColor = .labelColor, bold: Bool = false, indent: CGFloat = 0) {
                let f = NSTextField(labelWithString: s)
                f.font = bold ? .menuFont(ofSize: 13).withWeight(.semibold) : .menuFont(ofSize: 13)
                f.textColor = c
                let row = NSStackView(views: [f])
                row.edgeInsets = NSEdgeInsets(top: 0, left: indent, bottom: 0, right: 0)
                stack.addArrangedSubview(row)
            }
            let chart = CardView(frame: NSRect(x: 0, y: 0, width: 340, height: 200))
            chart.inset = NSEdgeInsets(top: 2, left: 0, bottom: 2, right: 0)
            chart.readings = all.filter { $0.t > end.timeIntervalSince1970 - 12 * 3600 - 120 }
            chart.marks = store.rows.filter { !$0.del }.map { GraphMark(t: TimeInterval($0.t), k: $0.k, a: $0.a, n: $0.n) }
            chart.style = CardStyle(graph: cfg.graphStyle(), rings: cfg.rings, value: true)
            chart.tirOf = { spec, b in GlucoseStats.timeInRange(all, from: end.timeIntervalSince1970 - spec.window, to: end.timeIntervalSince1970, bands: b) }
            chart.now = end.addingTimeInterval(30)
            chart.translatesAutoresizingMaskIntoConstraints = false
            chart.widthAnchor.constraint(equalToConstant: 340).isActive = true
            chart.heightAnchor.constraint(equalToConstant: 200).isActive = true
            stack.addArrangedSubview(chart)
            line("Source: Bluetooth · Tartaruga", .secondaryLabelColor)
            line(i == 0 ? "Following (v2) · key epoch 2 · 4 relays" : "Following (v2) · key epoch 2 · 4 relays", .secondaryLabelColor)
            stack.addArrangedSubview(separator(w - 28))
            line("Log treatment…", p.canLog ? .labelColor : .tertiaryLabelColor)
            for l in (p.canLog ? [] : [TreatmentPaths.notAllowed]) + p.lines { line(l, .secondaryLabelColor, indent: 14) }
            line("Treatments (24 h)", .secondaryLabelColor)
            for t in store.rows.sorted(by: { $0.t > $1.t }) {
                let st = TreatmentPaths.status(of: t, store: store)
                line(StatusController.describe(t) + (st.isEmpty ? "" : "   " + st))
            }
            let box = NSView(frame: NSRect(x: 0, y: 0, width: w, height: 10))
            box.appearance = NSAppearance(named: dark ? .darkAqua : .aqua)
            box.wantsLayer = true
            stack.translatesAutoresizingMaskIntoConstraints = false
            box.addSubview(stack)
            NSLayoutConstraint.activate([stack.leadingAnchor.constraint(equalTo: box.leadingAnchor),
                                         stack.topAnchor.constraint(equalTo: box.topAnchor),
                                         stack.widthAnchor.constraint(equalToConstant: w)])
            box.layoutSubtreeIfNeeded()
            box.setFrameSize(NSSize(width: w, height: stack.fittingSize.height))
            box.layoutSubtreeIfNeeded()
            box.appearance?.performAsCurrentDrawingAppearance {
                box.layer?.backgroundColor = (dark ? NSColor(white: 0.17, alpha: 1) : NSColor(white: 0.96, alpha: 1)).cgColor
            }
            panels.append(box)
        }
        let base = path.hasSuffix(".png") ? String(path.dropLast(4)) : path
        for (i, v) in panels.enumerated() {
            v.layoutSubtreeIfNeeded()
            let scale: CGFloat = 2
            let rep = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: Int(v.bounds.width * scale), pixelsHigh: Int(v.bounds.height * scale),
                                       bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true, isPlanar: false,
                                       colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0)!
            rep.size = v.bounds.size
            v.cacheDisplay(in: v.bounds, to: rep)
            let out = "\(base)-\(i == 0 ? "light" : "dark").png"
            try! rep.representation(using: .png, properties: [:])!.write(to: URL(fileURLWithPath: out))
            print("wrote", out)
        }
    }

    private static func separator(_ width: CGFloat) -> NSView {
        let b = NSBox()
        b.boxType = .separator
        b.translatesAutoresizingMaskIntoConstraints = false
        b.widthAnchor.constraint(equalToConstant: width).isActive = true
        return b
    }
}

private extension NSFont {
    func withWeight(_ w: NSFont.Weight) -> NSFont { NSFont.systemFont(ofSize: pointSize, weight: w) }
}
