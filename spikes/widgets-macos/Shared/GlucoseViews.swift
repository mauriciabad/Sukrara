// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import SwiftUI
import WidgetKit

/// Widget content. Pure SwiftUI so the same views run inside the WidgetKit extension and in the
/// ImageRenderer CLI (Render/). Family and rendering mode are parameters (the WidgetKit environment
/// values are read-only outside a widget host); the widget passes the real ones in. The card itself comes from
/// `MacWidget.image` (CardSurfaces.swift), the widgets' one drawing entry.
enum RenderMode { case fullColor, accented, vibrant
    init(_ m: WidgetRenderingMode) {
        self = m == .accented ? .accented : (m == .vibrant ? .vibrant : .fullColor)
    }
    var tinted: Bool { self != .fullColor }
}

struct GlucoseEntryContent {
    var snapshot: GlucoseSnapshot?
    var date: Date               // entry date (what "now" is for this entry)
    var error: String? = nil     // shown when there is no data at all

    var latest: GlucoseSample? { snapshot?.latest }
}

// MARK: - Card (investigation 16)

/// Every widget draws the same card as Sukrara on Android and the menubar dropdown (SukraraGraph.swift), as one
/// bitmap at the widget's size: rings that don't fit are dropped, short wide sizes put the value beside the graph.
struct GlucoseWidgetView: View {
    var kind: WidgetKind
    var family: WidgetFamily
    var c: GlucoseEntryContent
    var mode: RenderMode
    var overrides = WidgetOverrides()
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        if let s = c.snapshot {
            GeometryReader { geo in
                let now = c.date.timeIntervalSince1970
                // tinted modes map luminance to opacity: fainter grid and band there
                let pal = mode.tinted ? GraphPalette.of(dark: scheme == .dark).tinted() : GraphPalette.of(dark: scheme == .dark)
                // the picture reaches `overflow` into the widget's margins, so a ring's laurel or crown that overflows the
                // card shows there instead of being cut at the card's edge
                let overflow: CGFloat = 15
                if let img = MacWidget.image(kind, size: geo.size, cfg: s.config, overrides: overrides, sensor: s.sensor,
                                             readings: s.graphReadings, marks: s.graphMarks, now: now, pal,
                                             metricsOf: { r, b in s.metrics(r, b, now: now) }, alerts: s.alerts,
                                             paused: s.paused ?? [:], overflow: overflow) {
                    Image(decorative: img, scale: 2)
                        .resizable()
                        .frame(width: geo.size.width + 2 * overflow, height: geo.size.height + 2 * overflow)
                        .frame(width: geo.size.width, height: geo.size.height)
                }
            }
            .widgetAccentable(false)
        } else {
            VStack(alignment: .leading, spacing: 4) {
                Text("– – –").font(.system(size: 34, weight: .semibold, design: .rounded))
                Text(c.error ?? "No data").font(.caption).foregroundStyle(.secondary).lineLimit(2)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
        }
    }
}
