// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import AppKit

/// "One graph everywhere" on the Mac, tested (part of `-graphselftest`; Android's twin is CardLayoutTest.kt): every
/// registered Mac surface (spikes/graph-fixtures/surfaces/surfaces.json) is rendered through its real entry path (the
/// dropdown's `CardView.paint`, the widgets' `MacWidget.image`, the status item's `StatusController.titleFace`) at each
/// of its sizes with every scene and settings variant of layout/scenes.json. The card reports what it laid out
/// (`CardLayout`); layout/rules.json says what each element may depend on (never the surface), and the shared rules'
/// answers must match layout/expect.json, which Android generates from its own surfaces: a Mac surface that lays an
/// element out differently from another Mac surface, or from Android, fails here. Returns the number of failures.
enum LayoutSelfTest {
    private struct Scene { var id: String; var readings: [GraphReading]; var marks: [GraphMark]; var sensor: SensorInfo? }
    private struct Render: CustomStringConvertible {
        var surface: CardSurface; var size: String; var scene: String; var settings: String
        var layouts: [CardLayout]; var faces: [GlucoseLook]
        /// Every clock time its entry path formatted (Clock.observer) and drew (card "time.text"): text, 24-hour.
        var clock: [(String, Bool)] = []
        var description: String { "\(surface.rawValue) \(size)" }
    }

    @MainActor static func run(_ dir: URL) -> Int {
        func json(_ path: String) -> [String: Any]? {
            FileManager.default.contents(atPath: dir.appendingPathComponent(path).path)
                .flatMap { try? JSONSerialization.jsonObject(with: $0) as? [String: Any] }
        }
        guard let reg = (json("surfaces/surfaces.json")?["mac"] as? [String: Any]), let scenesRoot = json("layout/scenes.json"),
              let rules = json("layout/rules.json")?["rules"] as? [[String: Any]],
              let expect = json("layout/expect.json")?["answers"] as? [String: String] else {
            print("FAIL layout: surfaces/surfaces.json, layout/scenes.json, rules.json or expect.json unreadable"); return 1
        }
        var failures = 0
        func fail(_ s: String) { print("FAIL layout \(s)"); failures += 1 }
        let entries = reg["surfaces"] as? [[String: Any]] ?? []
        let ids = entries.compactMap { $0["id"] as? String }.sorted()
        if CardSurface.allCases.filter({ $0 != .test }).map(\.rawValue).sorted() != ids {
            fail("CardSurface (\(CardSurface.allCases.map(\.rawValue))) and surfaces.json mac (\(ids)) differ")
        }
        NSTimeZone.default = TimeZone(identifier: "UTC")!
        let now = (scenesRoot["now"] as? NSNumber)?.doubleValue ?? 1_800_000_000
        func num(_ a: Any?) -> Double { (a as? NSNumber)?.doubleValue ?? 0 }

        // scenes: the same integer maths as CardLayoutTest
        let scenes: [Scene] = (scenesRoot["scenes"] as? [[String: Any]] ?? []).map { o in
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
                rs.append(GraphReading(t: TimeInterval(last), v: num(o["last"]), rate: (o["rate"] as? NSNumber)?.doubleValue))
            }
            let marks = (o["treatments"] as? [[String: Any]] ?? []).map {
                GraphMark(t: now + num($0["t"]), k: $0["k"] as? String ?? "", a: num($0["a"]), n: $0["n"] as? String ?? "", id: $0["id"] as? String ?? "")
            }
            let sensor = (o["sensor"] as? [String: Any]).map {
                SensorInfo(start: now + num($0["start"]), end: now + num($0["end"]), warmupEnd: now + num($0["warmupEnd"]))
            }
            return Scene(id: o["id"] as? String ?? "?", readings: rs, marks: marks, sensor: sensor)
        }
        var hour24Of: [String: Bool] = [:]
        let settings: [(String, GraphConfig)] = (scenesRoot["settings"] as? [[String: Any]] ?? []).map { o in
            var c = GraphConfig()
            if let v = o["sensorTime"] as? Bool { c.sensorTime = v }
            if let v = o["side"] as? Bool { c.side = v }
            if let v = o["ringCount"] as? NSNumber { c.ringCount = v.intValue }
            if let v = o["lastPlace"] as? String, let p = LastPlace(rawValue: v) { c.lastPlace = p }
            if let v = o["lines"] as? [NSNumber] { c.lines = v.map(\.intValue) }
            if let v = o["gridHours"] as? NSNumber { c.gridHours = v.intValue }
            if let v = o["high"] as? NSNumber { c.bands.high = v.intValue }
            hour24Of[o["id"] as? String ?? "?"] = o["hour24"] as? Bool ?? true
            return (o["id"] as? String ?? "?", c)
        }
        func sizes(_ s: CardSurface) -> [CGSize] {
            let e = entries.first { $0["id"] as? String == s.rawValue }
            guard let a = e?["sizes"] as? [[NSNumber]] else { return [.zero] }
            return a.map { CGSize(width: $0[0].doubleValue, height: $0[1].doubleValue) }
        }

