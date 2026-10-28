// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import SukraraCore
import SukraraGraph

extension GraphConfig {
    /// The card's settings from the store, the wearer's mirrored values first while "Settings from: Wearer"
    /// (bands, hours, vertical lines, sensor countdown; the Graph/Rings pane settings keep their defaults until
    /// that pane lands).
    public static func load(from store: some SettingsStoring, wearer: WearerSettings?) -> GraphConfig {
        let mirror = store.settingsFrom == .wearer ? wearer : nil
        func n(_ s: MacSettings.Number) -> Int { Int((mirror?.number(s.id) ?? store.number(s)).rounded()) }
        var c = GraphConfig()
        c.bands = Bands(
            veryLow: n(MacSettings.bandVeryLow), low: n(MacSettings.bandLow), tight: n(MacSettings.bandTight),
            high: n(MacSettings.bandHigh), veryHigh: n(MacSettings.bandVeryHigh),
            extremelyHigh: n(MacSettings.bandExtremelyHigh))
        c.hours = n(MacSettings.graphHours)
        c.gridHours = n(MacSettings.gridHours)
        c.countdownHours = n(MacSettings.countdownHours)
        return c
    }
}
