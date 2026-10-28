// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation
import SukraraCore
import SukraraGraph
import SukraraShare
import SukraraTesting
import Testing

@testable import SukraraApp

/// The widget feed in the composed app: a normal run feeds the widgets on every follower snapshot, a dev or snapshot
/// run creates no feed and writes nothing.
@Suite struct WidgetWiringTests {
    let home = URL(fileURLWithPath: "/Users/u", isDirectory: true)
    let clock = FakeClock()
    let file = FileManager.default.temporaryDirectory.appending(
        path: "sukrara-widget-wiring-\(UUID().uuidString)/glucose.json")

    /// The app's services for `plan`, with the widget feed writing to `file` and recording its reloads.
    func services(_ plan: LaunchPlan, reloads: @escaping @MainActor ([String]) -> Void = { _ in }) -> AppServices {
        AppServices(plan: plan) { [file, clock] in WidgetFeed(file: file, clock: clock, reload: reloads) }
    }

    func following(_ mgdl: Int) -> FollowerSnapshot {
        var snapshot = FollowerSnapshot.empty
        snapshot.link = .following(wearer: "Ana", canWrite: false)
        snapshot.readings = [Reading(t: clock.now, mgdl: mgdl, rate: 0)]
        return snapshot
    }

    @Test func aNormalRunFeedsTheWidgetsOnEveryChange() throws {
        let installed = BuildInfo(isDebugBuild: false, bundlePath: "/Applications/Sukrara.app", isBundle: true)
        let plan = try LaunchPlan.parse([], build: installed, env: [:], home: home)
        #expect(plan.services.allows(.widgetFeed))
        var reloaded: [[String]] = []
        let services = services(plan) { reloaded.append($0) }
        // only the widget feed: the test process must not start notifications, relays or the login item
        let widgetOnly = ServicesPlan(ble: false, relays: false, alerts: false, widgetFeed: true, notifications: false)
        plan.services.limited(by: widgetOnly).start(using: services)
        let model = services.model(clock: clock)
        #expect(model.widget != nil)

        model.apply(following(120))
        let written = try #require(WidgetSnapshot.decode(try Data(contentsOf: file)))
        #expect(written.data.readings.map(\.v) == [120])
        #expect(written.writtenAt == clock.now.timeIntervalSince1970)
        #expect(reloaded == [SukraraIDs.WidgetKind.all])

        // stopped following: the widget gets the empty card at once, never the last value
        model.apply(.empty)
        #expect(try WidgetSnapshot.decode(Data(contentsOf: file))?.data.readings.isEmpty == true)
        #expect(reloaded.count == 2)

        // a settings change (unit) is written and redrawn at once
        model.unit = .mmol
        model.feedWidget(settingsChanged: true)
        #expect(try WidgetSnapshot.decode(Data(contentsOf: file))?.unit == .mmol)
        #expect(reloaded.count == 3)
    }

    @Test func devAndSnapshotRunsStartNoFeed() throws {
        let dev = try LaunchPlan.parse(
            [], build: BuildInfo(isDebugBuild: true, bundlePath: "/Users/u/repo/build/Sukrara.app", isBundle: true),
            env: ["SUKRARA_DEV": "1"], home: home, exists: { $0 == "/Users/u/repo/.git" })
        let snapshot = try LaunchPlan.snapshot(
            SnapshotRequest(surface: "card", outputDirectory: URL(fileURLWithPath: "/tmp/out")),
            build: BuildInfo(isDebugBuild: true, bundlePath: "/x", isBundle: false),
            scratch: URL(fileURLWithPath: "/tmp/scratch"))
        for plan in [dev, snapshot] {
            var reloaded = 0
            let services = services(plan) { _ in reloaded += 1 }
            plan.services.start(using: services)
            let model = services.model(clock: clock)
            #expect(services.widget == nil && model.widget == nil)
            model.apply(following(120))
            #expect(!FileManager.default.fileExists(atPath: file.path) && reloaded == 0)
        }
    }
}
