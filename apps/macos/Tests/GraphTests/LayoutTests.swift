// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import CoreGraphics
import Foundation
import SukraraCore
import Testing

@testable import SukraraGraph

// "One graph everywhere", tested (Android's twin: CardLayoutTest.kt): every registered Mac card surface
// (contracts/graph/surfaces/surfaces.json) is rendered through its style decision (`CardSurface.style`) at each of its
// sizes with every scene and settings variant of layout/scenes.json. The card reports what it laid out (`CardLayout`)
// layout/rules.json says what each element may depend on (never the surface), and the shared rules' answers must match
// layout/expect.json, which Android generates from its own surfaces. Disagreements are never fixed by editing
// expect.json: they are a renderer bug on one side.

private let now = GraphFixtures.now

/// One surface × size × scene × settings render.
private struct Render: CustomStringConvertible {
    var surface: CardSurface
    var size: String
    var scene: String
    var settings: String
    var layouts: [CardLayout]
    var faces: [GlucoseLook]
    var description: String { "\(surface.rawValue) \(size) (\(scene)/\(settings))" }
}

private struct Scene {
    var id: String
    var readings: [GraphReading]
    var marks: [GraphMark]
    var sensor: SensorInfo?
}

/// The alert decision the layout scenes are drawn with: the alert engine's default rules (contracts/alerts/SPEC.md §3:
/// very low 55, low 70, high 180, very high 250, extremely high 300; carbs quiet lows 15 min, rapid insulin quiets
/// highs 60 min, never extremely high), as Android does for these fixtures.
enum DefaultAlertRules {
    private static let levels: [(kind: String, side: AlertStatus.Side, low: Bool, mgdl: Double)] = [
        ("very_low", .low, true, 55), ("extremely_high", .high, false, 300), ("very_high", .high, false, 250),
        ("low", .low, true, 70), ("high", .high, false, 180),
    ]

    static func muteEnd(_ m: GraphMark) -> TimeInterval? {
        switch m.k {
        case "rapid": m.t + 60 * 60
        case "carbs": m.t + 15 * 60
        default: nil
        }
    }

    static func alerts(_ readings: [GraphReading], _ marks: [GraphMark], now: TimeInterval) -> CardAlerts {
        var ends: [String: TimeInterval] = [:]
        for m in marks { if let e = muteEnd(m) { ends[m.id] = e } }
        let status = status(readings.max { $0.t < $1.t }, marks, now: now)
        return CardAlerts(status: status, last: last(marks, status, now: now), muteEnds: ends)
    }

    private static func status(_ r: GraphReading?, _ marks: [GraphMark], now: TimeInterval) -> AlertStatus {
        guard let r, now - r.t <= 15 * 60,
            let level = levels.first(where: { $0.low ? r.v < $0.mgdl : r.v >= $0.mgdl })
        else { return .none }
        let by = level.kind == "extremely_high" ? nil : (level.side == .low ? "carbs" : "rapid")
        let cover = marks.filter { $0.k == by && $0.t <= now }
            .compactMap { m in muteEnd(m).map { (m, $0) } }
            .filter { $0.1 > now }
            .max { $0.0.t < $1.0.t }
        if let cover { return .taken(level.side, treatment: cover.0, until: cover.1) }
        return .needed(level.side)
    }

    private static func keep(_ m: GraphMark) -> TimeInterval? {
        switch m.k {
        case "rapid": 4 * 3600
        case "carbs": min(max(m.a * 180, 30 * 60), 3 * 3600)
        default: nil
        }
    }

