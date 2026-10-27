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

/// Records what the controller asks the notification layer to do.
final class RecordingNotifier: AlertNotifying {
    enum Call: Equatable {
        case post(AlertKind, id: String, sound: Bool, category: String)
        case clear(AlertGroup)
        case muted(AlertSide)
        case unmuted(AlertSide)
    }
    var calls: [Call] = []
    var posts: [Call] { calls.filter { if case .post = $0 { true } else { false } } }

    func post(_ f: AlertFiring, sound: Bool, category: String) {
        calls.append(.post(f.kind, id: f.id, sound: sound, category: category))
    }
    func clear(_ group: AlertGroup) { calls.append(.clear(group)) }
    func postMuted(_ side: AlertSide, until: Date) { calls.append(.muted(side)) }
    func removeMuted(_ side: AlertSide) { calls.append(.unmuted(side)) }
}

final class MemoryAlertStore: AlertStateStoring {
    var state: AlertState?
    func load() -> AlertState? { state }
    func save(_ state: AlertState) { self.state = state }
}

final class RecordingWake: WakeScheduling {
    var at: Date?
    func schedule(at date: Date?, _ fire: @escaping @MainActor () -> Void) { at = date }
}

@Suite struct AlertControllerTests {
    let clock = FakeClock()
    let store = MemoryAlertStore()
    let notifier = RecordingNotifier()
    let wake = RecordingWake()

    func controller(_ settings: AlertSettings = AlertSettings()) -> AlertController {
        AlertController(settings: settings, clock: clock, store: store, notifier: notifier, wake: wake)
    }

    func reading(_ mgdl: Int, minutesAgo: Double = 0, rate: Double? = 0) -> Reading {
        Reading(t: clock.now.addingTimeInterval(-minutesAgo * 60), mgdl: mgdl, rate: rate)
    }

    @Test func noTimerAndNoAlarmBeforeTheFirstReading() {
        let c = controller()
        #expect(c.nextWake == nil && wake.at == nil)
        clock.advance(by: 6 * 3600)
        c.tick()
        #expect(notifier.posts.isEmpty && c.nextWake == nil)
    }

    @Test func noDataIsTimedByTheEngineNotByTheNextReading() {
        let c = controller()
        c.reading(reading(110))
        let deadline = clock.now.addingTimeInterval(AlertSettings()[.noData].threshold * 60)
        #expect(wake.at != nil && wake.at! <= deadline)
        clock.advance(by: AlertSettings()[.noData].threshold * 60 + 1)
        c.tick()
        #expect(notifier.posts.contains { if case .post(.noData, _, true, _) = $0 { true } else { false } })
    }

    @Test func aLowFiresWithSoundAndItsCategory() {
        let c = controller()
        c.reading(reading(60, rate: -0.5))
        guard case .post(.low, _, true, let category)? = notifier.posts.first else {
            Issue.record("no low posted: \(notifier.calls)")
            return
        }
        #expect(
            category == AlertNotificationIDs.alertCategory || category == AlertNotificationIDs.alertMuteOnlyCategory)
        #expect(c.ui.isAlerting)
    }

    @Test func episodesAndMutesSurviveARelaunchWithoutRingingAgain() {
        let c = controller()
        c.reading(reading(60, rate: -0.5))
        c.perform(.muteAll(until: clock.now.addingTimeInterval(3600)))
        #expect(store.state != nil)
        clock.advance(by: 60)
        notifier.calls = []
        let relaunched = controller()
        // the alert that was up shows again silently, nothing rings
        #expect(!notifier.posts.contains { if case .post(_, _, true, _) = $0 { true } else { false } })
        #expect(relaunched.ui.muteAllUntil != nil)
    }

    @Test func muteActionPostsTheMutedNoteAndUnmuteRemovesIt() {
        let c = controller()
        c.reading(reading(60, rate: -0.5))
        c.perform(.mute(.low, minutes: 30))
        #expect(notifier.calls.contains(.muted(.low)))
        #expect(!c.ui.isAlerting)
        c.perform(.unmute(.low))
        #expect(notifier.calls.contains(.unmuted(.low)))
    }

    @Test func mirroringShowsTheWearersAlertAndRunsOnlyNoDataHere() throws {
        let c = controller()
        c.setMirroring(true)
        c.reading(reading(60, rate: -0.5))  // a low here: the wearer decides, not this Mac
        #expect(notifier.posts.isEmpty)
        c.wearerAlert(try payload(kind: "low", fires: true))
        #expect(notifier.posts.contains { if case .post(.low, "w1", true, _) = $0 { true } else { false } })
        c.wearerAlert(try payload(kind: "low", fires: false))
        #expect(notifier.calls.last == .clear(.level))
    }

    @Test func aMirroredMuteSurvivesARelaunch() throws {
        let c = controller()
        c.setMirroring(true)
        c.perform(.mute(.low, minutes: 60))
        let relaunched = controller()
        relaunched.setMirroring(true)
        notifier.calls = []
        relaunched.wearerAlert(try payload(kind: "low", fires: true))
        #expect(notifier.posts.isEmpty)
    }

    @Test func stoppingFollowingDisarmsNoData() {
        let c = controller()
        c.reading(reading(110))
        c.resetMonitoring()
        #expect(c.nextWake == nil)
        clock.advance(by: 3 * 3600)
        c.tick()
        #expect(!notifier.posts.contains { if case .post(.noData, _, _, _) = $0 { true } else { false } })
    }

    func payload(kind: String, fires: Bool) throws -> AlertPayload {
        let at = Int(clock.now.timeIntervalSince1970)
        let json =
            #"{"v":2,"type":"alert","k":"\#(kind)","state":"\#(fires ? "fire" : "clear")","id":"w1","at":\#(at),"t":\#(at),"sgv":60,"r":-0.5,"th":70,"n":1}"#
        return try AlertPayload.decode(json)
    }
}
