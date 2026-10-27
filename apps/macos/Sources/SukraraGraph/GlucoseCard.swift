// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import SukraraCore

/// The one card renderer (CLAUDE.md "One graph everywhere"). Chunk 14 brings the layout, chart, ring and badges;
/// every surface (menu bar, panel, widget, snapshots) takes its text and drawing from here.
public enum GlucoseCard {
    /// What a surface shows while there is no reading to show (never a made-up value).
    public static let noValueText = "—"

    /// The value text a text place (menu bar title, VoiceOver) shows. Scaffold: the latest value as is.
    /// Chunk 14 replaces it with the full face (unit, arrow, freshness steps from `contracts/graph/freshness`, so an
    /// old reading never reads as current); callers already go through here.
    public static func face(_ snapshot: GlucoseSnapshot?) -> String {
        snapshot?.latest.map { String($0.mgdl) } ?? noValueText
    }
}
