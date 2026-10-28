// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation

/// The widget's own words in the two languages the app speaks (the card's words are the renderer's `CardWords`):
/// like the renderer, the extension carries no string catalog.
struct WidgetWords: Sendable {
    let valueName, valueDescription, graphName, graphDescription, combinedName, combinedDescription: String
    /// No snapshot to show: the app isn't running or set up yet, or wrote a format this widget doesn't read.
    let openApp: String
    let openAppSpoken: String

    static let en = WidgetWords(
        valueName: "Glucose", valueDescription: "Current glucose, trend and time in range.",
        graphName: "Glucose graph", graphDescription: "The graph with time in range beside it.",
        combinedName: "Glucose + graph", combinedDescription: "Value, time-in-range rings and the graph.",
        openApp: "Open Sukrara", openAppSpoken: "Glucose: no data. Open Sukrara.")

    static let es = WidgetWords(
        valueName: "Glucosa", valueDescription: "Glucosa actual, tendencia y tiempo en rango.",
        graphName: "Gráfica de glucosa", graphDescription: "La gráfica con el tiempo en rango al lado.",
        combinedName: "Glucosa + gráfica", combinedDescription: "Valor, anillos de tiempo en rango y la gráfica.",
        openApp: "Abre Sukrara", openAppSpoken: "Glucosa: sin datos. Abre Sukrara.")

    static var current: WidgetWords {
        Bundle.preferredLocalizations(from: ["en", "es"]).first == "es" ? .es : .en
    }
}