    private static func last(_ marks: [GraphMark], _ status: AlertStatus, now: TimeInterval) -> LastTreatment.Info? {
        let live = marks.filter { $0.t <= now }
        let tr: GraphMark
        if let r = live.filter({ $0.k == "rapid" && (muteEnd($0) ?? 0) > now }).max(by: { $0.t < $1.t }) {
            tr = r
        } else if case let .taken(_, t, _) = status {
            tr = t
        } else {
            func key(_ m: GraphMark) -> (Int, Int) { (Int((m.t / 60).rounded(.down)), m.k == "rapid" ? 1 : 0) }
            guard let t = live.filter({ m in keep(m).map { now - m.t < $0 } == true }).max(by: { key($0) < key($1) })
            else { return nil }
            tr = t
        }
        let until = muteEnd(tr)
        var carbs: LastTreatment.Companion?
        if tr.k == "rapid", (until ?? 0) > now,
            let c = live.filter({ m in m.k == "carbs" && keep(m).map { now - m.t < $0 } == true })
                .max(by: { $0.t < $1.t })
        {
            carbs = LastTreatment.Companion(tr: c, until: muteEnd(c))
        }
        return LastTreatment.Info(tr: tr, until: until, carbs: carbs)
    }
}

@Suite struct LayoutTests {
    private static func num(_ a: Any?) -> Double { (a as? NSNumber)?.doubleValue ?? 0 }

    /// The scenes' readings: the same integer maths as CardLayoutTest.
    private static func scene(_ o: [String: Any]) -> Scene {
        var rs: [GraphReading] = []
        let days = Int(num(o["days"]))
        if days > 0 {
            let lo = Int64(num(o["lo"])), hi = Int64(num(o["hi"])), period = Int64(num(o["period"])), half = period / 2
            let first = Int64(now) - Int64(days) * 86400, last = Int64(now) - Int64(num(o["age"]))
            var t = first
            while t < last {
                let k = ((t - first) % period + period) % period
                let tri = k <= half ? k : period - k
                rs.append(GraphReading(t: TimeInterval(t), v: Double(lo + (hi - lo) * tri / half)))
                t += 300
            }
            rs.append(
                GraphReading(t: TimeInterval(last), v: num(o["last"]), rate: (o["rate"] as? NSNumber)?.doubleValue))
        }
        let marks = (o["treatments"] as? [[String: Any]] ?? []).map {
            GraphMark(
                t: now + num($0["t"]), k: $0["k"] as? String ?? "", a: num($0["a"]), n: $0["n"] as? String ?? "",
                id: $0["id"] as? String ?? "")
        }
        let sensor = (o["sensor"] as? [String: Any]).map {
            SensorInfo(start: now + num($0["start"]), end: now + num($0["end"]), warmupEnd: now + num($0["warmupEnd"]))
        }
        return Scene(id: o["id"] as? String ?? "?", readings: rs, marks: marks, sensor: sensor)
    }

    private static func config(_ o: [String: Any]) -> GraphConfig {
        var c = GraphConfig()
        if let v = o["sensorTime"] as? Bool { c.sensorTime = v }
        if let v = o["side"] as? Bool { c.side = v }
        if let v = o["ringCount"] as? NSNumber { c.ringCount = v.intValue }
        if let v = o["lastPlace"] as? String, let p = LastPlace(rawValue: v) { c.lastPlace = p }
        if let v = o["lines"] as? [NSNumber] { c.lines = v.map(\.intValue) }
        if let v = o["gridHours"] as? NSNumber { c.gridHours = v.intValue }
        if let v = o["high"] as? NSNumber { c.bands.high = v.intValue }
        if o["marks"] != nil { c.marks = LabelFixtureTests.marksOf(o["marks"]) }
        return c
    }

    @Test func surfacesMatchTheRegistry() {
        let reg = GraphFixtures.object("surfaces/surfaces.json").child("mac")
        let ids = (reg["surfaces"] as? [[String: Any]] ?? []).compactMap { $0["id"] as? String }.sorted()
        #expect(CardSurface.allCases.filter { $0 != .test }.map(\.rawValue).sorted() == ids)
    }