        var captured: [CardLayout] = [], faces: [GlucoseLook] = []
        GlucoseCard.observer = { captured.append($0) }
        GlucoseCard.faceObserver = { s, f in if s != .test { faces.append(f) } }
        var clockCalls: [(String, Bool)] = []
        Clock.observer = { t, h in clockCalls.append((t, h)) }
        defer { GlucoseCard.observer = nil; GlucoseCard.faceObserver = nil; Clock.observer = nil; Clock.hour24Override = nil }
        /// Is a drawn clock time 24-hour? nil = no hour in it (a date).
        func cycleOf(_ t: String) -> Bool? {
            if t.range(of: "(?i)\\b(am|pm|a\\.\\s?m\\.|p\\.\\s?m\\.)", options: .regularExpression) != nil { return false }
            return t.range(of: "\\d{1,2}:\\d{2}", options: .regularExpression) != nil ? true : nil
        }
        let pal = GraphPalette.of(dark: false)

        /// Renders `s` through its real entry path; every case is a registered surface (exhaustive switch).
        func render(_ s: CardSurface, _ size: CGSize, _ sc: Scene, _ cfg: GraphConfig) {
            let metrics: (RingSpec, Bands) -> GlucoseMetrics = { r, b in GlucoseMetrics.of(sc.readings, from: now - r.window, to: now, bands: b) }
            switch s {
            case .menubarCard:
                let v = CardView(frame: NSRect(origin: .zero, size: size))
                v.readings = sc.readings; v.marks = sc.marks; v.style = StatusController.dropdownStyle(cfg, sensor: sc.sensor)
                v.metricsOf = metrics; v.alerts = AlertSettings(); v.now = Date(timeIntervalSince1970: now)
                guard let ctx = CGContext(data: nil, width: Int(size.width * 2), height: Int(size.height * 2), bitsPerComponent: 8, bytesPerRow: 0,
                                          space: CGColorSpace(name: CGColorSpace.sRGB)!, bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return }
                ctx.translateBy(x: 0, y: size.height * 2); ctx.scaleBy(x: 2, y: -2)
                v.paint(ctx, size: size, pal)
            case .menubarTitle:
                // through a Snapshot of the scene's whole history, as the app does: its rate is what the title shows
                let hist = sc.readings.map { Reading(date: Date(timeIntervalSince1970: $0.t), mgdl: $0.v, rate: $0.rate) }
                guard let last = hist.last else { return }
                _ = StatusController.titleFace(Snapshot(latest: last, history: hist, source: "test"), now: now, sensor: sc.sensor)
            case .widgetValue, .widgetGraph, .widgetCombined:
                let kind: WidgetKind = s == .widgetValue ? .value : s == .widgetGraph ? .graph : .combined
                _ = MacWidget.image(kind, size: size, cfg: cfg, overrides: WidgetOverrides(), sensor: sc.sensor, readings: sc.readings,
                                    marks: sc.marks, now: now, pal, metricsOf: metrics, alerts: AlertSettings(), paused: [:], overflow: 15)
            case .test: return
            }
        }

