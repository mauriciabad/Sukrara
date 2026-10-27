// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation

/// What the UI shows about the alerts (SPEC §2 "States and exits"): the active alert per group, every quiet or relaxed
/// side with its way out, and Mute all. A value built from the engine; the UI words it with `MuteCopy`.
public struct AlertUIState: Equatable, Sendable {
    /// An episode whose condition holds now.
    public struct Active: Equatable, Sendable {
        public var kind: AlertKind
        public var quiet: QuietReason?
        public var firedAt: Date?
        public var n: Int
    }

    /// A side the user can act on now: quiet (`quiet` why: Unmute / Alert me anyway) and / or relaxed (`relaxMin` > 0:
    /// Remind as usual). `canRelax`: Remind less would slow it. A side muted or relaxed after its episode ended has
    /// `isOn` false and the side's main kind.
    public struct Quiet: Equatable, Sendable {
        public var kind: AlertKind
        public var isOn: Bool
        public var quiet: QuietReason?
        public var relaxMin: Int
        public var canRelax: Bool
        public var side: AlertSide { kind.side }
    }

    public var active: [AlertGroup: Active]
    public var quiets: [Quiet]
    /// Mute all's end (nil = off, `muteAllForever` = until turned off).
    public var muteAllUntil: Date?

    /// Nothing active, nothing quiet.
    public static let idle = AlertUIState(active: [:], quiets: [], muteAllUntil: nil)

    /// An alert is up now (it fired in its episode and nothing quiets it).
    public var isAlerting: Bool { active.values.contains { $0.quiet == nil && $0.firedAt != nil } }
}

extension AlertEngine {
    /// The UI state at `t`.
    public func uiState(at t: Date) -> AlertUIState {
        let s = settings
        var active: [AlertGroup: AlertUIState.Active] = [:]
        var quiets: [AlertUIState.Quiet] = []
        var seen: Set<AlertSide> = []
        func canRelax(_ k: AlertKind, _ relax: Int) -> Bool { relax == 0 && s.canRelax(k) }
        for g in AlertGroup.allCases {
            guard let e = episodes[g] else { continue }
            if e.isOn { active[g] = AlertUIState.Active(kind: e.kind, quiet: e.quiet, firedAt: e.firedAt, n: e.n) }
            seen.insert(e.side)
            let sideMute = snoozes.muteEnd(e.side, at: t)
            let relax = snoozes.relaxMin(e.side)
            var why: QuietReason?
            if let by = s.quietedBy(e.kind), let tc = cover(e, e.kind, at: t) {
                why = .treatment(
                    by, at: tc, until: max(tc.addingTimeInterval(s.quietDuration(by)), sideMute ?? .distantPast))
            } else if let sideMute {
                why = .sideMuted(until: sideMute)
            } else if e.isOn, case .fallingBack = e.quiet {
                why = e.quiet
            }
            guard why != nil || relax > 0 else { continue }
            quiets.append(
                AlertUIState.Quiet(
                    kind: e.kind, isOn: e.isOn, quiet: why, relaxMin: relax, canRelax: canRelax(e.kind, relax)))
        }
        for (side, sn) in snoozes.all.sorted(by: { $0.key < $1.key }) where !seen.contains(side) {
            let until = sn.until.flatMap { $0 > t ? $0 : nil }
            guard until != nil || sn.relaxMin > 0 else { continue }
            let k = side.mainKind
            quiets.append(
                AlertUIState.Quiet(
                    kind: k, isOn: false, quiet: until.map { .sideMuted(until: $0) }, relaxMin: sn.relaxMin,
                    canRelax: canRelax(k, sn.relaxMin)))
        }
        return AlertUIState(active: active, quiets: quiets, muteAllUntil: muteAllEnd(at: t))
    }
}
