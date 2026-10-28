// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import SukraraCore

/// The alert settings' number formats, from the generated schema (`SettingsSpec`, `contracts/settings/settings.json`).
public enum AlertLimits {
    public static func threshold(_ kind: AlertKind) -> NumberSpec {
        switch kind {
        case .veryLow: SettingsSpec.thresholdVeryLow
        case .low: SettingsSpec.thresholdLow
        case .high: SettingsSpec.thresholdHigh
        case .veryHigh: SettingsSpec.thresholdVeryHigh
        case .extremelyHigh: SettingsSpec.thresholdExtremelyHigh
        case .fallingFast, .risingFast: SettingsSpec.thresholdRate
        case .noData: SettingsSpec.thresholdNoData
        }
    }

    /// The longest Mute a notification accepts, in minutes.
    public static var longestMuteMin: Double { SettingsSpec.actionMute.max }
    /// The longest timed Mute all, in minutes.
    public static var longestMuteAllMin: Double { SettingsSpec.muteAll.max }
    public static var mutePresets: [Int] { SettingsSpec.actionMute.presets.map { Int($0) } }
    public static var relaxPresets: [Int] { SettingsSpec.actionRelax.presets.map { Int($0) } }
    /// Mute all's presets in minutes; 0 = until turned off.
    public static var muteAllPresets: [Int] { SettingsSpec.muteAll.presets.map { Int($0) } }
}
