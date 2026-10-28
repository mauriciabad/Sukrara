// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation
import SukraraAlerts
import SukraraTesting
import Testing

/// SPEC §3 JSON: defaults, lenient decoding (a wearer's mirrored settings, Android-encoded), keys out, schema limits.
@Suite struct SettingsCodecTests {
    /// The SPEC §3 example, as Android writes it (ints where Kotlin has ints, `2.0` rates, `alarm` only where true).
    static let specExample = """
        {"v":1,"on":true,"alerts":{"very_low":{"on":true,"mgdl":55,"repeatMin":5,"alarm":true},"low":{"on":true,"mgdl":70,"repeatMin":5},
        "high":{"on":true,"mgdl":180,"repeatMin":15},"very_high":{"on":true,"mgdl":250,"repeatMin":10},
        "extremely_high":{"on":true,"mgdl":300,"repeatMin":5,"alarm":true},
        "falling_fast":{"on":false,"rate":2.0,"repeatMin":30},"rising_fast":{"on":false,"rate":2.0,"repeatMin":30},
        "no_data":{"on":true,"min":20,"repeatMin":60}},"muteAfterInsulin":{"on":true,"min":60},
        "muteFallingHigh":{"on":true,"min":20},"muteAfterCarbs":{"on":true,"min":15},
        "sounds":{"pack":"quest","kinds":{},"shuffle":true},
        "notificationActions":{"muteMin":30,"relaxMin":30,"backMgdl":10,"backMin":10},"source":"wearer"}
        """

    @Test func specExampleIsTheDefaults() {
        #expect(AlertSettings.decode(Data(Self.specExample.utf8)) == AlertSettings())
    }

    /// The SPEC.md §3 block itself (contracts) decodes to the defaults: the spec and the code can't drift.
    @Test func specDocumentBlockIsTheDefaults() throws {
        let spec = try String(contentsOf: Contracts.url("alerts/SPEC.md"), encoding: .utf8)
        let section = try #require(spec.range(of: "## 3. Settings"))
        let fenceStart = try #require(spec.range(of: "```json\n", range: section.upperBound..<spec.endIndex))
        let fenceEnd = try #require(spec.range(of: "\n```", range: fenceStart.upperBound..<spec.endIndex))
        let json = String(spec[fenceStart.upperBound..<fenceEnd.lowerBound])
        #expect(AlertSettings.decode(Data(json.utf8)) == AlertSettings())
        // and the defaults write exactly that object (so the decode above cannot pass by falling back)
        let written = try JSONSerialization.jsonObject(with: AlertSettings().encoded()) as? NSDictionary
        let documented = try JSONSerialization.jsonObject(with: Data(json.utf8)) as? NSDictionary
        #expect(written != nil && written == documented)
    }

