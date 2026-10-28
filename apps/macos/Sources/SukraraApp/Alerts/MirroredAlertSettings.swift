// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import SukraraAlerts
import SukraraCore

extension AlertSettings {
    /// The rules this Mac runs while following in "Settings from: Wearer": this Mac's own settings with every value
    /// the schema marks `mirror` replaced by the wearer's (each kind's on / threshold / repeat, the quiet-after rules).
    /// What the wearer didn't send keeps the value it is applied to (`inUse` applies it to the defaults); what isn't
    /// mirrored (alerts on/off, alarm, sounds, no data, notification actions) is always this Mac's, so a mirrored
    /// payload can't silence this Mac.
    func mirroring(_ wearer: WearerSettings) -> AlertSettings {
        var s = self
        // no data is this Mac's own alarm (schema `mirrorExcept`): a wearer's payload never changes or silences it
        for kind in AlertKind.allCases where kind != .noData {
            var rule = s[kind]
            if let on = wearer.bool("alerts.kind.on", kind: kind.rawValue) { rule.on = on }
            if let threshold = wearer.number("alerts.threshold.\(kind.rawValue)") { rule.threshold = threshold }
            if let repeatMin = wearer.number("alerts.kind.repeat", kind: kind.rawValue) {
                rule.repeatMin = Int(repeatMin)
            }
            s[kind] = rule
        }
        func quiet(_ rule: inout QuietRule, _ id: String) {
            if let on = wearer.bool("alerts.\(id).on") { rule.on = on }
            if let min = wearer.number("alerts.\(id).min") { rule.min = min }
        }
        quiet(&s.muteAfterInsulin, "muteAfterInsulin")
        quiet(&s.muteFallingHigh, "muteFallingHigh")
        quiet(&s.muteAfterCarbs, "muteAfterCarbs")
        return s
    }

    /// The rules this Mac runs in "Settings from: Default": this Mac's own settings with every mirrored value at
    /// Sukrara's default (each kind's on / threshold / repeat but no data's, the quiet-after rules), so choosing Default
    /// never overwrites this Mac's own values (they come back with Custom).
    func withMirroredDefaults() -> AlertSettings {
        var s = self
        let d = AlertSettings()
        for kind in AlertKind.allCases where kind != .noData {
            var rule = s[kind]
            rule.on = d[kind].on
            rule.threshold = d[kind].threshold
            rule.repeatMin = d[kind].repeatMin
            s[kind] = rule
        }
        s.muteAfterInsulin = d.muteAfterInsulin
        s.muteFallingHigh = d.muteFallingHigh
        s.muteAfterCarbs = d.muteAfterCarbs
        return s
    }

    /// The rules in use for "Settings from" `from`: Custom = this Mac's own; Default = the defaults; Wearer = the
    /// wearer's mirrored values, the defaults until they arrive. What isn't mirrored is always this Mac's own.
    /// A key the wearer didn't send is its default (PROTOCOL-v2 "Settings"), as for the card. `source` follows `from`,
    /// so whether this Mac mirrors the wearer's alerts has one source of truth.
    func inUse(from: SettingsFrom, wearer: WearerSettings?) -> AlertSettings {
        var s: AlertSettings
        switch from {
        case .custom: s = self
        case .default: s = withMirroredDefaults()
        case .wearer: s = wearer.map { withMirroredDefaults().mirroring($0) } ?? withMirroredDefaults()
        }
        s.source = from == .wearer ? .wearer : .own
        return s
    }
}
