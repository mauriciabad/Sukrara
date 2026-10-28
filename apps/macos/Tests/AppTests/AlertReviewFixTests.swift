// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation
import SukraraAlerts
import SukraraCore
import SukraraShare
import SukraraTesting
import Testing

@testable import SukraraApp

/// Relaunch keeps the wearer's firing for Unmute, settings changes reach the rules, a relaunch
/// re-shows silently, the file log rotates.
@Suite struct AlertReviewFixTests {
    let clock = FakeClock()
    let store = MemoryAlertStore()
    let notifier = RecordingNotifier()
    let wake = RecordingWake()

    func controller() -> AlertController {
        AlertController(settings: AlertSettings(), clock: clock, store: store, notifier: notifier, wake: wake)
    }

    func lowPayload() throws -> AlertPayload {
        let at = Int(clock.now.timeIntervalSince1970)
        return try AlertPayload.decode(
            #"{"v":2,"type":"alert","k":"low","state":"fire","id":"w1","at":\#(at),"t":\#(at),"sgv":60,"th":70,"n":1}"#)
    }

    @Test func unmuteAfterARelaunchShowsTheWearersAlertStillUp() throws {
        let c = controller()
        c.setMirroring(true)
        c.perform(.mute(.low, minutes: 60))
        c.wearerAlert(try lowPayload())  // muted: not shown
        let relaunched = controller()
        relaunched.setMirroring(true)
        notifier.calls = []
        relaunched.perform(.unmute(.low))
        #expect(notifier.posts.contains { if case .post(.low, "w1", true, _) = $0 { true } else { false } })
    }

    @Test func turningAlertsOffStopsThemAtOnce() {
        let c = controller()
        var off = AlertSettings()
        off.on = false
        c.settings(off)
        notifier.calls = []
        c.reading(Reading(t: clock.now, mgdl: 55, rate: -1))
        #expect(notifier.posts.isEmpty)
    }

    @Test func aRelaunchReshowsTheOpenAlertSilently() {
        let c = controller()
        c.reading(Reading(t: clock.now, mgdl: 60, rate: -0.5))
        clock.advance(by: 30)
        notifier.calls = []
        _ = controller()
        #expect(notifier.posts.contains { if case .post(.low, _, false, _) = $0 { true } else { false } })
    }

    @Test func fileLogRotatesAtItsLimit() throws {
        let dir = FileManager.default.temporaryDirectory.appending(path: "sukrara-log-\(UUID().uuidString)")
        let log = RotatingFileLog(directory: dir, limit: 200)
        for i in 0..<20 { log.write("event \(i)") }
        let current = try Data(contentsOf: log.current)
        #expect(current.count <= 200)
        #expect(FileManager.default.fileExists(atPath: log.previous.path))
    }
}
