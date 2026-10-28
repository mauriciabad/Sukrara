// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation
import SukraraAlerts
import SukraraCore
import SukraraTesting
import Testing
import UserNotifications

@testable import SukraraApp

/// Records requests instead of posting (no real notification ever leaves a test).
final class FakeCenter: NotificationPosting {
    var added: [UNNotificationRequest] = []
    var removed: [String] = []
    var timeSensitiveAllowed = true
    func add(_ request: UNNotificationRequest) { added.append(request) }
    func removeDelivered(ids: [String]) { removed += ids }
}

@Suite struct NotifierTests {
    let clock = FakeClock()
    let center = FakeCenter()

    func firing(_ kind: AlertKind, mgdl: Double = 62, rate: Double? = -1.5, treated: Int = 0) -> AlertFiring {
        AlertFiring(
            id: "abc", kind: kind, at: clock.now, reading: GlucoseSample(at: clock.now, mgdl: mgdl, rate: rate),
            threshold: 70, n: 1, treated: treated)
    }

    func notifier(_ lang: AlertText.Language = .en, settings: AlertSettings = AlertSettings()) -> Notifier {
        Notifier(center: center, sounds: nil, text: AlertText(language: lang), clock: clock, settings: { settings })
    }

    @Test func categoryIDsAreTheAppOnes() {
        let ids = Set(Notifier.categories(AlertText(language: .en)).map(\.identifier))
        #expect(
            ids == [
                "app.mauri.sukrara.alert", "app.mauri.sukrara.alert.relaxed", "app.mauri.sukrara.alert.muteOnly",
                "app.mauri.sukrara.muted",
            ])
        let muted = Notifier.categories(AlertText(language: .en)).first { $0.identifier == "app.mauri.sukrara.muted" }
        #expect(
            muted?.actions.map(\.identifier) == [
                AlertNotificationIDs.unmuteAction, AlertNotificationIDs.changeMuteAction,
            ])
    }

    @Test func alertTextIsSelfContainedInEnglishAndSpanish() {
        notifier(.en).post(firing(.low), sound: true, category: AlertNotificationIDs.alertCategory)
        notifier(.es).post(firing(.low), sound: true, category: AlertNotificationIDs.alertCategory)
        let en = center.added[0].content, es = center.added[1].content
        #expect(en.title == "Low glucose")
        #expect(en.body == "62 mg/dL ↘")
        #expect(es.title == "Glucosa baja")
        #expect(es.body == "62 mg/dL ↘")
        #expect(center.added[0].identifier == AlertNotificationIDs.notificationID(.level))
        #expect(en.userInfo[Notifier.sideKey] as? String == "low")
    }

    /// contracts/alerts/SPEC.md §1: no threshold; fast alerts add the rate; no data says how long and the last value.
    @Test func bodyFollowsTheSpec() {
        let en = AlertText(language: .en), es = AlertText(language: .es)
        let fast = firing(.fallingFast, mgdl: 120, rate: -2.4)
        #expect(en.body(fast, now: clock.now) == "120 mg/dL ↓ · falling 2.4 mg/dL/min")
        #expect(es.body(fast, now: clock.now) == "120 mg/dL ↓ · baja 2,4 mg/dL/min")
        #expect(en.body(firing(.risingFast, mgdl: 150, rate: 2.0), now: clock.now).hasSuffix(" · rising 2.0 mg/dL/min"))
        #expect(!en.body(firing(.fallingFast, rate: nil), now: clock.now).contains("·"))
        let old = AlertFiring(
            id: "n", kind: .noData, at: clock.now,
            reading: GlucoseSample(at: clock.now.addingTimeInterval(-25 * 60), mgdl: 120, rate: nil), threshold: 20,
            n: 1, treated: 0)
        #expect(en.body(old, now: clock.now) == "No reading for 25 min · last 120 mg/dL")
        #expect(es.body(old, now: clock.now) == "Sin lecturas desde hace 25 min · última 120 mg/dL")
        let lo = firing(.veryLow, mgdl: 39, rate: nil)
        #expect(en.body(lo, now: clock.now).hasPrefix("LO · below 40 mg/dL"))
        #expect(es.headline(firing(.low, treated: 2)) == "Glucosa baja · vuelve a comer")
    }

    @Test func aSilentResumeHasNoSoundAndASoundCopyFailureRingsTheDefault() {
        notifier().post(firing(.low), sound: false, category: AlertNotificationIDs.alertCategory)
        #expect(center.added[0].content.sound == nil)
        // sounds: nil = nothing could be copied → the default sound, never silence
        notifier().post(firing(.low), sound: true, category: AlertNotificationIDs.alertCategory)
        #expect(center.added[1].content.sound != nil)
    }

    @Test func alarmsAreTimeSensitiveOnlyWhenAllowed() {
        var s = AlertSettings()
        s.rules[.veryLow]?.alarm = true
        notifier(settings: s).post(firing(.veryLow, mgdl: 50), sound: true, category: "")
        center.timeSensitiveAllowed = false
        notifier(settings: s).post(firing(.veryLow, mgdl: 50), sound: true, category: "")
        #expect(center.added[0].content.interruptionLevel == .timeSensitive)
        #expect(center.added[1].content.interruptionLevel == .active)
    }

    @Test func actionsMapToAlertActions() {
        let s = AlertSettings()
        typealias IDs = AlertNotificationIDs
        #expect(
            Notifier.action(actionID: IDs.muteAction, side: "low", typed: "", settings: s) == .mute(.low, minutes: 30))
        #expect(
            Notifier.action(actionID: IDs.muteAction, side: "high", typed: "1 h", settings: s)
                == .mute(.high, minutes: 60))
        #expect(
            Notifier.action(actionID: IDs.changeMuteAction, side: "low", typed: "45", settings: s)
                == .mute(.low, minutes: 45))
        #expect(Notifier.action(actionID: IDs.muteAction, side: "low", typed: "soon", settings: s) == nil)
        let longest = Int(AlertLimits.longestMuteMin)
        #expect(
            Notifier.action(actionID: IDs.muteAction, side: "low", typed: "\(longest + 600)", settings: s)
                == .mute(.low, minutes: longest))
        #expect(
            Notifier.action(actionID: IDs.remindLessAction, side: "high", typed: nil, settings: s) == .remindLess(.high)
        )
        #expect(
            Notifier.action(actionID: IDs.remindAsUsualAction, side: "high", typed: nil, settings: s)
                == .remindAsUsual(.high))
        #expect(
            Notifier.action(actionID: IDs.unmuteAction, side: "no_data", typed: nil, settings: s) == .unmute(.noData))
        #expect(
            Notifier.action(actionID: UNNotificationDefaultActionIdentifier, side: "low", typed: nil, settings: s)
                == nil)
        #expect(Notifier.action(actionID: IDs.unmuteAction, side: "sideways", typed: nil, settings: s) == nil)
    }

    @Test func mutedNoteIsSilentWithItsCategory() {
        notifier().postMuted(.low, until: clock.now.addingTimeInterval(1800))
        let r = center.added[0]
        #expect(r.identifier == AlertNotificationIDs.mutedNotificationID(.low))
        #expect(r.content.title == "Low alerts muted" && r.content.sound == nil)
        #expect(r.content.categoryIdentifier == AlertNotificationIDs.mutedCategory)
    }

    @Test func preferredLanguageFallsBackToEnglish() {
        #expect(AlertText.preferred(["es-ES", "en"]).language == .es)
        #expect(AlertText.preferred(["fr-FR", "es"]).language == .es)
        #expect(AlertText.preferred(["de"]).language == .en)
    }
}
