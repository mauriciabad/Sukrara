// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import AppKit

/// CLI checks, no network and no Bluetooth:
///   Sukrara -treatmentsselftest   merge rule, the local store
enum SelfTest {
    static func merge() -> Bool {
        var failures = 0
        func check(_ name: String, _ cond: Bool) { if !cond { print("FAIL \(name)"); failures += 1 } }
        let base = Treatment(id: "a", k: "rapid", a: 2, t: 100, u: 1000, o: "phone")
        var newer = base; newer.a = 3; newer.u = 2000; newer.o = "mac"
        var older = base; older.a = 9; older.u = 500
        var tie = base; tie.a = 4; tie.o = "mac"                     // same u: "phone" > "mac", so base wins

        check("insert unknown", Treatment.merge(nil, base) == base)
        check("newer u wins", Treatment.merge(base, newer).a == 3)
        check("older u loses", Treatment.merge(base, older).a == 2)
        check("tie broken by o", Treatment.merge(base, tie).a == 2 && Treatment.merge(tie, base).a == 2)
        check("same row unchanged", Treatment.merge(base, base) == base)
        check("note truncated to 100 B", Treatment(k: "note", a: 0, t: 0, n: String(repeating: "é", count: 80)).n.utf8.count == 100)
        MainActor.assumeIsolated {
            let dir = FileManager.default.temporaryDirectory.appendingPathComponent("sukrara-selftest-\(UUID().uuidString)")
            defer { try? FileManager.default.removeItem(at: dir) }
            let st = TreatmentStore(directory: dir)
            let mine = Treatment(k: "carbs", a: 10, t: 100)
            st.upsertLocal(mine)
            check("delete leaves a newer tombstone", st.delete(mine.id).map { $0.del && $0.u > mine.u } ?? false)
            check("older copy from v2 doesn't undo the delete", !st.receive(mine) && st.row(mine.id)?.del == true)
            check("persisted", TreatmentStore(directory: dir).row(mine.id)?.del == true)
        }
        print(failures == 0 ? "treatments selftest OK" : "treatments selftest: \(failures) failure(s)")
        return failures == 0
    }
}

