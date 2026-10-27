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
enum CardPart: String { case all, header, value, slots, graph }

/// Every place on the Mac that shows the card or its value: the registry of spikes/graph-fixtures/surfaces/surfaces.json
/// (CardSurface.kt is Android's). A card surface draws only through `GlucoseCard.draw` / `image` and names itself; a
/// glyph surface (the menubar title) takes its value from `GlucoseCard.face`. tools/check (card-surface, card-api) and
/// the layout self-test (LayoutSelfTest.swift, in -graphselftest) enforce it.
enum CardSurface: String, CaseIterable {
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
/// coordinates) and what it shows. Read by the layout self-test; ignored by the app.
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
