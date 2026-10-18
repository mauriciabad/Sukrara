// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation

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
        check("gaps", near(g.tight, (60.0 + 15 * 60) / (60 + 15 * 60 + 60 + 60), 0.01) && g.coverage < 0.2)

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
                let st = AlertStatus.of(r, settings, marks, now: now)
                let stText: String
                switch st {
                case .none: stText = "none"
                case .needed(let k): stText = "needed:\(k.rawValue)"
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
        return failures
    }
}
