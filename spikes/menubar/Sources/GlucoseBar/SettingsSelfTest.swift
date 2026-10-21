// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation

/// `Sukrara -settingsselftest`: the Mac's settings against the shared schema (spikes/settings/settings.json →
/// SettingsSpec.swift; Android's SettingsSchemaTest checks the same file): (a) every schema row with a Mac key is
/// stored by the Mac, (b) every key the Mac stores is in the schema, (c) model defaults = schema defaults,
/// (d) every number spec round-trips min, max, every preset and special and a custom value, and coerces invalid ones,
/// (e) Settings from Default / Wearer / Custom give the expected mirrored vs local values, (f) every mirrored schema
/// row's wire key is read from the wearer's settings event. Throwaway UserDefaults suites only.
enum SettingsSelfTest {
    // MARK: adapters (the one place that names the Mac API under test)

    static func graph(_ d: UserDefaults, place: String? = nil) -> GraphConfig { GraphConfig.load(d, place: place) }
    static func alerts(_ d: UserDefaults) -> AlertSettings { AlertSettings.load(d) }
    static func setAlerts(_ d: UserDefaults, _ json: [String: Any]) {
        d.set(String(decoding: try! JSONSerialization.data(withJSONObject: json), as: UTF8.self), forKey: AlertSettings.key)
    }
    /// Every UserDefaults key the Mac stores a setting in.
    static var macDefaultsKeys: [String] { StatusController.settingsKeys }

    // MARK: run

    nonisolated(unsafe) private static var ok = true
    private static func check(_ name: String, _ cond: Bool, _ detail: @autoclosure () -> String = "") {
        print("\(cond ? "PASS" : "FAIL")  \(name)\(cond ? "" : "  — " + detail())")
        ok = ok && cond
    }
    private static func suite() -> UserDefaults {
        let name = "sukrara-settingstest-\(UUID().uuidString)"
        let d = UserDefaults(suiteName: name)!
        d.removePersistentDomain(forName: name)
        return d
    }
    private static func drop(_ d: UserDefaults) {
        for k in d.dictionaryRepresentation().keys { d.removeObject(forKey: k) }
    }

    /// "alerts.{kind}.on" → one key per kind; "menubarHours,widgetHours" → both.
    static func expand(_ key: String) -> [String] {
        key.split(separator: ",").map(String.init).flatMap { k in
            k.contains("{kind}") ? AlertKind.allCases.map { k.replacingOccurrences(of: "{kind}", with: $0.rawValue) } : [k]
        }
    }
    /// The schema's Mac keys in one store ("defaults", "alerts").
    static func schemaKeys(_ store: String) -> Set<String> {
        Set(SettingsSpec.settings.filter { $0.mac.hasPrefix(store + ":") }.flatMap { expand(String($0.mac.dropFirst(store.count + 1))) })
    }
    /// The alert settings JSON flattened to dotted leaf keys ("alerts.high.mgdl", "sounds.kinds.low").
    static func flatten(_ o: [String: Any], _ prefix: String = "") -> Set<String> {
        var out = Set<String>()
        for (k, v) in o {
            if let sub = v as? [String: Any], !sub.isEmpty { out.formUnion(flatten(sub, prefix + k + ".")) } else { out.insert(prefix + k) }
        }
        return out
    }

    static func run() -> Bool {
        ok = true
        inventory()
        defaults()
        conformance()
        effective()
        wire()
        print(ok ? "settings self-test OK" : "settings self-test FAILED")
        return ok
    }

    // MARK: (a) (b) inventory

    static func inventory() {
        var full = AlertSettings()
        for k in AlertKind.allCases { full.sounds.kinds[k.rawValue] = "kart" }
        let json = (try? JSONSerialization.jsonObject(with: Data(full.json.utf8))) as? [String: Any] ?? [:]
        let stored = flatten(json)
        let macDefaults = Set(macDefaultsKeys)
        for s in SettingsSpec.settings where !s.mac.isEmpty {
            let store = String(s.mac.prefix { $0 != ":" })
            let keys = expand(String(s.mac.dropFirst(store.count + 1)))
            switch store {
            case "defaults": check("(a) \(s.id): stored in defaults", keys.allSatisfy(macDefaults.contains), "missing \(keys.filter { !macDefaults.contains($0) })")
            case "alerts": check("(a) \(s.id): in the alert settings JSON", keys.allSatisfy(stored.contains), "missing \(keys.filter { !stored.contains($0) })")
            case "system": check("(a) \(s.id): system store (SMAppService)", s.mac == "system:SMAppService.mainApp")
            default: check("(a) \(s.id): known Mac store", false, s.mac)
            }
        }
        let sd = schemaKeys("defaults"), sa = schemaKeys("alerts")
        for k in macDefaults.sorted() { check("(b) defaults key \(k) is in the schema", sd.contains(k)) }
        for k in stored.sorted() { check("(b) alerts key \(k) is in the schema", sa.contains(k)) }
    }

