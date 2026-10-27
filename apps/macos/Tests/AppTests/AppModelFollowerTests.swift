// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation
import SukraraAlerts
import SukraraCore
import SukraraShare
import SukraraTesting
import Testing

@testable import SukraraApp

@Suite struct AppModelFollowerTests {
    let clock = FakeClock()
    let notifier = RecordingNotifier()
    let wake = RecordingWake()

    @Test func followingFeedsTheAlertsAndUnfollowingDisarmsThem() {
        let alerts = AlertController(
            settings: AlertSettings(), clock: clock, store: MemoryAlertStore(), notifier: notifier, wake: wake)
        let plan = LaunchPlan(mode: .normal, services: .all, data: .user(home: URL(fileURLWithPath: "/Users/u")))
        let model = AppModel(plan: plan, clock: clock, alerts: alerts)
        var snapshot = FollowerSnapshot.empty
        model.apply(snapshot)
        #expect(model.notMonitoringReason == "Not monitoring: not following anyone yet" && wake.at == nil)
        snapshot.link = .following(wearer: "Ana", canWrite: false)
        model.apply(snapshot)
        #expect(model.notMonitoringReason == "Waiting for the first reading from the wearer" && wake.at == nil)
        snapshot.readings = [Reading(t: clock.now, mgdl: 112, rate: 0)]
        model.apply(snapshot)
        #expect(model.notMonitoringReason == nil && model.statusTitle == "112 →" && wake.at != nil)
        #expect(alerts.mirroring)
        snapshot.link = .notFollowing
        model.apply(snapshot)
        #expect(model.statusTitle == "—" && alerts.nextWake == nil && !alerts.mirroring)
    }
}
