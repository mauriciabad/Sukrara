// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import CoreGraphics
import Foundation
import SukraraCore
import Testing

@testable import SukraraGraph

// The shared graph fixtures (contracts/graph, README there): the same files Android's GraphFixtureTest runs, so both
// renderers decide every case the same way.

private let now = GraphFixtures.now

/// A y-down bitmap context of `size` points at 2 px per point, as the surfaces draw into.
func cardContext(_ size: CGSize) -> CGContext {
    let ctx = CGContext(
        data: nil, width: Int(size.width * 2), height: Int(size.height * 2), bitsPerComponent: 8, bytesPerRow: 0,
        space: CGColorSpace(name: CGColorSpace.sRGB)!, bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
    ctx.translateBy(x: 0, y: size.height * 2)
    ctx.scaleBy(x: 2, y: -2)
    return ctx
}

@Suite struct GlucoseCardTests {
    @Test func noValueIsADashNotANumber() {
        #expect(GlucoseCard.noValueText == "—")
        #expect(Int(GlucoseCard.noValueText) == nil)
    }

    @Test func noReadingDrawsNoValue() {
        let st = CardSurface.test.style(GraphConfig(), sensor: nil)
        let lay = GlucoseCard.draw(
            cardContext(CGSize(width: 340, height: 196)), size: CGSize(width: 340, height: 196), readings: [],
            marks: [],
            now: now, st, GraphPalette.of(dark: false), surface: .test, part: .all)
        #expect(lay.all("value.number").isEmpty)
        #expect(lay.first("value.none")?.info == "NONE")
    }
}

/// Every fixture set has cases (a moved or emptied folder would let the parameterized tests pass with none).
@Test(arguments: [
    "freshness/freshness.json", "clock", "sensor", "place/place.json", "mark", "share", "status.json", "labels",
    "trend/trend.json",
])
func fixtureSetHasCases(_ set: String) {
    #expect(!GraphFixtures.cases(set).isEmpty, "\(set) under \(GraphFixtures.dir.path)")
}

/// contracts/graph/trend: the one trend arrow (Android TrendFixtureTest): the drawn angle and double, the text arrow,
/// and the face's `arrowText` every text place writes (a stale value writes the ring, as the card draws it).
@Suite struct TrendFixtureTests {
    @Test(arguments: GraphFixtures.cases("trend/trend.json"))
    func arrow(_ c: JSON) {
        let e = c.expect, rate = c.number("rate")
        #expect(TrendAngle.text(rate) == e.string("text"))
        if let rate, let deg = e.number("deg") {
            #expect(abs(TrendAngle.deg(rate) - deg) < 1e-3)
            #expect(TrendAngle.isDouble(rate) == e.bool("double"))
        } else {
            #expect(rate == nil && e.number("deg") == nil)
        }
        let fresh = Freshness.look(sgv: 100, t: now - 30, rate: rate, now: now, ended: false)
        #expect(fresh.arrowText == e.string("text"))
        let stale = Freshness.look(sgv: 100, t: now - 6 * 60, rate: rate, now: now, ended: false)
        #expect(stale.arrowText == "○")
    }
}

@Suite struct FreshnessFixtureTests {
    static let root = GraphFixtures.object("freshness/freshness.json")

    @Test func redrawTimes() throws {
        let steps = Self.root.child("steps")
        let from = try #require(steps["from"] as? [NSNumber]), next = try #require(steps["next"] as? [Any])
        #expect(from.count == next.count)
        for (f, x) in zip(from, next) {
            // null = the step never changes again
            #expect(Freshness.nextChangeAt(0, now: f.doubleValue) == (x as? NSNumber)?.doubleValue, "step after \(f)")
        }
    }

    @Test(arguments: GraphFixtures.cases("freshness/freshness.json"))
    func look(_ c: JSON) {
        let e = c.expect
        let l = Freshness.look(
            sgv: c.number("sgv").map { Int($0) }, t: c.number("age").map { now - $0 }, rate: c.number("rate"), now: now,
            ended: c.bool("ended") ?? false)
        #expect(l.state.rawValue == e.string("state"))
        #expect(l.text == e.string("text"))
        #expect(l.strike == (e.bool("strike") ?? false))
        #expect(l.trend?.rawValue == e.string("trend"))
        #expect(l.fresh.showTime == (e.bool("time") ?? false))
        #expect(l.fresh.showGraph == (e.bool("graph") ?? false))
    }

    /// The card draws what `look` decided: the value (struck), the no-trend ring, the time, the chart.
    @Test(arguments: GraphFixtures.cases("freshness/freshness.json"))
    func card(_ c: JSON) {
        let e = c.expect
        let state = e.string("state") ?? "?", strike = e.bool("strike") ?? false
        var readings: [GraphReading] = []
        if let age = c.number("age"), let sgv = c.number("sgv") {
            let t = now - age
            readings = (1...60).reversed().map { GraphReading(t: t - Double($0) * 60, v: 120, rate: 0) }
            readings.append(GraphReading(t: t, v: sgv, rate: c.number("rate")))
        }
        var st = CardStyle(graph: GraphStyle(), rings: [], sensorTime: false)
        if c.bool("ended") == true {
            st.sensor = SensorInfo(start: now - 15 * 86400 - 3600, end: now - 3600, warmupEnd: now - 15 * 86400)
        }
        let size = CGSize(width: 360, height: 200)
        let lay = GlucoseCard.draw(
            cardContext(size), size: size, readings: readings, marks: [], now: now, st, GraphPalette.of(dark: true),
            surface: .test, part: .all)
        switch state {
        case "value":
            #expect(lay.all("value.number").map(\.info) == [(e.string("text") ?? "") + (strike ? " struck" : "")])
            #expect((lay.first("value.trend")?.info == "ring") == (e.string("trend") == "none"))
            #expect((lay.first("value.time") != nil) == (e.bool("time") ?? false))
        case "no_readings": #expect(lay.first("value.none")?.info == "NO_READINGS")
        case "none": #expect(lay.first("value.none")?.info == "NONE")
        case "ended": #expect(lay.first("value.ended") != nil)
        default: Issue.record("unknown state \(state)")
        }
        if state != "ended" { #expect((lay.first("chart") != nil) == (e.bool("graph") ?? false)) }
    }

    /// A card without a value (the graph widget) shows its data's age: the time label, the dimmed chart, the icon.
    @Test(arguments: GraphFixtures.cases("freshness/freshness.json"))
    func graphOnly(_ c: JSON) {
        let want = c.expect.string("graph_only")
        let fr = Freshness.of(age: c.number("age"))
        #expect(fr.ageMark.rawValue == want)
        var readings: [GraphReading] = []
        if let age = c.number("age"), let sgv = c.number("sgv") {
            readings = (1...60).reversed().map { GraphReading(t: now - age - Double($0) * 60, v: 120, rate: 0) }
            readings.append(GraphReading(t: now - age, v: sgv, rate: c.number("rate")))
        }
        let st = CardStyle(graph: GraphStyle(), rings: [], value: false, sensorTime: false)
        let size = CGSize(width: 332, height: 138)
        let lay = GlucoseCard.draw(
            cardContext(size), size: size, readings: readings, marks: [], now: now, st, GraphPalette.of(dark: false),
            surface: .widgetGraph, part: .all)
        switch want {
        case "none": #expect(lay.first("graph.age") == nil && lay.first("chart") != nil)
        case "face": #expect(lay.first("graph.age") == nil && lay.first("value.none") != nil)
        default:
            #expect(lay.first("graph.age")?.info == want)
            #expect(lay.first("chart") != nil)
        }
        // VoiceOver hears the same age (nothing extra while fresh)
        let spoken = GlucoseCard.accessibilityLabel(
            CardData(readings: readings, now: now), unit: .mgdl, surface: .widgetGraph, config: GraphConfig(),
            words: .en)
        #expect(spoken.hasPrefix("Glucose:") == (want != "none"), "\(spoken)")
        #expect(spoken.contains("old reading") == (want == "dimmed"), "\(spoken)")
    }
}

@Suite struct ClockFixtureTests {
    /// U+00A0 and U+202F (the day period's spaces) compare as plain spaces.
    private func plain(_ s: String) -> String {
        s.replacingOccurrences(of: "\u{00A0}", with: " ").replacingOccurrences(of: "\u{202F}", with: " ")
    }

    @Test(arguments: GraphFixtures.cases("clock"))
    func timeAndHourLabel(_ c: JSON) {
        let locale = Locale(identifier: c.string("locale") ?? "en-US")
        let minute = Int(c.number("minute") ?? 0), hour24 = c.bool("hour24") ?? true
        #expect(plain(Clock.minuteOfDay(minute, hour24: hour24, locale: locale)) == c.expect.string("time"))
        #expect(plain(Clock.hourLabel(minute / 60, hour24, locale: locale)) == c.expect.string("axis"))
    }
}

@Suite struct SensorLifeFixtureTests {
    @Test(arguments: GraphFixtures.cases("sensor"))
    func badgeWarmupAndRing(_ c: JSON) throws {
        let start = now + (c.number("start") ?? 0)
        let info = SensorInfo(
            start: start, end: now + (c.number("end") ?? 0),
            warmupEnd: c.number("warmup").map { now + $0 } ?? start + 3600)
        let e = c.expect
        let look = try #require(SensorBadge.look(info, now: now, latest: c.number("latest").map { now + $0 }))
        let ring = SensorBadge.ring(look, info, now: now, hours: Int(c.number("countdownHours") ?? 8))
        #expect(look.label == e.string("label"))
        #expect(look.ended == e.bool("ended"))
        #expect(abs(look.life - (e.number("life") ?? .nan)) <= 0.001)
        #expect(look.warmup == e.string("warmup"))
        if let want = e.number("ring") { #expect(abs((ring ?? .nan) - want) <= 0.001) } else { #expect(ring == nil) }
        let pill = look.ended ? StatusBadge.replaceSensor : nil
        let wp = e.child("pill")
        #expect(pill?.kind == wp.string("kind") && pill?.text == wp.string("text") && pill?.short == wp.string("short"))
    }
}

@Suite struct PlaceFixtureTests {
    static let root = GraphFixtures.object("place/place.json")

    @Test func roomsKeptFreeOfLabels() {
        for r in Self.root["rooms"] as? [[String: Any]] ?? [] {
            let label = r["label"] as? String ?? ""
            let got = (Double(GlucoseGraph.sensorWidth(label, d: 1)) * 10).rounded() / 10
            #expect(abs(((r["room"] as? NSNumber)?.doubleValue ?? .nan) - got) <= 0.05, "room \(label)")
        }
    }

    @Test(arguments: GraphFixtures.cases("place/place.json"))
    func sensorSpot(_ c: JSON) {
        let card = c.child("card")
        var g = GraphStyle()
        g.axisLabels = card.bool("axisLabels") ?? true
        let st = CardStyle(
            graph: card.bool("graph") == true ? g : nil, value: card.bool("value") ?? true,
            sensorTime: card.bool("sensorTime") ?? true)
        let latest = now - (c.number("age") ?? 60)
        let kind = c.string("sensor") ?? "none"
        let info: SensorInfo? =
            switch kind {
            case "live": SensorInfo(start: now - 2 * 86400, end: now + 13 * 86400, warmupEnd: now - 2 * 86400 + 3600)
            case "warmup": SensorInfo(start: now - 600, end: now + 15 * 86400 - 600, warmupEnd: now + 3000)
            case "ended": SensorInfo(start: now - 15 * 86400 - 3600, end: now - 3600, warmupEnd: now - 15 * 86400)
            default: nil
            }
        let look = SensorBadge.look(info, now: now, latest: kind == "warmup" ? nil : latest)
        #expect(GlucoseCard.sensorSpot(st, look, Freshness.of(age: now - latest)).rawValue == c.expect.string("spot"))
        let chart = c.child("chart")
        if let h = chart.number("h") {
            let top = GlucoseGraph.sensorTop(CGFloat(h), lane1: 0, d: 1)
            let want = (c.expect["box"] as? [NSNumber])?.map(\.doubleValue) ?? []
            #expect(want.count == 3)
            if want.count == 3 {
                #expect(want[0] == 0)
                #expect(abs(Double(top) - want[1]) < 0.05)
                #expect(abs(Double(GlucoseGraph.sensorDp) - want[2]) < 0.05)
            }
        }
    }
}

@Suite struct MarkFixtureTests {
    @Test(arguments: GraphFixtures.cases("mark"))
    func ringMarkAndCrown(_ c: JSON) {
        let t = c["tir"] as? [String: Double] ?? [:], e = c.expect
        let tir = TimeInRange(
            veryLow: t["veryLow"] ?? 0, low: t["low"] ?? 0, tight: t["tight"] ?? 0, target: t["target"] ?? 0,
            high: t["high"] ?? 0, veryHigh: t["veryHigh"] ?? 0, coverage: t["coverage"] ?? 0)
        let range: TirRange = c.string("range") == "tight" ? .tight : .inRange
        let col = GlucoseGraph.pct3(tir.below, tir.inRange, tir.above)
        #expect(TirMark.of(tir, range).rawValue == e.string("mark"))
        #expect(ShareText.of(tir.of(range), range == .inRange ? .inRange : nil) == e.string("text"))
        #expect([col.0, col.1, col.2] == (e["column"] as? [Int] ?? []))
        #expect(TirMark.column(tir) == (e.bool("crown") ?? false))
        #expect((Bloom.of(tir, range)?.rawValue ?? "none") == e.string("flower"))
    }
}

@Suite struct ShareFixtureTests {
    @Test(arguments: GraphFixtures.cases("share"))
    func shareText(_ c: JSON) {
        let goals: [String: Goal] = [
            "veryHigh": .veryHigh, "above": .above, "inRange": .inRange, "below": .below, "veryLow": .veryLow,
        ]
        let locale = Locale(identifier: c.string("locale") ?? "en-US")
        let x = c.number("x") ?? 0, goal = c.string("goal")
        let got =
            goal == "coverage"
            ? ShareText.coverage(x, locale: locale) : ShareText.of(x, goal.flatMap { goals[$0] }, locale: locale)
        #expect(got == c.string("expect"))
    }
}

/// The treatment amounts of the status fixtures (the status / last-treatment decision itself is the alert engine's).
@Suite struct AmountFixtureTests {
    @Test(arguments: GraphFixtures.cases("status.json"))
    func amounts(_ c: JSON) {
        let marks = (c["treatments"] as? [[String: Any]] ?? []).map {
            GraphMark(
                t: now + (($0["t"] as? NSNumber)?.doubleValue ?? 0), k: $0["k"] as? String ?? "",
                a: ($0["a"] as? NSNumber)?.doubleValue ?? 0, n: $0["n"] as? String ?? "", id: $0["id"] as? String ?? "")
        }
        for (id, want) in c.expect["amounts"] as? [String: Any] ?? [:] {
            let mark = marks.first { $0.id == id }
            #expect(mark != nil, "amount \(id) exists")
            if let mark { #expect(TreatmentMarks.amount(mark) == want as? String, "amount \(id)") }
        }
    }
}

@Suite struct LabelFixtureTests {
    static let files: [String: (pct: CGFloat, value: CGFloat)] = {
        let names =
            (try? FileManager.default.contentsOfDirectory(atPath: GraphFixtures.dir.appending(path: "labels").path))
            ?? []
        return Dictionary(
            uniqueKeysWithValues: names.filter { $0.hasSuffix(".json") }.map { f in
                let o = GraphFixtures.object("labels/\(f)")
                return ("labels/\(f)", (CGFloat(o.number("pctLabel") ?? 0), CGFloat(o.number("valueLabel") ?? 0)))
            })
    }()

    static func marksOf(_ m: Any?) -> MarkShow {
        if m == nil { return .defaults }
        if (m as? String) == "all" { return .all }
        guard let o = m as? [String: [String]] else { return .defaults }
        return MarkShow.parse(o.map { "\($0.key):" + $0.value.joined(separator: "+") }.joined(separator: ","))
    }

    @Test(arguments: GraphFixtures.cases("labels"))
    func layout(_ c: JSON) throws {
        func r(_ v: CGFloat) -> Double { (Double(v) * 2).rounded() / 2 }
        func near(_ a: Any?, _ b: Double) -> Bool {
            ((a as? NSNumber)?.doubleValue).map { abs($0 - b) <= 0.5 } ?? false
        }
        let file = try #require(c.testDescription.components(separatedBy: ": ").first)
        let widths = try #require(Self.files[file])
        let chart = c.child("chart")
        let w = CGFloat(chart.number("w") ?? 0), h = CGFloat(chart.number("h") ?? 0)
        let window = (c.number("hours") ?? 12) * 3600
        let (left, right) = GlucoseGraph.plotX(
            0, w, pct: c.bool("pct") ?? true, labels: true, d: 1, pctW: widths.pct, valW: widths.value)
        let from = now - window
        let marks = (c["treatments"] as? [[String: Any]] ?? []).map {
            GraphMark(
                t: now + (($0["t"] as? NSNumber)?.doubleValue ?? 0), k: $0["k"] as? String ?? "",
                a: ($0["a"] as? NSNumber)?.doubleValue ?? 0, id: $0["id"] as? String ?? "")
        }.filter { $0.t >= from && $0.t <= now }
        let lanes = GlucoseGraph.lanes(h, d: 1)
        let lay = TreatmentMarks.layout(
            marks, x: { left + CGFloat(($0 - from) / window) * (right - left) }, now: now, minX: 0, maxX: w,
            lanes: lanes,
            d: 1, show: Self.marksOf(c["marks"]))
        let got = lay.labels.sorted { $0.left + $0.w / 2 < $1.left + $1.w / 2 }
        // the width model holds the real text (SF Pro) of every label
        let bold = Ink.font(TreatmentMarks.labelSize, .bold), medium = Ink.font(TreatmentMarks.labelSize, .medium)
        for l in got {
            let real =
                l.amounts.reduce(0) { $0 + Ink.width($1, bold) }
                + CGFloat(max(0, l.amounts.count - 1)) * Ink.width(TreatmentMarks.sep, medium)
                + (l.time.map { Ink.width((l.amounts.isEmpty ? "" : " ") + $0, medium) } ?? 0)
            #expect(real <= l.w + 0.01, "\"\(l.text)\" is \(real) wide, modelled \(l.w)")
        }
        let e = c.expect
        let plot = e["plot"] as? [Any] ?? []
        #expect(plot.count == 2 && near(plot[0], r(left)) && near(plot[1], r(right)), "plot [\(r(left)), \(r(right))]")
        #expect((e["lanes"] as? Int) == lanes)
        #expect((e["dropped"] as? [[String]] ?? []) == lay.dropped.map { $0.map(\.id) })
        let want = e["labels"] as? [[String: Any]] ?? []
        let dump = got.map { "{\($0.members.map(\.id)) \"\($0.text)\" lane \($0.lane) x \(r($0.left + $0.w / 2))}" }
        #expect(
            want.count == got.count
                && zip(want, got).allSatisfy { wl, l in
                    (wl["ids"] as? [String]) == l.members.map(\.id) && (wl["text"] as? String) == l.text
                        && (wl["lane"] as? Int) == l.lane && near(wl["x"], r(l.left + l.w / 2))
                }, "labels \(dump)")
    }
}
