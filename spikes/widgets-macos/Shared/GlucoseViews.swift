// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import SwiftUI
import WidgetKit

/// Widget content. Pure SwiftUI so the same views run inside the WidgetKit extension and in the
/// ImageRenderer CLI (Render/). Family and rendering mode are parameters (the WidgetKit environment
/// values are read-only outside a widget host); the widget passes the real ones in.
enum WidgetKind: String, CaseIterable { case value, graph, combined }

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
    var isStale: Bool {
        guard let l = latest else { return true }
        return date.timeIntervalSince(l.t) > GlucoseRange.staleAfter
    }
}

// MARK: - Trend arrow (continuous angle, same geometry as the menubar TrendArrow)

struct TrendArrowShape: Shape {
    var double: Bool
    func path(in r: CGRect) -> Path {
        // Unit arrow pointing to +x, centred on the origin; y down in SwiftUI.
        func head(_ tip: CGFloat) -> Path {
            var p = Path()
            p.move(to: CGPoint(x: tip, y: 0))
            p.addLine(to: CGPoint(x: tip - 0.40, y: -0.30))
            p.addLine(to: CGPoint(x: tip - 0.30, y: 0))
            p.addLine(to: CGPoint(x: tip - 0.40, y: 0.30))
            p.closeSubpath()
            return p
        }
        var p = Path()
        p.addPath(Path { s in s.move(to: CGPoint(x: -0.42, y: 0)); s.addLine(to: CGPoint(x: 0.25, y: 0)) }
            .strokedPath(StrokeStyle(lineWidth: 0.14, lineCap: .round)))
        p.addPath(head(0.46))
        if double { p.addPath(head(0.12)) }
        let s = min(r.width, r.height)
        return p.applying(CGAffineTransform(translationX: r.midX, y: r.midY).scaledBy(x: s, y: s))
    }
}

struct TrendArrow: View {
    var rate: Double?
    var body: some View {
        if let rate {
            TrendArrowShape(double: abs(rate) >= TrendAngle.fullScaleRate)
                .rotationEffect(.degrees(-TrendAngle.degrees(forRate: rate))) // CCW positive → up
        } else {
            Circle().scale(0.22)
        }
    }
}

// MARK: - Card (investigation 16)

/// One placement's own choices (the widget's "Edit Widget" settings, when the build carries App Intents metadata);
/// nil = follow the host app's graph settings.
struct WidgetOverrides: Equatable {
    var hours: Int? = nil
    var rings: Int? = nil
    var ringRange: TirRange? = nil
    var side: Bool? = nil
    var sensor: Bool? = nil
}

/// Every widget draws the same card as Sukrara on Android and the menubar dropdown (SukraraGraph.swift), as one
/// bitmap at the widget's size: rings that don't fit are dropped, short wide sizes put the value beside the graph.
struct GlucoseWidgetView: View {
    var kind: WidgetKind
    var family: WidgetFamily
    var c: GlucoseEntryContent
    var mode: RenderMode
    var overrides = WidgetOverrides()
    @Environment(\.colorScheme) private var scheme

    func style(_ cfg: GraphConfig) -> CardStyle {
        var g = cfg.graphStyle(hours: overrides.hours)
        g.side = overrides.side ?? cfg.side
        var rc = cfg
        rc.ringCount = overrides.rings ?? cfg.ringCount
        rc.ringRange = overrides.ringRange ?? cfg.ringRange
        rc.sensorTime = overrides.sensor ?? cfg.sensorTime
        switch kind {
        case .value: return rc.cardStyle(graph: nil)
        case .graph: return CardStyle(graph: g, rings: [], value: false, sensorTime: rc.sensorTime)
        case .combined: return rc.cardStyle(graph: g)
        }
    }

    private func withSensor(_ st: CardStyle, _ sensor: SensorInfo?) -> CardStyle { var st = st; st.sensor = sensor; return st }

    var body: some View {
        if let s = c.snapshot {
            GeometryReader { geo in
                let now = c.date.timeIntervalSince1970
                // tinted modes map luminance to opacity: fainter grid and band there
                let pal = mode.tinted ? GraphPalette.of(dark: scheme == .dark).tinted() : GraphPalette.of(dark: scheme == .dark)
                // the picture reaches `overflow` into the widget's margins, so a ring's laurel or crown that overflows the
                // card shows there instead of being cut at the card's edge
                let overflow: CGFloat = 15
                if let img = GlucoseCard.image(size: geo.size, scale: 2, readings: s.graphReadings, marks: s.graphMarks,
                                               now: now, withSensor(style(s.config), s.sensor), pal, metricsOf: { r, b in s.metrics(r, b, now: now) },
                                               alerts: s.alerts, overflow: overflow) {
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
