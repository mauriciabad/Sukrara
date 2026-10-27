// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation
import SukraraAlerts
import SukraraCore
import SukraraTesting
import Testing

/// One timeline of `contracts/alerts/scenarios/*.txt` (format and driver: SPEC §7), the same files Android runs.
struct Scenario: CustomTestStringConvertible, Sendable {
    struct Line: Sendable {
        var minute: Int
        var second: Int
        var events: [String]
        var expect: [String]
    }

    var file: String
    var title: String
    var settings: [String]
    var lines: [Line]
    var end: Int
    var mirror: Bool

    var testDescription: String { "\(file): \(title)" }

    static var directory: URL { Contracts.url("alerts/scenarios") }

    /// Every scenario of every file, in file order.
    static func all() -> [Scenario] {
        let names = (try? FileManager.default.contentsOfDirectory(atPath: directory.path)) ?? []
        return names.filter { $0.hasSuffix(".txt") }.sorted().flatMap { name in
            parse(name, (try? String(contentsOf: directory.appendingPathComponent(name), encoding: .utf8)) ?? "")
        }
    }

    static func parse(_ file: String, _ text: String) -> [Scenario] {
        func trim(_ s: some StringProtocol) -> String { s.trimmingCharacters(in: .whitespaces) }
        var out: [Scenario] = []
        var title: String?
        var settings: [String] = []
        var lines: [Line] = []
        var end: Int?
        var mirror = false
        func flush() {
            guard let t = title else { return }
            out.append(
                Scenario(
                    file: file, title: t, settings: settings, lines: lines, end: end ?? lines.map(\.minute).max() ?? 0,
                    mirror: mirror))
            title = nil
            settings = []
            lines = []
            end = nil
            mirror = false
        }
        for raw in text.components(separatedBy: "\n") {
            let l = trim(raw.split(separator: "#", maxSplits: 1, omittingEmptySubsequences: false).first ?? "")
            if l.isEmpty { continue }
            if l.hasPrefix("scenario ") {
                flush()
                title = trim(l.dropFirst(9))
            } else if l.hasPrefix("settings ") {
                settings = trim(l.dropFirst(9)).split(whereSeparator: \.isWhitespace).map(String.init)
            } else if l.hasPrefix("end ") {
                end = Int(trim(l.dropFirst(4)))
            } else if l == "mirror" {
                mirror = true
            } else {
                let (left, right) =
                    l.range(of: "=>").map { (trim(l[..<$0.lowerBound]), String(l[$0.upperBound...])) } ?? (l, "")
                let time = String(left.prefix { $0 != " " })
                let ms = time.split(separator: ":").map { Int($0) ?? 0 }
                let at = ms[0] * 60 + (ms.count > 1 ? ms[1] : 0)
                let events = left.dropFirst(time.count).split(separator: ",").map(trim).filter { !$0.isEmpty }
                let expect = right.split(separator: ";").map(trim).filter { !$0.isEmpty && $0 != "nothing" }
                lines.append(Line(minute: at / 60, second: at, events: events, expect: expect))
            }
        }
        flush()
        return out
    }
}

enum ScenarioError: Error, CustomStringConvertible {
    case bad(String)
    var description: String {
        switch self {
        case .bad(let s): s
        }
    }
}

/// One `key=value` of a `settings` line or `set` event on top of `s`.
func applySetting(_ s: inout AlertSettings, _ kv: String) throws {
    let p = kv.split(separator: "=", maxSplits: 1).map(String.init)
    guard p.count == 2 else { throw ScenarioError.bad("bad setting \(kv)") }
    let (k, v) = (p[0], p[1])
    func int() throws -> Int {
        guard let i = Int(v) else { throw ScenarioError.bad("not a number: \(kv)") }
        return i
    }
    func quiet(_ current: QuietRule) throws -> QuietRule {
        if v == "off" { return QuietRule(on: false, min: current.min) }
        guard let m = Double(v) else { throw ScenarioError.bad("not a number: \(kv)") }
        return QuietRule(on: true, min: m)
    }
    switch k {
    case "alerts": s.on = v == "on"
    case "carbs.mute": s.muteAfterCarbs = try quiet(s.muteAfterCarbs)
    case "insulin.mute": s.muteAfterInsulin = try quiet(s.muteAfterInsulin)
    case "falling.mute": s.muteFallingHigh = try quiet(s.muteFallingHigh)
    case "mute.min": s.notificationActions.muteMin = try int()
    case "relax.min": s.notificationActions.relaxMin = try int()
    case "back.mgdl": s.notificationActions.backMgdl = try int()
    case "back.min": s.notificationActions.backMin = try int()
    default:
        let parts = k.split(separator: ".", maxSplits: 1).map(String.init)
        guard let kind = AlertKind(rawValue: parts[0]) else { throw ScenarioError.bad("unknown setting \(kv)") }
        if parts.count == 1 {
            switch v {
            case "on": s[kind].on = true
            case "off": s[kind].on = false
            default:
                guard let x = Double(v) else { throw ScenarioError.bad("not a number: \(kv)") }
                s[kind].threshold = x
            }
        } else if parts[1] == "repeat" {
            s[kind].repeatMin = try int()
        } else {
            throw ScenarioError.bad("unknown setting \(kv)")
        }
    }
}