/// `Sukrara -graphselftest`: the shared graph math (SukraraGraph.swift) against the same cases as the Android unit
/// test (GlucoseStatsTest.kt), so both renderers agree.
enum GraphSelfTest {
    static func run() -> Bool {
        var failures = 0
        func check(_ name: String, _ cond: Bool) { if !cond { print("FAIL \(name)"); failures += 1 } }
        func near(_ a: Double, _ b: Double, _ eps: Double) -> Bool { abs(a - b) <= eps }
        let b = Bands()
        func r(_ min: Int, _ v: Int) -> GraphReading { GraphReading(t: TimeInterval(min * 60), v: Double(v)) }

        check("bands", b.of(53) == .veryLow && b.of(54) == .low && b.of(69) == .low && b.of(70) == .tight && b.of(140) == .tight
              && b.of(141) == .target && b.of(180) == .target && b.of(181) == .high && b.of(250) == .high && b.of(251) == .veryHigh)
        let vs = Bands(veryLow: 90, low: 70, tight: 140, high: 180, veryHigh: 900).sane()
        check("sane very", vs.veryLow == 65 && vs.veryHigh == 400)
        check("sane", Bands(low: 200, tight: 10, high: 90).sane() == Bands(low: 120, tight: 130, high: 140))

        let oneMin = (0..<60).map { r($0, 100) }
        let fiveMin = stride(from: 60, to: 120, by: 5).map { r($0, 200) }
        let t = GlucoseStats.timeInRange(oneMin + fiveMin, from: 0, to: 120 * 60, bands: b)
        check("time weighted", near(t.tight, 0.5, 0.01) && near(t.high, 0.5, 0.01) && near(t.coverage, 1, 0.01))

        let g = GlucoseStats.timeInRange([r(0, 100), r(1, 100), r(120, 250), r(121, 250)], from: 0, to: 122 * 60, bands: b)
        let inTight: Double = 60 + 15 * 60, total: Double = inTight + 60 + 60      // typed: CI's compiler times out
        check("gaps", near(g.tight, inTight / total, 0.01) && g.coverage < 0.2)

        let rs = (0..<600).map { r($0, Int(120 + 80 * sin(Double($0) / 40))) }
        let tir = GlucoseStats.timeInRange(rs, from: 0, to: 600 * 60, bands: b)
        let p = GlucoseStats.timeAtLevel(rs, from: 0, to: 600 * 60, bands: b, tir: tir)
        for band in Band.allCases {
            let area = p.indices.filter { b.of(Double(GlucoseStats.minMg + $0)) == band }.reduce(0) { $0 + p[$1] }
            check("band-exact \(band)", near(area, tir.share(band), 1e-9))
        }
        check("time at level sums to 1", near(p.reduce(0, +), 1, 1e-6))
        check("below/above", near(tir.below, tir.veryLow + tir.low, 1e-12) && near(tir.above, tir.high + tir.veryHigh, 1e-12)
              && near(tir.below + tir.inRange + tir.above, 1, 1e-9))
        check("pct3 a", GlucoseGraph.pct3(1 / 3.0, 1 / 3.0 + 0.0001, 1 / 3.0 - 0.0001) == (33, 34, 33))
        check("pct3 b", GlucoseGraph.pct3(0.1, 0.7, 0.2) == (10, 70, 20))
        check("freshness", Freshness.of(age: 90) == .fresh && Freshness.of(age: 91) == .late && Freshness.of(age: 301) == .stale
              && Freshness.of(age: 1201) == .old && Freshness.of(age: 3 * 3600 + 1) == .gone && Freshness.of(age: nil) == .gone)
        // Investigation 18: report metrics. A constant 141 mg/dL: GMI 6.68 %, 49.5 mmol/mol, CV 0.
        let flat = GlucoseMetrics.of((0..<120).map { r($0, 141) }, from: 0, to: 120 * 60, bands: b)
        check("GMI 141", near(flat.gmi, 6.683, 0.001) && near(flat.gmiMmolMol, 49.5, 0.05) && near(flat.cv, 0, 1e-9) && near(flat.mean, 141, 1e-9))
        // Time-weighted: 1 h at 100 (1-min readings) + 1 h at 200 (5-min) → mean 150, SD 50, CV 33.3 %.
        let tw = GlucoseMetrics.of(oneMin.map { GraphReading(t: $0.t, v: $0.v) } + fiveMin, from: 0, to: 120 * 60, bands: b)
        check("time-weighted mean/SD/CV", near(tw.mean, 150, 0.5) && near(tw.sd, 50, 0.5) && near(tw.cv, 33.3, 0.3))
        check("goals", Goal.gmi.met(flat) && Goal.average.met(flat) && Goal.cv.met(flat) && Goal.cv.met(tw))
        let slots = GraphConfig.parseSlots("TIR:86400:1,CV:2592000:0,GMI:1209600:1")
        check("slots", slots?[1].kind == .cv && slots?[1].window == 2592000 && slots?[1].faded == false
              && GraphConfig.slotsText(GraphConfig.defaultSlots) == "TIR:86400:1,TIR:604800:1,GMI:1209600:1")
        // Custom periods (schema ringWindow, Android CustomValuesTest): whole days 1–90 besides the presets; anything
        // else is coerced to the nearest one (91 d → 90 d, 2 h → 1 d, 1.5 d → 2 d).
        check("custom slot periods", GraphConfig.parseSlot("TIR:432000:1")?.window == 432000 && GraphConfig.parseSlot("GMI:7776000:1") != nil
              && GraphConfig.parseSlot("TIR:7862400:1")?.window == 7776000 && GraphConfig.parseSlot("TIR:7200:1")?.window == 86400
              && GraphConfig.parseSlot("TIR:129600:1")?.window == 172800)
        // Ring order (the user's): very good, in range, low, very low, high, very high; a bad setting falls back.
        check("ring order default", DEFAULT_RING_ORDER == [.tight, .target, .low, .veryLow, .high, .veryHigh]
              && ringOrderText(DEFAULT_RING_ORDER) == "TIGHT,TARGET,LOW,VERY_LOW,HIGH,VERY_HIGH"
              && GraphConfig().ringOrder == DEFAULT_RING_ORDER && GraphConfig().rings.allSatisfy { $0.order == DEFAULT_RING_ORDER })
        check("ring order parse", parseRingOrder("HIGH,VERY_HIGH,TARGET,TIGHT,LOW,VERY_LOW") == [.high, .veryHigh, .target, .tight, .low, .veryLow]
              && parseRingOrder("TIGHT,TARGET,LOW,VERY_LOW,VERY_HIGH") == nil && parseRingOrder("TIGHT,TIGHT,LOW,VERY_LOW,VERY_HIGH,HIGH") == nil)
        failures += GraphFixtures.run()
        print(failures == 0 ? "graph self-test OK" : "graph self-test: \(failures) failed")
        return failures == 0
    }
}

