// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation
import SukraraAlerts
import Testing

/// Invariants beyond the timelines: safety rules, effect order, the follower's local no-data, time jumps, purity.
@Suite struct EngineTests {
    let t0 = Date(timeIntervalSince1970: 1_760_650_020)
    func at(_ m: Int) -> Date { t0.addingTimeInterval(TimeInterval(m * 60)) }

    func kinds(_ effects: [AlertEffect]) -> [String] {
        effects.compactMap {
            switch $0 {
            case .fire(let f): "fire \(f.kind.rawValue)"
            case .clear(let f): "clear \(f.kind.rawValue)"
            case .resume(let f): "resume \(f.kind.rawValue)"
            case .back(let s): "back \(s.rawValue)"
            case .schedule: nil
            }
        }
    }

    @Test func noTreatmentEverQuietsExtremelyHighOrNoData() {
        let s = AlertSettings()
        #expect(s.quietedBy(.extremelyHigh) == nil)
        #expect(s.quietedBy(.noData) == nil)
        var e = AlertEngine(context: AlertContext(treatments: [AlertTreatment(kind: .rapidInsulin, at: at(0))]))
        #expect(
            kinds(e.handle(.reading(GlucoseSample(at: at(1), mgdl: 320, rate: 0)), at: at(1))) == [
                "fire extremely_high"
            ])
    }

    @Test func scheduleIsAlwaysTheLastEffect() {
        var e = AlertEngine(context: AlertContext())
        let inputs: [AlertInput] = [
            .reading(GlucoseSample(at: at(0), mgdl: 60, rate: -3)), .tick, .mute(.low, minutes: 5), .unmute(.low),
            .unmuteAll,
            .muteAll(until: at(30)), .settings(AlertSettings()),
        ]
        for input in inputs {
            let fx = e.handle(input, at: at(0))
            guard case .schedule = fx.last else {
                Issue.record("\(input): last effect is \(String(describing: fx.last))")
                continue
            }
            #expect(fx.dropLast().allSatisfy { if case .schedule = $0 { false } else { true } })
        }
    }

    @Test func everyKindHasAHeadline() {
        let expected = [
            "Very low glucose", "Low glucose", "High glucose", "Very high glucose", "Extremely high glucose",
            "Falling fast",
            "Rising fast", "No glucose data",
        ]
        #expect(AlertKind.allCases.map { $0.headline() } == expected)
        #expect(AlertKind.high.headline(treated: 1) == "High glucose · still high after insulin")
        #expect(AlertKind.low.headline(treated: 2) == "Low glucose · eat more again")
        #expect(AlertKind.risingFast.headline(treated: 2) == "Rising fast")
    }

    /// A follower mirroring the wearer runs only no data here; it carries the threshold and the last reading.
    @Test func noDataOnlyScope() {
        var e = AlertEngine(context: AlertContext(scope: .noDataOnly))
        #expect(kinds(e.handle(.reading(GlucoseSample(at: at(0), mgdl: 300, rate: 3)), at: at(0))).isEmpty)
        let fx = e.handle(.tick, at: at(25))
        #expect(kinds(fx) == ["fire no_data"])
        guard case .fire(let f) = fx.first else { return }
        #expect(f.threshold == 20 && f.reading?.mgdl == 300 && f.id.count == 16)
    }

    /// The Mac slept 8 h without a timer: the first tick after wake judges the present (here: no data, once).
    @Test func longSleepThenTick() {
        var e = AlertEngine(context: AlertContext())
        _ = e.handle(.reading(GlucoseSample(at: at(0), mgdl: 120, rate: 0)), at: at(0))
        #expect(kinds(e.handle(.tick, at: at(8 * 60))) == ["fire no_data"])
        #expect(kinds(e.handle(.tick, at: at(8 * 60 + 1))).isEmpty)
    }

    /// The clock was set back after an alert: repeats keep coming instead of stalling until the clock catches up.
    @Test func clockSetBackDoesNotStallRepeats() {
        var e = AlertEngine(context: AlertContext())
        #expect(kinds(e.handle(.reading(GlucoseSample(at: at(60), mgdl: 60, rate: 0)), at: at(60))) == ["fire low"])
        // this Mac's clock jumps 60 min back; the wearer's readings (their own timestamps) keep coming every minute
        var fires: [Int] = []
        for m in 1...12 {
            let fx = e.handle(.reading(GlucoseSample(at: at(60 + m), mgdl: 60, rate: 0)), at: at(m))
            if kinds(fx).contains("fire low") { fires.append(m) }
        }
        #expect(fires == [6, 11])
    }

    /// Firing ids come from the injected generator: a replay is deterministic.
    @Test func idsComeFromTheInjectedGenerator() {
        struct Fixed: RandomNumberGenerator {
            var state: UInt64
            mutating func next() -> UInt64 {
                state &+= 0x9E37_79B9_7F4A_7C15
                return state
            }
        }
        func firstID() -> String? {
            var e = AlertEngine(context: AlertContext())
            var rng = Fixed(state: 1)
            let fx = e.handle(.reading(GlucoseSample(at: at(0), mgdl: 60)), at: at(0), using: &rng)
            if case .fire(let f) = fx.first { return f.id }
            return nil
        }
        #expect(firstID() != nil && firstID() == firstID())
    }

    /// The UI state lists every quiet side with its way out (SPEC §2 "States and exits").
    @Test func uiStateListsQuietSides() {
        var e = AlertEngine(context: AlertContext())
        _ = e.handle(.reading(GlucoseSample(at: at(0), mgdl: 60, rate: 0)), at: at(0))
        #expect(AlertUIState.idle.isAlerting == false)
        #expect(e.uiState(at: at(0)).isAlerting)
        _ = e.handle(.mute(.low, minutes: 30), at: at(1))
        _ = e.handle(.remindLess(.high), at: at(1))
        let ui = e.uiState(at: at(2))
        #expect(!ui.isAlerting)
        #expect(ui.active[.level]?.kind == .low)
        #expect(ui.active[.level]?.quiet == .sideMuted(until: at(31)))
        #expect(ui.quiets.map(\.side) == [.low, .high])
        #expect(ui.quiets.first?.quiet == .sideMuted(until: at(31)))
        #expect(ui.quiets.last.map { !$0.isOn && $0.relaxMin == 30 && !$0.canRelax } == true)
        _ = e.handle(.muteAll(until: muteAllForever), at: at(2))
        #expect(e.uiState(at: at(3)).muteAllUntil == muteAllForever)
        _ = e.handle(.unmuteAll, at: at(3))
        #expect(e.uiState(at: at(3)).muteAllUntil == nil)
    }
}
