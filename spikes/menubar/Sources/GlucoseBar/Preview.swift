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
        let th = Thresholds()
        for (row, dark) in [false, true].enumerated() {
            let fg: NSColor = dark ? .white : .black
            let y = size.height - CGFloat(row + 1) * cell.height
            (dark ? NSColor(white: 0.12, alpha: 1) : NSColor(white: 0.93, alpha: 1)).setFill()
            NSRect(x: 0, y: y, width: size.width, height: cell.height).fill()
            for (i, rate) in rates.enumerated() {
                let x = CGFloat(i) * cell.width + 6
                let color = th.color(for: values[i]) ?? fg
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

/// `Sukrara -menuPreview out.png`: the dropdown's glucose chart and treatment section, light and dark side by side,
/// from synthetic data (3 h of readings, insulin / carbs / notes, entries in every v2 delivery state). Uses the same
/// SparklineView, StatusController.describe and TreatmentPaths code as the live menu; a temp store, no network.
enum MenuPreview {
    @MainActor static func render(to path: String) {
        let end = Date(timeIntervalSince1970: (Date().timeIntervalSince1970 / 60).rounded(.down) * 60)
        let readings = (0...180).map { m -> Reading in
            let x = Double(180 - m)
            return Reading(date: end.addingTimeInterval(-Double(m) * 60),
                           mgdl: 135 + 55 * sin(x / 34) + 18 * sin(x / 9) - (x > 120 ? (x - 120) * 0.6 : 0))
        }
        let now = Int64(end.timeIntervalSince1970)
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("sukrara-preview-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: dir) }
        let store = TreatmentStore(directory: dir)
        func at(_ min: Int64) -> Int64 { now - min * 60 }
        let rows = [
            Treatment(k: "rapid", a: 4.5, t: at(165), o: "phone", lv: Treatment.nowMs()),
            Treatment(k: "carbs", a: 30, t: at(160), o: "phone", lv: Treatment.nowMs()),
            Treatment(k: "long", a: 12, t: at(110), o: "phone", lv: Treatment.nowMs()),
            Treatment(k: "note", a: 0, t: at(75), n: "walk", o: "follower"),
            Treatment(k: "rapid", a: 1.5, t: at(40), o: "mac"),
            Treatment(k: "carbs", a: 15, t: at(22), o: "mac"),
            Treatment(k: "note", a: 0, t: at(4), n: "sensor ok", o: "mac"),
        ]
        for (i, t) in rows.enumerated() {
            if t.o == "mac" { store.upsertLocal(t) } else { store.receive(t, v2: i == 3) }
        }
        store.markLegacyLinked()
        store.setV2(rows[4].id, u: rows[4].u, .accepted, via: "ble:Tartaruga")
        store.setV2(rows[5].id, u: rows[5].u, .sent, via: "relay:nos.lol")
        store.setV2(rows[6].id, u: rows[6].u, .sending)

        let paths = [
            TreatmentPaths(route: .v2, legacy: "Bluetooth link to Tartaruga: read-only · connected",
                           v2: "→ wearer over Sukrara sharing (v2) · allowed"),
            TreatmentPaths(route: .none, legacy: "Bluetooth link to Tartaruga: read-only · connected",
                           v2: "✕ Sukrara sharing (v2): the wearer hasn't allowed this Mac to add entries"),
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
            line("\(Int(readings.last!.mgdl)) mg/dL   \(String(format: "%+.1f", -0.6)) mg/dL/min", bold: true)
            line("at \(end.formatted(date: .omitted, time: .standard))  (32s ago)", .secondaryLabelColor)
            let chart = SparklineView(frame: NSRect(x: 0, y: 0, width: 300, height: 104))
            chart.readings = readings
            chart.treatments = store.rows.filter { !$0.del }
            chart.end = end
            chart.translatesAutoresizingMaskIntoConstraints = false
            chart.widthAnchor.constraint(equalToConstant: 300).isActive = true
            chart.heightAnchor.constraint(equalToConstant: 104).isActive = true
            stack.addArrangedSubview(chart)
            line("Source: Bluetooth · Tartaruga", .secondaryLabelColor)
            line(i == 0 ? "Following (v2) · key epoch 2 · 4 relays" : "Following (v2) · key epoch 2 · 4 relays", .secondaryLabelColor)
            stack.addArrangedSubview(separator(w - 28))
            line("Log treatment…", p.canLog ? .labelColor : .tertiaryLabelColor)
            for l in (p.canLog ? [] : [TreatmentPaths.notAllowed]) + p.lines { line(l, .secondaryLabelColor, indent: 14) }
            line("Treatments (24 h)", .secondaryLabelColor)
            for t in store.rows.sorted(by: { $0.t > $1.t }) {
                let st = TreatmentPaths.status(of: t, store: store, lvOn: true)
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
        let h = panels.map(\.frame.height).max()!
        let scale: CGFloat = 2
        let rep = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: Int(w * 2 * scale), pixelsHigh: Int(h * scale),
                                   bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true, isPlanar: false,
                                   colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0)!
        rep.size = NSSize(width: w * 2, height: h)
        for (i, v) in panels.enumerated() {
            v.setFrameSize(NSSize(width: w, height: h))
            v.layoutSubtreeIfNeeded()
            guard let r = v.bitmapImageRepForCachingDisplay(in: v.bounds) else { continue }
            v.cacheDisplay(in: v.bounds, to: r)
            NSGraphicsContext.saveGraphicsState()
            NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: rep)
            r.draw(in: NSRect(x: CGFloat(i) * w, y: 0, width: w, height: h))
            NSGraphicsContext.restoreGraphicsState()
        }
        try! rep.representation(using: .png, properties: [:])!.write(to: URL(fileURLWithPath: path))
        print("wrote", path)
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