/// The shared card decisions (spikes/graph-fixtures/*.json, format in its README), the same files Android's
/// GraphFixtureTest.kt runs: status, badge, last-treatment slot, amount labels. Found by walking up from the working
/// directory (run from the repo). Returns the number of failures.
enum GraphFixtures {
    static func run() -> Int {
        var dir = URL(fileURLWithPath: FileManager.default.currentDirectoryPath)
        while !FileManager.default.fileExists(atPath: dir.appendingPathComponent("spikes/graph-fixtures").path) {
            if dir.path == "/" { print("FAIL graph fixtures: spikes/graph-fixtures not found above the working directory"); return 1 }
            dir.deleteLastPathComponent()
        }
        dir.appendPathComponent("spikes/graph-fixtures")
        let now: TimeInterval = 1_800_000_000
        var failures = 0, n = 0
        func check(_ name: String, _ ok: Bool, _ got: Any?) { if !ok { print("FAIL \(name) (got \(got ?? "nil"))"); failures += 1 } }
        let files = ((try? FileManager.default.contentsOfDirectory(atPath: dir.path)) ?? []).filter { $0.hasSuffix(".json") }.sorted()
        for f in files {
            guard let data = FileManager.default.contents(atPath: dir.appendingPathComponent(f).path),
                  let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                  let cases = root["cases"] as? [[String: Any]] else { print("FAIL \(f): unreadable"); failures += 1; continue }
            for c in cases {
                let name = "\(f): \(c["name"] as? String ?? "?")"
                var settings = AlertSettings()
                if let js = c["settings"], let d = try? JSONSerialization.data(withJSONObject: js),
                   let s = try? JSONDecoder().decode(AlertSettings.self, from: d) { settings = s }
                let rd = c["reading"] as? [String: Any] ?? [:]
                let r = GraphReading(t: now + ((rd["t"] as? Double) ?? 0), v: (rd["sgv"] as? Double) ?? 0)
                var ids: [Int: String] = [:]
                let marks: [GraphMark] = (c["treatments"] as? [[String: Any]] ?? []).enumerated().map { i, j in
                    ids[i] = j["id"] as? String
                    return GraphMark(t: now + ((j["t"] as? Double) ?? 0), k: j["k"] as? String ?? "", a: (j["a"] as? Double) ?? 0, n: j["n"] as? String ?? "")
                }
                func id(_ m: GraphMark) -> String? { marks.firstIndex(of: m).flatMap { ids[$0] } }
                let e = c["expect"] as? [String: Any] ?? [:]
                // the alert notification's Mute in force: side → seconds from now
                let paused = (c["paused"] as? [String: Double] ?? [:]).mapValues { now + $0 }
                // this Mac's Mute all: seconds from now or "never"
                func end(_ a: Any?) -> TimeInterval? { a as? String == "never" ? AlertStatus.never : (a as? Double).map { now + $0 } }
                let allMuted = end(c["allMuted"]) ?? 0
                let st = AlertStatus.of(r, settings, marks, now: now, paused: paused, allMuted: allMuted)
                let wantUntil = end(e["until"]) ?? .nan
                let stText: String
                switch st {
                case .muted(let until):
                    stText = "muted"
                    check("\(name): until", until == wantUntil, until - now)
                case .none: stText = "none"
                case .needed(let k): stText = "needed:\(k.rawValue)"
                case .paused(let k, let until):
                    stText = "paused:\(k.rawValue)"
                    check("\(name): until", until == wantUntil, until - now)
                case .taken(let k, let tr, let until):
                    stText = "taken:\(k.rawValue):\(id(tr) ?? "?")"
                    check("\(name): until", until == now + ((e["until"] as? Double) ?? .nan), until - now)
                }
                check("\(name): status", stText == e["status"] as? String, stText)
                let look = StatusBadge.look(st, now: now)
                if let b = e["badge"] as? [String: Any] {
                    let want = StatusBadge.Look(kind: b["kind"] as? String ?? "", text: b["text"] as? String ?? "",
                                                short: b["short"] as? String ?? "", tonal: b["tonal"] as? Bool ?? false)
                    check("\(name): badge", look == want, look)
                } else { check("\(name): badge", look == nil, look) }
                let last = LastTreatment.of(marks, settings, st, now: now)
                if let l = e["last"] as? [String: Any] {
                    check("\(name): last id", last.flatMap { id($0.tr) } == l["id"] as? String, last.flatMap { id($0.tr) })
                    let until = (l["until"] as? Double).map { now + $0 }
                    check("\(name): last until", last?.until == until, last?.until.map { $0 - now })
                    let w = l["with"] as? [String: Any]
                    check("\(name): last with", last?.carbs.flatMap { id($0.tr) } == w?["id"] as? String, last?.carbs.flatMap { id($0.tr) })
                    if let w { check("\(name): last with until", last?.carbs?.until == (w["until"] as? Double).map { now + $0 }, last?.carbs?.until.map { $0 - now }) }
                } else { check("\(name): last", last == nil, last) }
                for (key, want) in e["amounts"] as? [String: Any] ?? [:] {
                    guard let m = marks.first(where: { id($0) == key }) else { check("\(name): amount \(key) exists", false, nil); continue }
                    let got = TreatmentMarks.amount(m)
                    check("\(name): amount \(key)", got == want as? String, got)
                }
                n += 1
            }
        }
        print("graph fixtures: \(n) cases, \(failures) failed")
        return failures + labels(dir.appendingPathComponent("labels")) + sensor(dir.appendingPathComponent("sensor"))
            + clock(dir.appendingPathComponent("clock")) + share(dir.appendingPathComponent("share"))
            + freshness(dir.appendingPathComponent("freshness/freshness.json"))
            + mark(dir.appendingPathComponent("mark")) + place(dir.appendingPathComponent("place/place.json"))
            + MainActor.assumeIsolated { LayoutSelfTest.run(dir) }
            + MainActor.assumeIsolated { ScrollSelfTest.run() }
    }