    // MARK: (c) defaults

    static func defaults() {
        let g = GraphConfig(), S = SettingsSpec.self
        let pairs: [(String, Double, NumberSpec)] = [
            ("bands.veryLow", Double(g.bands.veryLow), S.bandVeryLow), ("bands.low", Double(g.bands.low), S.bandLow),
            ("bands.tight", Double(g.bands.tight), S.bandTight), ("bands.high", Double(g.bands.high), S.bandHigh),
            ("bands.veryHigh", Double(g.bands.veryHigh), S.bandVeryHigh), ("bands.extremelyHigh", Double(g.bands.extremelyHigh), S.bandExtremelyHigh),
            ("hours", Double(g.hours), S.graphHours), ("ringCount", Double(g.ringCount), S.ringCount),
            ("gridHours", Double(g.gridHours), S.gridHours), ("countdownHours", Double(g.countdownHours), S.countdownHours),
            ("main slot period", g.slots[0].window, S.ringWindow), ("first line", Double(g.lines.first ?? -1), S.line),
        ]
        for (n, v, spec) in pairs { check("(c) graph \(n) default \(v) = schema \(spec.id) \(spec.default)", v == spec.default) }
        let d = suite()
        check("(c) per-place window default = same as default (0)", graph(d, place: "menubar").hours == graph(d).hours && S.placeHours.default == 0)
        let a = AlertSettings()
        for k in AlertKind.allCases {
            let spec = k.thresholdSpec
            check("(c) \(k.rawValue) threshold \(a[k].threshold) = schema \(spec.id) \(spec.default)", a[k].threshold == spec.default)
            check("(c) \(k.rawValue) repeat \(a[k].repeatMin) is a valid \(S.alertRepeat.id)", S.alertRepeat.valid(Double(a[k].repeatMin)))
        }
        check("(c) quiet after insulin \(a.muteAfterInsulin.min) = schema", a.muteAfterInsulin.min == S.muteInsulin.default)
        check("(c) quiet falling \(a.muteFallingHigh.min) = schema", a.muteFallingHigh.min == S.muteFalling.default)
        check("(c) quiet after carbs \(a.muteAfterCarbs.min) = schema", a.muteAfterCarbs.min == S.muteCarbs.default)
        check("(c) Mute for \(a.notificationActions.muteMin) = schema", Double(a.notificationActions.muteMin) == S.actionMute.default)
        check("(c) Slow down \(a.notificationActions.slowMin) = schema", Double(a.notificationActions.slowMin) == S.actionSlow.default)
    }

    // MARK: (d) conformance

    /// One number setting as the Mac stores and loads it.
    struct Bound {
        let name: String
        let spec: NumberSpec
        let write: (UserDefaults, Double) -> Void
        let read: (UserDefaults) -> Double
        /// What `read` gives for a special value (e.g. a place's "same as default" = the default window).
        var specialExpect: ((Double) -> Double)? = nil
        /// A list entry: an invalid value is dropped (`read` gives -1) rather than coerced.
        var dropsInvalid = false
    }

