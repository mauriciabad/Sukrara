// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation
import SukraraCore
import SukraraGraph
import Testing

/// The widget's timeline: an entry at each moment its card changes without the app (WidgetTimeline).
@Suite struct TimelineTests {
    private let now = GraphFixtures.now
    private var nowDate: Date { Date(timeIntervalSince1970: now) }

    private func snapshot(readingAge: TimeInterval?, alerts: CardAlerts = .none, sensor: SensorInfo? = nil)
        -> WidgetSnapshot
    {
        let readings = readingAge.map { [GraphReading(t: now - $0, v: 112, rate: -1)] } ?? []
        return WidgetSnapshot(
            writtenAt: now, data: CardData(readings: readings, sensor: sensor, alerts: alerts, now: now),
            config: GraphConfig(), unit: .mgdl)
    }

    private func offsets(_ dates: [Date]) -> [TimeInterval] { dates.map { $0.timeIntervalSince1970 - now } }

    @Test func freshReadingGetsAnEntryAtEveryFreshnessStep() {
        let dates = WidgetTimeline.dates(snapshot(readingAge: 30), now: nowDate)
        // reading at now-30: late after 90 s, struck after 5 min, hidden after 20 min, no graph after 3 h (+1 s each)
        #expect(offsets(dates) == [0, 61, 271, 1171, 10771])
        #expect(WidgetTimeline.reloadAfter(dates) == nowDate.addingTimeInterval(10772))
    }

    @Test func eachEntryAgesTheSameSnapshot() {
        let snap = snapshot(readingAge: 30)
        let dates = WidgetTimeline.dates(snap, now: nowDate)
        let faces = dates.map { GlucoseCard.face(snap.data(at: $0), unit: .mgdl, surface: .widgetValue) }
        #expect(faces.map(\.state) == [.value, .value, .value, .noReadings, .noReadings])
        #expect(faces.map(\.strike) == [false, false, true, false, false])
        #expect(faces[0].text == "112")
        #expect(faces[3].text == nil)  // never a number after 20 min
    }

    @Test func aStaleSnapshotOnlyKeepsTheStepsStillAhead() {
        #expect(offsets(WidgetTimeline.dates(snapshot(readingAge: 600), now: nowDate)) == [0, 601, 10201])
        let gone = WidgetTimeline.dates(snapshot(readingAge: 4 * 3600), now: nowDate)
        #expect(offsets(gone) == [0])
        #expect(WidgetTimeline.reloadAfter(gone) == nil)
    }

    @Test func noSnapshotIsOneEntryAndNoReload() {
        let dates = WidgetTimeline.dates(nil, now: nowDate)
        #expect(dates == [nowDate])
        #expect(WidgetTimeline.reloadAfter(dates) == nil)
    }

    @Test func aMuteCountsDownAndEnds() {
        let alerts = CardAlerts(status: .paused(.high, until: now + 5400))
        let o = offsets(WidgetTimeline.dates(snapshot(readingAge: nil, alerts: alerts), now: nowDate))
        #expect(o.contains(5400))
        // each minute of the last 15, every 5 min of the hour before, nothing earlier
        #expect((1...15).allSatisfy { o.contains(5400 - Double($0 * 60)) })
        #expect(o.contains(5400 - 1200) && !o.contains(5400 - 960) && o.contains(5400 - 3600))
        #expect(!o.contains(5400 - 3660))
        #expect(o.count == 1 + 1 + 15 + 9)
        // Mute all without an end never adds entries
        let forever = CardAlerts(status: .muted(until: AlertStatus.never))
        #expect(offsets(WidgetTimeline.dates(snapshot(readingAge: nil, alerts: forever), now: nowDate)) == [0])
    }

    @Test func sensorEndAndWarmupEndAreEntries() {
        let sensor = SensorInfo(start: now - 600, end: now + 7200, warmupEnd: now + 3000)
        let o = offsets(WidgetTimeline.dates(snapshot(readingAge: nil, sensor: sensor), now: nowDate))
        #expect(o == [0, 3000, 7200])
    }

    @Test func entriesAreBoundedAndAscending() {
        var ends: [String: TimeInterval] = [:]
        for k in 0..<20 { ends["t\(k)"] = now + 600 + TimeInterval(k * 17 * 60) }
        let dates = WidgetTimeline.dates(snapshot(readingAge: 30, alerts: CardAlerts(muteEnds: ends)), now: nowDate)
        #expect(dates.count == 100)
        #expect(dates == dates.sorted())
        #expect(dates.first == nowDate)
        #expect(dates.last! <= nowDate.addingTimeInterval(24 * 3600))
    }
}