    /// The freshness steps (spikes/graph-fixtures/freshness/freshness.json, the file Android's FreshnessFixtureTest
    /// runs): `Freshness.look` at every step boundary, the redraw times, the card (its layout report) and every Mac
    /// glyph surface of the one registry (surfaces/surfaces.json) through its own code (the menubar title). A
    /// registered glyph surface without an adapter here fails; the card surfaces are rendered by LayoutSelfTest.
    static func freshness(_ file: URL) -> Int {
        let now: TimeInterval = 1_800_000_000
        guard let data = FileManager.default.contents(atPath: file.path),
              let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let cases = root["cases"] as? [[String: Any]] else { print("FAIL freshness fixtures: unreadable"); return 1 }
        var failures = 0, n = 0
        func fail(_ s: String) { print("FAIL freshness \(s)"); failures += 1 }
        func num(_ a: Any?) -> Double? { (a as? NSNumber)?.doubleValue }
        // the redraw times
        if let st = root["steps"] as? [String: Any], let from = st["from"] as? [Any], let next = st["next"] as? [Any] {
            for (f, x) in zip(from, next) {
                let got = Freshness.nextChangeAt(0, now: num(f) ?? -1)
                if got != num(x) { fail("step after \(f): got \(got.map { "\($0)" } ?? "nil")") }
            }
        } else { fail("steps missing") }
        // one adapter per Mac surface in the registry
        typealias Adapter = ([String: Any], [String: Any]) -> [String]
        let adapters: [String: Adapter] = ["menubar-title": freshnessTitle]
        let reg = FileManager.default.contents(atPath: file.deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("surfaces/surfaces.json").path).flatMap { try? JSONSerialization.jsonObject(with: $0) as? [String: Any] }
        let ids = Set(((reg?["mac"] as? [String: Any])?["surfaces"] as? [[String: Any]] ?? [])
            .filter { $0["kind"] as? String == "glyph" }.compactMap { $0["id"] as? String })
        if ids != Set(adapters.keys) { fail("registry glyph surfaces \(ids.sorted()) vs adapters \(adapters.keys.sorted())") }
        for c in cases {
            let name = c["name"] as? String ?? "?"
            let e = c["expect"] as? [String: Any] ?? [:]
            let age = num(c["age"]), sgv = num(c["sgv"]).map { Int($0) }
            let l = Freshness.look(sgv: sgv, t: age.map { now - $0 }, rate: num(c["rate"]), now: now, ended: c["ended"] as? Bool ?? false)
            let got = "\(l.state.rawValue) \(l.text ?? "nil") \(l.strike) \(l.trend?.rawValue ?? "nil") \(l.fresh.showTime) \(l.fresh.showGraph)"
            let want = "\(e["state"] as? String ?? "?") \(e["text"] as? String ?? "nil") \(e["strike"] as? Bool ?? false) \(e["trend"] as? String ?? "nil") \(e["time"] as? Bool ?? false) \(e["graph"] as? Bool ?? false)"
            if got != want { fail("\(name): look \(got), want \(want)") }
            for m in freshnessCard(c, e) { fail("card · \(name): \(m)") }
            for (id, a) in adapters { for m in a(c, e) { fail("\(id) · \(name): \(m)") } }
            n += 1
        }
        print("freshness fixtures: \(n) cases × the card + \(adapters.count) glyph surfaces, \(failures) failed")
        return failures
    }

