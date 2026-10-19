// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import AppKit
import SwiftUI
import WidgetKit

/// Renders the widget views to PNG with ImageRenderer, outside WidgetKit (no Xcode, no chronod).
/// The container (rounded background + 16 pt margins) imitates what macOS adds around a widget.
/// Usage: render <out-dir>

/// macOS widget sizes in points (desktop / Notification Center, macOS 14+).
let sizes: [WidgetFamily: CGSize] = [
    .systemSmall: CGSize(width: 170, height: 170),
    .systemMedium: CGSize(width: 364, height: 170),
    .systemLarge: CGSize(width: 364, height: 382),
    .systemExtraLarge: CGSize(width: 746, height: 382),
]
let families: [WidgetKind: [WidgetFamily]] = [
    .value: [.systemSmall, .systemMedium],
    .graph: [.systemSmall, .systemMedium, .systemLarge, .systemExtraLarge],
    .combined: [.systemSmall, .systemMedium, .systemLarge, .systemExtraLarge],
]
func name(_ f: WidgetFamily) -> String {
    switch f {
    case .systemSmall: "small"
    case .systemMedium: "medium"
    case .systemLarge: "large"
    case .systemExtraLarge: "xl"
    default: "other"
    }
}

/// "Now" for the evidence renders: 22:14 local today (the mock-up's evening, meals in view), whatever the clock says.
let renderNow: Date = {
    var c = Calendar.current.dateComponents([.year, .month, .day], from: Date())
    c.hour = 22; c.minute = 14
    return Calendar.current.date(from: c)!
}()

/// The realistic synthetic day ending `lag` seconds before `renderNow` (rings from 15 days of it).
func scenario(lag: TimeInterval = 30) -> GlucoseSnapshot {
    var s = Synthetic.realistic(now: renderNow.addingTimeInterval(-lag))
    s.written = renderNow
    return s
}

struct Frame<Content: View>: View {
    var size: CGSize
    var mode: RenderMode
    @ViewBuilder var content: Content
    var body: some View {
        content
            .padding(16)
            .frame(width: size.width, height: size.height)
            .background {
                RoundedRectangle(cornerRadius: 22, style: .continuous)
                    .fill(mode == .fullColor ? AnyShapeStyle(.background.secondary) : AnyShapeStyle(Color.white.opacity(0.14)))
            }
            .padding(12)
            .background(mode == .fullColor ? Color.clear : Color(red: 0.12, green: 0.16, blue: 0.24))
    }
}

@MainActor
func render<V: View>(_ v: V, to url: URL, dark: Bool) {
    let r = ImageRenderer(content: v.environment(\.colorScheme, dark ? .dark : .light)
        .background(dark ? Color(white: 0.11) : Color(white: 0.93)))
    r.scale = 2
    guard let img = r.nsImage, let tiff = img.tiffRepresentation,
          let png = NSBitmapImageRep(data: tiff)?.representation(using: .png, properties: [:]) else {
        print("render failed", url.lastPathComponent); return
    }
    try! png.write(to: url)
    print("wrote", url.path)
}