    @Test func defaultsMatchSpec() {
        let s = AlertSettings()
        #expect(s.on && s.source == .wearer)
        #expect(s[.veryLow] == AlertRule(on: true, threshold: 55, repeatMin: 5, alarm: true))
        #expect(s[.low] == AlertRule(on: true, threshold: 70, repeatMin: 5))
        #expect(s[.high] == AlertRule(on: true, threshold: 180, repeatMin: 15))
        #expect(s[.veryHigh] == AlertRule(on: true, threshold: 250, repeatMin: 10))
        #expect(s[.extremelyHigh] == AlertRule(on: true, threshold: 300, repeatMin: 5, alarm: true))
        #expect(s[.fallingFast] == AlertRule(on: false, threshold: 2, repeatMin: 30))
        #expect(s[.risingFast] == AlertRule(on: false, threshold: 2, repeatMin: 30))
        #expect(s[.noData] == AlertRule(on: true, threshold: 20, repeatMin: 60))
        #expect(s.muteAfterInsulin == QuietRule(on: true, min: 60))
        #expect(s.muteFallingHigh == QuietRule(on: true, min: 20))
        #expect(s.muteAfterCarbs == QuietRule(on: true, min: 15))
        #expect(s.sounds == AlertSoundSettings(pack: "quest", kinds: [:], shuffle: true))
        #expect(
            s.notificationActions == NotificationActionSettings(muteMin: 30, relaxMin: 30, backMgdl: 10, backMin: 10))
    }

    @Test func partialAndJunkKeepDefaults() {
        let json =
            #"{"on":false,"alerts":{"high":{"mgdl":200},"urgent_low":{"mgdl":40}},"source":"bogus","sounds":7,"extra":{"x":1}}"#
        let s = AlertSettings.decode(Data(json.utf8))
        #expect(!s.on)
        #expect(s[.high] == AlertRule(on: true, threshold: 200, repeatMin: 15))
        #expect(s[.veryLow] == .defaults(.veryLow))
        #expect(s.source == .wearer)
        #expect(s.sounds.pack == "quest")
    }

    /// One malformed field loses only itself: a wearer's low 80 survives a junk field next to it.
    @Test func oneMalformedFieldLosesOnlyItself() {
        let json =
            #"{"alerts":{"low":{"mgdl":80,"on":1},"high":{"mgdl":"200"},"very_low":7},"sounds":{"pack":"kart","kinds":{"low":3}}}"#
        let s = AlertSettings.decode(Data(json.utf8))
        #expect(s[.low] == AlertRule(on: true, threshold: 80, repeatMin: 5))
        #expect(s[.high] == .defaults(.high))
        #expect(s[.veryLow] == .defaults(.veryLow))
        #expect(s.sounds.pack == "kart" && s.sounds.kinds.isEmpty)
    }

    @Test func unreadableJSONGivesDefaultsWithAlertsOn() {
        #expect(AlertSettings.decode(Data("not json".utf8)) == AlertSettings())
        #expect(AlertSettings.decode(Data(#"{"on":"yes"}"#.utf8)).on)
    }

    @Test func numbersAreCoercedIntoTheSchema() {
        let json =
            #"{"alerts":{"low":{"mgdl":10},"high":{"mgdl":180.4},"falling_fast":{"rate":2.04},"no_data":{"repeatMin":0}},"notificationActions":{"muteMin":9999}}"#
        let s = AlertSettings.decode(Data(json.utf8))
        #expect(s[.low].threshold == 50)
        #expect(s[.high].threshold == 180)
        #expect(abs(s[.fallingFast].threshold - 2.0) < 1e-9)
        #expect(s[.noData].repeatMin == 0)  // 0 = once, a special value
        #expect(s.notificationActions.muteMin == 720)
    }

    @Test func perKindAlarmIsRead() {
        let s = AlertSettings.decode(Data(#"{"v":1,"alerts":{"very_low":{"alarm":false},"low":{"alarm":true}}}"#.utf8))
        #expect(!s[.veryLow].alarm && s[.low].alarm && s[.low].threshold == 70)
    }

    @Test func writtenJSONHasTheSpecKeys() throws {
        let out = try #require(try JSONSerialization.jsonObject(with: AlertSettings().encoded()) as? [String: Any])
        #expect(
            Set(out.keys) == [
                "v", "on", "alerts", "muteAfterInsulin", "muteFallingHigh", "muteAfterCarbs", "sounds",
                "notificationActions", "source",
            ])
        let alerts = try #require(out["alerts"] as? [String: [String: Any]])
        #expect(Set(alerts.keys) == Set(AlertKind.allCases.map(\.rawValue)))
        #expect(Set(alerts["no_data"]?.keys ?? [:].keys) == ["on", "min", "repeatMin", "alarm"])
        #expect(Set(alerts["low"]?.keys ?? [:].keys) == ["on", "mgdl", "repeatMin", "alarm"])
        #expect(Set(alerts["rising_fast"]?.keys ?? [:].keys) == ["on", "rate", "repeatMin", "alarm"])
        #expect(out["v"] as? Int == 1)
    }

    @Test func roundTrip() {
        var s = AlertSettings()
        s[.high].threshold = 200
        s[.risingFast].on = true
        s.sounds.kinds = ["high": "kart"]
        s.source = .own
        s.notificationActions.relaxMin = 45
        #expect(AlertSettings.decode(s.encoded()) == s)
    }

    @Test func choices() {
        var s = AlertSettings()
        #expect(s.muteChoices == [30, 15, 60, 120])
        #expect(s.relaxChoices == [20, 30, 45, 60, 90, 120])  // slower than the slowest level repeat (high, 15)
        s.notificationActions.muteMin = 45
        #expect(s.muteChoices == [45, 15, 30, 60, 120])
    }
}