    /// The card (menubar dropdown, widgets): drawn into a bitmap; what it laid out is its `CardLayout`.
    static func freshnessCard(_ c: [String: Any], _ e: [String: Any]) -> [String] {
        let now: TimeInterval = 1_800_000_000
        var out: [String] = []
        func num(_ a: Any?) -> Double? { (a as? NSNumber)?.doubleValue }
        let state = e["state"] as? String ?? "?", text = e["text"] as? String, strike = e["strike"] as? Bool ?? false
        var readings: [GraphReading] = []
        if let age = num(c["age"]), let sgv = num(c["sgv"]) {
            let t = now - age
            readings = (1...60).reversed().map { GraphReading(t: t - Double($0) * 60, v: 120, rate: 0) } + [GraphReading(t: t, v: sgv, rate: num(c["rate"]))]
        }
        var st = CardStyle(graph: GraphStyle(), rings: [], sensorTime: false)
        if c["ended"] as? Bool == true { st.sensor = SensorInfo(start: now - 15 * 86400 - 3600, end: now - 3600, warmupEnd: now - 15 * 86400) }
        let w = 360, h = 200
        guard let ctx = CGContext(data: nil, width: w * 2, height: h * 2, bitsPerComponent: 8, bytesPerRow: 0,
                                  space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return ["no context"] }
        ctx.translateBy(x: 0, y: CGFloat(h * 2)); ctx.scaleBy(x: 2, y: -2)
        let lay = GlucoseCard.draw(ctx, size: CGSize(width: w, height: h), readings: readings, marks: [], now: now, st,
                                   GraphPalette.of(dark: true), surface: .test, part: .all)
        switch state {
        case "value":
            let v = lay.all("value.number").map(\.info)
            if v != [(text ?? "") + (strike ? " struck" : "")] { out.append("value drawn (strike) \(v)") }
            let ring = lay.first("value.trend")?.info == "ring"
            if ring != (e["trend"] as? String == "none") { out.append("no-trend ring \(ring)") }
            if (lay.first("value.time") != nil) != (e["time"] as? Bool ?? false) { out.append("the reading's time") }
        case "no_readings":
            if lay.first("value.none")?.info != "NO_READINGS" { out.append("says no reading since (with the symbol)") }
        case "none": if lay.first("value.none")?.info != "NONE" { out.append("says no readings yet") }
        case "ended": if lay.first("value.ended") == nil { out.append("says sensor ended") }
        default: out.append("unknown state \(state)")
        }
        // the chart (its own box under the value row)
        if state != "ended" {
            let chart = lay.first("chart") != nil
            if chart != (e["graph"] as? Bool ?? false) { out.append("chart \(chart)") }
        }
        return out
    }

    /// The menubar title (`MenubarTitle.of`).
    static func freshnessTitle(_ c: [String: Any], _ e: [String: Any]) -> [String] {
        let now: TimeInterval = 1_800_000_000
        func num(_ a: Any?) -> Double? { (a as? NSNumber)?.doubleValue }
        let l = Freshness.look(sgv: num(c["sgv"]).map { Int($0) }, t: num(c["age"]).map { now - $0 }, rate: num(c["rate"]), now: now,
                               ended: c["ended"] as? Bool ?? false)
        let t = MenubarTitle.of(l)
        let strike = e["strike"] as? Bool ?? false
        let want: MenubarTitle.Image = switch e["state"] as? String {
            case "value": e["trend"] as? String == "arrow" ? .arrow : .ring
            case "no_readings": .noReadings
            case "ended": .flag
            default: .none
        }
        var out: [String] = []
        if t.image != want { out.append("image \(t.image) want \(want)") }
        if e["state"] as? String == "value" {
            if t.text != e["text"] as? String { out.append("text \(t.text)") }
            if t.strike != strike || t.grey != strike { out.append("strike/grey \(t.strike)/\(t.grey)") }
        } else if e["state"] as? String != "none", !t.text.isEmpty { out.append("a value without one: '\(t.text)'") }
        return out
    }

    /// Where the sensor's time left goes (spikes/graph-fixtures/place, the file GraphFixtureTest.place runs):
    /// `GlucoseCard.sensorSpot` for whole cards of every shape, the badge's box and the room it keeps from labels.
    static func place(_ file: URL) -> Int {
        let now: TimeInterval = 1_800_000_000
        guard let data = FileManager.default.contents(atPath: file.path),
              let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { print("FAIL place fixtures: unreadable"); return 1 }
        var failures = 0, n = 0
        func num(_ a: Any?) -> Double? { (a as? NSNumber)?.doubleValue }
        for r in root["rooms"] as? [[String: Any]] ?? [] {
            let got = (Double(GlucoseGraph.sensorWidth(r["label"] as? String ?? "", d: 1)) * 10).rounded() / 10
            if num(r["room"]).map({ abs($0 - got) > 0.05 }) ?? true { print("FAIL place room \(r["label"] ?? "?"): got \(got)"); failures += 1 }
        }
        for c in root["cases"] as? [[String: Any]] ?? [] {
            let card = c["card"] as? [String: Any] ?? [:]
            var g = GraphStyle(); g.axisLabels = card["axisLabels"] as? Bool ?? true
            let st = CardStyle(graph: card["graph"] as? Bool == true ? g : nil, value: card["value"] as? Bool ?? true,
                               sensorTime: card["sensorTime"] as? Bool ?? true)
            let latest = now - (num(c["age"]) ?? 60)
            let kind = c["sensor"] as? String ?? "none"
            let info: SensorInfo? = switch kind {
                case "live": SensorInfo(start: now - 2 * 86400, end: now + 13 * 86400, warmupEnd: now - 2 * 86400 + 3600)
                case "warmup": SensorInfo(start: now - 600, end: now + 15 * 86400 - 600, warmupEnd: now + 3000)
                case "ended": SensorInfo(start: now - 15 * 86400 - 3600, end: now - 3600, warmupEnd: now - 15 * 86400)
                default: nil
            }
            let look = SensorBadge.look(info, now: now, latest: kind == "warmup" ? nil : latest)
            let spot = GlucoseCard.sensorSpot(st, look, Freshness.of(age: now - latest)).rawValue
            let want = c["expect"] as? [String: Any] ?? [:]
            var ok = spot == want["spot"] as? String
            var box = ""
            if let ch = c["chart"] as? [String: Any] {
                let top = GlucoseGraph.sensorTop(CGFloat(num(ch["h"]) ?? 0), lane1: 0, d: 1)
                box = "[0, \(top), \(GlucoseGraph.sensorDp)]"
                let wb = (want["box"] as? [Any])?.compactMap(num) ?? []
                ok = ok && wb.count == 3 && wb[0] == 0 && abs(Double(top) - wb[1]) < 0.05 && abs(Double(GlucoseGraph.sensorDp) - wb[2]) < 0.05
            }
            if !ok { print("FAIL place: \(c["name"] as? String ?? "?") (got \(spot) \(box))"); failures += 1 }
            n += 1
        }
        print("place fixtures: \(n) cases, \(failures) failed")
        return failures
    }

