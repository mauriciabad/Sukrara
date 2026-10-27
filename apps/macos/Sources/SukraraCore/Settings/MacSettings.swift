// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation

/// The Mac's typed settings: one `SettingSpec` per schema entry the Mac windows edit (keys from the generated
/// `SettingsSpec.Keys`, number defaults from the generated `NumberSpec`s). Number values are always read through
/// their format (`NumberSpec.coerce`), so only values the settings accept get in.
public enum MacSettings {
    public static let bandVeryLow = number("graph.band.veryLow", SettingsSpec.Keys.bandVeryLow, SettingsSpec.bandVeryLow)
    public static let bandLow = number("graph.band.low", SettingsSpec.Keys.bandLow, SettingsSpec.bandLow)
    public static let bandTight = number("graph.band.tight", SettingsSpec.Keys.bandTight, SettingsSpec.bandTight)
    public static let bandHigh = number("graph.band.high", SettingsSpec.Keys.bandHigh, SettingsSpec.bandHigh)
    public static let bandVeryHigh = number(
        "graph.band.veryHigh", SettingsSpec.Keys.bandVeryHigh, SettingsSpec.bandVeryHigh)
    public static let bandExtremelyHigh = number(
        "graph.band.extremelyHigh", SettingsSpec.Keys.bandExtremelyHigh, SettingsSpec.bandExtremelyHigh)
    public static let graphHours = number("graph.hours", SettingsSpec.Keys.graphHours, SettingsSpec.graphHours)
    public static let gridHours = number("graph.gridHours", SettingsSpec.Keys.gridHours, SettingsSpec.gridHours)
    public static let countdownHours = number(
        "sensor.countdownHours", SettingsSpec.Keys.countdownHours, SettingsSpec.countdownHours)
    public static let settingsFrom = SettingSpec(key: SettingsSpec.Keys.settingsFrom, defaultValue: "wearer")
    public static let unit = SettingSpec(key: SettingsSpec.Keys.unit, defaultValue: GlucoseUnit.mgdl.rawValue)
    /// The alert settings: ONE JSON value (contracts/alerts/SPEC.md §3), owned by the alert settings store.
    public static let alerts = SettingSpec(key: SettingsSpec.Keys.alerts, defaultValue: Data())
    /// The first-run walk's step states (JSON), owned by `SetupFlow`.
    public static let setupSteps = SettingSpec(key: SettingsSpec.Keys.setupSteps, defaultValue: Data())

    /// The six range bands, low to high (their rows keep them in order).
    public static let bands = [bandVeryLow, bandLow, bandTight, bandHigh, bandVeryHigh, bandExtremelyHigh]

    /// A number setting: its schema id, where it's stored and its format.
    public struct Number: Sendable {
        public let id: String
        public let spec: SettingSpec<Double>
        public let format: NumberSpec
    }

    static func number(_ id: String, _ key: String, _ format: NumberSpec) -> Number {
        Number(id: id, spec: SettingSpec(key: key, defaultValue: format.default), format: format)
    }
}

/// Where a follower's mirrored settings come from (schema option list `settingsFrom`).
public enum SettingsFrom: String, Sendable, CaseIterable {
    case wearer, `default`, custom
}

extension SettingsStoring {
    /// A number setting, coerced to a value its format accepts.
    public func number(_ n: MacSettings.Number) -> Double { n.format.coerce(value(n.spec)) }

    /// Stores `v` when the format accepts it; returns whether it did.
    @discardableResult
    public func setNumber(_ v: Double, for n: MacSettings.Number) -> Bool {
        guard n.format.valid(v) else { return false }
        set(v, for: n.spec)
        return true
    }

    public var unit: GlucoseUnit { GlucoseUnit(rawValue: value(MacSettings.unit)) ?? .mgdl }
    public var settingsFrom: SettingsFrom { SettingsFrom(rawValue: value(MacSettings.settingsFrom)) ?? .wearer }
}