    static func bounds() -> [Bound] {
        let S = SettingsSpec.self
        var out: [Bound] = []
        // the six bands, written with the others where the order lets the value stand (lower ones at their minimum,
        // higher ones at their maximum): Bands.sane keeps them in order
        let bandKeys = [("bandVeryLow", S.bandVeryLow, \Bands.veryLow), ("bandLow", S.bandLow, \Bands.low), ("bandTight", S.bandTight, \Bands.tight),
                        ("bandHigh", S.bandHigh, \Bands.high), ("bandVeryHigh", S.bandVeryHigh, \Bands.veryHigh),
                        ("bandExtremelyHigh", S.bandExtremelyHigh, \Bands.extremelyHigh)]
        for (i, b) in bandKeys.enumerated() {
            out.append(Bound(name: b.0, spec: b.1, write: { d, v in
                for (j, o) in bandKeys.enumerated() where j != i { d.set(Int(j < i ? o.1.min : o.1.max), forKey: o.0) }
                d.set(v, forKey: b.0)
            }, read: { Double(graph($0).bands[keyPath: b.2]) }))
        }
        out.append(Bound(name: "graphHours", spec: S.graphHours, write: { $0.set($1, forKey: "graphHours") }, read: { Double(graph($0).hours) }))
        for p in ["menubar", "widget"] {
            out.append(Bound(name: "\(p)Hours", spec: S.placeHours, write: { d, v in d.set(5, forKey: "graphHours"); d.set(v, forKey: "\(p)Hours") },
                             read: { Double(graph($0, place: p).hours) }, specialExpect: { _ in 5 }))
        }
        out.append(Bound(name: "ringCount", spec: S.ringCount, write: { $0.set($1, forKey: "ringCount") }, read: { Double(graph($0).ringCount) }))
        out.append(Bound(name: "slots (main period)", spec: S.ringWindow,
                         write: { d, v in d.set("TIR:\(Int(v)):1,TIR:604800:1,GMI:1209600:1", forKey: "slots") }, read: { graph($0).slots[0].window }))
        out.append(Bound(name: "lastElse period", spec: S.ringWindow, write: { d, v in d.set("TIR:\(Int(v)):1", forKey: "lastElse") },
                         read: { graph($0).lastElse?.window ?? -1 }))
        out.append(Bound(name: "lines", spec: S.line, write: { d, v in d.set(S.line.text(v), forKey: "lines") },
                         read: { Double(graph($0).lines.first ?? -1) }, dropsInvalid: true))
        out.append(Bound(name: "gridHours", spec: S.gridHours, write: { $0.set($1, forKey: "gridHours") }, read: { Double(graph($0).gridHours) }))
        out.append(Bound(name: "countdownHours", spec: S.countdownHours, write: { $0.set($1, forKey: "countdownHours") },
                         read: { Double(graph($0).countdownHours) }))
        for k in AlertKind.allCases {
            let field = k.group == .rate ? "rate" : k.group == .noData ? "min" : "mgdl"
            out.append(Bound(name: "alerts.\(k.rawValue).\(field)", spec: k.thresholdSpec,
                             write: { d, v in setAlerts(d, ["alerts": [k.rawValue: [field: v]]]) }, read: { alerts($0)[k].threshold }))
        }
        out.append(Bound(name: "alerts.high.repeatMin", spec: S.alertRepeat, write: { d, v in setAlerts(d, ["alerts": ["high": ["repeatMin": v]]]) },
                         read: { Double(alerts($0)[.high].repeatMin) }))
        for (key, spec, kp) in [("muteAfterInsulin", S.muteInsulin, \AlertSettings.muteAfterInsulin), ("muteFallingHigh", S.muteFalling, \.muteFallingHigh),
                                ("muteAfterCarbs", S.muteCarbs, \.muteAfterCarbs)] {
            out.append(Bound(name: "\(key).min", spec: spec, write: { d, v in setAlerts(d, [key: ["on": true, "min": v]]) },
                             read: { alerts($0)[keyPath: kp].min }))
        }
        out.append(Bound(name: "notificationActions.muteMin", spec: S.actionMute,
                         write: { d, v in setAlerts(d, ["notificationActions": ["muteMin": v, "slowMin": 30]]) },
                         read: { Double(alerts($0).notificationActions.muteMin) }))
        out.append(Bound(name: "notificationActions.slowMin", spec: S.actionSlow,
                         write: { d, v in setAlerts(d, ["notificationActions": ["muteMin": 30, "slowMin": v]]) },
                         read: { Double(alerts($0).notificationActions.slowMin) }))
        return out
    }

    /// A custom value near the middle that is no preset.
    static func midCustom(_ s: NumberSpec) -> Double? {
        guard s.custom else { return nil }
        var v = s.min + ((s.max - s.min) / 2 / s.step).rounded(.down) * s.step
        while s.isPreset(v) || s.specialOf(v) != nil { v += s.step }
        return v <= s.max ? v : nil
    }

    /// A suite where this Mac's stored values apply (Settings from: Custom; in Default / Wearer the mirrored ones don't).
    private static func customSuite() -> UserDefaults {
        let d = suite()
        d.set("custom", forKey: WearerSettings.modeKey)
        return d
    }

