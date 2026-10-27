// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation

/// The trend of a rate of change (mg/dL per minute), with Android's thresholds (core/Glucose.kt `Trend`): steady
/// under 1, rising / falling from 1, fast from 2; the arrow points straight up / down from `fullScale` (doubled).
public enum Trend: String, Sendable, CaseIterable {
    case risingFast, rising, steady, falling, fallingFast

    /// mg/dL per minute the arrow maps to ±90° (and doubles at).
    public static let fullScale = 3.0

    public init(rate: Double) {
        switch rate {
        case 2...: self = .risingFast
        case 1..<2: self = .rising
        case ..<(-2): self = .fallingFast
        case ..<(-1): self = .falling
        default: self = .steady
        }
    }

    /// What a screen reader says: "rising fast", "steady".
    public var words: String {
        switch self {
        case .risingFast: "rising fast"
        case .rising: "rising"
        case .steady: "steady"
        case .falling: "falling"
        case .fallingFast: "falling fast"
        }
    }

    /// The words for an optional rate: "trend unknown" without one.
    public static func words(rate: Double?) -> String { rate.map { Trend(rate: $0).words } ?? "trend unknown" }
}
