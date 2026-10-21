// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation

/// Evidence aid: `-settingsSnapshot <dir>` renders every Settings pane, in four states, to `<dir>/<name>.png`
/// without screen recording. Synthetic settings only: each state goes into the volatile arguments domain (read like
/// `-key value` launch arguments, never written to disk), so a dev run (`SUKRARA_DEV=1` with a throwaway
/// `-keychainService`, `-readingsDir`, `-treatmentsDir`) keeps no state and touches no one's real settings.
///  - (no suffix)   the defaults: Settings from Wearer, not following anyone
///  - `-wearer`     Settings from Wearer with a sample wearer settings event (the self-test's) from "Ana"
///  - `-custom`     Settings from Custom with custom numbers everywhere (7 h, 36 h widgets, 45-day period, …)
///  - `-invalid`    Custom with an invalid number typed into Time shown › Default (the red range note)
@MainActor enum SettingsSnapshot {
    struct State {
        let suffix: String
        var values: [String: Any] = [:]
        var wearer: String? = nil
        var typed: [String: String] = [:]
        var panes: [SettingsWindow.Pane] = SettingsWindow.Pane.allCases
        var details: AlertKind? = .high
    }

    static var states: [State] {
        var content = SettingsSelfTest.sampleContent
        content["u"] = Int(Date().timeIntervalSince1970) - 3 * 3600
        let wearerJSON = String(decoding: try! JSONSerialization.data(withJSONObject: content), as: UTF8.self)
        let customAlerts = String(decoding: try! JSONSerialization.data(withJSONObject: [
            "alerts": ["high": ["on": true, "mgdl": 183, "repeatMin": 7], "low": ["on": true, "mgdl": 72, "repeatMin": 5]],
            "muteAfterInsulin": ["on": true, "min": 75], "notificationActions": ["muteMin": 45, "slowMin": 50],
        ] as [String: Any]), as: UTF8.self)
        let custom: [String: Any] = [
            WearerSettings.modeKey: "custom", "graphHours": 7, "widgetHours": 36, "countdownHours": 10, "bandLow": 68, "bandHigh": 175,
            "lines": "100,155", "ringCount": 3, "slots": "TIR:86400:1,TIR:3888000:1,GMI:1209600:1", "lastElse": "TIR:5184000:1",
            "ringOrder": "TIGHT,TARGET,HIGH,VERY_HIGH,LOW,VERY_LOW", "marks": "rapid:amount+time,carbs:point+amount", AlertSettings.key: customAlerts,
        ]
        return [
            State(suffix: "", details: .low),
            State(suffix: "-wearer", values: [WearerSettings.modeKey: "wearer", WearerSettings.storeKey: wearerJSON], wearer: "Ana",
                  panes: [.general, .graph, .rings, .alerts]),
            State(suffix: "-custom", values: custom, panes: [.general, .graph, .rings, .alerts]),
            State(suffix: "-invalid", values: custom, typed: ["Default": "60"], panes: [.graph], details: nil),
        ]
    }

    static func run(_ w: SettingsWindow, dir: String) {
        Task { @MainActor in
            let d = UserDefaults.standard
            let base = d.volatileDomain(forName: UserDefaults.argumentDomain)
            for st in states {
                d.setVolatileDomain(base.merging(st.values) { _, new in new }, forName: UserDefaults.argumentDomain)
                SettingsFromModel.previewWearer = st.wearer
                NumberSettingRow.previewTexts = st.typed
                w.rebuildViews()
                NotificationCenter.default.post(name: WearerSettings.changed, object: nil)
                try? await Task.sleep(for: .milliseconds(500))
                for p in st.panes { await w.snapshot(p, to: "\(dir)/\(p.rawValue)\(st.suffix).png") }
                if let k = st.details { await w.snapshotDetails(k, to: "\(dir)/alert-details\(st.suffix).png") }
            }
            d.setVolatileDomain(base, forName: UserDefaults.argumentDomain)
            log("settings snapshot: done")
        }
    }
}
