// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation
import SukraraCore

/// The words VoiceOver says for a card, English and Spanish (Android: core/graph `values*/strings.xml`, the `a11y_*`
/// and `trend_*` strings, same wording). Kept in code, not a String Catalog: the widget extension links this module
/// and a SwiftPM resource bundle isn't packaged into the appex.
public struct CardWords: Sendable {
    let unit: (mgdl: String, mmol: String)
    let below, above, old, justNow: String
    let ago: @Sendable (String) -> String
    let trend: (unknown: String, steady: String, rising: String, risingFast: String, falling: String, fallingFast: String)
    let band: (veryLow: String, low: String, inRange: String, high: String, veryHigh: String, extremelyHigh: String)
    let noReadings: String
    let noReadingSince: @Sendable (String) -> String
    /// A card without a value: when its data is from (the graph widgets, `Freshness.AgeMark`).
    let lastReading: @Sendable (String) -> String
    let chart: @Sendable (_ hours: Int, _ inRange: Int, _ below: Int, _ above: Int) -> String
    let chartEmpty: @Sendable (_ hours: Int) -> String
    let warmingUp, sensorEnded: String
    /// The status pill's sentence: the action an alert asks for, and the mutes with the time left.
    let eatCarbs, takeInsulin, mutedForever: String
    let mutedLeft: @Sendable (_ left: String) -> String
    let sideMuted: @Sendable (_ low: Bool, _ left: String) -> String
    let sideAfter: @Sendable (_ low: Bool, _ carbs: Bool, _ left: String) -> String

    public static let en = CardWords(
        unit: ("milligrams per decilitre", "millimoles per litre"), below: "below", above: "above",
        old: "old reading", justNow: "just now", ago: { "\($0) ago" },
        trend: ("trend unknown", "steady", "rising", "rising fast", "falling", "falling fast"),
        band: ("very low", "low", "in range", "high", "very high", "extremely high"),
        noReadings: "Glucose: no readings yet", noReadingSince: { "Glucose: no reading since \($0)" },
        lastReading: { "Glucose: last reading \($0)" },
        chart: { "Last \($0) hours: \($1)% in range, \($2)% below, \($3)% above" },
        chartEmpty: { "Last \($0) hours: no data" },
        warmingUp: "Warming up", sensorEnded: "Sensor ended. Replace sensor",
        eatCarbs: "Eat carbs", takeInsulin: "Take insulin", mutedForever: "Alerts muted until you turn it off",
        mutedLeft: { "Alerts muted · \($0) left" },
        sideMuted: { "\($0 ? "Low" : "High") alerts muted · \($1) left" },
        sideAfter: { "\($0 ? "Low" : "High") alerts muted after \($1 ? "carbs" : "insulin") · \($2) left" })

    public static let es = CardWords(
        unit: ("miligramos por decilitro", "milimoles por litro"), below: "por debajo de", above: "por encima de",
        old: "lectura antigua", justNow: "ahora mismo", ago: { "hace \($0)" },
        trend: ("tendencia desconocida", "estable", "subiendo", "subiendo rápido", "bajando", "bajando rápido"),
        band: ("muy baja", "baja", "en rango", "alta", "muy alta", "extremadamente alta"),
        noReadings: "Glucosa: aún no hay lecturas", noReadingSince: { "Glucosa: sin lecturas desde \($0)" },
        lastReading: { "Glucosa: última lectura \($0)" },
        chart: { "Últimas \($0) horas: \($1)% en rango, \($2)% por debajo, \($3)% por encima" },
        chartEmpty: { "Últimas \($0) horas: sin datos" },
        warmingUp: "Calentando", sensorEnded: "Sensor terminado. Cambiar sensor",
        eatCarbs: "Come hidratos", takeInsulin: "Ponte insulina",
        mutedForever: "Alertas silenciadas hasta que lo desactives",
        mutedLeft: { "Alertas silenciadas · quedan \($0)" },
        sideMuted: { "Alertas de glucosa \($0 ? "baja" : "alta") silenciadas · quedan \($1)" },
        sideAfter: {
            "Alertas de glucosa \($0 ? "baja" : "alta") silenciadas tras \($1 ? "los hidratos" : "la insulina")"
                + " · quedan \($2)"
        })

    /// The user's language among the two the app speaks (the system's preferred order; English otherwise).
    public static var current: CardWords {
        Bundle.preferredLocalizations(from: ["en", "es"]).first == "es" ? .es : .en
    }

    func unit(_ u: GlucoseUnit) -> String { u == .mgdl ? unit.mgdl : unit.mmol }

