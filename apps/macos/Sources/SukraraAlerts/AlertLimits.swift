// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later

/// The number formats of the alert settings, as in the schema `contracts/settings/settings.json` "numbers"
/// (`SettingsCodecTests` compares them with the schema). Used to coerce decoded values into range.
public enum AlertLimits {
    /// A number format: valid = a special value, or in `min...max` on `step`.
    public struct Number: Equatable, Sendable {
        public let id: String
        public let min: Double
        public let max: Double
        public let step: Double
        public let specials: [Double]

        /// `v` if valid, else the nearest value in range on step.
        public func coerce(_ v: Double) -> Double {
            guard v.isFinite else { return min }
            if specials.contains(where: { abs($0 - v) < 1e-9 }) { return v }
            let clamped = Swift.min(Swift.max(v, min), max)
            return Swift.min(Swift.max(min + ((clamped - min) / step).rounded() * step, min), max)
        }
    }

    public static let thresholdVeryLow = Number(id: "thresholdVeryLow", min: 40, max: 80, step: 1, specials: [])
    public static let thresholdLow = Number(id: "thresholdLow", min: 50, max: 120, step: 1, specials: [])
    public static let thresholdHigh = Number(id: "thresholdHigh", min: 120, max: 300, step: 1, specials: [])
    public static let thresholdVeryHigh = Number(id: "thresholdVeryHigh", min: 150, max: 400, step: 1, specials: [])
    public static let thresholdExtremelyHigh = Number(
        id: "thresholdExtremelyHigh", min: 200, max: 450, step: 1, specials: [])
    public static let thresholdRate = Number(id: "thresholdRate", min: 0.5, max: 5, step: 0.1, specials: [])
    public static let thresholdNoData = Number(id: "thresholdNoData", min: 5, max: 240, step: 1, specials: [])
    public static let alertRepeat = Number(id: "alertRepeat", min: 1, max: 240, step: 1, specials: [0])
    public static let muteInsulin = Number(id: "muteInsulin", min: 10, max: 360, step: 1, specials: [])
    public static let muteFalling = Number(id: "muteFalling", min: 5, max: 120, step: 1, specials: [])
    public static let muteCarbs = Number(id: "muteCarbs", min: 5, max: 120, step: 1, specials: [])
    public static let actionMute = Number(id: "actionMute", min: 1, max: 720, step: 1, specials: [])
    public static let actionRelax = Number(id: "actionRelax", min: 5, max: 120, step: 1, specials: [])
    public static let backMgdl = Number(id: "backMgdl", min: 0, max: 50, step: 1, specials: [])
    public static let backMin = Number(id: "backMin", min: 0, max: 60, step: 1, specials: [])
    /// Mute all's custom length; 0 = until turned off.
    public static let muteAll = Number(id: "muteAll", min: 5, max: 1440, step: 1, specials: [0])

    public static let mutePresets = [15, 30, 60, 120]
    public static let relaxPresets = [15, 20, 30, 45, 60, 90, 120]
    /// Mute all's presets in minutes; 0 = until turned off.
    public static let muteAllPresets = [30, 60, 120, 240, 480, 0]

    public static let all: [Number] = [
        thresholdVeryLow, thresholdLow, thresholdHigh, thresholdVeryHigh, thresholdExtremelyHigh, thresholdRate,
        thresholdNoData, alertRepeat, muteInsulin, muteFalling, muteCarbs, actionMute, actionRelax, backMgdl, backMin,
        muteAll,
    ]

    public static func threshold(_ kind: AlertKind) -> Number {
        switch kind {
        case .veryLow: thresholdVeryLow
        case .low: thresholdLow
        case .high: thresholdHigh
        case .veryHigh: thresholdVeryHigh
        case .extremelyHigh: thresholdExtremelyHigh
        case .fallingFast, .risingFast: thresholdRate
        case .noData: thresholdNoData
        }
    }
}
