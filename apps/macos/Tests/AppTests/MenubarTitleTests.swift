// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation
import SukraraAlerts
import SukraraCore
import SukraraGraph
import SukraraTesting
import Testing

@testable import SukraraApp

@Suite struct MenubarTitleTests {
    let clock = FakeClock()

    func title(minutesAgo: Double?, alerts: AlertUIState = .idle, sensor: SensorInfo? = nil) -> MenubarTitle {
        let now = clock.now.timeIntervalSince1970
        let readings = minutesAgo.map { [GraphReading(t: now - $0 * 60, v: 112, rate: -1.2)] } ?? []
        let data = CardData(readings: readings, sensor: sensor, now: now)
        return MenubarTitle.of(
            look: GlucoseCard.face(data, unit: .mgdl, surface: .menubarTitle),
            spoken: GlucoseCard.accessibilityLabel(data, unit: .mgdl, surface: .menubarTitle, config: GraphConfig()),
            alerts: alerts,
            reason: nil)
    }

    func engineState(_ input: AlertInput) -> AlertUIState {
        var engine = AlertEngine(context: AlertContext())
        _ = engine.handle(input, at: clock.now)
        return engine.uiState(at: clock.now)
    }

    @Test func freshValueWithArrow() {
        let t = title(minutesAgo: 1)
        #expect(t.text == "112 ↘" && !t.strike && !t.muted)
        #expect(t.accessibilityLabel.contains("112"))
    }

    /// Same rate, same arrow on every text place: the menu bar title and the alert title write the arrow of
    /// contracts/graph/trend for each of its rates (the card draws it from the same `TrendAngle`).
    @Test func everyTextPlaceWritesTheFixtureArrow() throws {
        let url = Contracts.root.appending(path: "graph/trend/trend.json")
        let root = try #require(try JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any])
        let cases = try #require(root["cases"] as? [[String: Any]])
        #expect(!cases.isEmpty)
        let now = clock.now.timeIntervalSince1970
        for c in cases {
            let rate = (c["rate"] as? NSNumber)?.doubleValue
            let want = try #require((c["expect"] as? [String: Any])?["text"] as? String)
            let data = CardData(readings: [GraphReading(t: now - 30, v: 64, rate: rate)], sensor: nil, now: now)
            let menubar = MenubarTitle.of(
                look: GlucoseCard.face(data, unit: .mgdl, surface: .menubarTitle), spoken: "", alerts: .idle,
                reason: nil)
            #expect(menubar.text == "64 \(want)", "menu bar, \(c["name"] ?? "")")
            let firing = AlertFiring(
                id: "a", kind: .low, at: clock.now, reading: GlucoseSample(at: clock.now, mgdl: 64, rate: rate),
                threshold: 70, n: 1, treated: 0)
            #expect(AlertText(language: .en).title(firing).hasSuffix("64 mg/dL \(want)"), "alert, \(c["name"] ?? "")")
        }
    }

    @Test func staleValueIsStruck() {
        let t = title(minutesAgo: 10)
        #expect(t.strike && t.text.hasPrefix("112"))
    }

    @Test func oldValueIsHidden() {
        let t = title(minutesAgo: 30)
        #expect(t.text == GlucoseCard.noValueText)
    }

    @Test func muteAllShowsTheBellAndSaysSo() {
        let t = title(minutesAgo: 1, alerts: engineState(.muteAll(until: clock.now.addingTimeInterval(600))))
        #expect(t.muted && t.accessibilityLabel.hasSuffix("Alerts muted"))
    }

    @Test func warmingUpSaysSo() {
        let now = clock.now.timeIntervalSince1970
        let t = title(
            minutesAgo: nil, sensor: SensorInfo(start: now - 600, end: now + 14 * 86400, warmupEnd: now + 1800))
        #expect(t.text.hasPrefix("Warming up"))
    }

    @Test func offlinePlanStartsNoService() {
        let plan = LaunchPlan(mode: .normal, services: .none, data: .isolated(root: URL(fileURLWithPath: "/tmp/x")))
        let services = AppServices(plan: plan)
        plan.services.start(using: services)
        #expect(services.alerts == nil && services.center == nil && services.updates == nil)
        #expect(services.loginItem == nil && services.sounds == nil)
    }

    @Test func cardBadgeFollowsTheAlertState() {
        let low = engineState(.reading(GlucoseSample(at: clock.now, mgdl: 60, rate: 0)))
        #expect(CardAlertsMapping.of(low, now: clock.now).status == .needed(.low))
        let muted = engineState(.muteAll(until: muteAllForever))
        #expect(CardAlertsMapping.of(muted, now: clock.now).status == .muted(until: AlertStatus.never))
    }
}
