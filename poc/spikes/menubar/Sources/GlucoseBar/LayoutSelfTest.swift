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
        func scene(_ o: [String: Any]) -> Scene {
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
        let scenes: [Scene] = (scenesRoot["scenes"] as? [[String: Any]] ?? []).map(scene)
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
            if o["marks"] != nil { c.marks = GraphFixtures.marksOf(o["marks"]) }
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
                                    marks: sc.marks, now: now, pal, metricsOf: metrics, alerts: AlertSettings(), paused: [:], allMuted: 0, overflow: 15)
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
        // the sensor badge never moves a label (rules.json "badge"): with and without it, the same layout
        if let badge = json("layout/rules.json")?["badge"] as? [String: Any], let cmp = badge["compare"] as? [String], cmp.count == 2 {
            let els = Set(badge["elements"] as? [String] ?? [])
            func desc(_ r: Render) -> [String] {
                r.layouts.flatMap { l in l.elements.filter { els.contains($0.name) }.map { e in
                    "\(e.name) \(e.info)" + (e.box.map { b in " @\(CardLayout.dp(b.minX, l.d)),\(CardLayout.dp(b.minY, l.d)),\(CardLayout.dp(b.maxX, l.d)),\(CardLayout.dp(b.maxY, l.d))" } ?? "")
                } }.sorted()
            }
            var compared = 0, shown = 0
            for r in renders where r.settings == cmp[0] && r.layouts.contains(where: { $0.all("chart.sensor").contains { !$0.info.contains("(no room)") } }) {
                guard let o = renders.first(where: { $0.surface == r.surface && $0.size == r.size && $0.scene == r.scene && $0.settings == cmp[1] }) else { continue }
                compared += 1
                let a = desc(r), b = desc(o)
                if a != b && shown < 10 {
                    shown += 1
                    fail("badge on \(r) (\(r.scene)): the layout changes with the sensor badge: with \(a.filter { !b.contains($0) }), without \(b.filter { !a.contains($0) })")
                }
            }
            if compared == 0 { fail("badge: no render shows the sensor badge (rule untested)") }
        } else { fail("rules.json has no \"badge\"") }

        // the distribution's erased outline: the dots plus one constant margin (rules.json "distribution"), everywhere
        if let dist = json("layout/rules.json")?["distribution"] as? [String: Any] {
            let cut = num(dist["cut"])
            if abs(Double(GlucoseGraph.profilePitch) - num(dist["pitch"])) > 1e-4 || abs(Double(GlucoseGraph.profileR) - num(dist["dot"])) > 1e-4
                || abs(Double(GlucoseGraph.profileCut) - cut) > 1e-4 {
                fail("distribution: GlucoseGraph \(GlucoseGraph.profilePitch)/\(GlucoseGraph.profileR)/\(GlucoseGraph.profileCut), rules.json \(dist)")
            }
            var cuts = 0, shown = 0
            for r in renders { for l in r.layouts {
                guard let c = l.first("chart.distribution.cut")?.box, let d = l.first("chart.distribution.dots")?.box else { continue }
                cuts += 1
                let right = Double((c.maxX - d.maxX) / l.d), top = Double((d.minY - c.minY) / l.d)
                if (abs(right - cut) > 0.05 || abs(top - cut) > 0.05) && shown < 10 {
                    shown += 1
                    fail("distribution cut on \(r) (\(r.scene)/\(r.settings)): margin right \(String(format: "%.2f", right)) top \(String(format: "%.2f", top)) pt, want \(cut)")
                }
            } }
            if cuts == 0 { fail("distribution: no render erases its outline (rule untested)") }
            print("distribution outline checked on \(cuts) renders")
        } else { fail("rules.json has no \"distribution\"") }

        // A scrolled chart (the dropdown; scenes.json "scroll", CardLayoutTest.scrolledWindowShowsOnlyItsData's twin)
        // shows only what's in its window: the chart of the visible data alone, the visible readings' y range, the Now
        // pill only when scrolled, and the shared font-independent "expect".
        if let scroll = scenesRoot["scroll"] as? [String: Any] {
            let all = (scroll["scenes"] as? [[String: Any]] ?? []).map(scene) + scenes
            let size = sizes(.menubarCard).first ?? CGSize(width: 340, height: 196)
            let cfg = GraphConfig(), b = cfg.bands.sane(), span = cfg.graphStyle().window
            func card(_ rs: [GraphReading], _ marks: [GraphMark], at: TimeInterval, end: TimeInterval?, span zs: TimeInterval? = nil,
                      cfg: GraphConfig = cfg) -> CardLayout? {
                captured = []
                let v = CardView(frame: NSRect(origin: .zero, size: size))
                v.readings = rs; v.marks = marks; v.style = StatusController.dropdownStyle(cfg, sensor: nil)
                v.metricsOf = { r, bb in GlucoseMetrics.of(rs, from: at - r.window, to: at, bands: bb) }
                v.alerts = AlertSettings(); v.now = Date(timeIntervalSince1970: at)
                v.scrollEnd = end
                v.scrollSpan = zs
                guard let ctx = CGContext(data: nil, width: Int(size.width * 2), height: Int(size.height * 2), bitsPerComponent: 8, bytesPerRow: 0,
                                          space: CGColorSpace(name: CGColorSpace.sRGB)!, bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return nil }
                ctx.translateBy(x: 0, y: size.height * 2); ctx.scaleBy(x: 2, y: -2)
                return v.paint(ctx, size: size, pal)
            }
            for c in scroll["cases"] as? [[String: Any]] ?? [] {
                guard let sc = all.first(where: { $0.id == c["scene"] as? String }) else { fail("scroll: no scene \(c["scene"] ?? "?")"); continue }
                // a zoomed case: the window's length; its chart must equal a place set to that "time shown"
                let zs: TimeInterval? = num(c["span"]) > 0 ? num(c["span"]) : nil
                let w = zs ?? span
                let back = num(c["back"]), end = now - back, from = end - w
                let name = "\(sc.id) back \(Int(back))s" + (zs.map { " span \(Int($0))s" } ?? "")
                var aloneCfg = cfg
                aloneCfg.hours = Int(w / 3600)
                let visible = sc.readings.filter { $0.t >= from - 60 && $0.t <= end }
                guard let scrolled = card(sc.readings, sc.marks, at: now, end: back > 0 ? end : nil, span: zs),
                      let alone = card(visible, sc.marks.filter { $0.t >= from && $0.t <= end }, at: end, end: nil, cfg: aloneCfg) else { fail("scroll: \(name) not drawn"); continue }
                func info(_ l: CardLayout, _ n: String) -> String { l.all(n).map(\.info).joined(separator: ",") }
                func box(_ l: CardLayout, _ n: String) -> String {
                    l.first(n)?.box.map { "\(CardLayout.dp($0.minX, l.d)),\(CardLayout.dp($0.minY, l.d)),\(CardLayout.dp($0.maxX, l.d)),\(CardLayout.dp($0.maxY, l.d))" } ?? "-"
                }
                let markers = scrolled.first("chart.markers") != nil
                for n in ["chart.pct", "chart.y", "chart.values", "chart.markers", "chart.lines"] + (markers ? [] : ["time.text"]) where info(scrolled, n) != info(alone, n) {
                    fail("scroll \(name): \(n) \"\(info(scrolled, n))\", the visible data alone \"\(info(alone, n))\"")
                }
                for n in ["chart.distribution", "chart.distribution.dots", "chart.distribution.cut", "chart.plot"] where box(scrolled, n) != box(alone, n) {
                    fail("scroll \(name): \(n) at \(box(scrolled, n)), the visible data alone \(box(alone, n))")
                }
                let lo = Int(visible.map(\.v).min() ?? 0), hi = Int(visible.map(\.v).max() ?? 0)
                let want = "\(max(GlucoseStats.minMg, min(b.low - 22, b.veryLow - 6, lo - 6)))-\(min(GlucoseStats.maxMg, max(b.high + 40, b.veryHigh + 8, hi + 8)))"
                if info(scrolled, "chart.y") != want { fail("scroll \(name): y range \(info(scrolled, "chart.y")), want \(want)") }
                let window = back > 0 ? "\(Int((from - now) / 60))m..\(Int((end - now) / 60))m" : ""
                if info(scrolled, "chart.window") != window { fail("scroll \(name): window \"\(info(scrolled, "chart.window"))\", want \"\(window)\"") }
                // treatment labels only up to rules.json "treatmentLabels" maxHours
                let maxLabel = num((json("layout/rules.json")?["treatmentLabels"] as? [String: Any])?["maxHours"]) * 3600
                if GlucoseGraph.labelsMaxS != maxLabel { fail("labels: GlucoseGraph.labelsMaxS \(GlucoseGraph.labelsMaxS), rules.json \(maxLabel)") }
                let labelled = !scrolled.all("chart.label").isEmpty
                if w > maxLabel && labelled { fail("scroll \(name): a \(Int(w / 3600)) h window draws treatment labels") }
                if w <= maxLabel && !labelled && sc.marks.contains(where: { $0.k != "note" && $0.t >= from && $0.t <= end && cfg.marks.label($0.k) }) {
                    fail("scroll \(name): a \(Int(w / 3600)) h window with treatments draws no labels")
                }
                let away = back > 0 || zs != nil
                if (scrolled.first("chart.now") != nil) != away { fail("scroll \(name): the Now pill \(away ? "is missing" : "shows at now")") }
                for (k, v) in c["expect"] as? [String: String] ?? [:] {
                    let got = scrolled.all(k).map { $0.info.replacingOccurrences(of: "\\s*\\[[^]]*]", with: "", options: .regularExpression) }.joined(separator: ",")
                    if got != v { fail("scroll \(name): \(k) \"\(got)\", expect.json-style answer \"\(v)\" (Android agrees)") }
                }
            }
            print("scroll cases: \((scroll["cases"] as? [Any])?.count ?? 0) checked")
            // the cost of one scrolled frame of the dropdown's card over 31 days of 1-minute readings (the store's whole
            // history), as a fling draws it: printed, and a failure only far above a frame's budget
            let long = (0..<(31 * 1440)).map { i -> GraphReading in
                let x = Double(i)
                let t: Double = now - Double(31 * 1440 - i) * 60
                let mg: Double = 130 + 70 * sin(x / 180) + 25 * sin(x / 37)
                return GraphReading(t: t, v: mg, rate: 0)
            }
            var times: [Double] = []
            let v = CardView(frame: NSRect(origin: .zero, size: size))
            v.readings = long; v.style = StatusController.dropdownStyle(cfg, sensor: nil); v.alerts = AlertSettings()
            v.now = Date(timeIntervalSince1970: now)
            v.metricsOf = { _, _ in GlucoseMetrics.of([], from: 0, to: 1, bands: b) }
            if let ctx = CGContext(data: nil, width: Int(size.width * 2), height: Int(size.height * 2), bitsPerComponent: 8, bytesPerRow: 0,
                                   space: CGColorSpace(name: CGColorSpace.sRGB)!, bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) {
                ctx.translateBy(x: 0, y: size.height * 2); ctx.scaleBy(x: 2, y: -2)
                for k in 0..<400 {
                    v.scrollEnd = now - 3600 - Double(k) * 97
                    let t0 = CACurrentMediaTime()
                    v.paint(ctx, size: size, pal)
                    if k >= 100 { times.append((CACurrentMediaTime() - t0) * 1000) }
                }
            }
            times.sort()
            let med = times.isEmpty ? 0 : times[times.count / 2], p95 = times.isEmpty ? 0 : times[times.count * 95 / 100]
            print("scrolled dropdown frame over \(long.count) readings: median \(String(format: "%.2f", med)) ms, p95 \(String(format: "%.2f", p95)) ms")
            #if DEBUG
            let budget = 80.0   // an unoptimised build over 31 days, on a busy machine (release: ~2.5 ms on an M-series Mac, 2026-10-21)
            #else
            let budget = 16.0
            #endif
            // the widest zoom (the whole 31 days): every reading in the window, drawn as columns
            var wide: [Double] = []
            if let ctx = CGContext(data: nil, width: Int(size.width * 2), height: Int(size.height * 2), bitsPerComponent: 8, bytesPerRow: 0,
                                   space: CGColorSpace(name: CGColorSpace.sRGB)!, bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) {
                ctx.translateBy(x: 0, y: size.height * 2); ctx.scaleBy(x: 2, y: -2)
                v.scrollSpan = ChartView.maxSpan
                for k in 0..<60 {
                    v.scrollEnd = k % 2 == 0 ? nil : now - 60
                    let t0 = CACurrentMediaTime()
                    v.paint(ctx, size: size, pal)
                    if k >= 20 { wide.append((CACurrentMediaTime() - t0) * 1000) }
                }
                v.scrollSpan = nil
            }
            wide.sort()
            let wideMed = wide.isEmpty ? 0 : wide[wide.count / 2]
            print("zoomed out (31 d) dropdown frame: median \(String(format: "%.2f", wideMed)) ms")
            #if DEBUG
            let wideBudget = 200.0   // an unoptimised build walks 44 640 readings in slow Swift (release: ~5 ms, 2026-10-21)
            #else
            let wideBudget = 16.0
            #endif
            if wideMed > wideBudget { fail("zoom: a 31-day frame takes \(String(format: "%.1f", wideMed)) ms (median)") }
            if med > budget { fail("scroll: a scrolled frame takes \(String(format: "%.1f", med)) ms (median), far above a frame (\(Int(budget)) ms)") }
        } else { fail("scenes.json has no \"scroll\"") }

        // a change of the 24-hour setting or the time zone redraws (StatusController observes Clock.changeNotifications)
        let center = NotificationCenter(); var fired = 0
        let tokens = Clock.observeChanges(center) { fired += 1 }
        for n in Clock.changeNotifications { center.post(name: n, object: nil) }
        RunLoop.main.run(until: Date().addingTimeInterval(0.2))
        if fired != 2 || !Clock.changeNotifications.contains(NSLocale.currentLocaleDidChangeNotification) {
            fail("clock: a 24-hour / time zone change fired \(fired) of 2 redraws")
        }
        tokens.forEach(center.removeObserver)
        print(failures == 0 ? "layout self-test OK (\(renders.count) renders)" : "layout self-test: \(failures) failure(s)")
        return failures
    }
}
