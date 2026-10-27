// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation
import SukraraAlerts
import Testing

/// A relaunch resumes open episodes without ringing again (an update or relaunch must not take an ongoing alert for a
/// new one), and an untrustworthy snapshot fails safe (alerts active, nothing muted).
@Suite struct EpisodesRestoreTests {
    let t0 = Date(timeIntervalSince1970: 1_760_000_040)
    func minute(_ m: Int) -> Date { t0.addingTimeInterval(TimeInterval(m * 60)) }

    /// One 200 mg/dL reading per minute from `start` for `count` minutes; "minute headline" per firing.
    func highs(_ e: inout AlertEngine, _ count: Int, start: Int = 0) -> [String] {
        var out: [String] = []
        for m in start..<(start + count) {
            for effect in e.handle(.reading(GlucoseSample(at: minute(m), mgdl: 200, rate: 0)), at: minute(m)) {
                if case .fire(let f) = effect { out.append("\(m) \(f.headline)") }
                if case .clear(let f) = effect { out.append("\(m) clear \(f.kind.rawValue)") }
            }
        }
        return out
    }

    func fired(_ effects: [AlertEffect]) -> [AlertFiring] {
        effects.compactMap { if case .fire(let f) = $0 { f } else { nil } }
    }

    @Test func restartResumesWithoutRingingAgain() throws {
        var before = AlertEngine(context: AlertContext())
        var firstID: String?
        for m in 0..<4 {
            let fx = before.handle(.reading(GlucoseSample(at: minute(m), mgdl: 200, rate: 0)), at: minute(m))
            if let f = fired(fx).first { firstID = f.id }
        }
        let snapshot = try #require(AlertEngine.Snapshot.decode(before.snapshot.encoded()))
        var (after, effects) = AlertEngine.restore(snapshot, context: before.context, at: minute(4))
        let resumed = effects.compactMap { if case .resume(let f) = $0 { f } else { nil } }
        #expect(resumed.map(\.kind) == [.high])
        #expect(resumed.first?.id == firstID && resumed.first?.n == 1)
        guard case .schedule = effects.last else {
            Issue.record("restore must end with a schedule")
            return
        }
        #expect(highs(&after, 16, start: 4) == ["15 High glucose"])
        var fresh = AlertEngine(context: AlertContext())
        #expect(highs(&fresh, 1, start: 4) == ["4 High glucose"])
    }

    @Test func episodesThatEndedWhileDownAreDropped() {
        var ended = AlertEngine(context: AlertContext())
        _ = highs(&ended, 1)
        let (late, effects) = AlertEngine.restore(ended.snapshot, context: ended.context, at: minute(60))
        #expect(late.uiState(at: minute(60)).active.isEmpty)
        #expect(effects.count == 1)
    }

    @Test func corruptOrForeignSnapshotStartsFresh() {
        #expect(AlertEngine.Snapshot.decode(Data("garbage".utf8)) == nil)
        #expect(AlertEngine.Snapshot.decode(Data(#"{"v":2,"episodes":[],"snoozes":{"sides":{}}}"#.utf8)) == nil)
        var (e, _) = AlertEngine.restore(nil, context: AlertContext(), at: minute(0))
        #expect(highs(&e, 1) == ["0 High glucose"])
    }

    @Test func untrustworthyMutesAreSanitized() throws {
        var e = AlertEngine(context: AlertContext())
        _ = e.handle(.mute(.low, minutes: 30), at: minute(0))
        _ = e.handle(.mute(.high, minutes: 720), at: minute(0))
        _ = e.handle(.muteAll(until: minute(10_000)), at: minute(0))
        var snap = e.snapshot
        // a snapshot from a clock far ahead: the high Mute ends years from now
        snap.snoozes.mute(.high, until: minute(10_000_000))
        let (restored, _) = AlertEngine.restore(snap, context: AlertContext(), at: minute(40))
        #expect(restored.snoozes[.low] == nil)  // over by then
        #expect(try #require(restored.snoozes[.high]?.until) <= minute(40 + 720))
        #expect(try #require(restored.muteAllUntil) <= minute(40 + 1440))
        let (forever, _) = AlertEngine.restore(
            AlertEngine.Snapshot(episodes: [], snoozes: SideSnoozes(), muteAllUntil: muteAllForever),
            context: AlertContext(),
            at: minute(0))
        #expect(forever.muteAllUntil == muteAllForever)
    }

    @Test func snapshotJSONRoundTrip() throws {
        var e = AlertEngine(context: AlertContext())
        _ = highs(&e, 3)
        _ = e.handle(.remindLess(.high), at: minute(3))
        let snap = e.snapshot
        #expect(AlertEngine.Snapshot.decode(snap.encoded()) == snap)
        let json = try #require(try JSONSerialization.jsonObject(with: snap.encoded()) as? [String: Any])
        #expect(json["v"] as? Int == 1)
    }
}
