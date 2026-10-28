// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation
import SukraraCore
import SukraraGraph
import SukraraTesting
import Testing

@testable import SukraraApp

@Suite struct ReportTests {
    let clock = FakeClock()

    /// Every 5 minutes for `days`, at `mgdl(i)`.
    func readings(days: Int, _ mgdl: (Int) -> Int) -> [Reading] {
        let now = clock.now
        return (0..<(days * 288)).map { i in
            Reading(t: now.addingTimeInterval(-TimeInterval(i) * 300), mgdl: mgdl(i))
        }
    }

    func model(_ rs: [Reading], store: InMemorySettingsStore = InMemorySettingsStore()) -> (AppModel, ReportModel) {
        let plan = LaunchPlan(
            mode: .normal, services: .none, data: .isolated(root: FileManager.default.temporaryDirectory))
        let app = AppModel(plan: plan, clock: clock)
        app.apply(readings: rs)
        return (app, ReportModel(store: store, app: app))
    }

    /// A steady 154 mg/dL: all in range, the average just misses its goal (< 154), GMI 6.99 % meets < 7 %, CV 0.
    @Test func reportNumbersForASteadyValue() {
        let r = GlucoseReport.of(
            readings(days: 14) { _ in 154 }.map { GraphReading(t: $0.t.timeIntervalSince1970, v: Double($0.mgdl)) },
            days: 14, now: clock.now.timeIntervalSince1970, config: GraphConfig(), unit: .mgdl)
        #expect(!r.empty && !r.lowCoverage, "\(r.coverage) \(r.rows.map(\.share))")
        let target = r.rows.first { $0.kind == .target }
        #expect(target?.share == "100" && target?.met == true)
        #expect(r.rows.first { $0.kind == .below }?.share == "0.0")
        #expect(r.average.value == "154 mg/dL" && !r.average.met)
        #expect(r.gmi.value.hasPrefix("7.0 %") && r.gmi.met)
        #expect(r.cv.value == "0.0 %" && r.cv.met)
        #expect(r.bar.count == 1)
    }

    /// Half the time at 60 (low), half at 120: the shares and goals follow the time-in-range rules (ShareText).
    @Test func reportSharesAndGoals() {
        let rs = readings(days: 7) { $0 % 2 == 0 ? 60 : 120 }
        let r = GlucoseReport.of(
            rs.map { GraphReading(t: $0.t.timeIntervalSince1970, v: Double($0.mgdl)) }, days: 7,
            now: clock.now.timeIntervalSince1970, config: GraphConfig(), unit: .mmol)
        let below = r.rows.first { $0.kind == .below }
        #expect(below?.share == "50" && below?.met == false)
        #expect(r.rows.first { $0.kind == .veryLow }?.met == true)
        #expect(r.average.value.hasSuffix("mmol/L"))
        // a week of data in a 30-day report: under the 70 % a report needs
        let month = GlucoseReport.of(
            rs.map { GraphReading(t: $0.t.timeIntervalSince1970, v: Double($0.mgdl)) }, days: 30,
            now: clock.now.timeIntervalSince1970, config: GraphConfig(), unit: .mgdl)
        #expect(month.lowCoverage)
        #expect(GlucoseReport.of([], days: 1, now: 0, config: GraphConfig(), unit: .mgdl).empty)
    }

    /// The period is remembered; an unknown stored value falls back to 14 days.
    @Test func reportPeriodIsRemembered() {
        let store = InMemorySettingsStore()
        let (_, m) = model([], store: store)
        #expect(m.days == 14)
        m.days = 7
        #expect(store.value(MacSettings.reportDays) == 7)
        store.set(5, for: MacSettings.reportDays)
        #expect(model([], store: store).1.days == 14)
    }

    /// Export CSV…: each period writes its own range and file name; the save panel starts on the report's period.
    @Test func csvPeriods() throws {
        let (_, m) = model(readings(days: 20) { _ in 100 })
        m.days = 7
        #expect(m.startPeriod == .d7)
        m.days = 1
        #expect(m.startPeriod == .d7)
        let now = clock.now
        let week = try #require(m.csv(.d7, now: now))
        let all = try #require(m.csv(.all, now: now))
        let rows = { (text: String) in text.components(separatedBy: "\r\n").filter { $0.hasPrefix("Sukrara,") }.count }
        #expect(rows(week.text) == 7 * 288)
        #expect(rows(all.text) == 20 * 288)
        #expect(week.name.hasPrefix("sukrara-glucose-") && week.name.hasSuffix(".csv"))
        #expect(m.csv(.sensor, now: now) == nil, "no sensor known: no This sensor range")
        #expect(!GlucoseCsv.Period.offered(sensor: m.sensor).contains(.sensor))
    }
}
