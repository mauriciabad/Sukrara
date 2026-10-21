// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation

/// Followers (round f28): where the settings a follower can mirror come from — schema `follower.settingsFrom`
/// (spikes/settings/settings.json, the entries with `"mirror": true`): `default` (the schema's defaults), `wearer` (the
/// wearer's own, from their settings event, PROTOCOL-v2 "Settings"; the default) or `custom` (this Mac's). Everything
/// else (sounds, like an alarm, the notification's Mute / Slow down, no data, per-place windows and sensor time, the
/// alerts main switch, open at login) is always this Mac's.
///
/// Wire content (Android's GraphConfig JSON keys under "graph", SPEC §3 keys under "alerts", mirrored keys only):
/// `{"v":2,"type":"settings","u":unix_s,"graph":{"veryLow",…,"countdownHours"},"alerts":{"alerts":{kind:{"on",
/// "mgdl"|"rate","repeatMin"}},"muteAfterInsulin","muteFallingHigh","muteAfterCarbs"}}`. UserDefaults keys:
/// `settingsFrom` and `wearerSettings` (the newest content as a JSON string).
enum WearerSettings {
    static let modeKey = "settingsFrom"
    static let storeKey = "wearerSettings"
    static let modes = ["wearer", "default", "custom"]
    /// Posted (main queue) when the mode or the wearer's settings change.
    static let changed = Notification.Name("SukraraWearerSettingsChanged")

    static func mode(_ d: UserDefaults = .standard) -> String {
        d.string(forKey: modeKey).flatMap { modes.contains($0) ? $0 : nil } ?? "wearer"
    }

    static func setMode(_ m: String, _ d: UserDefaults = .standard) {
        guard modes.contains(m) else { return }
        d.set(m, forKey: modeKey)
        post()
    }

    /// The wearer's latest content, or nil.
    static func content(_ d: UserDefaults = .standard) -> [String: Any]? {
        guard let s = d.string(forKey: storeKey),
              let o = try? JSONSerialization.jsonObject(with: Data(s.utf8)) as? [String: Any] else { return nil }
        return o
    }

    /// The decrypted content of a wearer's settings event (a JSON object); kept when newer (`u`) than the one held.
    @discardableResult
    static func received(_ json: String, _ d: UserDefaults = .standard) -> Bool {
        guard let o = try? JSONSerialization.jsonObject(with: Data(json.utf8)) as? [String: Any],
              o["type"] as? String == "settings", let u = (o["u"] as? NSNumber)?.int64Value else { return false }
        if let cur = content(d), let cu = (cur["u"] as? NSNumber)?.int64Value, u <= cu { return false }
        d.set(json, forKey: storeKey)
        post()
        return true
    }

    private static func post() {
        DispatchQueue.main.async { NotificationCenter.default.post(name: changed, object: nil) }
    }
}
