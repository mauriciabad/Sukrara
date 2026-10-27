// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import CoreGraphics
import CoreText
import Foundation
import SukraraCore

/// How old the latest reading is decides what a surface shows (see Freshness.kt for the table).
public enum Freshness: Sendable {
    case fresh, late, stale, old, gone
    static let lateS: TimeInterval = 90, staleS: TimeInterval = 5 * 60, oldS: TimeInterval = 20 * 60,
        goneS: TimeInterval = 3 * 3600

    static func of(age: TimeInterval?) -> Freshness {
        guard let a = age else { return .gone }
        if a <= lateS { return .fresh }
        if a <= staleS { return .late }
        if a <= oldS { return .stale }
        if a <= goneS { return .old }
        return .gone
    }
    var showValue: Bool { self == .fresh || self == .late || self == .stale }
    var showTime: Bool { self != .fresh }
    var strike: Bool { self == .stale }
    var showGraph: Bool { self != .gone }

    /// What a card WITHOUT a value (graph-only) shows of its data's age, so it never looks current when it isn't
    /// (contracts/graph/freshness `graph_only`; Freshness.kt AgeMark): the same steps as the value's time, strike and
    /// "no readings".
    enum AgeMark: String, Sendable {
        case none, time, dimmed, noReadings = "no_readings", face
        /// The chart's opacity while dimmed.
        static let dimAlpha: CGFloat = 0.4
        var dims: Bool { self == .dimmed || self == .noReadings }
    }

    var ageMark: AgeMark {
        switch self {
        case .fresh: .none
        case .late: .time
        case .stale: .dimmed
        case .old: .noReadings
        case .gone: .face
        }
    }

    /// The next age at which `of` changes (for a one-shot timer / timeline entry).
    static func nextChange(age: TimeInterval) -> TimeInterval? {
        [lateS, staleS, oldS, goneS].first { $0 >= age }.map { $0 + 1 }
    }

    /// When the step of a reading taken at `t` changes next, seen at `now`; nil = never again / no reading.
    public static func nextChangeAt(_ t: TimeInterval?, now: TimeInterval) -> TimeInterval? {
        guard let t else { return nil }
        return nextChange(age: now - t).map { t + $0 }
    }

    /// What every glucose surface shows for the latest reading (`sgv` at `t`, `rate` mg/dL/min) at `now`: the one
    /// decision behind the card (menubar dropdown, widgets) and the menubar title. Android's `Freshness.look`
    /// (graph/Freshness.kt) is the same rule; both run spikes/graph-fixtures/freshness. `ended` = the sensor has ended,
    /// `warmup` = a new sensor's warm-up time left: their own states, before any value. Surfaces ask `GlucoseCard.face`.
    /// `format` writes a value in the user's unit (mg/dL by default); LO / HI outside 40–400 mg/dL.
    static func look(
        sgv: Int?, t: TimeInterval?, rate: Double?, now: TimeInterval, ended: Bool = false, warmup: String? = nil,
        format: (Int) -> String = { "\($0)" }
    ) -> GlucoseLook {
        let fr = of(age: t.map { now - $0 })
        if let warmup {
            return GlucoseLook(state: .warmingUp, fresh: fr, text: nil, strike: false, trend: nil, warmup: warmup)
        }
        if ended { return GlucoseLook(state: .ended, fresh: fr, text: nil, strike: false, trend: nil) }
        guard let sgv, t != nil else {
            return GlucoseLook(state: .none, fresh: fr, text: nil, strike: false, trend: nil)
        }
        guard fr.showValue else {
            return GlucoseLook(state: .noReadings, fresh: fr, text: nil, strike: false, trend: nil)
        }
        return GlucoseLook(
            state: .value, fresh: fr, text: sgv < 40 ? "LO" : sgv > 400 ? "HI" : format(sgv), strike: fr.strike,
            trend: rate != nil && !fr.strike ? TrendMark.arrow : TrendMark.noTrend, rate: rate)
    }

