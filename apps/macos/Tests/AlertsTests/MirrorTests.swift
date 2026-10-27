// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation
import SukraraAlerts
import Testing

/// The mirroring follower's actions act on one side only (SPEC §2): a high's Mute never hides or quiets a low.
@Suite struct MirrorTests {
    let t0 = Date(timeIntervalSince1970: 1_760_650_020)
    func at(_ m: Int) -> Date { t0.addingTimeInterval(TimeInterval(m * 60)) }

    @Test func muteOfTheOtherSideKeepsTheShownAlert() {
        var m = AlertMirror()
        let shownLow = m.fire(.low, id: "a", at: at(0))
        let hidByHighMute = m.mute(.high, until: at(30))
        #expect(shownLow && !hidByHighMute && m.isShowing(.level))
        let hidByLowMute = m.mute(.low, until: at(30))
        #expect(hidByLowMute && !m.isShowing(.level))
    }

    @Test func unmuteNeverShowsTheOtherSide() {
        var m = AlertMirror()
        _ = m.mute(.high, until: at(60))
        _ = m.mute(.low, until: at(60))
        let shownHigh = m.fire(.high, id: "h", at: at(1))
        let afterLowUnmute = m.unmute(.low, at: at(2))  // must not show the still-muted high
        #expect(!shownHigh && afterLowUnmute == nil && !m.isShowing(.level))
        let afterHighUnmute = m.unmute(.high, at: at(3))
        #expect(afterHighUnmute == "h")
    }

    @Test func remindAsUsualShowsOnlyItsSide() {
        var m = AlertMirror()
        m.remindLess(.low, relaxMin: 30)
        let first = m.fire(.low, id: "l1", at: at(0))
        let repeatShown = m.fire(.low, id: "l2", at: at(5))  // relaxed: the repeat is skipped
        let otherSide = m.remindAsUsual(.high, at: at(6))
        let sameSide = m.remindAsUsual(.low, at: at(6))
        #expect(first && !repeatShown && otherSide == nil && sameSide == "l2")
    }

    @Test func muteSurvivesARelaunch() throws {
        var m = AlertMirror()
        _ = m.fire(.low, id: "l1", at: at(0))
        _ = m.mute(.low, until: at(30))
        _ = m.muteAll(until: at(45))
        let data = try JSONEncoder().encode(m)
        var back = try JSONDecoder().decode(AlertMirror.self, from: data)
        back.sanitize(at: at(10))
        #expect(back == m)
        #expect(back.muteAllEnd(at: at(10)) == at(45))
        // still muted after the relaunch: the wearer's repeat doesn't show
        let repeatShown = back.fire(.low, id: "l2", at: at(11))
        #expect(!repeatShown)
    }

    @Test func sanitizeDropsAnEndedMuteAll() {
        var m = AlertMirror()
        _ = m.muteAll(until: at(5))
        m.sanitize(at: at(10))
        #expect(m.muteAllUntil == nil)
    }
}