    static func conformance() {
        for b in bounds() {
            let s = b.spec
            var values = s.presets + Array(s.special.keys)
            if s.custom { values += [s.min, s.max] + (midCustom(s).map { [$0] } ?? []) }
            var bad: [String] = []
            for v in Set(values).sorted() {
                let d = customSuite()
                b.write(d, v)
                let got = b.read(d)
                let want = s.specialOf(v) != nil ? (b.specialExpect?(v) ?? v) : v
                if abs(got - want) > 1e-9 { bad.append("\(s.text(v)) → \(s.text(got))") }
                drop(d)
            }
            check("(d) \(b.name) [\(s.id)]: min, max, presets, specials and a custom value load back", bad.isEmpty, bad.joined(separator: ", "))
            var invalid = [s.max + s.step]
            if s.step > 1e-9, !s.valid(s.min + s.step / 2) { invalid.append(s.min + s.step / 2) }
            if !s.custom { invalid.append(s.max + 2 * s.step) }
            for v in invalid {
                let d = customSuite()
                b.write(d, v)
                let got = b.read(d)
                check("(d) \(b.name): invalid \(v) is coerced or falls back (got \(got))", s.valid(got) || (b.dropsInvalid && got == -1))
                drop(d)
            }
        }
    }

    // MARK: (e) effective config

    static let sampleContent: [String: Any] = [
        "v": 2, "type": "settings", "u": 1_760_000_000,
        "graph": ["veryLow": 50, "low": 80, "tight": 130, "high": 190, "veryHigh": 240, "extremelyHigh": 320, "defaultHours": 9,
                  "ringCount": 3, "slots": "AVERAGE:259200:0,TIR:86400:1,CV:604800:1", "ringRange": "TIGHT",
                  "ringOrder": "VERY_LOW,LOW,TIGHT,TARGET,HIGH,VERY_HIGH", "ringMarks": false, "side": false, "columnCrown": false,
                  "lines": "90,150", "gridHours": 3, "lastPlace": "END", "lastElse": "GMI:1209600:0", "countdownHours": 5],
        "alerts": ["alerts": ["very_low": ["on": false, "mgdl": 52, "repeatMin": 7], "low": ["on": true, "mgdl": 75, "repeatMin": 0],
                              "high": ["on": true, "mgdl": 170, "repeatMin": 25], "very_high": ["on": false, "mgdl": 260, "repeatMin": 12],
                              "extremely_high": ["on": true, "mgdl": 330, "repeatMin": 3],
                              "falling_fast": ["on": true, "rate": 1.5, "repeatMin": 20], "rising_fast": ["on": true, "rate": 2.5, "repeatMin": 40]],
                   "muteAfterInsulin": ["on": false, "min": 90], "muteFallingHigh": ["on": true, "min": 35], "muteAfterCarbs": ["on": false, "min": 25]],
    ]
    static var sampleJSON: String { String(decoding: try! JSONSerialization.data(withJSONObject: sampleContent), as: UTF8.self) }

    static func effective() {
        func own(_ d: UserDefaults) {
            d.set(6, forKey: "graphHours"); d.set(75, forKey: "bandLow"); d.set(3, forKey: "menubarHours"); d.set(false, forKey: "menubarSensorTime")
            setAlerts(d, ["alerts": ["high": ["mgdl": 200, "alarm": true], "no_data": ["min": 45]], "sounds": ["pack": "kart"],
                          "notificationActions": ["muteMin": 60, "slowMin": 45]])
        }
        for (mode, content) in [("default", true), ("wearer", true), ("wearer", false), ("custom", true)] {
            let d = suite()
            own(d)
            d.set(mode, forKey: WearerSettings.modeKey)
            if content { WearerSettings.received(sampleJSON, d) }
            let g = graph(d), m = graph(d, place: "menubar"), a = alerts(d)
            let tag = "(e) \(mode)\(content ? "" : " (nothing received)")"
            let fromWearer = mode == "wearer" && content
            let wantHours = mode == "custom" ? 6 : fromWearer ? 9 : 12
            let wantLow = mode == "custom" ? 75 : fromWearer ? 80 : 70
            let wantHigh: Double = mode == "custom" ? 200 : fromWearer ? 170 : 180
            check("\(tag): mirrored time shown \(g.hours) = \(wantHours)", g.hours == wantHours)
            check("\(tag): mirrored low band \(g.bands.low) = \(wantLow)", g.bands.low == wantLow)
            check("\(tag): mirrored high alert \(a[.high].threshold) = \(wantHigh)", a[.high].threshold == wantHigh)
            check("\(tag): local menu bar window stays 3 h", m.hours == 3)
            check("\(tag): local sensor time per place stays off", !m.sensorTime)
            check("\(tag): local like an alarm, sound pack, no data, notification actions stay this Mac's",
                  a[.high].alarm && a.sounds.pack == "kart" && a[.noData].threshold == 45 && a.notificationActions.muteMin == 60
                  && a.notificationActions.slowMin == 45)
            check("\(tag): alert source \(a.source)", a.source == (mode == "wearer" ? "wearer" : "own"))
            drop(d)
        }
    }

