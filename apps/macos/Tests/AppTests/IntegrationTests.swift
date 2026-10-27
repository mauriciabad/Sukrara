// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation
import SukraraAlerts
import SukraraCore
import SukraraShare
import Testing

@testable import SukraraApp

/// The app's wiring: the fixture launch, mirrored alert settings, the runtime → windows mapping.
@Suite struct IntegrationTests {
    let home = URL(fileURLWithPath: "/Users/u", isDirectory: true)

    // MARK: fixture launch

    @Test func fixtureNeedsADebugDevRun() throws {
        let debug = BuildInfo(isDebugBuild: true, bundlePath: "/Users/u/repo/build/Sukrara.app", isBundle: true)
        let release = BuildInfo(isDebugBuild: false, bundlePath: "/Users/u/repo/build/Sukrara.app", isBundle: true)
        let installed = BuildInfo(isDebugBuild: false, bundlePath: "/Applications/Sukrara.app", isBundle: true)
        let exists: (String) -> Bool = { $0 == "/Users/u/repo/.git" }
        let env = ["SUKRARA_DEV": "1", "SUKRARA_FIXTURE": "1"]

        let p = try LaunchPlan.parse([], build: debug, env: env, home: home, exists: exists)
        #expect(p.mode == .fixture)
        #expect(p.services == .none)
        #expect(p.data.isIsolated)
        // a release build never shows made-up data
        #expect(throws: LaunchError.debugOnly) {
            try LaunchPlan.parse([], build: release, env: env, home: home, exists: exists)
        }
        // the installed app ignores the variable
        let user = try LaunchPlan.parse([], build: installed, env: env, home: home, exists: exists)
        #expect(user.mode == .normal)
        #expect(user.services == .all)
    }

    @Test func fixtureShowsTheSyntheticWearer() async {
        let plan = LaunchPlan(mode: .fixture, services: .none, data: .isolated(root: home))
        let model = AppModel(plan: plan)
        var snapshot: FollowerSnapshot?
        for await s in await FixtureFollower().snapshots() {
            snapshot = s
            break
        }
        model.apply(try! #require(snapshot))
        #expect(model.notMonitoringReason == nil)
        #expect(model.statusTitle != "")
        // an offline dev run without the fixture still says why nothing is shown
        let offline = AppModel(plan: LaunchPlan(mode: .normal, services: .none, data: .isolated(root: home)))
        #expect(offline.notMonitoringReason?.contains("offline") == true)
    }

    // MARK: mirrored alert settings

    @Test func wearerValuesReplaceOnlyMirroredFields() throws {
        var own = AlertSettings()
        own[.low].threshold = 80
        own[.noData].threshold = 25
        own[.low].alarm = true
        own.sounds.pack = "kart"
        let wearer = try #require(
            WearerSettings(
                json: #"""
                    {"alerts":{"alerts":{"low":{"on":false,"mgdl":65,"repeatMin":10},"no_data":{"min":60}},
                     "muteAfterCarbs":{"on":false,"min":30}}}
                    """#, wearer: "Marta"))
        let s = own.mirroring(wearer)
        #expect(s[.low].on == false)
        #expect(s[.low].threshold == 65)
        #expect(s[.low].repeatMin == 10)
        #expect(s[.low].alarm == true)  // alarm is this Mac's
        #expect(s[.noData].threshold == 25)  // no data is always this Mac's
        #expect(s.sounds.pack == "kart")
        #expect(s.muteAfterCarbs == QuietRule(on: false, min: 30))
        #expect(s[.high] == own[.high])  // not sent: own value
    }

    // MARK: runtime → windows

    @Test func linkStatesMapToTheFollowWindow() {
        var s = FollowerSnapshot.empty
        s.link = .requested(code: "1234", wearer: "Marta", nearby: true)
        #expect(FollowerAdapter.status(s) == .waiting(wearer: "Marta", code: "1234"))
        s.link = .requested(code: "1234", wearer: "Marta", nearby: false)
        #expect(FollowerAdapter.status(s) == .waiting(wearer: "Marta", code: nil))
        s.link = .following(wearer: "Marta", canWrite: false)
        s.readings = [Reading(t: Date(timeIntervalSince1970: 100), mgdl: 120)]
        #expect(
            FollowerAdapter.status(s)
                == .following(wearer: "Marta", lastReading: Date(timeIntervalSince1970: 100), canWrite: false))
        #expect(FollowerAdapter.failure(.cancelled) == nil)
        #expect(FollowerAdapter.failure(.keyFailed) == .keychain)
        #expect(FollowerAdapter.failure(.alreadyFollowing) == .alreadyFollowing)
    }

    @Test func wearerSettingsOnlyWhileFollowing() {
        var s = FollowerSnapshot.empty
        s.wearerSettings = MirroredSettings(u: 1, json: #"{"graph":{"low":72}}"#)
        #expect(WindowCoordinator.wearerSettings(s) == nil)
        s.link = .following(wearer: "Marta", canWrite: true)
        #expect(WindowCoordinator.wearerSettings(s)?.wearer == "Marta")
    }

    @Test func treatmentsSyncKeepsFailedRowsAndDelivery() {
        let model = TreatmentsModel(rows: [], canWrite: false, actions: nil)
        let mine = Treatment(id: "a", k: .carbs, a: 30, t: 200, u: 1, o: .mac)
        let phone = Treatment(id: "b", k: .rapid, a: 2, t: 100, u: 1, o: .phone)
        let gone = Treatment(id: "c", k: .note, a: 0, t: 300, n: "x", u: 2, del: true, o: .mac)
        model.sync([phone, mine, gone], deliveries: ["a": .sending], canWrite: true)
        #expect(model.canWrite)
        #expect(model.rows.map(\.id) == ["a", "b"])
        #expect(model.rows[0].delivery == .sending)
        #expect(model.rows[1].delivery == nil)
        model.set("a", .failed)
        // still sending in the runtime: the failure (and its Retry) stays
        model.sync([phone, mine], deliveries: ["a": .sending], canWrite: true)
        #expect(model.rows[0].delivery == .failed)
        // the relay took it
        model.sync([phone, mine], deliveries: ["a": .sent], canWrite: true)
        #expect(model.rows[0].delivery == .sent)
    }
}
