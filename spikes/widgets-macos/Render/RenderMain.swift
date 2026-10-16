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

@main
struct RenderMain {
    @MainActor static func main() {
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
    }
}