/// Runs `sc` (driver: SPEC §7) and returns one line per minute that differs; empty when everything matches. A `mirror`
/// scenario also runs a follower mirroring the wearer: `AlertMirror` over a second engine that gets everything but the
/// wearer's notification actions (what the wearer publishes, SPEC §2).
func runScenario(_ sc: Scenario) throws -> [String] {
    let t0 = 1_760_000_040  // a whole minute
    var s = AlertSettings()
    for kv in sc.settings { try applySetting(&s, kv) }
    var clock = Date(timeIntervalSince1970: TimeInterval(t0))
    var treatments: [(kind: TreatmentKind, at: Date, deleted: Bool)] = []
    var got: [Int: [String]] = [:]
    var follower = AlertMirror()
    var published: [String: AlertFiring] = [:]

    func record(_ what: String) { got[Int(clock.timeIntervalSince1970) / 60 - t0 / 60, default: []].append(what) }
    func live() -> [AlertTreatment] {
        treatments.filter { !$0.deleted }.map { AlertTreatment(kind: $0.kind, at: $0.at) }
    }

    func wearerEffects(_ effects: [AlertEffect]) {
        for e in effects {
            switch e {
            case .fire(let f): record(f.headline)
            case .clear(let f): record("clear \(f.kind.rawValue)")
            case .back(let side): record("back \(side.rawValue)")
            case .resume(let f): record("resume \(f.kind.rawValue)")
            case .schedule: break
            }
        }
    }
    func publishedEffects(_ effects: [AlertEffect]) {
        for e in effects {
            switch e {
            case .fire(let f):
                published[f.id] = f
                if follower.fire(f.kind, id: f.id, at: f.at) { record("follower \(f.headline)") }
            case .clear(let f):
                if follower.clear(f.kind, id: f.id) { record("follower clear \(f.kind.rawValue)") }
            case .back, .resume, .schedule: break
            }
        }
    }

    var wearer = AlertEngine(context: AlertContext(settings: s))
    // mirror: the wearer's shared engine (no notification actions: what it publishes), feeding `follower`
    var shared = AlertEngine(context: AlertContext(settings: s))
    func both(_ input: AlertInput) {
        wearerEffects(wearer.handle(input, at: clock))
        if sc.mirror { publishedEffects(shared.handle(input, at: clock)) }
    }
    func followerReading(_ r: GlucoseSample?) {
        guard sc.mirror else { return }
        for side in follower.reading(r, s, at: clock) { record("follower back \(side.rawValue)") }
    }
    func minutes(_ w: [String], _ from: Int) throws -> Int? {
        guard w.count > from else { return nil }
        let t = w[from...].joined(separator: " ")
        guard let m = Durations.parse(t) else { throw ScenarioError.bad("not a duration: \(t)") }
        return m
    }
    func allEnd(_ w: [String], _ from: Int) throws -> Date {
        if w.count > from && w[from] == "never" { return muteAllForever }
        guard let m = try minutes(w, from) else { throw ScenarioError.bad("mute all needs a length") }
        return clock.addingTimeInterval(TimeInterval(m * 60))
    }
    func side(_ name: String) throws -> AlertSide {
        guard let k = AlertKind(rawValue: name) else { throw ScenarioError.bad("unknown kind \(name)") }
        return k.side
    }
    func newestKind(_ g: AlertGroup) -> AlertKind? {
        published.values.filter { $0.kind.group == g }.max { $0.at < $1.at }?.kind
    }

    var glucose = 0.0
    var rate = 0.0
    var age = 0
    var sensorOn = true
    var every = 1
    var nextReading: Int?
    var timer: Int?
    guard Set(sc.lines.map(\.minute)).count == sc.lines.count else {
        throw ScenarioError.bad("\(sc.file): \(sc.title): two lines for the same minute")
    }
    let byTime = Dictionary(uniqueKeysWithValues: sc.lines.map { (t0 + $0.second, $0) })
    let stop = t0 + (sc.end + 1) * 60
    var t = t0
    while t < stop {
        clock = Date(timeIntervalSince1970: TimeInterval(t))
        for ev in byTime[t]?.events ?? [] {
            let w = ev.split(whereSeparator: \.isWhitespace).map(String.init)
            func after(_ key: String) -> String? {
                w.firstIndex(of: key).flatMap { $0 > 0 && $0 + 1 < w.count ? w[$0 + 1] : nil }
            }
            switch w[0] {
            case "glucose":
                glucose = Double(w[1]) ?? 0
                rate = after("rate").flatMap(Double.init) ?? 0
                age = after("age").flatMap(Int.init) ?? 0
                if nextReading == nil { nextReading = t }
            case "readings": every = Int(w[2]) ?? 1
            case "sensor":
                switch w[1] {
                case "ended":
                    sensorOn = false
                    both(
                        .sensor(
                            SensorWindow(start: clock - 15 * 86400, end: clock, warmupEnd: clock - 15 * 86400 + 3600)))
                case "started":
                    sensorOn = false
                    both(.sensor(SensorWindow(start: clock, end: clock + 15 * 86400, warmupEnd: clock + 3600)))
                default:
                    sensorOn = w[1] == "on"
                    if sensorOn { nextReading = t }
                }
            case "carbs", "insulin":
                treatments.append((w[0] == "carbs" ? .carbs : .rapidInsulin, clock, false))
                both(.treatments(live()))
            case "delete":
                let k: TreatmentKind = w[1] == "carbs" ? .carbs : .rapidInsulin
                if let i = treatments.lastIndex(where: { $0.kind == k && !$0.deleted }) { treatments[i].deleted = true }
                both(.treatments(live()))
            case "mute":
                if w[1] == "all" {
                    wearerEffects(wearer.handle(.muteAll(until: try allEnd(w, 2)), at: clock))
                } else {
                    let m = try minutes(w, 2) ?? s.notificationActions.muteMin
                    wearerEffects(wearer.handle(.mute(try side(w[1]), minutes: m), at: clock))
                }
            case "relax": wearerEffects(wearer.handle(.remindLess(try side(w[1])), at: clock))
            case "unmute":
                wearerEffects(wearer.handle(w[1] == "all" ? .unmuteAll : .unmute(try side(w[1])), at: clock))
            case "unrelax": wearerEffects(wearer.handle(.remindAsUsual(try side(w[1])), at: clock))
            case "follower":
                func show(_ id: String?) { if let id, let f = published[id] { record("follower \(f.headline)") } }
                switch (w[1], w.count > 2 ? w[2] : "") {
                case ("mute", "all"):
                    for g in follower.muteAll(until: try allEnd(w, 3)) {
                        record("follower clear \(newestKind(g)?.rawValue ?? g.rawValue)")
                    }
                case ("mute", let k):
                    let sd = try side(k)
                    let m = try minutes(w, 3) ?? s.notificationActions.muteMin
                    if follower.mute(sd, until: clock.addingTimeInterval(TimeInterval(m * 60))) {
                        record("follower clear \(k)")
                    }
                case ("relax", let k): follower.remindLess(try side(k), relaxMin: s.notificationActions.relaxMin)
                case ("unmute", "all"): for id in follower.unmuteAll(at: clock) { show(id) }
                case ("unmute", let k): show(follower.unmute(try side(k), at: clock))
                case ("unrelax", let k): show(follower.remindAsUsual(try side(k), at: clock))
                default: throw ScenarioError.bad("unknown event: \(ev)")
                }
            case "set":
                for kv in w.dropFirst() { try applySetting(&s, kv) }
                both(.settings(s))
            case "restart":
                let (restarted, resumed) = AlertEngine.restore(
                    AlertEngine.Snapshot.decode(wearer.snapshot.encoded()), context: wearer.context, at: clock)
                wearer = restarted
                wearerEffects(resumed)
                // the followers' alerts stay as they are
                shared =
                    AlertEngine.restore(
                        AlertEngine.Snapshot.decode(shared.snapshot.encoded()), context: shared.context, at: clock
                    ).0
            default: throw ScenarioError.bad("unknown event: \(ev)")
            }
        }
        if sensorOn, let r = nextReading, r <= t {
            let rd = GlucoseSample(at: clock.addingTimeInterval(TimeInterval(-age * 60)), mgdl: glucose, rate: rate)
            both(.reading(rd))
            followerReading(rd)
            nextReading = t + every * 60
        } else if let tm = timer, tm <= t {
            both(.tick)
            followerReading(wearer.context.latest)
        }
        timer = [wearer.nextCheck(after: clock), sc.mirror ? shared.nextCheck(after: clock) : nil].compactMap { $0 }
            .min()
            .map { Int($0.timeIntervalSince1970.rounded(.up)) }
        guard
            let next = [byTime.keys.filter({ $0 > t }).min(), sensorOn ? nextReading : nil, timer].compactMap({ $0 })
                .min()
        else {
            break
        }
        t = next
    }
    // the wearer's and the follower's actions are compared apart (their order within a minute is free)
    func split(_ l: [String]) -> [String] {
        l.filter { !$0.hasPrefix("follower ") } + l.filter { $0.hasPrefix("follower ") }
    }
    let expected = Dictionary(sc.lines.map { ($0.minute, $0.expect) }, uniquingKeysWith: { a, _ in a })
    return (0...sc.end).compactMap { m in
        let want = split(expected[m] ?? [])
        let have = split(got[m] ?? [])
        func show(_ l: [String]) -> String { l.isEmpty ? "[nothing]" : "[\(l.joined(separator: ", "))]" }
        return want == have ? nil : "minute \(m): expected \(show(want)), got \(show(have))"
    }
}
