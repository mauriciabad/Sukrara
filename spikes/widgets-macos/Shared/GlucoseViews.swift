// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Charts
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

// MARK: - Colours

func rangeColor(_ v: Double) -> Color {
    switch GlucoseRange.level(v) {
    case .low: return Color(red: 0.93, green: 0.23, blue: 0.21)
    case .high: return Color(red: 0.98, green: 0.62, blue: 0.10)
    case .inRange: return Color(red: 0.20, green: 0.75, blue: 0.40)
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

// MARK: - Building blocks

struct ValueBlock: View {
    var c: GlucoseEntryContent
    var mode: RenderMode
    var numberSize: CGFloat
    var showDelta = false

    var body: some View {
        if let l = c.latest {
            let color: Color = c.isStale ? .secondary : rangeColor(l.v)
            VStack(alignment: .leading, spacing: 2) {
                HStack(alignment: .center, spacing: numberSize * 0.12) {
                    Text("\(Int(l.v))")
                        .font(.system(size: numberSize, weight: .semibold, design: .rounded))
                        .monospacedDigit()
                        .strikethrough(c.isStale)
                        .minimumScaleFactor(0.5)
                        .lineLimit(1)
                    TrendArrow(rate: c.isStale ? nil : c.snapshot?.trendRate)
                        .frame(width: numberSize * 0.62, height: numberSize * 0.62)
                }
                .foregroundStyle(mode.tinted ? AnyShapeStyle(.primary) : AnyShapeStyle(color))
                .widgetAccentable()
                HStack(spacing: 4) {
                    if showDelta, !c.isStale, let d = c.snapshot?.delta5 {
                        Text(String(format: "%+.0f", d))
                        Text("·")
                    }
                    // Ticks on its own (no timeline reload needed): "3 min ago".
                    Text(.currentDate, format: .reference(to: l.t, allowedFields: [.hour, .minute]))
                }
                .font(.system(size: max(11, numberSize * 0.26)))
                .foregroundStyle(.secondary)
                .lineLimit(1)
            }
        } else {
            VStack(alignment: .leading, spacing: 4) {
                Text("– – –").font(.system(size: numberSize, weight: .semibold, design: .rounded))
                Text(c.error ?? "No data").font(.caption).foregroundStyle(.secondary).lineLimit(2)
            }
        }
    }
}

struct GlucoseChart: View {
    var c: GlucoseEntryContent
    var hours: Double
    var mode: RenderMode
    var showAxes: Bool

    var body: some View {
        let end = max(c.date, c.latest?.t ?? c.date)
        let pts = c.snapshot?.window(hours: hours, until: end) ?? []
        let maxV = max(250, (pts.map(\.v).max() ?? 0) + 10)
        let minV = min(40, (pts.map(\.v).min() ?? 40) - 5)
        Chart {
            RectangleMark(xStart: .value("from", end.addingTimeInterval(-hours * 3600)),
                          xEnd: .value("to", end),
                          yStart: .value("low", GlucoseRange.targetLow),
                          yEnd: .value("high", GlucoseRange.targetHigh))
                .foregroundStyle(Color.green.opacity(mode.tinted ? 0.12 : 0.15))
            ForEach(pts, id: \.t) { s in
                PointMark(x: .value("t", s.t), y: .value("mg/dL", s.v))
                    .symbolSize(hours > 4 ? 6 : 10)
                    .foregroundStyle(mode.tinted ? Color.primary : rangeColor(s.v))
            }
        }
        .chartXScale(domain: end.addingTimeInterval(-hours * 3600)...end)
        .chartYScale(domain: minV...maxV)
        .chartXAxis {
            if showAxes {
                AxisMarks(values: .stride(by: .hour, count: hours > 6 ? 3 : 1)) { _ in
                    AxisGridLine().foregroundStyle(.secondary.opacity(0.3))
                    AxisValueLabel(format: .dateTime.hour())
                }
            }
        }
        .chartYAxis {
            if showAxes {
                AxisMarks(position: .trailing, values: [70, 180]) { _ in
                    AxisGridLine().foregroundStyle(.secondary.opacity(0.3))
                    AxisValueLabel()
                }
            }
        }
        .widgetAccentable(false)
    }
}

// MARK: - Widget layouts

struct GlucoseWidgetView: View {
    var kind: WidgetKind
    var family: WidgetFamily
    var c: GlucoseEntryContent
    var mode: RenderMode

    /// More history when the widget is larger.
    var hours: Double {
        switch family {
        case .systemSmall: return 3
        case .systemMedium: return 3
        case .systemLarge: return 6
        case .systemExtraLarge: return 12
        default: return 3
        }
    }

    var body: some View {
        switch kind {
        case .value: valueLayout
        case .graph: graphLayout
        case .combined: combinedLayout
        }
    }

    @ViewBuilder var valueLayout: some View {
        switch family {
        case .systemSmall:
            VStack(alignment: .leading) {
                header
                Spacer(minLength: 0)
                ValueBlock(c: c, mode: mode, numberSize: 46)
            }.frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
        default:
            VStack(alignment: .leading) {
                header
                Spacer(minLength: 0)
                ValueBlock(c: c, mode: mode, numberSize: family == .systemMedium ? 64 : 110, showDelta: true)
                Spacer(minLength: 0)
            }.frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
        }
    }

    var graphLayout: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack { header; Spacer(); Text("\(Int(hours)) h").font(.caption2).foregroundStyle(.secondary) }
            GlucoseChart(c: c, hours: hours, mode: mode, showAxes: family != .systemSmall)
        }
    }

    @ViewBuilder var combinedLayout: some View {
        switch family {
        case .systemSmall:
            VStack(alignment: .leading, spacing: 4) {
                ValueBlock(c: c, mode: mode, numberSize: 34)
                GlucoseChart(c: c, hours: hours, mode: mode, showAxes: false)
            }
        case .systemMedium:
            HStack(spacing: 12) {
                VStack(alignment: .leading) {
                    header
                    Spacer(minLength: 0)
                    ValueBlock(c: c, mode: mode, numberSize: 44, showDelta: true)
                }.frame(width: 130, alignment: .leading)
                GlucoseChart(c: c, hours: hours, mode: mode, showAxes: true)
            }
        default:
            VStack(alignment: .leading, spacing: 8) {
                HStack(alignment: .bottom) {
                    ValueBlock(c: c, mode: mode, numberSize: family == .systemLarge ? 56 : 72, showDelta: true)
                    Spacer()
                    if let s = c.snapshot { tir(s) }
                }
                GlucoseChart(c: c, hours: hours, mode: mode, showAxes: true)
            }
        }
    }

    var header: some View {
        Text("Glucose").font(.caption.weight(.semibold)).foregroundStyle(.secondary)
    }

    /// Time in range over the shown window (only on the big layouts).
    func tir(_ s: GlucoseSnapshot) -> some View {
        let w = s.window(hours: hours, until: c.date)
        let inR = w.filter { $0.v >= GlucoseRange.targetLow && $0.v <= GlucoseRange.targetHigh }.count
        let pct = w.isEmpty ? 0 : Int((Double(inR) / Double(w.count) * 100).rounded())
        return VStack(alignment: .trailing, spacing: 0) {
            Text("\(pct)%").font(.title3.weight(.semibold)).monospacedDigit()
            Text("in range \(Int(hours)) h").font(.caption2).foregroundStyle(.secondary)
        }
    }
}
