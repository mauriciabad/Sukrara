// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation
import SukraraCore
import SukraraTesting
import Testing

/// `contracts/<path>`, or the frozen PoC copy it is made from until the contracts land.
private func fixture(_ contractsPath: String, poc: String) -> URL {
    Contracts.exists(contractsPath)
        ? Contracts.url(contractsPath)
        : Contracts.root.deletingLastPathComponent().appending(path: "poc/spikes/\(poc)")
}

private func json(_ url: URL) -> [String: Any] {
    guard let data = FileManager.default.contents(atPath: url.path),
        let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
    else {
        Issue.record("unreadable fixture \(url.path)")
        return [:]
    }
    return root
}

/// One immutable fixture case as a Swift Testing argument.
struct Case: @unchecked Sendable, CustomTestStringConvertible {
    // Immutable after init, so sharing between test tasks is safe.
    let o: [String: Any]
    var testDescription: String { o["name"] as? String ?? "?" }
}

@Suite struct DurationsFixtureTests {
    static let root = Case(o: json(fixture("settings/durations.json", poc: "durations/durations.json")))

    private static func pairs(_ key: String) -> [(Int, String)] {
        (root.o[key] as? [[Any]] ?? []).compactMap { p in
            guard let m = (p.first as? NSNumber)?.intValue, let s = p.last as? String else { return nil }
            return (m, s)
        }
    }