    /// The ring's mark and the % column's crown (spikes/graph-fixtures/mark/*.json, the files GraphFixtureTest.mark runs).
    static func mark(_ dir: URL) -> Int {
        var failures = 0, n = 0
        let files = ((try? FileManager.default.contentsOfDirectory(atPath: dir.path)) ?? []).filter { $0.hasSuffix(".json") }.sorted()
        if files.isEmpty { print("FAIL mark fixtures: none in \(dir.path)"); return 1 }
        for f in files {
            guard let data = FileManager.default.contents(atPath: dir.appendingPathComponent(f).path),
                  let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                  let cases = root["cases"] as? [[String: Any]] else { print("FAIL mark/\(f): unreadable"); failures += 1; continue }
            for c in cases {
                let t = c["tir"] as? [String: Double] ?? [:], e = c["expect"] as? [String: Any] ?? [:]
                let tir = TimeInRange(veryLow: t["veryLow"] ?? 0, low: t["low"] ?? 0, tight: t["tight"] ?? 0, target: t["target"] ?? 0,
                                      high: t["high"] ?? 0, veryHigh: t["veryHigh"] ?? 0, coverage: t["coverage"] ?? 0)
                let range: TirRange = c["range"] as? String == "tight" ? .tight : .inRange
                let col = GlucoseGraph.pct3(tir.below, tir.inRange, tir.above)
                let text = ShareText.of(tir.of(range), range == .inRange ? .inRange : nil)
                let checks: [(String, String, String)] = [
                    ("mark", TirMark.of(tir, range).rawValue, e["mark"] as? String ?? "?"),
                    ("text", text, e["text"] as? String ?? "?"),
                    ("column", "\([col.0, col.1, col.2])", "\(e["column"] as? [Int] ?? [])"),
                    ("crown", "\(TirMark.column(tir))", "\(e["crown"] as? Bool ?? false)"),
                    ("flower", Bloom.of(tir, range)?.rawValue ?? "none", e["flower"] as? String ?? "?"),
                ]
                for (k, got, want) in checks where got != want {
                    print("FAIL mark/\(f): \(c["name"] as? String ?? "?"): \(k) \(got) ≠ \(want)"); failures += 1
                }
                n += 1
            }
        }
        print("mark fixtures: \(n) cases, \(failures) failed")
        return failures
    }

    /// Time shares against a goal (spikes/graph-fixtures/share/*.json, the files GraphFixtureTest.share runs).
    static func share(_ dir: URL) -> Int {
        var failures = 0, n = 0
        let files = ((try? FileManager.default.contentsOfDirectory(atPath: dir.path)) ?? []).filter { $0.hasSuffix(".json") }.sorted()
        if files.isEmpty { print("FAIL share fixtures: none in \(dir.path)"); return 1 }
        let goals: [String: Goal] = ["veryHigh": .veryHigh, "above": .above, "inRange": .inRange, "below": .below, "veryLow": .veryLow]
        for f in files {
            guard let data = FileManager.default.contents(atPath: dir.appendingPathComponent(f).path),
                  let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                  let cases = root["cases"] as? [[String: Any]] else { print("FAIL share/\(f): unreadable"); failures += 1; continue }
            for c in cases {
                let loc = Locale(identifier: c["locale"] as? String ?? "en-US")
                let x = (c["x"] as? NSNumber)?.doubleValue ?? 0
                let g = c["goal"] as? String
                let got = g == "coverage" ? ShareText.coverage(x, locale: loc) : ShareText.of(x, g.flatMap { goals[$0] }, locale: loc)
                if got != c["expect"] as? String { print("FAIL share/\(f): \(c["name"] as? String ?? "?") (got \(got))"); failures += 1 }
                n += 1
            }
        }
        print("share fixtures: \(n) cases, \(failures) failed")
        return failures
    }

