// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import CoreGraphics
import Foundation
import SukraraCore
import SukraraGraph
import Testing

/// The entry points every Mac surface uses (no `@testable`: only what the app and the widget can call).
@Suite struct PublicAPITests {
    private let now = GraphFixtures.now
    private var reading: GraphReading { GraphReading(t: now - 60, v: 112, rate: -1.2) }

    @Test func widgetSnapshotRoundTripsAndRejectsOtherVersions() throws {
        var config = GraphConfig()
        config.bands.high = 200
        let data = CardData(
            readings: [reading], marks: [GraphMark(t: now - 600, k: "rapid", a: 4.5, id: "i1")],
            sensor: SensorInfo(start: now - 86400, end: now + 13 * 86400, warmupEnd: now - 82800),
            alerts: CardAlerts(status: .paused(.high, until: AlertStatus.never), muteEnds: ["i1": now + 3000]), now: now
        )
        let snap = WidgetSnapshot(writtenAt: now, data: data, config: config, unit: .mmol)
        let bytes = try snap.encoded()
        #expect(WidgetSnapshot.decode(bytes) == snap)
        var other = try #require(try JSONSerialization.jsonObject(with: bytes) as? [String: Any])
        other["v"] = WidgetSnapshot.version + 1
        #expect(WidgetSnapshot.decode(try JSONSerialization.data(withJSONObject: other)) == nil)
        #expect(WidgetSnapshot.decode(Data("{}".utf8)) == nil)
    }

    @Test func faceIsInTheUsersUnit() {
        let data = CardData(readings: [reading], now: now)
        #expect(GlucoseCard.face(data, unit: .mgdl, surface: .menubarTitle).text == "112")
        #expect(GlucoseCard.face(data, unit: .mmol, surface: .menubarTitle).text == "6.2")
        #expect(GlucoseCard.face(CardData(readings: [], now: now), unit: .mgdl, surface: .menubarTitle).text == nil)
    }

    /// The value widget's sentence (no chart) in `words`, with the default settings unless `config` says otherwise.
    private func spoken(
        _ data: CardData, _ unit: GlucoseUnit = .mgdl, surface: CardSurface = .widgetValue,
        config: GraphConfig = GraphConfig(), words: CardWords = .en
    ) -> String {
        GlucoseCard.accessibilityLabel(data, unit: unit, surface: surface, config: config, words: words)
    }