    @Test func everySurfaceLaysOutLikeEveryOtherAndLikeAndroid() throws {
        let reg = GraphFixtures.object("surfaces/surfaces.json").child("mac")
        let scenesRoot = GraphFixtures.object("layout/scenes.json")
        let rulesRoot = GraphFixtures.object("layout/rules.json")
        let rules = try #require(rulesRoot["rules"] as? [[String: Any]])
        let expect = try #require(GraphFixtures.object("layout/expect.json")["answers"] as? [String: String])
        let entries = reg["surfaces"] as? [[String: Any]] ?? []
        var failures = 0
        func fail(_ s: String) {
            failures += 1
            if failures <= 40 { Issue.record(Comment(rawValue: s)) }
        }

        let scenes = (scenesRoot["scenes"] as? [[String: Any]] ?? []).map(Self.scene)
        var hour24Of: [String: Bool] = [:]
        let settings: [(String, GraphConfig)] = (scenesRoot["settings"] as? [[String: Any]] ?? []).map { o in
            hour24Of[o["id"] as? String ?? "?"] = o["hour24"] as? Bool ?? true
            return (o["id"] as? String ?? "?", Self.config(o))
        }
        // an empty or moved scenes file must fail, not pass with nothing rendered
        try #require(!scenes.isEmpty && !settings.isEmpty && !entries.isEmpty)
        func sizes(_ s: CardSurface) -> [CGSize] {
            let e = entries.first { $0["id"] as? String == s.rawValue }
            guard let a = e?["sizes"] as? [[NSNumber]] else { return [.zero] }
            return a.map { CGSize(width: $0[0].doubleValue, height: $0[1].doubleValue) }
        }
        /// Is a drawn clock time 24-hour? nil = no hour in it (a date).
        func cycleOf(_ t: String) -> Bool? {
            if t.range(of: "(?i)\\b(am|pm|a\\.\\s?m\\.|p\\.\\s?m\\.)", options: .regularExpression) != nil {
                return false
            }
            return t.range(of: "\\d{1,2}:\\d{2}", options: .regularExpression) != nil ? true : nil
        }
        let pal = GraphPalette.of(dark: false)

        var renders: [Render] = []
        var clockShown = 0
        defer { Clock.hour24Source = nil }
        for (sid, cfg) in settings {
            let hour24 = hour24Of[sid] ?? true
            Clock.hour24Source = { hour24 }
            for sc in scenes {
                let data = CardData(
                    readings: sc.readings, marks: sc.marks, sensor: sc.sensor,
                    alerts: DefaultAlertRules.alerts(sc.readings, sc.marks, now: now), now: now)
                for s in CardSurface.allCases where s != .test {
                    if s.glyph {
                        guard sid == "default" || sid.hasPrefix("clock") else { continue }
                        renders.append(
                            Render(
                                surface: s, size: "-", scene: sc.id, settings: sid, layouts: [],
                                faces: sc.readings.isEmpty
                                    ? [] : [GlucoseCard.face(data, unit: .mgdl, surface: .menubarTitle)]))
                        continue
                    }
                    for size in sizes(s) {
                        let st = s.style(cfg, sensor: sc.sensor)
                        let lay = GlucoseCard.draw(
                            cardContext(size), size: size, readings: data.readings, marks: data.marks, now: now, st,
                            pal,
                            surface: s, part: .all, alerts: data.alerts)
                        let r = Render(
                            surface: s, size: "\(Int(size.width))x\(Int(size.height))", scene: sc.id, settings: sid,
                            layouts: [lay], faces: [])
                        // one clock: every time drawn is in that variant's 24-hour setting
                        if let bad = lay.all("time.text").first(where: {
                            cycleOf($0.info).map { $0 != hour24 } ?? false
                        }),
                            clockShown < 10
                        {
                            fail("clock: \(r) shows \"\(bad.info)\", want \(hour24 ? 24 : 12)-hour time")
                            clockShown += 1
                        }
                        renders.append(r)
                    }
                }
            }
        }
        for v in ["clock12", "clock24"]
        where !renders.contains(where: { $0.settings == v && $0.layouts.contains { !$0.all("time.text").isEmpty } }) {
            fail("clock: no render in \(v) shows a time")
        }
        // each card surface draws exactly the parts it is registered with, its value among them (or says why not)
        for s in CardSurface.allCases where s != .test && !s.glyph {
            let entry = entries.first { $0["id"] as? String == s.rawValue }
            let want = Set((entry?["parts"] as? [String]) ?? [])
            let drawn = Set(renders.filter { $0.surface == s }.flatMap { $0.layouts.map(\.part.rawValue) })
            if drawn != want { fail("\(s.rawValue): draws the parts \(drawn.sorted()), registered \(want.sorted())") }
            if entry?["no_value"] == nil, !want.contains(where: { ["all", "header", "value"].contains($0) }) {
                fail("\(s.rawValue): draws \(want) but not its value, or set \"no_value\" with the reason")
            }
        }

        checkRules(renders, rules, expect, fail)
        checkGaps(renders, rulesRoot, fail)
        checkBadge(renders, rulesRoot, fail)
        checkDistribution(renders, rulesRoot, fail)
        #expect(failures == 0, "\(failures) layout failure(s) over \(renders.count) renders")
    }

