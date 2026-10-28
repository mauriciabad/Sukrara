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

    func title(
        minutesAgo: Double?, mgdl: Double = 112, rate: Double? = -1.2, alerts: AlertUIState = .idle,
        sensor: SensorInfo? = nil
    ) -> MenubarTitle {
        let now = clock.now.timeIntervalSince1970
        let readings = minutesAgo.map { [GraphReading(t: now - $0 * 60, v: mgdl, rate: rate)] } ?? []
        let data = CardData(readings: readings, sensor: sensor, now: now)
        return MenubarTitle.of(
            look: GlucoseCard.face(data, unit: .mgdl, surface: .menubarTitle), data: data, config: GraphConfig(),
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
            #expect(
                AlertText(language: .en).body(firing, now: clock.now) == "64 mg/dL \(want)", "alert, \(c["name"] ?? "")"
            )
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
        #expect(t.muted && t.accessibilityLabel.contains("Alerts muted"))
        #expect(t.tooltip.contains("Alerts muted · 10 min left"))
    }

    /// The title-state matrix: every state the status item can be in, its value, mark, ink and tooltip.
    @Test func titleStateMatrix() {
        let now = clock.now.timeIntervalSince1970
        let fresh = title(minutesAgo: 1)
        #expect(fresh.value == "112" && fresh.mark == .arrow(-1.2) && fresh.tint == .plain && !fresh.strike)
        #expect(fresh.tooltip.hasPrefix("Sukrara · reading at "))
        #expect(title(minutesAgo: 1, rate: nil).mark == .ring)
        let late = title(minutesAgo: 3)
        #expect(late.value == "112" && !late.strike && late.tint == .plain)
        let stale = title(minutesAgo: 10, mgdl: 60)
        #expect(stale.strike && stale.tint == .grey, "a stale value is grey even when low")
        #expect(title(minutesAgo: 1, mgdl: 60).tint == .below)
        #expect(title(minutesAgo: 1, mgdl: 240).tint == .above)
        let none = title(minutesAgo: 30)
        #expect(none.value.isEmpty && none.mark == .symbol(GlucoseCard.noReadingsSymbol))
        #expect(none.tooltip.hasPrefix("Sukrara · no reading since "))
        #expect(none.accessibilityLabel.hasPrefix("Glucose: no reading since "))
        let warm = title(
            minutesAgo: nil, sensor: SensorInfo(start: now - 600, end: now + 14 * 86400, warmupEnd: now + 42 * 60))
        #expect(warm.value == "42m" && warm.tint == .grey && warm.mark == .none)
        #expect(warm.tooltip.hasPrefix("Sukrara · ready at "))
        let ended = title(minutesAgo: 1, sensor: SensorInfo(start: now - 15 * 86400, end: now - 60, warmupEnd: now))
        #expect(ended.value.isEmpty && ended.mark == .symbol(MenubarTitle.endedSymbol))
        #expect(ended.tooltip == "Sensor ended — replace sensor")
        let muted = title(minutesAgo: 1, alerts: engineState(.muteAll(until: muteAllForever)))
        #expect(muted.muted && muted.tooltip.hasSuffix("Alerts muted until you turn it off"))
    }

    /// The title wakes by itself at each freshness step, at the sensor's end and at each warm-up minute.
    @Test func titleWakesAtTheSensorEndAndEachWarmupMinute() {
        let now = clock.now.timeIntervalSince1970
        let ending = SensorInfo(start: now - 14 * 86400, end: now + 30, warmupEnd: now - 13 * 86400)
        #expect(AppModel.nextWake(latest: now - 10, sensor: ending, now: now) == now + 30.5)
        let warming = SensorInfo(start: now - 600, end: now + 14 * 86400, warmupEnd: now + 42 * 60 + 20)
        #expect(AppModel.nextWake(latest: nil, sensor: warming, now: now) == now + 20.5)
        #expect(AppModel.nextWake(latest: nil, sensor: nil, now: now) == nil)
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
