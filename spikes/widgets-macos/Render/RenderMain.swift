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

/// A synthetic history whose last reading satisfies `where`, shifted so it was `lag` seconds ago.
func scenario(lag: TimeInterval, where cond: (Double, Double) -> Bool) -> GlucoseSnapshot {
    let base = Date(timeIntervalSince1970: 1_792_000_000)
    var t = base
    for _ in 0..<2000 {
        let v = Synthetic.value(at: t), r = (Synthetic.value(at: t) - Synthetic.value(at: t.addingTimeInterval(-300))) / 5
        if cond(v, r) { break }
        t = t.addingTimeInterval(60)
    }
    var s = Synthetic.snapshot(now: t, hours: 12)
    let shift = Date().addingTimeInterval(-lag).timeIntervalSince(s.latest!.t)
    s.samples = s.samples.map { GlucoseSample(t: $0.t.addingTimeInterval(shift), v: $0.v) }
    s.treatments = Synthetic.treatments(until: s.latest!.t, hours: 12)
    s.written = Date()
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

@main
struct RenderMain {
    @MainActor static func main() {
        let out = URL(fileURLWithPath: CommandLine.arguments.dropFirst().first ?? "evidence")
        try? FileManager.default.createDirectory(at: out, withIntermediateDirectories: true)

        let rising = scenario(lag: 60) { v, r in v > 130 && v < 160 && r > 1.0 && r < 2.0 }
        let high = scenario(lag: 120) { v, r in v > 195 && r > 0.3 }
        let low = scenario(lag: 60) { v, r in v < 62 && r < -0.5 }
        let flat = scenario(lag: 60) { v, r in v > 100 && v < 120 && abs(r) < 0.3 }
        let stale = scenario(lag: 9 * 60) { v, r in v > 120 && r < -1 }

        // Every family of every widget, light + dark, rising scenario.
        for kind in WidgetKind.allCases {
            for fam in families[kind]! {
                for dark in [false, true] {
                    let c = GlucoseEntryContent(snapshot: rising, date: Date())
                    let v = Frame(size: sizes[fam]!, mode: .fullColor) {
                        GlucoseWidgetView(kind: kind, family: fam, c: c, mode: .fullColor)
                    }
                    render(v, to: out.appendingPathComponent("\(kind.rawValue)-\(name(fam))-\(dark ? "dark" : "light").png"), dark: dark)
                }
            }
        }
        // States: low / high / flat / stale / no data, on the combined medium widget.
        let states: [(String, GlucoseSnapshot?)] = [("low", low), ("high", high), ("flat", flat), ("stale", stale), ("nodata", nil)]
        for (n, s) in states {
            let c = GlucoseEntryContent(snapshot: s, date: Date(), error: s == nil ? "Host app not running" : nil)
            let v = HStack(spacing: 0) {
                Frame(size: sizes[.systemSmall]!, mode: .fullColor) { GlucoseWidgetView(kind: .value, family: .systemSmall, c: c, mode: .fullColor) }
                Frame(size: sizes[.systemMedium]!, mode: .fullColor) { GlucoseWidgetView(kind: .combined, family: .systemMedium, c: c, mode: .fullColor) }
            }
            render(v, to: out.appendingPathComponent("state-\(n).png"), dark: true)
        }
        // Accented (tinted desktop) rendering mode.
        let c = GlucoseEntryContent(snapshot: rising, date: Date())
        let acc = HStack(spacing: 0) {
            Frame(size: sizes[.systemSmall]!, mode: .accented) { GlucoseWidgetView(kind: .value, family: .systemSmall, c: c, mode: .accented) }
            Frame(size: sizes[.systemMedium]!, mode: .accented) { GlucoseWidgetView(kind: .combined, family: .systemMedium, c: c, mode: .accented) }
        }
        render(acc, to: out.appendingPathComponent("mode-accented.png"), dark: true)

        // Trend arrow strip: −4 … +4 mg/dL/min.
        let rates: [Double] = [-4, -3, -2, -1.5, -1, -0.5, 0, 0.5, 1, 1.5, 2, 3, 4]
        let strip = HStack(spacing: 14) {
            ForEach(rates, id: \.self) { r in
                VStack(spacing: 4) {
                    TrendArrow(rate: r).frame(width: 28, height: 28)
                    Text(String(format: "%+.1f", r)).font(.caption2).monospacedDigit()
                }
            }
        }.padding(16).foregroundStyle(.primary)
        render(strip, to: out.appendingPathComponent("trend-arrows.png"), dark: false)
    }
}