    // MARK: - Rules (layout/rules.json) and Android's answers (expect.json)

    private func strip(_ s: String) -> String {
        s.replacingOccurrences(of: "\\s*\\[[^]]*]", with: "", options: .regularExpression)
            .replacingOccurrences(of: "\\s*\\(no room\\)", with: "", options: .regularExpression)
            .trimmingCharacters(in: .whitespaces)
    }

    private func faceElements(_ f: GlucoseLook) -> [(String, String)] {
        switch f.state {
        case .value:
            return [
                ("value.number", (f.text ?? "") + (f.strike ? " struck" : "")),
                ("value.trend", f.trend == .arrow ? (abs(f.rate ?? 0) >= 3 ? "arrow double" : "arrow single") : "ring"),
            ]
        case .warmingUp: return [("value.warmup", f.warmup ?? "")]
        case .ended: return [("value.ended", "")]
        default: return [("value.none", f.state.layoutName)]
        }
    }

    private func ringsSignature(_ st: CardStyle) -> String {
        st.rings.map { "\($0.kind.rawValue):\(Int($0.window)):\($0.range.rawValue):\($0.marks ? 1 : 0)" }
            .joined(separator: ",") + "|\(st.last.rawValue)|"
            + (st.lastElse.map { "\($0.kind.rawValue):\(Int($0.window))" } ?? "-")
    }

    private func facts(_ r: Render) -> [String: String] {
        let st = r.layouts.first?.style
        let chart = r.layouts.lazy.compactMap { l in l.first("chart")?.box.map { (l, $0) } }.first
        let slots = r.layouts.first { $0.first("slots") != nil }
        func wh(_ w: CGFloat, _ h: CGFloat, _ d: CGFloat) -> String {
            "\(Int((w / d).rounded()))x\(Int((h / d).rounded()))"
        }
        func b(_ x: Bool?) -> String { x.map { "\($0)" } ?? "-" }
        let face =
            st.map { "\($0.value || ($0.graph != nil && chart == nil && r.layouts.contains { $0.part == .all }))" }
            ?? "\(!r.faces.isEmpty)"
        return [
            "scene": r.scene, "settings": r.settings, "face": face,
            "value": st.map { "\($0.value)" } ?? "glyph",
            "graph": "\(st?.graph != nil)",
            "labels": b(st?.graph?.axisLabels), "side": b(st?.graph?.side),
            "hours": st?.graph.map { "\(Int($0.window / 3600))" } ?? "-",
            "sensorTime": b(st?.sensorTime), "badge": b(st?.badge),
            "rings": st.map(ringsSignature) ?? "-",
            "chart": chart.map { wh($0.1.width, $0.1.height, $0.0.d) } ?? "-",
            "pctWide": chart.map { $0.1.width / $0.0.d >= GlucoseGraph.pctMinWidth ? "1" : "0" } ?? "-",
            "lane2": "\(r.layouts.contains { $0.first("chart.lane2") != nil })",
            "sensorShown":
                "\(r.layouts.contains { $0.all("chart.sensor").contains { !$0.info.contains("(no room)") } })",
            "part": slots?.part.rawValue ?? "-",
            "box": slots.flatMap { l in l.first("slots.box")?.box.map { wh($0.width, $0.height, l.d) } } ?? "-",
        ]
    }

