// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import CoreGraphics
import Foundation
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
        #expect(GlucoseCard.face(data).text == "112")
        #expect(GlucoseCard.face(data, unit: .mmol).text == "6.2")
        #expect(GlucoseCard.face(CardData(readings: [], now: now)).text == nil)
    }

    @Test func voiceOverSentence() {
        #expect(
            GlucoseCard.accessibilityLabel(CardData(readings: [reading], now: now))
                == "Glucose 112 milligrams per decilitre, falling")
        let late = CardData(readings: [GraphReading(t: now - 200, v: 112, rate: 0.2)], now: now)
        #expect(GlucoseCard.accessibilityLabel(late) == "Glucose 112 milligrams per decilitre, steady, 3 minutes ago")
        let stale = CardData(readings: [GraphReading(t: now - 600, v: 141, rate: 2)], now: now)
        #expect(
            GlucoseCard.accessibilityLabel(stale, unit: .mmol)
                == "Glucose 7.8 (old) millimoles per litre, trend unknown, 10 minutes ago")
        #expect(GlucoseCard.accessibilityLabel(CardData(readings: [], now: now)) == "Glucose: no readings yet")
        let low = CardData(
            readings: [GraphReading(t: now - 60, v: 64, rate: -1)], alerts: CardAlerts(status: .needed(.low)), now: now)
        #expect(GlucoseCard.accessibilityLabel(low) == "Glucose 64 milligrams per decilitre, falling. Eat carbs")
    }

    @Test func everyCardSurfaceRendersABitmap() {
        let data = CardData(readings: [reading], now: now)
        for s in CardSurface.allCases where s != .menubarTitle {
            let image = GlucoseCard.image(
                s, size: CGSize(width: 332, height: 138), config: GraphConfig(), data: data, dark: true)
            #expect(image?.width == 664, "\(s)")
        }
    }
}