        var renders: [Render] = []
        for (sid, cfg) in settings {
            Clock.hour24Override = hour24Of[sid] ?? true
            for sc in scenes {
                for s in CardSurface.allCases where s != .test {
                    if s.glyph && sid != "default" && !sid.hasPrefix("clock") { continue }
                    for size in sizes(s) {
                        captured = []; faces = []; clockCalls = []
                        render(s, size, sc, cfg)
                        var byPart: [CardPart: CardLayout] = [:]
                        for l in captured where l.surface == s { byPart[l.part] = l }
                        renders.append(Render(surface: s, size: "\(Int(size.width))x\(Int(size.height))", scene: sc.id, settings: sid,
                                              layouts: byPart.values.sorted { $0.part.rawValue < $1.part.rawValue },
                                              faces: s.glyph ? faces : [],
                                              clock: clockCalls + captured.filter { $0.surface == s }.flatMap { l in
                                                  l.all("time.text").compactMap { e in cycleOf(e.info).map { (e.info, $0) } } }))
                    }
                }
            }
        }
        // each card surface draws exactly the parts it is registered with
        for s in CardSurface.allCases where s != .test && !s.glyph {
            let want = Set((entries.first { $0["id"] as? String == s.rawValue }?["parts"] as? [String]) ?? [])
            let drawn = Set(renders.filter { $0.surface == s }.flatMap { $0.layouts.map(\.part.rawValue) })
            if drawn != want { fail("\(s.rawValue): draws the parts \(drawn.sorted()), registered \(want.sorted())") }
        }
        // a card surface draws its value through the renderer too, or the registry says why not ("no_value";
        // CardLayoutTest's twin: Android's notification once drew its own value row and missed the status pill)
        for e in entries where e["kind"] as? String == "card" && e["no_value"] == nil {
            let parts = e["parts"] as? [String] ?? []
            if !parts.contains(where: { ["all", "header", "value"].contains($0) }) {
                fail("\(e["id"] as? String ?? "?"): draws \(parts) but not its value (all / header / value), or set \"no_value\" with the reason")
            }
        }
        if !renders.contains(where: { $0.surface == .menubarTitle && !$0.faces.isEmpty }) { fail("menubar-title never asked GlucoseCard.face") }
        // one clock: every time any surface formats or draws is in that variant's setting (CardLayoutTest's twin)
        var clockShown = 0
        for r in renders {
            let want = hour24Of[r.settings] ?? true
            if let bad = r.clock.first(where: { $0.1 != want }), clockShown < 20 {
                fail("clock: \(r) (\(r.scene)/\(r.settings)) shows \"\(bad.0)\", want \(want ? 24 : 12)-hour time"); clockShown += 1
            }
        }
        for v in ["clock12", "clock24"] where !renders.contains(where: { $0.settings == v && !$0.clock.isEmpty }) { fail("clock: no render in \(v) shows a time") }