/// An approximation of macOS's tinted widget rendering: the bitmap's luminance becomes the opacity of one tint
/// colour over a dark desktop. Enough to judge how strong the grid and fills come out.
@MainActor
func renderTinted<V: View>(_ v: V, size: CGSize, to url: URL) {
    let r = ImageRenderer(content: v.environment(\.colorScheme, .dark).frame(width: size.width, height: size.height))
    r.scale = 2
    guard let cg = r.cgImage else { print("tinted render failed"); return }
    let w = cg.width, h = cg.height
    var px = [UInt8](repeating: 0, count: w * h * 4)
    let ctx = CGContext(data: &px, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w * 4,
                        space: CGColorSpace(name: CGColorSpace.sRGB)!, bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
    ctx.draw(cg, in: CGRect(x: 0, y: 0, width: w, height: h))
    let bg: (Double, Double, Double) = (0.12, 0.16, 0.24), tint: (Double, Double, Double) = (0.85, 0.92, 1.0)
    for i in stride(from: 0, to: px.count, by: 4) {
        let lum = (0.299 * Double(px[i]) + 0.587 * Double(px[i + 1]) + 0.114 * Double(px[i + 2])) / 255   // premultiplied
        px[i] = UInt8(255 * (bg.0 + (tint.0 - bg.0) * lum)); px[i + 1] = UInt8(255 * (bg.1 + (tint.1 - bg.1) * lum))
        px[i + 2] = UInt8(255 * (bg.2 + (tint.2 - bg.2) * lum)); px[i + 3] = 255
    }
    let out = CGContext(data: &px, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w * 4,
                        space: CGColorSpace(name: CGColorSpace.sRGB)!, bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!.makeImage()!
    try! NSBitmapImageRep(cgImage: out).representation(using: .png, properties: [:])!.write(to: url)
    print("wrote", url.path)
}

@main
struct RenderMain {
    @MainActor static func main() {
        if CommandLine.arguments.dropFirst().first == "treatments" {   // render treatments <out-dir>
            TreatmentSheets.run(URL(fileURLWithPath: CommandLine.arguments.dropFirst(2).first ?? "evidence"))
            return
        }
        if CommandLine.arguments.dropFirst().first == "marks" {   // render marks <out-dir>
            renderMarks(URL(fileURLWithPath: CommandLine.arguments.dropFirst(2).first ?? "build/marks"))
            return
        }
        let out = URL(fileURLWithPath: CommandLine.arguments.dropFirst().first ?? "evidence")
        try? FileManager.default.createDirectory(at: out, withIntermediateDirectories: true)
        let live = scenario()

        // Investigation 16: every family of the combined widget, value + graph medium, light + dark.
        for dark in [false, true] {
            let tag = dark ? "dark" : "light"
            for fam in families[.combined]! {
                let v = Frame(size: sizes[fam]!, mode: .fullColor) {
                    GlucoseWidgetView(kind: .combined, family: fam, c: GlucoseEntryContent(snapshot: live, date: renderNow), mode: .fullColor)
                }
                render(v, to: out.appendingPathComponent("mac-widget-\(name(fam))-\(tag).png"), dark: dark)
            }
            for kind in [WidgetKind.value, .graph] {
                let v = Frame(size: sizes[.systemMedium]!, mode: .fullColor) {
                    GlucoseWidgetView(kind: kind, family: .systemMedium, c: GlucoseEntryContent(snapshot: live, date: renderNow), mode: .fullColor)
                }
                render(v, to: out.appendingPathComponent("mac-widget-\(kind.rawValue)-medium-\(tag).png"), dark: dark)
            }
        }
        // Freshness steps on the combined medium widget: fresh, late (3 min), stale (12 min), old (75 min), gone (5 h).
        let steps: [(String, TimeInterval)] = [("fresh", 30), ("late", 180), ("stale", 720), ("old", 4500), ("gone", 18000)]
        let row = HStack(spacing: 0) {
            ForEach(steps, id: \.0) { (n, lag) in
                Frame(size: sizes[.systemMedium]!, mode: .fullColor) {
                    GlucoseWidgetView(kind: .combined, family: .systemMedium, c: GlucoseEntryContent(snapshot: scenario(lag: lag), date: renderNow), mode: .fullColor)
                }
            }
        }
        render(row, to: out.appendingPathComponent("mac-widget-freshness-dark.png"), dark: true)
        // Two placements of the same widget with their own settings (per-widget configuration).
        let two = HStack(spacing: 0) {
            Frame(size: sizes[.systemMedium]!, mode: .fullColor) {
                GlucoseWidgetView(kind: .combined, family: .systemMedium, c: GlucoseEntryContent(snapshot: live, date: renderNow), mode: .fullColor)
            }
            Frame(size: sizes[.systemMedium]!, mode: .fullColor) {
                GlucoseWidgetView(kind: .combined, family: .systemMedium, c: GlucoseEntryContent(snapshot: live, date: renderNow), mode: .fullColor,
                                  overrides: WidgetOverrides(hours: 3, rings: 1, ringRange: .tight, side: false))
            }
        }
        render(two, to: out.appendingPathComponent("mac-widget-two-configs-dark.png"), dark: true)
        // Tinted (accented / vibrant) desktop: full-colour decoration (left) vs the fainter tinted palette (right).
        let medium = sizes[.systemMedium]!
        for (n, m) in [("tinted-before", RenderMode.fullColor), ("tinted-after", .accented)] {
            renderTinted(GlucoseWidgetView(kind: .combined, family: .systemMedium, c: GlucoseEntryContent(snapshot: live, date: renderNow), mode: m)
                .padding(16), size: medium, to: URL(fileURLWithPath: "build/\(n).png"))   // a check, not evidence
        }
        // Investigation 18: the third slot shows the 14-day GMI at full strength (docs/img/18).
        var cfg = GraphConfig()
        cfg.ringCount = 3
        cfg.slots[2] = RingSpec(window: 14 * 86400, kind: .gmi, faded: false)
        var gmi = Synthetic.realistic(now: renderNow.addingTimeInterval(-30), config: cfg)
        gmi.written = renderNow
        let out18 = out.deletingLastPathComponent().appendingPathComponent("18")
        try? FileManager.default.createDirectory(at: out18, withIntermediateDirectories: true)
        for dark in [false, true] {
            let v = Frame(size: sizes[.systemMedium]!, mode: .fullColor) {
                GlucoseWidgetView(kind: .combined, family: .systemMedium, c: GlucoseEntryContent(snapshot: gmi, date: renderNow), mode: .fullColor)
            }
            render(v, to: out18.appendingPathComponent("mac-widget-medium-gmi-\(dark ? "dark" : "light").png"), dark: dark)
        }
    }
}

/// `render treatments <out-dir>`: the 2026-10-18 round's sheets (marks, badge, last treatment, places) with the same
/// states, sizes and clock as the Android gallery (GraphGallery.kt `marks`, `badge`, `lastTreatment`), for a side by
/// side comparison with docs/img/16.
enum TreatmentSheets {
    struct Tile { var title: String; var w: CGFloat; var h: CGFloat; var draw: (CGContext, CGSize, GraphPalette) -> Void }

    /// GraphGallery's `now`: 2026-10-17 22:14 local.
    static let now: TimeInterval = {
        var c = DateComponents(); c.year = 2026; c.month = 10; c.day = 17; c.hour = 22; c.minute = 14
        return Calendar.current.date(from: c)!.timeIntervalSince1970
    }()

    /// Synthetic.endAt: `data` bent over its last 40 minutes so the latest reading is `mgdl`, with a matching trend.
    static func endAt(_ data: [GraphReading], _ mgdl: Int) -> [GraphReading] {
        let last = Double(data.last!.sgv)
        var l = data.map { r -> GraphReading in
            let f = 1 - (now - r.t) / 2400
            var x = r
            if f > 0 { x.v = Double(max(40, Int((Double(r.sgv) + (Double(mgdl) - last) * f).rounded()))) }
            return x
        }
        for i in l.indices where i >= 5 { l[i].rate = Double(l[i].sgv - l[i - 5].sgv) / 5 }
        return l
    }
    static func tr(_ k: String, _ a: Double, _ minAgo: Int) -> GraphMark { GraphMark(t: now - TimeInterval(minAgo * 60), k: k, a: a) }

    static func card(_ data: [GraphReading], _ marks: [GraphMark], _ st: CardStyle) -> (CGContext, CGSize, GraphPalette) -> Void {
        { ctx, size, pal in GlucoseCard.draw(ctx, size: size, readings: data, marks: marks, now: now, st, pal, alerts: AlertSettings()) }
    }

    /// A tile drawn on its own transparent bitmap, then turned into what a tinted desktop shows: `.accented` keeps only
    /// the alpha (every drawn pixel takes the tint, whatever its colour), `.vibrant` multiplies it by the luminance.
    /// Both are approximations of macOS's rendering; the real one lies between them, so the badge must read in both.
    static func tintedTile(_ t: Tile, pal: GraphPalette, mode: RenderMode, scale: CGFloat) -> CGImage {
        let w = Int(t.w * scale), h = Int(t.h * scale)
        var px = [UInt8](repeating: 0, count: w * h * 4)
        let space = CGColorSpace(name: CGColorSpace.sRGB)!, info = CGImageAlphaInfo.premultipliedLast.rawValue
        let ctx = CGContext(data: &px, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w * 4, space: space, bitmapInfo: info)!
        ctx.translateBy(x: 0, y: CGFloat(h)); ctx.scaleBy(x: scale, y: -scale)
        t.draw(ctx, CGSize(width: t.w, height: t.h), pal)
        let tint: (Double, Double, Double) = (0.85, 0.92, 1.0)
        for i in stride(from: 0, to: px.count, by: 4) {
            let a = Double(px[i + 3]) / 255
            let lum = a > 0 ? (0.299 * Double(px[i]) + 0.587 * Double(px[i + 1]) + 0.114 * Double(px[i + 2])) / 255 / a : 0
            let o = mode == .vibrant ? a * lum : a
            px[i] = UInt8(255 * tint.0 * o); px[i + 1] = UInt8(255 * tint.1 * o); px[i + 2] = UInt8(255 * tint.2 * o)
            px[i + 3] = UInt8(255 * o)
        }
        return CGContext(data: &px, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w * 4, space: space, bitmapInfo: info)!.makeImage()!
    }

    static func sheet(_ url: URL, _ tiles: [Tile], cellW: CGFloat, cellH: CGFloat, cols: Int, dark: Bool, mode: RenderMode = .fullColor) {
        let pal = mode.tinted ? GraphPalette.of(dark: dark).tinted() : GraphPalette.of(dark: dark), pad: CGFloat = 14, cap: CGFloat = 34, scale: CGFloat = 2
        let rows = (tiles.count + cols - 1) / cols
        let sw = (cellW + 2 * pad) * CGFloat(cols) + pad, sh = (cellH + 2 * pad + cap) * CGFloat(rows) + pad
        let ctx = CGContext(data: nil, width: Int(sw * scale), height: Int(sh * scale), bitsPerComponent: 8, bytesPerRow: 0,
                            space: CGColorSpace(name: CGColorSpace.sRGB)!, bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
        ctx.translateBy(x: 0, y: sh * scale); ctx.scaleBy(x: scale, y: -scale)
        ctx.setFillColor(mode.tinted ? CGColor(srgbRed: 0.12, green: 0.16, blue: 0.24, alpha: 1) : GraphPalette.hex(dark ? 0x0E0E10 : 0xE9E9EE))
        ctx.fill(CGRect(x: 0, y: 0, width: sw, height: sh))
        for (i, t) in tiles.enumerated() {
            let cx = pad + CGFloat(i % cols) * (cellW + 2 * pad), cy = pad + CGFloat(i / cols) * (cellH + 2 * pad + cap)
            Ink.draw(ctx, t.title, x: cx + 4, y: cy + 14, Ink.font(13, .semibold), mode.tinted ? GraphPalette.hex(0xF2F2F2) : pal.ink)
            let top = cy + cap
            ctx.addPath(CGPath(roundedRect: CGRect(x: cx, y: top, width: t.w + 2 * pad, height: t.h + 2 * pad), cornerWidth: 22, cornerHeight: 22, transform: nil))
            ctx.setFillColor(mode.tinted ? CGColor(gray: 1, alpha: 0.14) : pal.bg); ctx.fillPath()
            if mode.tinted {
                ctx.saveGState()
                ctx.translateBy(x: cx + pad, y: top + pad + t.h); ctx.scaleBy(x: 1, y: -1)
                ctx.draw(tintedTile(t, pal: pal, mode: mode, scale: scale), in: CGRect(x: 0, y: 0, width: t.w, height: t.h))
                ctx.restoreGState()
                continue
            }
            ctx.saveGState()
            ctx.translateBy(x: cx + pad, y: top + pad)
            ctx.clip(to: CGRect(x: 0, y: 0, width: t.w, height: t.h))
            t.draw(ctx, CGSize(width: t.w, height: t.h), pal)
            ctx.restoreGState()
        }
        try! NSBitmapImageRep(cgImage: ctx.makeImage()!).representation(using: .png, properties: [:])!.write(to: url)
        print("wrote", url.path)
    }

    static func run(_ out: URL) {
        try? FileManager.default.createDirectory(at: out, withIntermediateDirectories: true)
        let day: TimeInterval = 86400
        var g = GraphStyle(); g.hour24 = true
        func gw(_ h: Int) -> GraphStyle { var x = g; x.window = TimeInterval(h * 3600); return x }
        let base31 = RealisticSynthetic.readings(until: now, days: 31)
        for dark in [true, false] {
            let tag = dark ? "dark" : "light"
            // marks
            let data15 = endAt(RealisticSynthetic.readings(until: now, days: 15), 150)
            let trs = [tr("rapid", 6, 8 * 60 + 10), tr("carbs", 60, 8 * 60), tr("long", 18, 6 * 60), tr("rapid", 4.5, 95),
                       tr("carbs", 45, 95), tr("carbs", 15, 40), tr("rapid", 2, 20), tr("note", 0, 150)]
            let mv: [(String, CGFloat, CGFloat, CardStyle)] = [
                ("4×2 combined, 12 h", 300, 150, CardStyle(graph: g)),
                ("4×2 combined, 3 h", 300, 150, CardStyle(graph: gw(3))),
                ("4×1 combined (side by side), 6 h", 300, 70, CardStyle(graph: gw(6))),
                ("notification (no value, 12 h)", 340, 110, CardStyle(graph: g, value: false)),
                ("5×3 combined, 24 h", 380, 230, CardStyle(graph: gw(24))),
                ("app, 12 h", 360, 210, CardStyle(graph: g, value: false)),
            ]
            sheet(out.appendingPathComponent("mac-marks-\(tag).png"), mv.map { Tile(title: $0.0, w: $0.1, h: $0.2, draw: card(data15, trs, $0.3)) },
                  cellW: 380, cellH: 230, cols: 2, dark: dark)
            // round sensor-life (GraphGallery.kt `sensorLife`): badge, countdown ring, warm-up, ended
            func sensor(_ left: TimeInterval) -> SensorInfo { SensorInfo(start: now + left - 15 * day, end: now + left, warmupEnd: now + left - 15 * day + 3600) }
            func withSensor(_ st: CardStyle, _ s: SensorInfo) -> CardStyle { var x = st; x.sensor = s; return x }
            let sv: [(String, CGFloat, CGFloat, CardStyle, [GraphReading])] = [
                ("4×2 · 13 days left", 300, 150, withSensor(CardStyle(graph: g), sensor(13 * day + 5 * 3600)), data15),
                ("4×2 · 20 h left (8 h window: no ring)", 300, 150, withSensor(CardStyle(graph: g), sensor(20 * 3600)), data15),
                ("4×2 · 5 h left: the countdown ring", 300, 150, withSensor(CardStyle(graph: g), sensor(5 * 3600)), data15),
                ("4×2 · 45 min left", 300, 150, withSensor(CardStyle(graph: g), sensor(45 * 60)), data15),
                ("warming up, 18 min in (old sensor's data)", 300, 150,
                 withSensor(CardStyle(graph: g), SensorInfo(start: now - 18 * 60, end: now - 18 * 60 + 15 * day, warmupEnd: now - 18 * 60 + 3600)),
                 data15.filter { $0.t <= now - 40 * 60 }),
                ("ended 2 h ago, no readings since", 300, 150, withSensor(CardStyle(graph: g), sensor(-2 * 3600)), data15.filter { $0.t <= now - 2 * 3600 }),
                ("3×1 · ended 10 min ago, last value still fresh", 230, 60, withSensor(CardStyle(graph: nil), sensor(-600)), data15.filter { $0.t <= now - 600 }),
                ("2×1 · ended 10 min ago", 150, 60, withSensor(CardStyle(graph: nil, rings: [RingSpec(window: 86400)]), sensor(-600)),
                 data15.filter { $0.t <= now - 600 }),
                ("4×1 side by side · ended yesterday", 300, 70, withSensor(CardStyle(graph: g), sensor(-26 * 3600)), data15.filter { $0.t <= now - 26 * 3600 }),
            ]
            sheet(out.appendingPathComponent("mac-sensor-\(tag).png"), sv.map { Tile(title: $0.0, w: $0.1, h: $0.2, draw: card($0.4, [], $0.3)) },
                  cellW: 300, cellH: 150, cols: 2, dark: dark)
            // treatments close together (GraphGallery.kt `marksClose`)
            let fullDay = [tr("carbs", 10, 1364), tr("rapid", 5, 859), tr("carbs", 40, 854), tr("rapid", 7, 509), tr("carbs", 70, 504),
                           tr("carbs", 15, 304), tr("carbs", 10, 284), tr("rapid", 8, 104), tr("carbs", 80, 99), tr("rapid", 1.5, 24), tr("long", 18, 9)]
            let cv: [(String, CGFloat, CGFloat, CardStyle, [GraphMark])] = [
                ("4×2, 12 h: two 5g snacks 11 h ago", 300, 150, CardStyle(graph: g),
                 [tr("carbs", 5, 680), tr("carbs", 5, 665), tr("carbs", 15, 40), tr("rapid", 2, 20)]),
                ("4×2, 3 h: meal, snack, correction", 300, 150, CardStyle(graph: gw(3)),
                 [tr("rapid", 4.5, 95), tr("carbs", 45, 95), tr("carbs", 15, 40), tr("rapid", 2, 20)]),
                ("notification, 12 h: insulin + carbs just now", 340, 110, CardStyle(graph: g, value: false),
                 [tr("rapid", 6, 490), tr("carbs", 60, 480), tr("carbs", 30, 5), tr("rapid", 3, 2)]),
                ("2×2 graph widget, 12 h", 150, 150, CardStyle(graph: g, rings: [], value: false),
                 [tr("rapid", 6, 490), tr("carbs", 60, 480), tr("rapid", 4.5, 95), tr("carbs", 45, 95), tr("carbs", 15, 40), tr("rapid", 2, 20)]),
                ("5×3, 24 h: a full day", 380, 230, CardStyle(graph: gw(24)), fullDay),
                ("app, 12 h: corrections close together", 360, 210, CardStyle(graph: g, value: false),
                 [tr("rapid", 1, 190), tr("rapid", 1.5, 170), tr("carbs", 10, 160), tr("rapid", 4.5, 95), tr("carbs", 45, 95),
                  tr("carbs", 15, 40), tr("rapid", 2, 20)]),
            ]
            sheet(out.appendingPathComponent("mac-marks-close-\(tag).png"), cv.map { Tile(title: $0.0, w: $0.1, h: $0.2, draw: card(data15, $0.4, $0.3)) },
                  cellW: 380, cellH: 230, cols: 2, dark: dark)
            // badge
            let states: [(String, [GraphReading], [GraphMark])] = [
                ("in range: no badge", endAt(base31, 120), []),
                ("low (62), nothing logged: eat carbs", endAt(base31, 62), []),
                ("low, 15g logged 6 min ago: muted", endAt(base31, 62), [tr("carbs", 15, 6)]),
                ("high (212), nothing logged: take insulin", endAt(base31, 212), []),
                ("high, 3u 22 min ago: muted", endAt(base31, 212), [tr("rapid", 3, 22)]),
                ("extremely high (318) after insulin: never muted", endAt(base31, 318), [tr("rapid", 4, 10)]),
            ]
            let sizes: [(String, CGFloat, CGFloat, CardStyle)] = [
                ("4×2 combined", 300, 150, CardStyle(graph: gw(6))),
                ("3×1 value + 2 rings", 230, 60, CardStyle(graph: nil)),
                ("2×1 value + 1 ring", 150, 60, CardStyle(graph: nil, rings: [RingSpec(window: day)])),
                ("4×1 side by side", 300, 70, CardStyle(graph: gw(6))),
            ]
            for (si, sz) in sizes.enumerated() {
                sheet(out.appendingPathComponent("mac-badge-\(si + 1)-\(tag).png"),
                      states.map { Tile(title: "\(sz.0) · \($0.0)", w: sz.1, h: sz.2, draw: card($0.1, $0.2, sz.3)) },
                      cellW: 300, cellH: 150, cols: 2, dark: dark)
            }
            // Tinted desktop (accented / vibrant): the badge in every state and size, and the last-treatment slot.
            for mode in [RenderMode.accented, .vibrant] {
                let m = mode == .accented ? "accented" : "vibrant"
                let compact = ("value only, short: icon badge", CGFloat(130), CGFloat(40), CardStyle(graph: nil, rings: []))
                sheet(out.appendingPathComponent("mac-badge-\(m)-\(tag).png"), (sizes + [compact]).flatMap { sz in
                    states.dropFirst().map { Tile(title: "\(sz.0) · \($0.0)", w: sz.1, h: sz.2, draw: card($0.1, $0.2, sz.3)) }
                }, cellW: 300, cellH: 150, cols: 5, dark: dark, mode: mode)
            }
            // last treatment
            let data = endAt(base31, 150)
            let rings = [RingSpec(window: day), RingSpec(window: 7 * day)]
            let fallback = RingSpec(window: 30 * day)
            func lst(_ graph: GraphStyle?, _ place: LastPlace = .nearValue, _ fb: RingSpec? = fallback) -> CardStyle {
                CardStyle(graph: graph, rings: rings, last: place, lastElse: fb)
            }
            let trsLast = [tr("long", 18, 5 * 60), tr("rapid", 4.5, 95), tr("carbs", 45, 95), tr("carbs", 15, 40), tr("rapid", 2, 20), tr("note", 0, 150)]
            let ls: [(String, [GraphMark])] = [
                ("rapid 4.5u, 12 min ago (mute 60 min)", [tr("rapid", 4.5, 12), tr("carbs", 45, 12)]),
                ("carbs 15g, 10 min ago (mute 15 min)", [tr("carbs", 15, 10)]),
                ("rapid 6u, 2 h ago: mute over, grey ring", [tr("rapid", 6, 125)]),
                ("rapid 6u, 5 h ago: hidden → 30d ring", [tr("rapid", 6, 300)]),
                ("long-acting only: not shown → 30d ring", [tr("long", 18, 40)]),
            ]
            sheet(out.appendingPathComponent("mac-last-\(tag).png"), ls.flatMap { l in [
                Tile(title: "3×1 · \(l.0)", w: 230, h: 60, draw: card(data, l.1, lst(nil))),
                Tile(title: "4×2 combined · next to the number", w: 300, h: 150, draw: card(data, l.1, lst(gw(6)))),
            ] }, cellW: 300, cellH: 150, cols: 2, dark: dark)
            sheet(out.appendingPathComponent("mac-last-places-\(tag).png"), [
                Tile(title: "at the right end", w: 380, h: 100, draw: card(data, ls[0].1, lst(nil, .end))),
                Tile(title: "next to the number, 2 rings + slot", w: 380, h: 100, draw: card(data, ls[0].1, lst(nil))),
                Tile(title: "no recent one, fallback off", w: 380, h: 100, draw: card(data, [], lst(nil, .nearValue, nil))),
                Tile(title: "2×1: the slot pushes the 7d ring out", w: 150, h: 60, draw: card(data, ls[0].1, lst(nil))),
            ], cellW: 380, cellH: 100, cols: 2, dark: dark)
            for mode in [RenderMode.accented, .vibrant] {
                sheet(out.appendingPathComponent("mac-last-\(mode == .accented ? "accented" : "vibrant")-\(tag).png"), ls.prefix(3).flatMap { l in [
                    Tile(title: "3×1 · \(l.0)", w: 230, h: 60, draw: card(data, l.1, lst(nil))),
                    Tile(title: "4×2 · \(l.0)", w: 300, h: 150, draw: card(data, l.1, lst(gw(6)))),
                ] } + [Tile(title: "4×2 · marks", w: 300, h: 150, draw: card(data, trsLast, lst(gw(6))))],
                      cellW: 300, cellH: 150, cols: 3, dark: dark, mode: mode)
            }
        }
    }
}

/// The time-in-range marks (the user, 2026-10-19; GraphGallery.tirMarks on Android): the last 24 h all in range (the
/// crown), the week before with a 1 h high each afternoon (≈ 96 %, the laurel), on every combined family and tinted.
@MainActor
func renderMarks(_ out: URL) {
    try? FileManager.default.createDirectory(at: out, withIntermediateDirectories: true)
    let end = floor(renderNow.timeIntervalSince1970 / 60) * 60 - 30
    let all = (0..<(7 * 288)).map { i -> GraphReading in
        let t = end - Double(7 * 288 - 1 - i) * 300
        let h = Int(t / 3600) % 24
        let base = 115 + 32 * sin(Double(i) / 23) + 9 * sin(Double(i) / 5.3)
        return GraphReading(t: t, v: (t < end - 86400 && h == 15 ? 205 : base).rounded())
    }
    let cfg = GraphConfig()
    let day = all.filter { $0.t > end - 86400 }
    var snap = GlucoseSnapshot(written: renderNow, source: "synthetic",
                               samples: day.map { GlucoseSample(t: Date(timeIntervalSince1970: $0.t), v: $0.v) }, rate: 0.2, graph: cfg,
                               slots: cfg.slots.map { SlotMetrics(window: $0.window, metrics: GlucoseMetrics.of(all, from: end - $0.window, to: end, bands: cfg.bands)) })
    snap.written = renderNow
    for dark in [false, true] {
        let row = HStack(alignment: .top, spacing: 0) {
            ForEach([WidgetFamily.systemSmall, .systemMedium, .systemLarge], id: \.self) { fam in
                Frame(size: sizes[fam]!, mode: .fullColor) {
                    GlucoseWidgetView(kind: .combined, family: fam, c: GlucoseEntryContent(snapshot: snap, date: renderNow), mode: .fullColor)
                }
            }
        }
        render(row, to: out.appendingPathComponent("mac-marks-\(dark ? "dark" : "light").png"), dark: dark)
    }
    renderTinted(GlucoseWidgetView(kind: .combined, family: .systemMedium, c: GlucoseEntryContent(snapshot: snap, date: renderNow), mode: .accented)
        .padding(16), size: sizes[.systemMedium]!, to: out.appendingPathComponent("mac-marks-tinted.png"))

    // Every neighbour combination (crown C, laurel L, nothing –; left to right, the 1 d ring rightmost), value widget.
    func tir(_ m: Character) -> TimeInRange {
        switch m {
        case "C": TimeInRange(tight: 0.8, target: 0.2, coverage: 1)
        case "L": TimeInRange(low: 0.01, tight: 0.75, target: 0.21, high: 0.03, coverage: 1)
        default: TimeInRange(low: 0.03, tight: 0.6, target: 0.27, high: 0.1, coverage: 1)
        }
    }
    let combos: [(String, Bool)] = [("LC", false), ("LL", false), ("CC", false), ("-C", false), ("L-", false), ("-L", false),
                                    ("LLC", false), ("L-L", false), ("-C-", false), ("LC", true)]
    func comboSnap(_ marks: String, insulin: Bool) -> GlucoseSnapshot {
        var c = GraphConfig()
        c.ringCount = marks.count
        c.slots[2] = RingSpec(window: 14 * 86400)
        c.lastPlace = insulin ? .nearValue : .off
        c.lastElse = nil
        var s = snap
        s.graph = c
        let windows: [TimeInterval] = [86400, 7 * 86400, 14 * 86400]
        s.slots = zip(windows, marks.reversed()).map { SlotMetrics(window: $0, metrics: GlucoseMetrics(tir: tir($1), mean: 120, sd: 30, readings: 1000)) }
        if insulin {
            s.treatments = [WidgetTreatment(t: renderNow.addingTimeInterval(-20 * 60), k: "rapid", a: 2)]
            s.alerts = AlertSettings()
        }
        return s
    }
    for dark in [false, true] {
        let grid = VStack(alignment: .leading, spacing: 0) {
            ForEach(0..<5, id: \.self) { row in
                HStack(spacing: 0) {
                    ForEach(0..<2, id: \.self) { col in
                        let (m, ins) = combos[row * 2 + col]
                        Frame(size: sizes[.systemMedium]!, mode: .fullColor) {
                            GlucoseWidgetView(kind: .value, family: .systemMedium, c: GlucoseEntryContent(snapshot: comboSnap(m, insulin: ins), date: renderNow), mode: .fullColor)
                        }
                    }
                }
            }
        }
        render(grid, to: out.appendingPathComponent("mac-marks-combos-\(dark ? "dark" : "light").png"), dark: dark)
    }
}