    /// The sensor badge, warm-up and countdown ring (spikes/graph-fixtures/sensor, the files GraphFixtureTest.sensor runs).
    /// Clock times in the 12/24-hour setting and the graph's hour labels (spikes/graph-fixtures/clock/*.json, the
    /// files GraphFixtureTest.clock runs). The day period's spaces (U+00A0, U+202F) compare as plain spaces.
    static func clock(_ dir: URL) -> Int {
        var failures = 0, n = 0
        func norm(_ s: String) -> String { s.replacingOccurrences(of: "\u{00A0}", with: " ").replacingOccurrences(of: "\u{202F}", with: " ") }
        let files = ((try? FileManager.default.contentsOfDirectory(atPath: dir.path)) ?? []).filter { $0.hasSuffix(".json") }.sorted()
        if files.isEmpty { print("FAIL clock fixtures: none in \(dir.path)"); return 1 }
        for f in files {
            guard let data = FileManager.default.contents(atPath: dir.appendingPathComponent(f).path),
                  let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                  let cases = root["cases"] as? [[String: Any]] else { print("FAIL clock/\(f): unreadable"); failures += 1; continue }
            for c in cases {
                let loc = Locale(identifier: c["locale"] as? String ?? "en-US")
                let m = c["minute"] as? Int ?? 0, h24 = c["hour24"] as? Bool ?? true
                let want = c["expect"] as? [String: Any] ?? [:]
                let time = norm(Clock.minuteOfDay(m, hour24: h24, locale: loc)), axis = norm(Clock.hourLabel(m / 60, h24, locale: loc))
                if time != want["time"] as? String || axis != want["axis"] as? String {
                    print("FAIL clock/\(f): \(c["name"] as? String ?? "?") (got \(time) / \(axis))"); failures += 1
                }
                n += 1
            }
        }
        print("clock fixtures: \(n) cases, \(failures) failed")
        return failures
    }

    static func sensor(_ dir: URL) -> Int {
        let now: TimeInterval = 1_800_000_000
        var failures = 0, n = 0
        func num(_ a: Any?) -> Double? { (a as? NSNumber)?.doubleValue }
        func near(_ a: Double?, _ b: Double?) -> Bool { a == nil || b == nil ? a == nil && b == nil : abs(a! - b!) <= 0.001 }
        let files = ((try? FileManager.default.contentsOfDirectory(atPath: dir.path)) ?? []).filter { $0.hasSuffix(".json") }.sorted()
        if files.isEmpty { print("FAIL sensor fixtures: none in \(dir.path)"); return 1 }
        for f in files {
            guard let data = FileManager.default.contents(atPath: dir.appendingPathComponent(f).path),
                  let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                  root["cases"] != nil else { print("FAIL sensor/\(f): unreadable"); failures += 1; continue }
            for c in root["cases"] as? [[String: Any]] ?? [] {
                let start = now + (num(c["start"]) ?? 0)
                let info = SensorInfo(start: start, end: now + (num(c["end"]) ?? 0), warmupEnd: num(c["warmup"]).map { now + $0 } ?? start + 3600)
                let latest = num(c["latest"]).map { now + $0 }
                let want = c["expect"] as? [String: Any] ?? [:]
                let look = SensorBadge.look(info, now: now, latest: latest)!
                let ring = SensorBadge.ring(look, info, now: now, hours: (c["countdownHours"] as? Int) ?? 8)
                let pill = look.ended ? StatusBadge.replaceSensor : nil
                let wp = want["pill"] as? [String: Any]
                let pillOK = pill?.kind == wp?["kind"] as? String && pill?.text == wp?["text"] as? String && pill?.short == wp?["short"] as? String
                let ok = pillOK && look.label == want["label"] as? String && look.ended == want["ended"] as? Bool
                    && near(look.life, num(want["life"])) && look.warmup == want["warmup"] as? String && near(ring, num(want["ring"]))
                if !ok {
                    print("FAIL sensor/\(f): \(c["name"] as? String ?? "?") (got label=\(look.label) ended=\(look.ended) life=\(look.life) warmup=\(look.warmup ?? "nil") ring=\(ring.map { "\($0)" } ?? "nil"))")
                    failures += 1
                }
                n += 1
            }
        }
        print("sensor fixtures: \(n) cases, \(failures) failed")
        return failures
    }

    /// The treatment label layout (spikes/graph-fixtures/labels/*.json, the files GraphFixtureTest.labels runs): the
    /// plot's edges, the lanes, which labels are drawn (entries, text, lane, centre in dp from the chart's left) or
    /// dropped. dp values match within 0.5 (Float on Android, Double here; both rounded to 0.5).
    /// A fixture's `marks`: absent = the defaults, "all" = every part, or `{kind: [parts]}` (missing kinds = their default).
    static func marksOf(_ m: Any?) -> MarkShow {
        if m == nil { return .defaults }
        if (m as? String) == "all" { return .all }
        guard let o = m as? [String: [String]] else { print("FAIL marks: \(String(describing: m))"); return .defaults }
        return MarkShow.parse(o.map { "\($0.key):" + $0.value.joined(separator: "+") }.joined(separator: ","))
    }