        func strip(_ s: String) -> String {
            s.replacingOccurrences(of: "\\s*\\[[^]]*]", with: "", options: .regularExpression)
                .replacingOccurrences(of: "\\s*\\(no room\\)", with: "", options: .regularExpression).trimmingCharacters(in: .whitespaces)
        }
        func faceElements(_ f: GlucoseLook) -> [(String, String)] {
            switch f.state {
            case .value:
                return [("value.number", (f.text ?? "") + (f.strike ? " struck" : "")),
                        ("value.trend", f.trend == .arrow ? (abs(f.rate ?? 0) >= 3 ? "arrow double" : "arrow single") : "ring")]
            case .warmingUp: return [("value.warmup", f.warmup ?? "")]
            case .ended: return [("value.ended", "")]
            default: return [("value.none", f.state.layoutName)]
            }
        }
        func ringsSignature(_ st: CardStyle) -> String {
            st.rings.map { "\($0.kind.rawValue):\(Int($0.window)):\($0.range.rawValue):\($0.marks ? 1 : 0)" }.joined(separator: ",")
                + "|\(st.last.rawValue)|" + (st.lastElse.map { "\($0.kind.rawValue):\(Int($0.window))" } ?? "-")
        }
        func facts(_ r: Render) -> [String: String] {
            let st = r.layouts.first?.style
            let chart = r.layouts.lazy.compactMap { l in l.first("chart")?.box.map { (l, $0) } }.first
            let slots = r.layouts.first { $0.first("slots") != nil }
            func wh(_ w: CGFloat, _ h: CGFloat, _ d: CGFloat) -> String { "\(Int((w / d).rounded()))x\(Int((h / d).rounded()))" }
            func b(_ x: Bool?) -> String { x.map { "\($0)" } ?? "-" }
            return [
                "scene": r.scene, "settings": r.settings,
                // the value's place: a card with the value, or a chart card without its chart; or a glyph surface
                "face": st.map { "\($0.value || ($0.graph != nil && chart == nil && r.layouts.contains { $0.part == .all }))" } ?? "\(!r.faces.isEmpty)",
                "value": st.map { "\($0.value)" } ?? "glyph",
                "graph": "\(st?.graph != nil)",
                "labels": b(st?.graph?.axisLabels), "side": b(st?.graph?.side),
                "hours": st?.graph.map { "\(Int($0.window / 3600))" } ?? "-",
                "sensorTime": b(st?.sensorTime), "badge": b(st?.badge),
                "rings": st.map(ringsSignature) ?? "-",
                "chart": chart.map { wh($0.1.width, $0.1.height, $0.0.d) } ?? "-",
                "pctWide": chart.map { $0.1.width / $0.0.d >= GlucoseGraph.pctMinWidth ? "1" : "0" } ?? "-",
                "lane2": "\(r.layouts.contains { $0.first("chart.lane2") != nil })",
                "sensorShown": "\(r.layouts.contains { $0.all("chart.sensor").contains { !$0.info.contains("(no room)") } })",
                "part": slots?.part.rawValue ?? "-",
                // the box the slots were laid out in (the value's row, or a box of their own)
                "box": slots.flatMap { l in l.first("slots.box")?.box.map { wh($0.width, $0.height, l.d) } } ?? "-",
            ]
        }
        func descriptor(_ r: Render, _ rule: [String: Any]) -> [String] {
            let names = Set(rule["elements"] as? [String] ?? [])
            let box = rule["box"] as? Bool ?? false
            let chart = r.layouts.lazy.compactMap { $0.first("chart")?.box }.first
            let els = r.layouts.flatMap { l in l.elements.filter { names.contains($0.name) }.map { (l, $0) } }
            if rule["mode"] as? String == "subset" { return els.sorted { ($0.1.box?.minX ?? 0) < ($1.1.box?.minX ?? 0) }.map { strip($0.1.info) } }
            var out = els.map { (l, e) -> String in
                var s = "\(e.name) \(strip(e.info))".trimmingCharacters(in: .whitespaces)
                if box, let b = e.box, let c = chart {
                    s += " @\(CardLayout.dp(b.minX - c.minX, l.d)),\(CardLayout.dp(c.maxY - b.minY, l.d)),\(CardLayout.dp(b.width, l.d))x\(CardLayout.dp(b.height, l.d))"
                }
                return s
            }
            out += r.faces.flatMap(faceElements).filter { names.contains($0.0) }.map { "\($0.0) \($0.1)".trimmingCharacters(in: .whitespaces) }
            return Array(Set(out)).sorted()
        }
        func isSubsequence(_ a: [String], _ b: [String]) -> Bool {
            var j = 0
            for x in a { while j < b.count && b[j] != x { j += 1 }; if j == b.count { return false }; j += 1 }
            return true
        }
        var shown = 0
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
                    for (r, d) in ds where !isSubsequence(d, longest) && shown < 40 { fail("\(name) [\(key)]: \(r) shows \(d), not a cut of \(longest)"); shown += 1 }
                    if shared {
                        // the same priority order cut at another length: either one is a cut of the other
                        if let g = golden.map({ $0.isEmpty ? [] : $0.components(separatedBy: " | ") }) {
                            if !isSubsequence(longest, g) && !isSubsequence(g, longest) && shown < 40 {
                                fail("\(name) [\(key)]: the Mac shows \(longest), Android (expect.json) \(g)"); shown += 1 } }
                        else if shown < 40 { fail("\(name) [\(key)]: not in expect.json (Android never renders it): \(longest)"); shown += 1 }
                    }
                } else {
                    let distinct = Dictionary(grouping: ds, by: { $0.1 })
                    if distinct.count > 1 && shown < 40 {
                        fail("\(name) [\(key)] differs between Mac surfaces:\n" + distinct.map { d, who in
                            "      \(d)  ← \(who.map(\.0.description).prefix(6).joined(separator: ", "))" }.joined(separator: "\n"))
                        shown += 1
                    }
                    if shared, let d = ds.first?.1 {
                        let mine = d.joined(separator: " | ")
                        if let g = golden { if g != mine && shown < 40 { fail("\(name) [\(key)]: the Mac shows \(mine), Android (expect.json) \(g)"); shown += 1 } }
                        else if shown < 40 { fail("\(name) [\(key)]: not in expect.json (Android never renders it): \(mine)"); shown += 1 }
                    }
                }
            }
        }
        // the gaps: a column is its widest drawn label plus one constant margin on each side (CardLayoutTest's twin)
        for g in json("layout/rules.json")?["gaps"] as? [[String: Any]] ?? [] {
            let colName = g["column"] as? String ?? "?", labName = g["labels"] as? String ?? "?", gap = num(g["gap"])
            var seen = 0, gapShown = 0
            for r in renders { for l in r.layouts {
                guard let col = l.first(colName)?.box else { continue }
                let ls = l.all(labName).compactMap { e in e.box.map { (e.info, $0) } }
                if ls.isEmpty { fail("\(colName): \(r) draws no \(labName)"); continue }
                seen += 1
                // the aligned side: every label; the other side: the widest
                let alignRight = g["align"] as? String == "right"
                let widest = Double((alignRight ? ls.map(\.1.minX).min()! - col.minX : col.maxX - ls.map(\.1.maxX).max()!) / l.d)
                let rights = ls.map { ($0.0, Double((alignRight ? col.maxX - $0.1.maxX : $0.1.minX - col.minX) / l.d)) }
                if (abs(widest - gap) > 0.05 || rights.contains { abs($0.1 - gap) > 0.05 }) && gapShown < 20 {
                    gapShown += 1
                    fail("\(colName) gaps on \(r) (\(r.scene)/\(r.settings)): widest label's free side \(String(format: "%.2f", widest)), aligned side "
                         + rights.map { "\($0.0) \(String(format: "%.2f", $0.1))" }.joined(separator: ", ")
                         + " dp; want \(gap) each (widest label \(String(format: "%.2f", Double(ls.map(\.1.width).max()! / l.d))) dp, column \(String(format: "%.2f", Double(col.width / l.d))) dp)")
                }
            } }
            if seen == 0 { fail("\(colName): no render shows it (gaps rule untested)") }
        }
        print(failures == 0 ? "layout self-test OK (\(renders.count) renders)" : "layout self-test: \(failures) failure(s)")
        return failures
    }
}
