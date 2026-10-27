// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import SukraraAlerts
import SukraraCore

extension AlertSettings {
    /// The rules this Mac runs while following in "Settings from: Wearer": this Mac's own settings with every value
    /// the schema marks `mirror` replaced by the wearer's (each kind's on / threshold / repeat, the quiet-after rules).
    /// What the wearer didn't send keeps this Mac's value; what isn't mirrored (alerts on/off, alarm, sounds, no data,
    /// notification actions) is always this Mac's, so a mirrored payload can't silence this Mac.
    func mirroring(_ wearer: WearerSettings) -> AlertSettings {
        var s = self
        for kind in AlertKind.allCases {
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
}
