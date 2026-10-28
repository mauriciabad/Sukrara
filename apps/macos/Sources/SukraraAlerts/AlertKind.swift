// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later

/// The alert kinds of `contracts/alerts/SPEC.md` §1; the raw values are the `k` of the settings and the `alert` event.
public enum AlertKind: String, CaseIterable, Codable, Sendable {
    case veryLow = "very_low"
    case low
    case high
    case veryHigh = "very_high"
    case extremelyHigh = "extremely_high"
    case fallingFast = "falling_fast"
    case risingFast = "rising_fast"
    case noData = "no_data"

    /// Level kinds by priority: the first that matches wins.
    public static let levelsByPriority: [AlertKind] = [.veryLow, .extremelyHigh, .veryHigh, .low, .high]

    public var group: AlertGroup {
        switch self {
        case .veryLow, .low, .high, .veryHigh, .extremelyHigh: .level
        case .fallingFast, .risingFast: .rate
        case .noData: .noData
        }
    }

    /// The side a Mute / Remind less and an episode act on.
    public var side: AlertSide {
        switch self {
        case .veryLow, .low: .low
        case .high, .veryHigh, .extremelyHigh: .high
        case .fallingFast: .fallingFast
        case .risingFast: .risingFast
        case .noData: .noData
        }
    }

    /// Lows alert strictly below their threshold (low 70 alerts at 69); the other levels at or above it.
    public var isBelowThreshold: Bool { self == .veryLow || self == .low }

    /// Severity within a side: moving to a more severe kind alerts at once.
    public var severity: Int {
        switch self {
        case .veryLow, .veryHigh: 2
        case .extremelyHigh: 3
        default: 1
        }
    }

    /// The treatment that answers it, whatever the mute settings; nil for no data.
    public var treatment: TreatmentKind? {
        switch self {
        case .veryLow, .low, .fallingFast: .carbs
        case .high, .veryHigh, .extremelyHigh, .risingFast: .rapidInsulin
        case .noData: nil
        }
    }

    public var title: String {
        switch self {
        case .veryLow: "Very low"
        case .low: "Low"
        case .high: "High"
        case .veryHigh: "Very high"
        case .extremelyHigh: "Extremely high"
        case .fallingFast: "Falling fast"
        case .risingFast: "Rising fast"
        case .noData: "No glucose data"
        }
    }

    /// The notification headline (SPEC §1). Level alerts acknowledge the answering treatments logged in the episode
    /// (`treated` 1, or 2 for two or more); highs never suggest more insulin.
    public func headline(treated: Int = 0) -> String {
        let base = group == .level ? "\(title) glucose" : title
        guard treated > 0, group == .level else { return base }
        if isBelowThreshold { return base + (treated == 1 ? " · eat more" : " · eat more again") }
        return base + (treated == 1 ? " · still high after insulin" : " · still high after more insulin")
    }
}

/// One notification per group: a new alert of the group replaces the previous one.
public enum AlertGroup: String, CaseIterable, Codable, Sendable {
    case level
    case rate
    case noData = "no_data"
}

/// What a Mute / Remind less / episode acts on: lows (low, very low), highs (high, very high, extremely high), falling
/// fast, rising fast, no data. A high's Mute never quiets a low.
public enum AlertSide: String, CaseIterable, Codable, Sendable, Comparable {
    case low
    case high
    case fallingFast = "falling_fast"
    case risingFast = "rising_fast"
    case noData = "no_data"

    /// The side's main kind (a Mute / Remind less shown without an episode).
    public var mainKind: AlertKind {
        switch self {
        case .low: .low
        case .high: .high
        case .fallingFast: .fallingFast
        case .risingFast: .risingFast
        case .noData: .noData
        }
    }

    /// Sides are processed in raw-value order, so effects in one evaluation come in a fixed order on every platform.
    public static func < (a: AlertSide, b: AlertSide) -> Bool { a.rawValue < b.rawValue }
}

/// The treatments that quiet alerts: carbs (lows, falling fast) and rapid insulin (highs, rising fast).
public enum TreatmentKind: String, Codable, Sendable {
    case carbs
    case rapidInsulin = "rapid"
}
