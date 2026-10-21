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

    /// Unfollowed: the wearer's settings go (a new wearer sends their own).
    static func forget(_ d: UserDefaults = .standard) {
        guard d.object(forKey: storeKey) != nil else { return }
        d.removeObject(forKey: storeKey)
        post()
    }

    // MARK: effective settings (the one merge every consumer goes through: GraphConfig.load, AlertSettings.load)

    /// Where the mirrored settings come from now: this Mac's stored values (Custom), nothing = the schema's defaults
    /// (Default, or Wearer before anything was received), or the wearer's latest settings content.
    enum Source { case own, defaults, wearer([String: Any]) }

    static func source(_ d: UserDefaults = .standard) -> Source {
        switch mode(d) {
        case "custom": .own
        case "default": .defaults
        default: content(d).map { .wearer($0) } ?? .defaults
        }
    }

    /// The mirrored graph settings: this Mac's UserDefaults key → its key under "graph" in the wearer's content
    /// (schema rows with `mirror` stored in `defaults`: bandVeryLow → veryLow, graphHours → defaultHours, …).
    static let graphWire: [String: String] = Dictionary(uniqueKeysWithValues: SettingsSpec.settings.compactMap { s in
        guard s.mirror, let w = s.wire, w.hasPrefix("graph."), s.mac.hasPrefix("defaults:") else { return nil }
        return (String(s.mac.dropFirst("defaults:".count)), String(w.dropFirst("graph.".count)))
    })

    /// A mirrored graph setting's raw value (`key` = this Mac's defaults key) from the current source; nil = default.
    static func graphValue(_ key: String, _ src: Source, _ d: UserDefaults) -> Any? {
        switch src {
        case .own: d.object(forKey: key)
        case .defaults: nil
        case .wearer(let c): (c["graph"] as? [String: Any])?[graphWire[key] ?? key]
        }
    }

    /// The alert rules that apply: this Mac's own settings (`own`) with the mirrored rules (each alert's on, threshold
    /// and repeat except no data; the three quiet rules) taken from the source. `source` follows the mode.
    static func alerts(own: AlertSettings, _ d: UserDefaults = .standard) -> AlertSettings {
        alerts(own: own, source: source(d), mode: mode(d))
    }

    static func alerts(own: AlertSettings, source src: Source, mode m: String) -> AlertSettings {
        var s = own
        s.source = m == "wearer" ? "wearer" : "own"
        switch src {
        case .own: break
        case .defaults: copyMirrored(from: AlertSettings(), to: &s)
        case .wearer(let c):
            let theirs = (c["alerts"] as? [String: Any]).flatMap { try? JSONSerialization.data(withJSONObject: $0) }
                .flatMap { try? JSONDecoder().decode(AlertSettings.self, from: $0) } ?? AlertSettings()
            copyMirrored(from: theirs, to: &s)
        }
        return s
    }

    /// The kinds whose on / threshold / repeat are mirrored (no data is this Mac's own link check).
    static let mirroredKinds = AlertKind.allCases.filter { $0 != .noData }

    static func copyMirrored(from src: AlertSettings, to s: inout AlertSettings) {
        for k in mirroredKinds {
            var r = s[k]
            r.on = src[k].on; r.threshold = src[k].threshold; r.repeatMin = src[k].repeatMin
            s[k] = r
        }
        s.muteAfterInsulin = src.muteAfterInsulin
        s.muteFallingHigh = src.muteFallingHigh
        s.muteAfterCarbs = src.muteAfterCarbs
    }

    /// Custom: overwrite this Mac's mirrored values with the wearer's (`fromWearer`) or the defaults. The local ones
    /// (sounds, like an alarm, no data, the notification's actions, per-place windows and sensor time) stay.
    static func overwriteCustom(fromWearer: Bool, _ d: UserDefaults = .standard) {
        let src: Source = fromWearer ? (content(d).map { .wearer($0) } ?? .defaults) : .defaults
        let g = GraphConfig.load(d, source: src)
        for (k, v) in g.mirroredDefaults() { if fromWearer { d.set(v, forKey: k) } else { d.removeObject(forKey: k) } }
        var own = AlertSettings.own(d)
        let from: AlertSettings
        if case .wearer = src { from = alerts(own: own, source: src, mode: "wearer") } else { from = AlertSettings() }
        copyMirrored(from: from, to: &own)
        own.save(d)
        post()
    }

    private static func post() {
        DispatchQueue.main.async { NotificationCenter.default.post(name: changed, object: nil) }
    }
}

extension AlertSettings {
    /// The alert settings that apply (Settings from: Default / Wearer / Custom); `own` is what this Mac stores.
    static func load(_ d: UserDefaults = .standard) -> AlertSettings { WearerSettings.alerts(own: own(d), d) }
}
