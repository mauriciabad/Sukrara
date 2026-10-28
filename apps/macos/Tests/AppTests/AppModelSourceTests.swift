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
@testable import SukraraGraph

/// Juggluco on this network as the app's source, and every surface's rings over the one stored history.
@Suite struct AppModelSourceTests {
    nonisolated static let phone = "192.168.1.7"
    let clock = FakeClock()
    let notifier = RecordingNotifier()
    let wake = RecordingWake()

    /// A month of readings every 15 min: high in the older weeks, in range in the last day, so the 7- and 30-day
    /// numbers differ from the last 24 hours'.
    func month() -> [Reading] {
        stride(from: 30 * 86_400.0, to: 600, by: -900).map { ago in
            Reading(t: clock.now.addingTimeInterval(-ago), mgdl: ago > 86_400 ? 220 : 110, rate: 0)
        }
    }

    /// Juggluco at `phone`: its history is the store's, the live stream sends one reading of 123 now.
    func network() -> FakeLanHTTP {
        let t = Int(clock.now.timeIntervalSince1970)
        return FakeLanHTTP(
            answer: { url, _ in
                guard url.host() == Self.phone else { return nil }
                return url.path().hasSuffix("entries.json") ? (200, Data("[]".utf8)) : (404, Data())
            },
            live: { url in
                url.host() == Self.phone
                    ? (200, ["event: glucose", "data: {\"timestamp\": \(t), \"glucoseMgDl\": 123}"]) : nil
            })
    }

    func model(_ http: FakeLanHTTP, alerts: AlertController? = nil) -> AppModel {
        let source = JugglucoLanSource(
            http: http, store: InMemoryReadingsStore(month()), clock: clock, interfaces: { [] },
            sleep: { _ in try await Task.sleep(for: .seconds(3600)) })
        let plan = LaunchPlan(mode: .normal, services: .all, data: .user(home: URL(fileURLWithPath: "/Users/u")))
        return AppModel(plan: plan, clock: clock, alerts: alerts, lan: source)
    }

    func until(_ ok: () -> Bool) async {
        for _ in 0..<500 where !ok() { try? await Task.sleep(for: .milliseconds(10)) }
    }

    @Test func jugglucoFeedsTheCardAndTheAlertsWhileNobodyIsFollowed() async {
        let alerts = AlertController(
            settings: AlertSettings(), clock: clock, store: MemoryAlertStore(), notifier: notifier, wake: wake)
        let model = model(network(), alerts: alerts)
        model.run()
        model.apply(FollowerSnapshot.empty)
        #expect(model.notMonitoringReason == "Not monitoring: not following anyone yet")
        model.lanConfig = LanConfig(address: Self.phone)
        await until { model.readings.last?.mgdl == 123 }
        #expect(model.statusTitle.hasPrefix("123"))
        #expect(model.notMonitoringReason == nil)
        #expect(alerts.nextWake != nil)  // the no-data alert is armed on Juggluco's readings
        #expect(!alerts.mirroring)
        model.lanConfig = nil
        await until { model.readings.isEmpty }
        #expect(model.statusTitle == "—" && alerts.nextWake == nil)
    }

    @Test func followingPausesJugglucoNeverMixingTwoPeople() async {
        let model = model(network())
        model.run()
        model.lanConfig = LanConfig(address: Self.phone)
        await until { model.readings.last?.mgdl == 123 }
        var snapshot = FollowerSnapshot.empty
        snapshot.link = .following(wearer: "Ana", canWrite: false)
        snapshot.wearer = Profile(name: "Ana", avatar: nil)
        snapshot.readings = [Reading(t: clock.now.addingTimeInterval(-30), mgdl: 200, rate: 0)]
        model.apply(snapshot)
        #expect(model.readings.map(\.mgdl) == [200])
        #expect(model.lanPausedFor == "Ana")
        await until { model.lanSnapshot.state == .off }
        #expect(model.lanSnapshot.state == .off)
        #expect(model.readings.map(\.mgdl) == [200])
    }

    /// The menu bar, panel and widgets show the same 7 / 30-day rings, before and after the panel opens.
    @Test func everySurfaceCountsTheRingsOverTheStoredHistory() async {
        let model = model(network())
        model.run()
        model.lanConfig = LanConfig(address: Self.phone)
        await until { model.history.count > 2000 && model.slots != nil && model.readings.count > 90 }
        let bands = model.graph.bands.sane()
        for days in [7.0, 14, 30] {
            let ring = RingSpec(window: days * 86_400)
            let card = model.cardData.metrics(ring, bands)  // the menu bar, the widgets (24 h of readings + slots)
            let panel = model.panelData.metrics(ring, bands)  // the panel (the whole month)
            #expect(card == panel)
            let lastDay = GlucoseMetrics.of(
                model.cardData.readings, from: model.cardData.now - ring.window, to: model.cardData.now, bands: bands)
            #expect(card != lastDay)  // not the last 24 hours' numbers
        }
        // the widget gets the same numbers in its snapshot
        let decoded = WidgetSnapshot.decode(
            try! WidgetSnapshot(writtenAt: 0, data: model.cardData, config: model.widgetGraph, unit: .mgdl).encoded())
        #expect(decoded?.data.slots == model.slots)
    }
}
