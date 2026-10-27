// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation
import SukraraApp
import SukraraCore
import SukraraTesting
import Testing

struct WidgetFeedTests {
    let start = Date(timeIntervalSince1970: 1_800_000_000)
    let following = "following"

    @Test func reloadOnlyForVisibleChangesAndAtMostEveryFiveMinutes() {
        var policy = WidgetReloadPolicy()
        let first = WidgetReloadPolicy.Shown(value: "120", state: following)
        #expect(policy.decide(first, now: start) == .now)
        policy.didReload(first, now: start)
        #expect(policy.decide(first, now: start.addingTimeInterval(60)) == .none)  // nothing new
        let next = WidgetReloadPolicy.Shown(value: "125", state: following)
        #expect(policy.decide(next, now: start.addingTimeInterval(60)) == .at(start.addingTimeInterval(300)))
        #expect(policy.decide(next, now: start.addingTimeInterval(301)) == .now)
        // A state change (stopped following, broken, …) reloads at once.
        let stopped = WidgetReloadPolicy.Shown(value: "—", state: "notFollowing")
        #expect(policy.decide(stopped, now: start.addingTimeInterval(61)) == .now)
    }

    @Test func feedWritesTheSnapshotAndCountsReloads() throws {
        let file = FileManager.default.temporaryDirectory.appending(
            path: "sukrara-widget-\(UUID().uuidString)/glucose.json")
        let clock = FakeClock(start)
        var reloaded: [[String]] = []
        let feed = WidgetFeed(file: file, clock: clock, reload: { reloaded.append($0) })
        let snapshot = GlucoseSnapshot(readings: [Reading(t: start, mgdl: 120)])
        feed.update(snapshot, shown: .init(value: "120", state: following))
        feed.update(snapshot, shown: .init(value: "120", state: following))
        #expect(try JSONDecoder().decode(GlucoseSnapshot.self, from: Data(contentsOf: file)) == snapshot)
        #expect(reloaded == [SukraraIDs.WidgetKind.all])
        #expect(feed.reloadCount == 1)
    }

    @Test func noAppGroupContainerIsAnIssueNotNoData() {
        let feed = WidgetFeed(file: nil, reload: { _ in })
        #expect(feed.issue != nil)
    }
}