    /// "14:05" today, "Tue 14:05" this week, "12 Oct" before; the time in the Mac's 12/24-hour setting (`Clock`).
    /// A clock time doesn't tick.
    static func timeText(_ t: TimeInterval, now: TimeInterval) -> String {
        let d = Date(timeIntervalSince1970: t), n = Date(timeIntervalSince1970: now)
        let time = Clock.time(d)
        if Calendar.current.isDate(d, inSameDayAs: n) { return time }
        if now - t < 6 * 86400 { return d.formatted(.dateTime.weekday(.abbreviated)) + " " + time }
        return d.formatted(.dateTime.day().month(.abbreviated))
    }
}

/// The trend beside a value: the arrow, or `.noTrend` = no trend to show (no rate, or the value is struck through): a
/// hollow ring of the arrow's size (graph/Freshness.kt TrendMark: a circle has no direction, so it can't read as steady,
/// and unlike the old dot it doesn't look like a speck). Text places write it as `ring`.
public enum TrendMark: String, Sendable {
    case arrow, noTrend = "none"
    static let ring = "○"
    static func ringRadius(_ size: CGFloat) -> CGFloat { size * 0.24 }
    static func ringStroke(_ size: CGFloat) -> CGFloat { size * 0.11 }
}

/// The trend arrow's one decision (graph/CardModel.kt TrendAngle; contracts/graph/trend): the drawn arrow's angle
/// (±3 mg/dL/min = straight up / down, doubled from there) and THE text arrow every text place writes (the menu bar,
/// alert titles), in Nightscout's direction bands. No place maps a rate to an arrow itself.
public enum TrendAngle {
    /// mg/dL per minute the arrow maps to ±90° (and doubles at).
    public static let fullScale = Trend.fullScale

    /// The drawn arrow's rotation in degrees, y-down (negative = up).
    public static func deg(_ rate: Double) -> Double { -max(-1, min(1, rate / fullScale)) * 90 }

    public static func isDouble(_ rate: Double) -> Bool { abs(rate) >= fullScale }

    /// The text arrow for a rate.
    public static func symbol(_ rate: Double) -> String {
        rate >= 3 ? "⇈" : rate >= 2 ? "↑" : rate >= 1 ? "↗" : rate > -1 ? "→" : rate > -2 ? "↘" : rate > -3 ? "↓" : "⇊"
    }

    /// `symbol` for a rate that may be missing: no rate = the "no trend" ring, as the card draws it.
    public static func text(_ rate: Double?) -> String { rate.map(symbol) ?? TrendMark.ring }
}

/// `Freshness.look`'s answer (graph/Freshness.kt GlucoseLook): the state; for a value its text, whether it's struck
/// through and grey (5–20 min) and its trend (nil without a value).
public struct GlucoseLook: Equatable, Sendable {
    public enum State: String, Sendable {
        case value, noReadings = "no_readings", none, ended, warmingUp = "warming_up"
        /// The name the layout report uses (Android's enum names).
        var layoutName: String { rawValue.uppercased() }
    }
    public var state: State
    public var fresh: Freshness
    public var text: String?
    public var strike: Bool
    public var trend: TrendMark?
    /// While warming up: the time left ("42m").
    public var warmup: String? = nil
    public var rate: Double? = nil
    public var grey: Bool { strike }
    public var shown: Bool { state == .value }
    /// The trend as text for places that can only write text (the menu bar): the arrow of `rate` when the card draws
    /// one, the ring when it draws the "no trend" ring, nil without a value (graph/Freshness.kt `arrowText`).
    public var arrowText: String? {
        switch trend {
        case .arrow: TrendAngle.text(rate)
        case .noTrend: TrendMark.ring
        case nil: nil
        }
    }
    /// SF Symbol for "no readings for 20 min" (Android: Material Symbols sensors_off): the menubar and the card.
    static let noReadingsSymbol = "antenna.radiowaves.left.and.right.slash"
}
