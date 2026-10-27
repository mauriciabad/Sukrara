// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later

/// The V1 identifiers. They are new (no PoC ids, no migration) and must match `Bundle/*.plist.in` and the
/// entitlements that `Bundle/bundle.sh` writes.
public enum SukraraIDs {
    public static let teamID = "QWUV9H4A3G"
    public static let appBundleID = "app.mauri.sukrara"
    public static let widgetBundleID = "app.mauri.sukrara.widget"
    /// macOS team-prefixed App Group: authorised by the Team ID in the signature, no provisioning profile.
    public static let appGroup = "\(teamID).app.mauri.sukrara"
    /// Info.plist key carrying the App Group, so app and widget read the same value the bundle was signed with.
    public static let appGroupInfoKey = "SukraraAppGroup"
    public static let keychainService = "app.mauri.sukrara"
    /// The standard defaults domain (= the bundle id); keys come from the settings schema.
    public static let defaultsDomain = appBundleID
    public static let urlScheme = "sukrara"

    /// WidgetKit kinds. Renaming one removes the widgets people placed.
    public enum WidgetKind {
        public static let value = "app.mauri.sukrara.widget.value"
        public static let graph = "app.mauri.sukrara.widget.graph"
        public static let combined = "app.mauri.sukrara.widget.combined"
        public static let all = [value, graph, combined]
    }
}