    private func descriptor(_ r: Render, _ rule: [String: Any]) -> [String] {
        let names = Set(rule["elements"] as? [String] ?? [])
        let box = rule["box"] as? Bool ?? false
        let chart = r.layouts.lazy.compactMap { $0.first("chart")?.box }.first
        let els = r.layouts.flatMap { l in l.elements.filter { names.contains($0.name) }.map { (l, $0) } }
        if rule["mode"] as? String == "subset" {
            return els.sorted { ($0.1.box?.minX ?? 0) < ($1.1.box?.minX ?? 0) }.map { strip($0.1.info) }
        }
        var out = els.map { (l, e) -> String in
            var s = "\(e.name) \(strip(e.info))".trimmingCharacters(in: .whitespaces)
            if box, let b = e.box, let c = chart {
                s +=
                    " @\(CardLayout.dp(b.minX - c.minX, l.d)),\(CardLayout.dp(c.maxY - b.minY, l.d)),"
                    + "\(CardLayout.dp(b.width, l.d))x\(CardLayout.dp(b.height, l.d))"
            }
            return s
        }
        out += r.faces.flatMap(faceElements).filter { names.contains($0.0) }
            .map { "\($0.0) \($0.1)".trimmingCharacters(in: .whitespaces) }
        return Array(Set(out)).sorted()
    }

    private func isSubsequence(_ a: [String], _ b: [String]) -> Bool {
        var j = 0
        for x in a {
            while j < b.count && b[j] != x { j += 1 }
            if j == b.count { return false }
            j += 1
        }
        return true
    }

    private func checkRules(
        _ renders: [Render], _ rules: [[String: Any]], _ expect: [String: String], _ fail: (String) -> Void
    ) {
        for rule in rules {
            let name = rule["name"] as? String ?? "?", by = rule["by"] as? [String] ?? []
            let subset = rule["mode"] as? String == "subset", shared = rule["shared"] as? Bool ?? false
            var groups: [String: [Render]] = [:]
            for r in renders where !r.layouts.isEmpty || !r.faces.isEmpty {
                let f = facts(r)
                groups[by.map { "\($0)=\(f[$0] ?? "?")" }.joined(separator: ";"), default: []].append(r)
            }
            for (key, rs) in groups.sorted(by: { $0.key < $1.key }) {
                let ds = rs.map { ($0, descriptor($0, rule)) }
                let golden = expect["\(name)|\(key)"]
                if subset {
                    let longest = ds.max { $0.1.count < $1.1.count }!.1
                    for (r, d) in ds where !isSubsequence(d, longest) {
                        fail("\(name) [\(key)]: \(r) shows \(d), not a cut of \(longest)")
                    }
                    guard shared else { continue }
                    if let g = golden.map({ $0.isEmpty ? [] : $0.components(separatedBy: " | ") }) {
                        if !isSubsequence(longest, g) && !isSubsequence(g, longest) {
                            fail("\(name) [\(key)]: the Mac shows \(longest), Android (expect.json) \(g)")
                        }
                    } else {
                        fail("\(name) [\(key)]: not in expect.json (Android never renders it): \(longest)")
                    }
                } else {
                    let distinct = Dictionary(grouping: ds, by: { $0.1 })
                    if distinct.count > 1 {
                        fail(
                            "\(name) [\(key)] differs between Mac surfaces: "
                                + distinct.map { d, who in "\(d) ← \(who.map(\.0.description).prefix(4))" }
                                .joined(separator: "; "))
                    }
                    guard shared, let d = ds.first?.1 else { continue }
                    let mine = d.joined(separator: " | ")
                    if let g = golden {
                        if g != mine { fail("\(name) [\(key)]: the Mac shows \(mine), Android (expect.json) \(g)") }
                    } else {
                        fail("\(name) [\(key)]: not in expect.json (Android never renders it): \(mine)")
                    }
                }
            }
        }
    }

    // MARK: - Gaps, the sensor badge, the distribution's outline

