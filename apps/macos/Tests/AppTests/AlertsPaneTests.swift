// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation
import SukraraAlerts
import SukraraCore
import SukraraTesting
import Testing

@testable import SukraraApp

/// Settings › Alerts: each alert's summary line and Send Test Alert.
@Suite struct AlertsPaneTests {
    @Test func summarySaysWhatApplies() {
        var s = AlertSettings()
        #expect(AlertsRangesPane.summary(.low, s, unit: .mgdl) == "Below 70 mg/dL · every 5 min · Quest")
        #expect(AlertsRangesPane.summary(.veryLow, s, unit: .mgdl).hasSuffix("like an alarm"))
        s[.high].on = false
        s[.high].repeatMin = 0
        s.sounds.kinds[AlertKind.high.rawValue] = "none"
        #expect(AlertsRangesPane.summary(.high, s, unit: .mgdl) == "Off · From 180 mg/dL · once · silent")
        #expect(AlertsRangesPane.summary(.noData, s, unit: .mgdl).hasPrefix("After 20 min without data"))
        #expect(AlertsRangesPane.summary(.low, s, unit: .mmol).hasPrefix("Below 3.9 mmol/L"))
    }

    /// The test alert goes through the notifier as its own notification; the engine and its mutes don't see it.
    @Test func sendTestAlertPostsATestOnly() {
        let notifier = RecordingNotifier()
        let c = AlertController(
            settings: AlertSettings(), clock: FakeClock(), store: MemoryAlertStore(), notifier: notifier,
            wake: RecordingWake())
        c.sendTest(.low)
        #expect(notifier.tests == [.low])
        #expect(notifier.calls.isEmpty)
        #expect(c.ui == .idle)
        let f = Notifier.testFiring(.low, threshold: 70, at: Date())
        #expect(f.kind == .low && (f.reading?.mgdl ?? 99) < 70)
        let nd = Notifier.testFiring(.noData, threshold: 20, at: Date())
        #expect(nd.reading.map { Date().timeIntervalSince($0.at) > 20 * 60 } == true)
    }
}