    // MARK: (f) wire keys

    /// Each mirrored wire key (schema "wire", {kind} expanded; no_data stays this Mac's) → whether the effective
    /// settings in wearer mode show `sampleContent`'s value for it.
    static func wireChecks(_ g: GraphConfig, _ a: AlertSettings) -> [String: Bool] {
        var c: [String: Bool] = [
            "graph.veryLow": g.bands.veryLow == 50, "graph.low": g.bands.low == 80, "graph.tight": g.bands.tight == 130,
            "graph.high": g.bands.high == 190, "graph.veryHigh": g.bands.veryHigh == 240, "graph.extremelyHigh": g.bands.extremelyHigh == 320,
            "graph.defaultHours": g.hours == 9, "graph.ringCount": g.ringCount == 3,
            "graph.slots": GraphConfig.slotsText(g.slots) == "AVERAGE:259200:0,TIR:86400:1,CV:604800:1",
            "graph.ringRange": g.ringRange == .tight, "graph.ringOrder": ringOrderText(g.ringOrder) == "VERY_LOW,LOW,TIGHT,TARGET,HIGH,VERY_HIGH",
            "graph.ringMarks": !g.ringMarks, "graph.side": !g.side, "graph.columnCrown": !g.columnCrown, "graph.lines": g.lines == [90, 150],
            "graph.gridHours": g.gridHours == 3, "graph.lastPlace": g.lastPlace == .end,
            "graph.lastElse": g.lastElse.map { GraphConfig.slotsText([$0]) } == "GMI:1209600:0", "graph.countdownHours": g.countdownHours == 5,
            "alerts.muteAfterInsulin.on": !a.muteAfterInsulin.on, "alerts.muteAfterInsulin.min": a.muteAfterInsulin.min == 90,
            "alerts.muteFallingHigh.on": a.muteFallingHigh.on, "alerts.muteFallingHigh.min": a.muteFallingHigh.min == 35,
            "alerts.muteAfterCarbs.on": !a.muteAfterCarbs.on, "alerts.muteAfterCarbs.min": a.muteAfterCarbs.min == 25,
        ]
        let rules = (sampleContent["alerts"] as! [String: Any])["alerts"] as! [String: [String: Any]]
        for k in AlertKind.allCases where k != .noData {
            let r = rules[k.rawValue]!
            c["alerts.alerts.\(k.rawValue).on"] = a[k].on == (r["on"] as! Bool)
            let field = k.group == .rate ? "rate" : "mgdl"
            c["alerts.alerts.\(k.rawValue).\(field)"] = a[k].threshold == (r[field] as! NSNumber).doubleValue
            c["alerts.alerts.\(k.rawValue).repeatMin"] = a[k].repeatMin == (r["repeatMin"] as! Int)
        }
        return c
    }

    static func wire() {
        let d = suite()
        d.set("wearer", forKey: WearerSettings.modeKey)
        WearerSettings.received(sampleJSON, d)
        let checks = wireChecks(graph(d), alerts(d))
        var wires: [String] = []
        for s in SettingsSpec.settings where s.mirror {
            guard let w = s.wire else { check("(f) \(s.id) has a wire key", false); continue }
            wires += w.contains("{kind}") ? AlertKind.allCases.filter { $0 != .noData }.map { w.replacingOccurrences(of: "{kind}", with: $0.rawValue) } : [w]
        }
        for w in wires { check("(f) wire \(w) is read from the wearer's settings", checks[w] == true, checks[w] == nil ? "no check for it" : "not applied") }
        check("(f) every checked wire key is a schema wire key", Set(checks.keys).isSubset(of: Set(wires)), "\(Set(checks.keys).subtracting(wires))")
        drop(d)
    }
}
