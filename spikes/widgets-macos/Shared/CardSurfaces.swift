// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import CoreGraphics
import Foundation

/// The Mac widgets as card surfaces (spikes/graph-fixtures/surfaces/surfaces.json): the one entry every widget kind and
/// family draws through, shared by the widget extension (GlucoseViews.swift) and the menubar app's layout self-test, so
/// the test renders exactly what the widgets show. A widget chooses only its size, its placement's settings
/// (`WidgetOverrides` over the app's) and the part; the card does the rest (`GlucoseCard`).

enum WidgetKind: String, CaseIterable { case value, graph, combined }

/// One placement's own choices (the widget's "Edit Widget" settings, when the build carries App Intents metadata);
/// nil = follow the host app's graph settings.
struct WidgetOverrides: Equatable {
    var hours: Int? = nil
    var rings: Int? = nil
    var ringRange: TirRange? = nil
    var side: Bool? = nil
    var sensor: Bool? = nil
}

enum MacWidget {
    /// The registered surface of each kind.
    static func surface(_ kind: WidgetKind) -> CardSurface {
        switch kind { case .value: .widgetValue; case .graph: .widgetGraph; case .combined: .widgetCombined }
    }

    /// What `kind` shows with this placement's overrides over the app's settings `cfg`; `sensor` = the sensor's state.
    static func style(_ kind: WidgetKind, _ cfg: GraphConfig, _ overrides: WidgetOverrides, sensor: SensorInfo?) -> CardStyle {
        var g = cfg.graphStyle(hours: overrides.hours)
        g.side = overrides.side ?? cfg.side
        var rc = cfg
        rc.ringCount = overrides.rings ?? cfg.ringCount
        rc.ringRange = overrides.ringRange ?? cfg.ringRange
        rc.sensorTime = overrides.sensor ?? cfg.sensorTime
        var st: CardStyle
        switch kind {
        case .value: st = rc.cardStyle(graph: nil)
        case .graph: st = CardStyle(graph: g, rings: [], value: false, sensorTime: rc.sensorTime, bands: cfg.bands)
        case .combined: st = rc.cardStyle(graph: g)
        }
        st.sensor = sensor
        return st
    }

    /// The widget's picture: the card for `kind` at the family's content `size` (pt), `overflow` pt of margin around it
    /// for what overflows the card (a ring's laurel or crown).
    static func image(_ kind: WidgetKind, size: CGSize, cfg: GraphConfig, overrides: WidgetOverrides, sensor: SensorInfo?,
                      readings: [GraphReading], marks: [GraphMark], now: TimeInterval, _ pal: GraphPalette,
                      metricsOf: @escaping (RingSpec, Bands) -> GlucoseMetrics, alerts: AlertSettings?,
                      paused: [String: TimeInterval], overflow: CGFloat) -> CGImage? {
        GlucoseCard.image(size: size, scale: 2, readings: readings, marks: marks, now: now,
                          style(kind, cfg, overrides, sensor: sensor), pal, surface: surface(kind), metricsOf: metricsOf,
                          alerts: alerts, paused: paused, overflow: overflow)
    }
}