    static func labels(_ dir: URL) -> Int {
        let now: TimeInterval = 1_800_000_000
        var failures = 0, n = 0
        func r(_ v: CGFloat) -> Double { (Double(v) * 2).rounded() / 2 }
        func near(_ a: Any?, _ b: Double) -> Bool { ((a as? NSNumber)?.doubleValue).map { abs($0 - b) <= 0.5 } ?? false }
        let files = ((try? FileManager.default.contentsOfDirectory(atPath: dir.path)) ?? []).filter { $0.hasSuffix(".json") }.sorted()
        if files.isEmpty { print("FAIL label fixtures: none in \(dir.path)"); return 1 }
        for f in files {
            guard let data = FileManager.default.contents(atPath: dir.appendingPathComponent(f).path),
                  let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                  let cases = root["cases"] as? [[String: Any]] else { print("FAIL labels/\(f): unreadable"); failures += 1; continue }
            let pctLabel = CGFloat((root["pctLabel"] as? NSNumber)?.doubleValue ?? 0)
            let valueLabel = CGFloat((root["valueLabel"] as? NSNumber)?.doubleValue ?? 0)
            for c in cases {
                let name = "labels/\(f): \(c["name"] as? String ?? "?")"
                let chart = c["chart"] as? [String: Double] ?? [:]
                let w = CGFloat(chart["w"] ?? 0), h = CGFloat(chart["h"] ?? 0)
                let window = ((c["hours"] as? Double) ?? 12) * 3600
                let (left, right) = GlucoseGraph.plotX(0, w, pct: c["pct"] as? Bool ?? true, labels: true, d: 1, pctW: pctLabel, valW: valueLabel)
                let from = now - window
                let marks = (c["treatments"] as? [[String: Any]] ?? []).map {
                    GraphMark(t: now + (($0["t"] as? Double) ?? 0), k: $0["k"] as? String ?? "", a: ($0["a"] as? Double) ?? 0, id: $0["id"] as? String ?? "")
                }.filter { $0.t >= from && $0.t <= now }
                let lanes = GlucoseGraph.lanes(h, d: 1)
                let lay = TreatmentMarks.layout(marks, x: { left + CGFloat(($0 - from) / window) * (right - left) }, now: now,
                                                minX: 0, maxX: w, lanes: lanes, d: 1,
                                                show: marksOf(c["marks"]))
                let got = lay.labels.sorted { $0.left + $0.w / 2 < $1.left + $1.w / 2 }
                // the width model holds the real text (SF Pro, the wider font) of every label
                let bold = Ink.font(TreatmentMarks.labelSize, .bold), medium = Ink.font(TreatmentMarks.labelSize, .medium)
                for l in got {
                    let real = l.amounts.reduce(0) { $0 + Ink.width($1, bold) } + CGFloat(max(0, l.amounts.count - 1)) * Ink.width(TreatmentMarks.sep, medium)
                        + (l.time.map { Ink.width((l.amounts.isEmpty ? "" : " ") + $0, medium) } ?? 0)
                    if real > l.w + 0.01 { print("FAIL \(name): \"\(l.text)\" is \(real) wide, modelled \(l.w)"); failures += 1 }
                }
                let e = c["expect"] as? [String: Any] ?? [:]
                let plot = e["plot"] as? [Any] ?? []
                var ok = plot.count == 2 && near(plot[0], r(left)) && near(plot[1], r(right)) && (e["lanes"] as? Int) == lanes
                ok = ok && (e["dropped"] as? [[String]] ?? []) == lay.dropped.map { $0.map(\.id) }
                let want = e["labels"] as? [[String: Any]] ?? []
                ok = ok && want.count == got.count && zip(want, got).allSatisfy { wl, l in
                    (wl["ids"] as? [String]) == l.members.map(\.id) && (wl["text"] as? String) == l.text
                        && (wl["lane"] as? Int) == l.lane && near(wl["x"], r(l.left + l.w / 2))
                }
                if !ok {
                    let dump = got.map { "{\($0.members.map(\.id)) \"\($0.text)\" lane \($0.lane) x \(r($0.left + $0.w / 2))}" }.joined(separator: ", ")
                    print("FAIL \(name)\n  got plot [\(r(left)), \(r(right))] lanes \(lanes) dropped \(lay.dropped.map { $0.map(\.id) }) labels \(dump)")
                    failures += 1
                }
                n += 1
            }
        }
        print("label fixtures: \(n) cases, \(failures) failed")
        return failures
    }
}