    @Test func formats() {
        for key in ["text", "compact", "whole"] { #expect(!Self.pairs(key).isEmpty, "\(key) cases") }
        for (m, s) in Self.pairs("text") { #expect(Durations.text(m) == s, "text \(m)") }
        for (m, s) in Self.pairs("compact") { #expect(Durations.compact(m) == s, "compact \(m)") }
        for (m, s) in Self.pairs("whole") { #expect(Durations.whole(m) == s, "whole \(m)") }
    }

    @Test func parse() {
        let cases = Self.root.o["parse"] as? [[Any]] ?? []
        #expect(!cases.isEmpty)
        for c in cases {
            guard let input = c.first as? String else { continue }
            #expect(Durations.parse(input) == (c.last as? NSNumber)?.intValue, "parse \"\(input)\"")
        }
    }
}

@Suite struct GlucoseCsvFixtureTests {
    static let cases: [Case] = {
        let dir = fixture("export", poc: "export-fixtures")
        let files = ((try? FileManager.default.contentsOfDirectory(atPath: dir.path)) ?? [])
            .filter { $0.hasSuffix(".json") }.sorted()
        return files.flatMap { f in
            (json(dir.appending(path: f))["cases"] as? [[String: Any]] ?? []).map(Case.init)
        }
    }()

    private static func i64(_ v: Any?) -> Int64 { (v as? NSNumber)?.int64Value ?? 0 }

    @Test func hasCases() { #expect(!Self.cases.isEmpty) }

    @Test func twentyThreeColumns() {
        #expect(GlucoseCsv.header(.mgdl).count == 23)
        #expect(GlucoseCsv.header(.mmol)[4] == "Historic Glucose mmol/L")
    }

    @Test(arguments: cases)
    func writesExactlyWhatAndroidWrites(_ c: Case) throws {
        let o = c.o
        let readings = (o["readings"] as? [[String: Any]] ?? []).map {
            Reading(
                t: Date(timeIntervalSince1970: TimeInterval(Self.i64($0["t"]))), mgdl: Int(Self.i64($0["sgv"])),
                rate: ($0["rate"] as? NSNumber)?.doubleValue)
        }
        let treatments = try (o["treatments"] as? [[String: Any]] ?? []).map { j -> Treatment in
            var j = j
            j["u"] = j["u"] ?? 1
            return try JSONDecoder().decode(Treatment.self, from: JSONSerialization.data(withJSONObject: j))
        }
        let sensor = (o["sensor"] as? [String: Any]).map {
            GlucoseCsv.Sensor(serial: $0["serial"] as? String ?? "", start: Self.i64($0["start"]), end: Self.i64($0["end"]))
        }
        let got = GlucoseCsv.write(
            readings: readings, treatments: treatments, from: Self.i64(o["from"]), to: Self.i64(o["to"]),
            zone: try #require(TimeZone(identifier: o["zone"] as? String ?? "UTC")), generatedAt: Self.i64(o["generated"]),
            sensor: sensor, unit: (o["mmol"] as? Bool) == true ? .mmol : .mgdl)
        let want = (o["expect"] as? [String] ?? []).map { $0 + "\r\n" }.joined()
        #expect(got == want)
    }

    @Test func periods() throws {
        let now: Int64 = 1_760_000_000
        let s = GlucoseCsv.Sensor(serial: "", start: 1_759_000_000, end: 1_760_200_000)
        #expect(GlucoseCsv.Period.allCases.map(\.rawValue) == ["sensor", "7", "14", "30", "all"])
        let sensor = try #require(GlucoseCsv.Period.sensor.range(now: now, sensor: s, oldest: nil))
        #expect(sensor == (1_759_000_000, now + 1))
        #expect(GlucoseCsv.Period.sensor.range(now: now, sensor: nil, oldest: nil) == nil)
        #expect(GlucoseCsv.Period.offered(sensor: nil) == [.d7, .d14, .d30, .all])
        let ended = GlucoseCsv.Sensor(serial: "", start: 1_758_000_000, end: 1_759_000_000)
        #expect(try #require(GlucoseCsv.Period.sensor.range(now: now, sensor: ended, oldest: nil)) == (1_758_000_000, 1_759_000_001))
        #expect(try #require(GlucoseCsv.Period.d14.range(now: now, sensor: nil, oldest: nil)) == (now + 1 - 14 * 86400, now + 1))
        #expect(try #require(GlucoseCsv.Period.all.range(now: now, sensor: nil, oldest: 1_757_000_000)) == (1_757_000_000, now + 1))
    }
}

@Suite struct TreatmentTests {
    private func entry(u: Int64, o: TreatmentOrigin, a: Double = 1, del: Bool = false) -> Treatment {
        Treatment(id: "x", k: .rapid, a: a, t: 100, u: u, del: del, o: o)
    }

    @Test func newerVersionWins() {
        #expect(TreatmentMerge.winner(entry(u: 1, o: .phone), entry(u: 2, o: .follower, a: 2)).a == 2)
        #expect(TreatmentMerge.winner(entry(u: 2, o: .follower, a: 2), entry(u: 1, o: .phone)).a == 2)
    }

    @Test func equalVersionsConvergeOnOrigin() {
        let phone = entry(u: 5, o: .phone, a: 3), mac = entry(u: 5, o: .mac, a: 4)
        #expect(TreatmentMerge.winner(mac, phone) == phone)
        #expect(TreatmentMerge.winner(phone, mac) == phone)
    }

    @Test func aDeletionIsANewerVersion() {
        var log = TreatmentLog(entries: [entry(u: 1, o: .mac)])
        #expect(log.merge([entry(u: 2, o: .mac, del: true)]).count == 1)
        #expect(log.live.isEmpty)
        // an older copy arriving later doesn't bring it back
        #expect(log.merge([entry(u: 1, o: .phone)]).isEmpty)
        #expect(log.live.isEmpty)
    }

    @Test func receiverRulesRejectBadPenEntries() {
        func decode(_ s: String) -> Treatment? { try? JSONDecoder().decode(Treatment.self, from: Data(s.utf8)) }
        let base = #""id":"a","k":"rapid","a":2,"t":1,"u":1,"o":"phone""#
        #expect(decode("{\(base)}")?.n == "")
        #expect(decode("{\(base),\"pr\":-1}") == nil)
        #expect(decode("{\(base),\"pd\":[{\"t\":5,\"a\":1},{\"t\":4,\"a\":1}]}") == nil)
        #expect(decode(#"{"id":"a","k":"pizza","a":2,"t":1,"u":1,"o":"phone"}"#) == nil)
    }

    @Test func notesAreCutToWholeCharactersWithin100Bytes() {
        let t = Treatment(k: .note, a: 0, t: 1, n: String(repeating: "é", count: 60), u: 1, o: .mac)
        #expect(t.n.utf8.count == 100)
    }
}

@Suite struct UnitAndTrendTests {
    @Test func unitsFormatTheStoredMgdl() {
        #expect(GlucoseUnit.mgdl.format(112) == "112")
        #expect(GlucoseUnit.mmol.format(112) == "6.2")
        #expect(GlucoseUnit.mmol.symbol == "mmol/L")
    }

    @Test func trendThresholdsMatchAndroid() {
        #expect(Trend(rate: 0.99) == .steady && Trend(rate: -0.99) == .steady)
        #expect(Trend(rate: 1) == .rising && Trend(rate: 2) == .risingFast)
        #expect(Trend(rate: -1.5) == .falling && Trend(rate: -2) == .fallingFast)
        #expect(Trend.words(rate: nil) == "trend unknown")
    }
}
