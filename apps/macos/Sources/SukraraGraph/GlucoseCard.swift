// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later

/// The one card renderer (CLAUDE.md "One graph everywhere"). Chunk 14 brings the layout, chart, ring and badges;
/// every surface (menu bar, panel, widget, snapshots) takes its text and drawing from here.
public enum GlucoseCard {
    /// What a surface shows while there is no reading to show (never a made-up value).
    public static let noValueText = "—"
}