    @Test func voiceOverSentence() {
        #expect(
            spoken(CardData(readings: [reading], now: now))
                == "112 milligrams per decilitre, falling, in range, 1 min ago")
        let fresh = CardData(readings: [GraphReading(t: now - 20, v: 112, rate: 0.2)], now: now)
        #expect(spoken(fresh) == "112 milligrams per decilitre, steady, in range, just now")
        let stale = CardData(readings: [GraphReading(t: now - 600, v: 141, rate: 2)], now: now)
        #expect(
            spoken(stale, .mmol) == "7.8 millimoles per litre, old reading, trend unknown, in range, 10 min ago")
        #expect(spoken(CardData(readings: [], now: now)) == "Glucose: no readings yet")
        let low = CardData(
            readings: [GraphReading(t: now - 60, v: 64, rate: -1)], alerts: CardAlerts(status: .needed(.low)), now: now)
        #expect(spoken(low) == "64 milligrams per decilitre, falling, low, 1 min ago. Eat carbs")
        // no shown value, no badge sentence (the card draws the badge only under a value)
        let gone = CardData(
            readings: [GraphReading(t: now - 3600, v: 64, rate: -1)], alerts: CardAlerts(status: .needed(.low)),
            now: now)
        #expect(!spoken(gone).contains("Eat carbs"))
    }

    /// Never by colour alone: every band in words, at the user's bounds (UX review F6).
    @Test func voiceOverSaysTheRange() {
        func band(_ v: Double, _ config: GraphConfig = GraphConfig()) -> String {
            spoken(CardData(readings: [GraphReading(t: now - 60, v: v, rate: 0)], now: now), config: config)
        }
        #expect(band(50).contains(", very low, "))
        #expect(band(65).contains(", low, "))
        #expect(band(100).contains(", in range, "))
        #expect(band(150).contains(", in range, "))
        #expect(band(200).contains(", high, "))
        #expect(band(260).contains(", very high, "))
        #expect(band(320).contains(", extremely high, "))
        var raised = GraphConfig()
        raised.bands.high = 220
        #expect(band(200, raised).contains(", in range, "))
        #expect(band(30) == "below 40 milligrams per decilitre, steady, very low, 1 min ago")
        #expect(band(420).hasPrefix("above 400 milligrams per decilitre, steady, extremely high"))
    }

    @Test func voiceOverSummarisesTheChart() {
        // every 5 min: the newest 9 h in range, the 3 h before high
        let rs = (0..<144).map { GraphReading(t: now - Double($0) * 300, v: $0 < 108 ? 120 : 200, rate: 0) }
        let data = CardData(readings: rs, now: now)
        #expect(
            spoken(data, surface: .widgetCombined)
                == "120 milligrams per decilitre, steady, in range, just now. Last 12 hours: 75% in range, 0% below, 25% above")
        var six = GraphConfig()
        six.hours = 6
        #expect(spoken(data, surface: .menubarCard, config: six).hasSuffix("Last 6 hours: 100% in range, 0% below, 0% above"))
        // the graph widget shows no value: the summary only; the menu bar title: the value only
        #expect(spoken(data, surface: .widgetGraph).hasPrefix("Last 12 hours: "))
        #expect(!spoken(data, surface: .menubarTitle).contains("Last"))
        #expect(
            spoken(CardData(readings: [], now: now), surface: .widgetCombined)
                == "Glucose: no readings yet. Last 12 hours: no data")
    }

    @Test func voiceOverInSpanish() {
        let low = CardData(
            readings: [GraphReading(t: now - 120, v: 64, rate: -2.5)], alerts: CardAlerts(status: .none), now: now)
        #expect(
            spoken(low, surface: .widgetCombined, words: .es)
                == "64 miligramos por decilitro, bajando rápido, baja, hace 2 min. Últimas 12 horas: 0% en rango, 100% por debajo, 0% por encima")
        #expect(spoken(CardData(readings: [], now: now), words: .es) == "Glucosa: aún no hay lecturas")
        // the status pill's sentence too
        let bolus = GraphMark(t: now - 600, k: "carbs", a: 30, id: "c1")
        let taken = CardData(
            readings: low.readings, alerts: CardAlerts(status: .taken(.low, treatment: bolus, until: now + 600)),
            now: now)
        #expect(
            spoken(taken, words: .es).hasSuffix("Alertas de glucosa baja silenciadas tras los hidratos · quedan 10 min"))
        let needed = CardData(readings: low.readings, alerts: CardAlerts(status: .needed(.low)), now: now)
        #expect(spoken(needed, words: .es).hasSuffix(". Come hidratos"))
    }

    @Test func anExpiredMuteSaysNothing() {
        let bolus = GraphMark(t: now - 4000, k: "rapid", a: 3, id: "i1")
        let data = CardData(
            readings: [GraphReading(t: now - 60, v: 210, rate: 0)],
            alerts: CardAlerts(status: .taken(.high, treatment: bolus, until: now - 400)), now: now)
        #expect(spoken(data) == "210 milligrams per decilitre, steady, high, 1 min ago")
        let running = CardData(
            readings: data.readings, alerts: CardAlerts(status: .taken(.high, treatment: bolus, until: now + 600)),
            now: now)
        #expect(spoken(running).hasSuffix("muted after insulin · 10 min left"))
    }

    @Test func everyCardSurfaceRendersABitmap() {
        let data = CardData(readings: [reading], now: now)
        for s in CardSurface.allCases where s != .menubarTitle {
            let image = GlucoseCard.image(
                surface: s, size: CGSize(width: 332, height: 138), config: GraphConfig(), data: data, unit: .mmol,
                dark: true)
            #expect(image?.width == 664, "\(s)")
        }
    }
}