    func trend(_ rate: Double?) -> String {
        guard let rate else { return trend.unknown }
        return switch Trend(rate: rate) {
        case .risingFast: trend.risingFast
        case .rising: trend.rising
        case .steady: trend.steady
        case .falling: trend.falling
        case .fallingFast: trend.fallingFast
        }
    }

    /// The range in words (never by colour alone): `extremelyHigh` from its bound up, like the value's colour.
    func band(_ sgv: Int, _ b: Bands) -> String {
        if sgv >= b.extremelyHigh { return band.extremelyHigh }
        return switch b.of(Double(sgv)) {
        case .veryLow: band.veryLow
        case .low: band.low
        case .tight, .target: band.inRange
        case .high: band.high
        case .veryHigh: band.veryHigh
        }
    }

    /// "just now" under a minute, else "2 min ago" / "1 h 5 min ago".
    func age(_ s: TimeInterval) -> String { s < 60 ? justNow : ago(Durations.text(Int(s / 60))) }
}

/// What VoiceOver says for a card (Android: graph/A11y.kt, same structure): the value with its unit, the trend, the
/// range and the age in words, the status pill's sentence when the card draws it, then the chart summary on a surface
/// with a chart. Built from the decisions the card draws with (`GlucoseCard.face`, `StatusBadge`).
enum A11y {
    /// "112 milligrams per decilitre, falling, in range, 2 min ago", then the pill's sentence ("Eat carbs") when a value
    /// is shown and the place draws the badge.
    static func value(_ data: CardData, unit: GlucoseUnit, surface: CardSurface, style: CardStyle, words w: CardWords)
        -> String
    {
        let look = GlucoseCard.face(data, unit: unit, surface: surface)
        switch look.state {
        case .ended: return w.sensorEnded
        case .warmingUp: return w.warmingUp
        case .none: return w.noReadings
        case .noReadings:
            return w.noReadingSince(data.latest.map { Freshness.timeText($0.t, now: data.now) } ?? "")
        case .value:
            guard let r = data.latest else { return w.noReadings }
            let number =
                switch r.sgv {
                case ..<40: "\(w.below) \(unit.format(40)) \(w.unit(unit))"
                case 401...: "\(w.above) \(unit.format(400)) \(w.unit(unit))"
                default: "\(look.text ?? GlucoseCard.noValueText) \(w.unit(unit))"
                }
            let parts = [
                number, look.strike ? w.old : nil, w.trend(look.trend == .arrow ? look.rate : nil),
                w.band(r.sgv, style.bands.sane()), w.age(data.now - r.t),
            ]
            let sentence = parts.compactMap { $0 }.joined(separator: ", ")
            let badge = style.badge ? StatusBadge.describe(data.alerts.status, now: data.now, words: w) : nil
            return sentence + (badge.map { ". " + $0 } ?? "")
        }
    }

    /// "Last 12 hours: 82% in range, 3% below, 15% above" (or "no data"): the window the chart shows.
    static func chart(_ data: CardData, _ g: GraphStyle, words w: CardWords) -> String {
        let hours = Int(g.window / 3600)
        let tir = GlucoseStats.timeInRange(data.readings, from: data.now - g.window, to: data.now, bands: g.bands.sane())
        if tir.empty { return w.chartEmpty(hours) }
        let (below, inRange, above) = GlucoseGraph.pct3(tir.below, tir.inRange, tir.above)
        return w.chart(hours, inRange, below, above)
    }

    /// The whole card: the value sentence (where the card shows the value), then the chart summary where it has a
    /// chart. A glyph surface (the menu bar title) says the value only.
    static func card(_ data: CardData, unit: GlucoseUnit, surface: CardSurface, style: CardStyle, words: CardWords)
        -> String
    {
        let value =
            style.value || surface.glyph
            ? value(data, unit: unit, surface: surface, style: style, words: words) : age(data, words: words)
        let chart = surface.glyph ? nil : style.graph.map { chart(data, $0, words: words) }
        return [value, chart].compactMap { $0 }.joined(separator: ". ")
    }

    /// A card without a value says how old its chart is, as the picture does (`Freshness.AgeMark`): nothing while
    /// fresh, "last reading 9:50" from 91 s, "old reading" too from 5 min, "no reading since 9:50" from 20 min (and
    /// once the chart is gone), "no readings yet" without any.
    static func age(_ data: CardData, words w: CardWords) -> String? {
        guard let t = data.latest?.t else { return w.noReadings }
        let time = Freshness.timeText(t, now: data.now)
        switch Freshness.of(age: data.now - t).ageMark {
        case .none: return nil
        case .time: return w.lastReading(time)
        case .dimmed: return "\(w.lastReading(time)), \(w.old)"
        case .noReadings, .face: return w.noReadingSince(time)
        }
    }
}
