// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation

/// `Sukrara -alerts-selftest`: the AlertEngine over the shared scenario timelines with an injected clock
/// (spikes/alerts/SPEC.md §1–§3, §7), plus the settings JSON and sound naming. No notifications, no network.
enum AlertsSelfTest {
    static func run() -> Bool {
        var ok = true
        func check(_ name: String, _ cond: Bool, _ detail: @autoclosure () -> String = "") {
            print("\(cond ? "PASS" : "FAIL")  \(name)\(cond ? "" : "  — " + detail())")
            ok = ok && cond
        }

        // 1. The shared timelines (spikes/alerts/scenarios, SPEC §7): the same files Android's AlertScenarioTest runs.
        let scenarios = AlertScenarios.load()
        check("scenario files found", !scenarios.isEmpty, AlertScenarios.dir()?.path ?? "no spikes/alerts/scenarios above \(#filePath)")
        var passed = 0
        for sc in scenarios {
            let errors = AlertScenarios.run(sc)
            if errors.isEmpty { passed += 1 } else { print("FAIL  \(sc.file): \(sc.title)\n      " + errors.joined(separator: "\n      ")) }
        }
        check("scenarios: \(passed)/\(scenarios.count) pass", passed == scenarios.count)

        // 2. Mac only: a follower mirroring the wearer runs just no_data here.
        var clock = Date(timeIntervalSince1970: 1_760_650_020)
        var log: [AlertEvent] = []
        let e = AlertEngine(settings: AlertSettings(), now: { clock })
        e.onFire = { log.append($0) }
        e.onClear = { log.append($0) }
        e.levelAndRate = false
        e.reading(Reading(date: clock, mgdl: 300, rate: 3))
        check("wearer mode: no level/rate rules here…", log.isEmpty)
        clock += 25 * 60; e.tick()
        check("…but no_data still runs locally", log.map { "\($0.k.rawValue) \($0.state)" } == ["no_data fire"], "\(log.map(\.k))")
        check("no_data carries th and the last reading", log.first.map { $0.th == 20 && $0.sgv == 300 && $0.id.count == 16 } ?? false)

        // 2b. Restart (AlertEngineTest.restartResumesWithoutRingingAgain / restoreDropsEpisodesThatEndedWhileDown):
        //     an update or relaunch must not take an ongoing alert for a new one (docs/15, "Two sounds at once").
        let t0 = Date(timeIntervalSince1970: 1_760_000_040)
        func minute(_ m: Int) -> Date { t0 + TimeInterval(m * 60) }
        /// One 200 mg/dL reading per minute from `start` for `count` minutes; "minute headline" per firing.
        var ids: [String] = []
        func highs(_ e: AlertEngine, _ count: Int, start: Int = 0) -> [String] {
            var out: [String] = [], m = start
            e.now = { minute(m) }
            e.onFire = { out.append("\(m) \($0.k.message(treated: $0.tr))"); ids.append($0.id) }
            e.onClear = { out.append("\(m) clear \($0.k.rawValue)") }
            for i in 0..<count { m = start + i; e.reading(Reading(date: minute(m), mgdl: 200, rate: 0)) }
            return out
        }
        let before = AlertEngine(settings: AlertSettings())
        let first = highs(before, 4)
        check("restart: the high fires once before", first == ["0 High glucose"], "\(first)")
        let after = AlertEngine(settings: AlertSettings())
        after.seed(Reading(date: minute(3), mgdl: 200, rate: 0))
        let shown = after.restore(before.snapshot(), at: minute(4))
        check("restart: the high that was up comes back (shown again silently), same firing",
              shown.map(\.k) == [.high] && shown.first?.id == ids.first, "\(shown.map(\.k))")
        let rest = highs(after, 16, start: 4)
        check("restart: the next sound is the repeat 15 min after the first, not the restart", rest == ["15 High glucose"], "\(rest)")
        check("restart: a fresh engine (the old behaviour) rings at once", highs(AlertEngine(settings: AlertSettings()), 1, start: 4) == ["4 High glucose"])
        let ended = AlertEngine(settings: AlertSettings())
        _ = highs(ended, 1)
        let late = AlertEngine(settings: AlertSettings())
        check("restart: episodes that ended while down are dropped",
              late.restore(ended.snapshot(), at: minute(60)).isEmpty && late.active[.level] == nil)

        // 3. Settings JSON (§3): the spec example, partial and junk input keep defaults, same keys out.
        let spec = """
        {"v":1,"on":true,"alerts":{"very_low":{"on":true,"mgdl":55,"repeatMin":5,"alarm":true},"low":{"on":true,"mgdl":70,"repeatMin":5},
        "high":{"on":true,"mgdl":180,"repeatMin":15},"very_high":{"on":true,"mgdl":250,"repeatMin":10},
        "extremely_high":{"on":true,"mgdl":300,"repeatMin":5,"alarm":true},
        "falling_fast":{"on":false,"rate":2.0,"repeatMin":30},"rising_fast":{"on":false,"rate":2.0,"repeatMin":30},
        "no_data":{"on":true,"min":20,"repeatMin":60}},"muteAfterInsulin":{"on":true,"min":60},
        "muteFallingHigh":{"on":true,"min":20},"muteAfterCarbs":{"on":true,"min":15},"sounds":{"pack":"quest","kinds":{"high":"kart"},"shuffle":true},"source":"wearer"}
        """
        let parsed = try? JSONDecoder().decode(AlertSettings.self, from: Data(spec.utf8))
        var expect = AlertSettings(); expect.sounds.kinds = ["high": "kart"]
        check("SPEC §3 example parses to the defaults (+ kart for high)", parsed == expect)
        let partial = try? JSONDecoder().decode(AlertSettings.self, from: Data(#"{"on":false,"alerts":{"high":{"mgdl":200}},"source":"bogus","sounds":7}"#.utf8))
        check("partial / junk JSON keeps the other defaults", partial.map { !$0.on && $0[.high].threshold == 200 && $0[.high].on && $0[.high].repeatMin == 15
                                                                         && $0.source == "wearer" && $0.sounds.pack == "quest" && $0[.low] == AlertKind.low.defaultRule } ?? false)
        let out = (try? JSONSerialization.jsonObject(with: Data(AlertSettings().json.utf8))) as? [String: Any] ?? [:]
        let keys = Set(out.keys), ruleKeys = Set(((out["alerts"] as? [String: Any])?["no_data"] as? [String: Any] ?? [:]).keys)
        check("written JSON has the §3 keys", keys == ["v", "on", "alerts", "muteAfterInsulin", "muteFallingHigh", "muteAfterCarbs", "sounds", "notificationActions", "source"]
              && ruleKeys == ["on", "min", "repeatMin", "alarm"], "\(keys) \(ruleKeys)")
        check("very low and extremely high default to alarm, the rest to notification", AlertSettings()[.veryLow].alarm
              && AlertSettings()[.extremelyHigh].alarm && !AlertSettings()[.low].alarm && !AlertSettings()[.high].alarm && !AlertSettings()[.noData].alarm)
        let old = try? JSONDecoder().decode(AlertSettings.self, from: Data(#"{"v":1,"alerts":{"urgent_low":{"mgdl":40}}}"#.utf8))
        check("the old urgent_low key is ignored", old?[.veryLow] == AlertKind.veryLow.defaultRule)
        let flipped = try? JSONDecoder().decode(AlertSettings.self,
            from: Data(#"{"v":1,"alerts":{"very_low":{"alarm":false},"low":{"alarm":true}}}"#.utf8))
        check("\"alarm\" is read per alert", flipped?[.veryLow].alarm == false && flipped?[.low].alarm == true && flipped?[.low].mgdl == 70)
        check("round trip", (try? JSONDecoder().decode(AlertSettings.self, from: Data(expect.json.utf8))) == expect)

        // 4. Sounds: pack, per-kind override, system/none, shuffle off = variant 1, files exist for every combination.
        var s = AlertSettings(); s.sounds = AlertSounds(pack: "quest", kinds: ["high": "kart", "low": "none", "no_data": "system"], shuffle: false)
        check("pack + overrides + no shuffle", Alerts.sound(for: .veryLow, s).label == "quest_very_low_1.caf"
              && Alerts.sound(for: .high, s).label == "kart_high_1.caf" && Alerts.sound(for: .low, s).label == "none"
              && Alerts.sound(for: .noData, s).label == "system")
        s.sounds.shuffle = true
        let variants = Set((0..<60).map { _ in Alerts.sound(for: .veryHigh, s).label })
        check("shuffle picks variants 1–3", variants == ["quest_very_high_1.caf", "quest_very_high_2.caf", "quest_very_high_3.caf"], "\(variants)")
        let missing = AlertSettings.packs.flatMap { p in AlertKind.allCases.flatMap { k in (1...3).map { "\(p)_\(k.rawValue)_\($0).caf" } } }
            .filter { Alerts.soundURL($0) == nil }
        check("all 96 sound files found (bundle or repo)", missing.isEmpty, "missing \(missing.prefix(3))")

        // 5. A wearer's §4 body parses; junk doesn't.
        let body: [String: Any] = ["v": 2, "type": "alert", "id": "0123456789abcdef", "k": "high", "state": "fire", "at": 1760650000,
                                   "t": 1760649990, "sgv": 192, "r": -0.4, "th": 180, "n": 2, "tr": 1]
        check("§4 alert body parses", AlertEvent(body: body).map { $0.k == .high && $0.n == 2 && $0.sgv == 192 && $0.th == 180 && $0.tr == 1 } ?? false)
        check("…its headline", AlertEvent(body: body)?.k.message(treated: 1) == "High glucose · still high after insulin")
        var plain = body; plain["tr"] = nil
        check("no \"tr\": treated 0", AlertEvent(body: plain)?.tr == 0)
        var bad = body; bad["k"] = "meteor"
        check("unknown kind rejected", AlertEvent(body: bad) == nil)

        print(ok ? "alerts self-test OK" : "alerts self-test FAILED")
        // 6. The card's status (CardStatusTest.kt): units, durations, AlertStatus, LastTreatment, badge, slot priority,
        //    the graph settings' lastPlace / lastElse keys.
        let now: TimeInterval = 1_800_000_000
        let st = AlertSettings()
        func tr(_ k: String, _ a: Double, _ minAgo: Int) -> GraphMark { GraphMark(t: now - TimeInterval(minAgo * 60), k: k, a: a) }
        func rd(_ v: Double) -> GraphReading { GraphReading(t: now - 30, v: v) }
        check("units 4.5u 18u 30g", [tr("rapid", 4.5, 0), tr("long", 18, 0), tr("carbs", 30, 0)].map(TreatmentMarks.amount) == ["4.5u", "18u", "30g"]
              && TreatmentMarks.amount(tr("note", 0, 0)) == nil)
        let durs = [TreatmentMarks.duration(30), TreatmentMarks.duration(12 * 60 + 40), TreatmentMarks.duration(65 * 60),
                    TreatmentMarks.duration(3 * 3600), TreatmentMarks.duration(7 * 3600 + 50 * 60), TreatmentMarks.duration(50 * 3600),
                    TreatmentMarks.duration(10, up: true)]
        check("durations", durs == ["now", "12m", "1h 5m", "3h", "7h", "2d", "1m"], "\(durs)")
        check("last: a meal at the same minute shows the insulin",
              LastTreatment.of([tr("carbs", 45, 12), tr("rapid", 4.5, 12)], st, .none, now: now)?.tr.k == "rapid")
        check("last: long and notes never", LastTreatment.of([tr("long", 18, 5), tr("note", 0, 1)], st, .none, now: now) == nil)
        check("last: kept 4 h rapid / carbs 3 min per gram, 30 min to 3 h",
              LastTreatment.of([tr("rapid", 6, 241), tr("carbs", 20, 61)], st, .none, now: now) == nil
              && LastTreatment.of([tr("rapid", 6, 200), tr("carbs", 20, 59)], st, .none, now: now)?.tr.a == 20
              && LastTreatment.of([tr("carbs", 5, 29)], st, .none, now: now)?.tr.a == 5
              && LastTreatment.of([tr("carbs", 5, 31)], st, .none, now: now) == nil
              && LastTreatment.of([tr("carbs", 80, 181)], st, .none, now: now) == nil)
        var noInsulinMute = st; noInsulinMute.muteAfterInsulin.on = false
        check("last: ring = share of the mute left",
              abs((LastTreatment.of([tr("rapid", 4.5, 12)], st, .none, now: now)?.left(now) ?? 0) - 0.8) < 0.001
              && LastTreatment.of([tr("carbs", 15, 20)], st, .none, now: now)?.left(now) == 0
              && LastTreatment.of([tr("rapid", 4.5, 12)], noInsulinMute, .none, now: now)?.left(now) == 0)
        let carbs = tr("carbs", 15, 8)
        check("last: rapid insulin first while its mute runs",
              LastTreatment.of([carbs, tr("rapid", 1, 2)], st, .taken(.low, treatment: carbs, until: now + 420), now: now)?.tr.k == "rapid")
        check("last: the muting treatment wins over a newer one",
              LastTreatment.of([carbs, tr("rapid", 1, 2)], noInsulinMute, .taken(.low, treatment: carbs, until: now + 420), now: now)?.tr == carbs)
        check("badge: Eat carbs / Take insulin / none",
              StatusBadge.look(AlertStatus.of(rd(60), st, [], now: now), now: now)?.text == "Eat carbs"
              && StatusBadge.look(AlertStatus.of(rd(220), st, [], now: now), now: now)?.text == "Take insulin"
              && StatusBadge.look(AlertStatus.of(rd(120), st, [], now: now), now: now) == nil)
        check("badge: muted 9m left", StatusBadge.look(AlertStatus.of(rd(60), st, [tr("carbs", 15, 6)], now: now), now: now)
              == StatusBadge.Look(kind: "carbs", text: "9m left", short: "9m", tonal: true))
        check("badge: extremely high never muted", AlertStatus.of(rd(318), st, [tr("rapid", 4, 10)], now: now) == .needed(.extremelyHigh))
        check("badge: same level as the engine",
              AlertStatus.of(rd(69), st, [], now: now) == .needed(.low) && AlertStatus.of(GraphReading(t: now - 16 * 60, v: 40), st, [], now: now) == .none)
        check("mute line ends: rapid +60, carbs +15, long none",
              AlertStatus.muteUntil(tr("rapid", 1, 0), st) == now + 3600 && AlertStatus.muteUntil(tr("carbs", 1, 0), st) == now + 900
              && AlertStatus.muteUntil(tr("long", 1, 0), st) == nil && AlertStatus.muteUntil(tr("rapid", 1, 0), noInsulinMute) == nil)
        let m = GlucoseMetrics.of([], from: 0, to: 1, bands: Bands())
        let rings = [(0, RingSpec(window: 86400), m), (1, RingSpec(window: 7 * 86400), m), (2, RingSpec(window: 14 * 86400), m)]
        let info = LastTreatment.Info(tr: tr("rapid", 4.5, 5), until: now + 55 * 60)
        let fb = (RingSpec(window: 30 * 86400), m)
        func names(_ l: [SlotItem]) -> [String] {
            l.map { if case let .metric(s, _, _) = $0 { "\(Int(s.window) / 86400)d" } else { "last" } }
        }
        check("slots: right to left, the treatment pushes the second ring out",
              names(Slots.arrange(rings, place: .nearValue, last: info, fallback: fb, max: 2)) == ["1d", "last"]
              && names(Slots.arrange(rings, place: .nearValue, last: info, fallback: fb, max: 3)) == ["1d", "7d", "last"]
              && names(Slots.arrange(rings, place: .end, last: info, fallback: fb, max: 3)) == ["last", "1d", "7d"])
        check("slots: the fallback only fills spare room",
              names(Slots.arrange(rings, place: .nearValue, last: nil, fallback: fb, max: 2)) == ["1d", "7d"]
              && names(Slots.arrange(rings, place: .nearValue, last: nil, fallback: fb, max: 3)) == ["1d", "7d", "30d"]
              && names(Slots.arrange(rings, place: .off, last: info, fallback: fb, max: 3)) == ["1d", "7d", "14d"]
              && !names(Slots.arrange(rings, place: .nearValue, last: nil, fallback: nil, max: 4)).contains("last"))
        let ud = UserDefaults(suiteName: "sukrara-selftest-\(UUID().uuidString)")!
        let dflt = GraphConfig.load(ud)
        ud.set("END", forKey: "lastPlace"); ud.set("GMI:1209600:0", forKey: "lastElse")
        let set = GraphConfig.load(ud)
        ud.set("", forKey: "lastElse")
        check("settings: lastPlace / lastElse (Android spellings)",
              dflt.lastPlace == .nearValue && dflt.lastElse == GraphConfig.defaultLastElse
              && set.lastPlace == .end && set.lastElse == RingSpec(window: 14 * 86400, kind: .gmi, faded: false)
              && GraphConfig.load(ud).lastElse == nil)
        return ok
    }
}

/// The shared alert timelines (spikes/alerts/scenarios/*.txt, format and driver in spikes/alerts/SPEC.md §7): the same
/// parser and driver loop as Android's AlertScenarioTest, so both engines must produce the same headlines and clears,
/// minute by minute, in order.
enum AlertScenarios {
    struct Line { var minute: Int, at: Int, events: [String], expect: [String] }
    struct Scenario { var file: String, title: String, settings: [String], lines: [Line], end: Int }

    static let t0 = 1_760_000_040     // a whole minute

    /// spikes/alerts/scenarios, found by walking up from this source file.
    static func dir() -> URL? {
        var u = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        let fm = FileManager.default
        while u.path != "/" {
            for sub in ["spikes/alerts/scenarios", "alerts/scenarios"] {
                var isDir: ObjCBool = false
                let d = u.appendingPathComponent(sub)
                if fm.fileExists(atPath: d.path, isDirectory: &isDir), isDir.boolValue { return d }
            }
            u.deleteLastPathComponent()
        }
        return nil
    }

    static func load() -> [Scenario] {
        guard let d = dir(), let names = try? FileManager.default.contentsOfDirectory(atPath: d.path) else { return [] }
        return names.filter { $0.hasSuffix(".txt") }.sorted().flatMap { n in
            parse(n, (try? String(contentsOf: d.appendingPathComponent(n), encoding: .utf8)) ?? "")
        }
    }

    static func parse(_ file: String, _ text: String) -> [Scenario] {
        func trim(_ s: some StringProtocol) -> String { s.trimmingCharacters(in: .whitespaces) }
        var out: [Scenario] = []
        var title: String?, settings: [String] = [], lines: [Line] = [], end: Int?
        func flush() {
            guard let t = title else { return }
            out.append(Scenario(file: file, title: t, settings: settings, lines: lines, end: end ?? lines.map(\.minute).max() ?? 0))
            title = nil; settings = []; lines = []; end = nil
        }
        for raw in text.components(separatedBy: "\n") {
            let l = trim(raw.split(separator: "#", maxSplits: 1, omittingEmptySubsequences: false).first ?? "")
            if l.isEmpty { continue }
            if l.hasPrefix("scenario ") { flush(); title = trim(l.dropFirst(9)) }
            else if l.hasPrefix("settings ") { settings = trim(l.dropFirst(9)).split(whereSeparator: \.isWhitespace).map(String.init) }
            else if l.hasPrefix("end ") { end = Int(trim(l.dropFirst(4))) }
            else {
                let (left, right) = l.range(of: "=>").map { (trim(l[..<$0.lowerBound]), String(l[$0.upperBound...])) } ?? (l, "")
                let time = String(left.prefix { $0 != " " })
                let ms = time.split(separator: ":").map { Int($0) ?? 0 }
                let at = ms[0] * 60 + (ms.count > 1 ? ms[1] : 0)
                let events = left.dropFirst(time.count).split(separator: ",").map(trim).filter { !$0.isEmpty }
                let expect = right.split(separator: ";").map(trim).filter { !$0.isEmpty && $0 != "nothing" }
                precondition(!lines.contains { $0.minute == at / 60 }, "\(file): two lines for minute \(at / 60)")
                lines.append(Line(minute: at / 60, at: at, events: events, expect: expect))
            }
        }
        flush()
        return out
    }

    /// One `key=value` of a `settings` line or `set` event on top of `s`.
    static func set(_ s: inout AlertSettings, _ kv: String) {
        let p = kv.split(separator: "=", maxSplits: 1).map(String.init)
        let (k, v) = (p[0], p[1])
        let mute = AlertMute(on: v != "off", min: Double(v) ?? 0)
        switch k {
        case "alerts": s.on = v == "on"
        case "carbs.mute": s.muteAfterCarbs = v == "off" ? AlertMute(on: false, min: s.muteAfterCarbs.min) : mute
        case "insulin.mute": s.muteAfterInsulin = v == "off" ? AlertMute(on: false, min: s.muteAfterInsulin.min) : mute
        case "falling.mute": s.muteFallingHigh = v == "off" ? AlertMute(on: false, min: s.muteFallingHigh.min) : mute
        case "mute.min": s.notificationActions.muteMin = Int(v)!
        case "slow.min": s.notificationActions.slowMin = Int(v)!
        default:
            let parts = k.split(separator: ".", maxSplits: 1).map(String.init)
            guard let kind = AlertKind(rawValue: parts[0]) else { fatalError("unknown setting \(kv)") }
            if parts.count == 1 {
                switch v { case "on": s[kind].on = true; case "off": s[kind].on = false; default: s[kind].threshold = Double(v)! }
            } else if parts[1] == "repeat" { s[kind].repeatMin = Int(v)! } else { fatalError("unknown setting \(kv)") }
        }
    }

    /// Runs `sc` (driver: SPEC.md §7); returns one line per minute that differs, empty when it all matches.
    static func run(_ sc: Scenario) -> [String] {
        var s = AlertSettings()
        for kv in sc.settings { set(&s, kv) }
        var clock = Date(timeIntervalSince1970: TimeInterval(t0))
        var trs: [(k: String, t: Date, del: Bool)] = []
        var got: [Int: [String]] = [:]
        var sensorInfo: SensorInfo?
        func record(_ what: String) { got[Int(clock.timeIntervalSince1970) / 60 - t0 / 60, default: []].append(what) }
        func engine() -> AlertEngine {
            let e = AlertEngine(settings: s, now: { clock })
            e.sensor = { sensorInfo }
            e.rapidInsulin = { trs.filter { $0.k == "rapid" && !$0.del }.map(\.t) }
            e.carbs = { trs.filter { $0.k == "carbs" && !$0.del }.map(\.t) }
            e.onFire = { record($0.k.message(treated: $0.tr)) }
            e.onClear = { record("clear \($0.k.rawValue)") }
            return e
        }
        var e = engine()
        var glucose = 0.0, rate = 0.0, age = 0, sensor = true, every = 1
        var nextReading: Int?, timer: Int?
        let byTime = Dictionary(sc.lines.map { (t0 + $0.at, $0) }, uniquingKeysWith: { a, _ in a })
        let stop = t0 + (sc.end + 1) * 60
        var t = t0
        while t < stop {
            clock = Date(timeIntervalSince1970: TimeInterval(t))
            for ev in byTime[t]?.events ?? [] {
                let w = ev.split(whereSeparator: \.isWhitespace).map(String.init)
                func after(_ key: String) -> String? { w.firstIndex(of: key).flatMap { $0 > 0 ? w[$0 + 1] : nil } }
                switch w[0] {
                case "glucose":
                    glucose = Double(w[1])!
                    rate = after("rate").flatMap(Double.init) ?? 0
                    age = after("age").flatMap(Int.init) ?? 0
                    if nextReading == nil { nextReading = t }
                case "readings": every = Int(w[2])!
                case "sensor":
                    let at = TimeInterval(t)
                    switch w[1] {
                    // round sensor-life: the sensor ends now (no more readings) / a new one starts now (60 min warm-up)
                    case "ended": sensor = false; sensorInfo = SensorInfo(start: at - 15 * 86400, end: at, warmupEnd: at - 15 * 86400 + 3600); e.tick()
                    case "started": sensor = false; sensorInfo = SensorInfo(start: at, end: at + 15 * 86400, warmupEnd: at + 3600); e.tick()
                    default:
                        sensor = w[1] == "on"
                        if sensor { nextReading = t }
                    }
                case "carbs", "insulin":
                    trs.append((w[0] == "carbs" ? "carbs" : "rapid", clock, false))
                    e.tick()
                case "delete":
                    let k = w[1] == "carbs" ? "carbs" : "rapid"
                    if let i = trs.lastIndex(where: { $0.k == k && !$0.del }) { trs[i].del = true }
                    e.tick()
                // the alert notification's actions: Mute (the setting's minutes, or typed ones), Slow down
                case "mute": e.mute(AlertKind(rawValue: w[1])!, min: w.count > 2 ? Int(w[2])! : s.notificationActions.muteMin)
                case "slow": e.slow(AlertKind(rawValue: w[1])!)
                case "set":
                    for kv in w.dropFirst() { set(&s, kv) }
                    e.settings = s
                    e.tick()
                case "restart":
                    let saved = e.snapshot(), last = e.latest
                    e = engine()
                    e.seed(last)
                    for f in e.restore(saved, at: clock) { record("resume \(f.k.rawValue)") }
                default: fatalError("unknown event: \(ev)")
                }
            }
            if sensor, let r = nextReading, r <= t {
                e.reading(Reading(date: clock.addingTimeInterval(TimeInterval(-age * 60)), mgdl: glucose, rate: rate))
                nextReading = t + every * 60
            } else if let tm = timer, tm <= t { e.tick() }
            timer = e.nextCheckAt(clock).map { Int($0.timeIntervalSince1970.rounded(.up)) }
            guard let next = [byTime.keys.filter { $0 > t }.min(), sensor ? nextReading : nil, timer].compactMap({ $0 }).min() else { break }
            t = next
        }
        let expected = Dictionary(sc.lines.map { ($0.minute, $0.expect) }, uniquingKeysWith: { a, _ in a })
        return (0...sc.end).compactMap { m in
            let want = expected[m] ?? [], have = got[m] ?? []
            func show(_ l: [String]) -> String { l.isEmpty ? "[nothing]" : "[\(l.joined(separator: ", "))]" }
            return want == have ? nil : "minute \(m): expected \(show(want)), got \(show(have))"
        }
    }
}