    /// A column is its widest drawn label plus one constant margin on each side.
    private func checkGaps(_ renders: [Render], _ root: JSON, _ fail: (String) -> Void) {
        for g in root["gaps"] as? [[String: Any]] ?? [] {
            let colName = g["column"] as? String ?? "?", labName = g["labels"] as? String ?? "?"
            let gap = Self.num(g["gap"]), alignRight = g["align"] as? String == "right"
            var seen = 0
            for r in renders {
                for l in r.layouts {
                    guard let col = l.first(colName)?.box else { continue }
                    let ls = l.all(labName).compactMap { e in e.box.map { (e.info, $0) } }
                    if ls.isEmpty {
                        fail("\(colName): \(r) draws no \(labName)")
                        continue
                    }
                    seen += 1
                    let widest = Double(
                        (alignRight ? ls.map(\.1.minX).min()! - col.minX : col.maxX - ls.map(\.1.maxX).max()!) / l.d)
                    let sides = ls.map { Double((alignRight ? col.maxX - $0.1.maxX : $0.1.minX - col.minX) / l.d) }
                    if abs(widest - gap) > 0.05 || sides.contains(where: { abs($0 - gap) > 0.05 }) {
                        fail(
                            "\(colName) gaps on \(r): widest label's free side \(widest), aligned sides \(sides); want \(gap)"
                        )
                    }
                }
            }
            if seen == 0 { fail("\(colName): no render shows it (gaps rule untested)") }
        }
    }

    /// The sensor badge never moves a label: with and without it, the same layout.
    private func checkBadge(_ renders: [Render], _ root: JSON, _ fail: (String) -> Void) {
        let badge = root.child("badge")
        guard let cmp = badge["compare"] as? [String], cmp.count == 2 else {
            return fail("rules.json has no \"badge\"")
        }
        let els = Set(badge["elements"] as? [String] ?? [])
        func desc(_ r: Render) -> [String] {
            r.layouts.flatMap { l in
                l.elements.filter { els.contains($0.name) }.map { e in
                    "\(e.name) \(e.info)"
                        + (e.box.map { b in
                            " @\(CardLayout.dp(b.minX, l.d)),\(CardLayout.dp(b.minY, l.d)),"
                                + "\(CardLayout.dp(b.maxX, l.d)),\(CardLayout.dp(b.maxY, l.d))"
                        } ?? "")
                }
            }.sorted()
        }
        var compared = 0
        for r in renders
        where r.settings == cmp[0]
            && r.layouts.contains(where: { $0.all("chart.sensor").contains { !$0.info.contains("(no room)") } })
        {
            guard
                let o = renders.first(where: {
                    $0.surface == r.surface && $0.size == r.size && $0.scene == r.scene && $0.settings == cmp[1]
                })
            else { continue }
            compared += 1
            let a = desc(r), b = desc(o)
            if a != b {
                fail(
                    "badge on \(r): the layout changes: with \(a.filter { !b.contains($0) }), without \(b.filter { !a.contains($0) })"
                )
            }
        }
        if compared == 0 { fail("badge: no render shows the sensor badge (rule untested)") }
    }

    /// The distribution's erased outline: the dots plus one constant margin, everywhere.
    private func checkDistribution(_ renders: [Render], _ root: JSON, _ fail: (String) -> Void) {
        let dist = root.child("distribution")
        guard let cut = dist.number("cut") else { return fail("rules.json has no \"distribution\"") }
        if abs(Double(GlucoseGraph.profilePitch) - (dist.number("pitch") ?? 0)) > 1e-4
            || abs(Double(GlucoseGraph.profileR) - (dist.number("dot") ?? 0)) > 1e-4
            || abs(Double(GlucoseGraph.profileCut) - cut) > 1e-4
        {
            fail(
                "distribution: GlucoseGraph \(GlucoseGraph.profilePitch)/\(GlucoseGraph.profileR)/\(GlucoseGraph.profileCut)"
            )
        }
        var cuts = 0
        for r in renders {
            for l in r.layouts {
                guard let c = l.first("chart.distribution.cut")?.box, let d = l.first("chart.distribution.dots")?.box
                else { continue }
                cuts += 1
                let right = Double((c.maxX - d.maxX) / l.d), top = Double((d.minY - c.minY) / l.d)
                if abs(right - cut) > 0.05 || abs(top - cut) > 0.05 {
                    fail("distribution cut on \(r): margin right \(right) top \(top), want \(cut)")
                }
            }
        }
        if cuts == 0 { fail("distribution: no render erases its outline (rule untested)") }
    }
}
