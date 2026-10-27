// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import CoreGraphics
import Foundation

// The surfaces that show the card (contracts/graph/surfaces/surfaces.json, Mac entries) and what one draw laid out.

/// Which part of a card one `GlucoseCard.draw` call paints (CardPart in GlucoseCard.kt): a surface that puts parts of
/// the card in boxes of its own draws each part with the SAME whole-card style, so the decisions that depend on the
/// whole card are still made once, in `GlucoseCard`. `header` = value + slots, `value` = the value alone, `slots` = the
/// slots alone, `graph` = the chart alone.
public enum CardPart: String, Sendable { case all, header, value, slots, graph }

/// Every place on the Mac that shows the card or its value: the registry of contracts/graph/surfaces/surfaces.json
/// (CardSurface.kt is Android's). A card surface draws only through `GlucoseCard.draw` / `image` and names itself; a
/// glyph surface (the menubar title) takes its value from `GlucoseCard.face`. tools/check (card-surface, card-api) and
/// the layout tests (GraphTests/LayoutTests.swift) enforce it.
public enum CardSurface: String, CaseIterable, Sendable {
    case menubarCard = "menubar-card"
    case menubarTitle = "menubar-title"
    case widgetValue = "widget-value"
    case widgetGraph = "widget-graph"
    case widgetCombined = "widget-combined"
    /// Snapshots and tests that draw the card outside any registered surface.
    case test = "test"

    var glyph: Bool { self == .menubarTitle }
}

/// What one `GlucoseCard.draw` call laid out (CardLayout in CardSurface.kt): every element, its box (in the call's own
/// coordinates) and what it shows. Read by the layout tests; ignored by the app.
final class CardLayout {
    struct El { var name: String; var box: CGRect?; var info: String }
    let surface: CardSurface, part: CardPart, style: CardStyle, w: CGFloat, h: CGFloat, d: CGFloat
    var elements: [El] = []
    init(_ surface: CardSurface, _ part: CardPart, _ style: CardStyle, w: CGFloat, h: CGFloat, d: CGFloat) {
        self.surface = surface; self.part = part; self.style = style; self.w = w; self.h = h; self.d = d
    }
    func add(_ name: String, _ box: CGRect? = nil, _ info: String = "") { elements.append(El(name: name, box: box, info: info)) }
    func first(_ name: String) -> El? { elements.first { $0.name == name } }
    func all(_ name: String) -> [El] { elements.filter { $0.name == name } }
    /// `px` in dp to the nearest 0.5, as the report writes it on both platforms ("33.5", "40").
    static func dp(_ px: CGFloat, _ d: CGFloat) -> String {
        let v = (Double(px / d) * 2).rounded(.toNearestOrAwayFromZero) / 2
        return v == v.rounded(.down) ? "\(Int64(v))" : "\(v)"
    }
}


/// Where the sensor's time left goes on a card (`GlucoseCard.sensorSpot`; SensorSpot in GlucoseCard.kt).
enum SensorSpot: String { case graph, none }

/// One placement's own choices over the app's graph settings (a widget's "Edit Widget" options); nil = follow the app.
public struct PlaceOverrides: Equatable, Sendable {
    public var hours: Int?
    public var rings: Int?
    public var ringRange: TirRange?
    public var side: Bool?
    public var sensor: Bool?
    public init(hours: Int? = nil, rings: Int? = nil, ringRange: TirRange? = nil, side: Bool? = nil, sensor: Bool? = nil) {
        self.hours = hours
        self.rings = rings
        self.ringRange = ringRange
        self.side = side
        self.sensor = sensor
    }
}

extension CardSurface {
    /// What this surface's card shows with the app's graph settings `cfg` and the placement's `overrides`; `sensor` =
    /// the sensor's state. The ONE place a surface's style is decided: the app, the widget and the layout tests ask it,
    /// so the tests render exactly what the surfaces show. A glyph surface has no card (its text is `GlucoseCard.face`).
    func style(_ cfg: GraphConfig, _ overrides: PlaceOverrides = PlaceOverrides(), sensor: SensorInfo?) -> CardStyle {
        var g = cfg.graphStyle(hours: overrides.hours)
        g.side = overrides.side ?? cfg.side
        var rc = cfg
        rc.ringCount = overrides.rings ?? cfg.ringCount
        rc.ringRange = overrides.ringRange ?? cfg.ringRange
        rc.sensorTime = overrides.sensor ?? cfg.sensorTime
        var st: CardStyle
        switch self {
        case .menubarCard, .menubarTitle, .test: st = cfg.cardStyle(graph: cfg.graphStyle())
        case .widgetValue: st = rc.cardStyle(graph: nil)
        case .widgetGraph: st = CardStyle(graph: g, rings: [], value: false, sensorTime: rc.sensorTime, bands: cfg.bands)
        case .widgetCombined: st = rc.cardStyle(graph: g)
        }
        st.sensor = sensor
        return st
    }
}
