// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation
import SukraraAlerts
import SukraraCore
import SukraraGraph

/// The alert engine's state as the card's decision (`CardAlerts`): the badge under the value, each treatment's quiet
/// end (the chart's mute lines) and the last-treatment slot. Everything comes from the engine's state and the rules it
/// runs, so the card and the alerts can't disagree.
enum CardAlertsMapping {
    /// `settings` = the rules the engine runs (nil = no alert engine in this run: no mute lines, the slot shows the
    /// newest treatment without a mute). `marks` = the live (not deleted) treatments.
    static func of(
        _ ui: AlertUIState, settings: AlertSettings? = nil, marks: [GraphMark] = [], now: Date
    ) -> CardAlerts {
        var ends: [String: TimeInterval] = [:]
        if let settings {
            for m in marks { if let e = muteEnd(m, settings) { ends[m.id] = e } }
        }
        let status = status(ui, marks: marks, now: now)
        let last = lastTreatment(marks, status: status, now: now.timeIntervalSince1970) { ends[$0.id] }
        return CardAlerts(status: status, last: last, muteEnds: ends)
    }

    /// The badge: what quiets the level alert (a treatment → Taken with its time left; falling back into range →
    /// paused for the minutes it needs; Mute of the side / Mute all → paused), else Needed; Mute all in range → muted.
    static func status(_ ui: AlertUIState, marks: [GraphMark], now: Date) -> AlertStatus {
        let all = ui.muteAllUntil.map { min($0.timeIntervalSince1970, AlertStatus.never) }
        guard let level = ui.active[.level] else { return all.map { .muted(until: $0) } ?? .none }
        let side: AlertStatus.Side = level.kind.isBelowThreshold ? .low : .high
        switch level.quiet {
        case .treatment(let kind, let at, let until)?:
            let k = kind == .carbs ? Treatment.Kind.carbs.rawValue : Treatment.Kind.rapid.rawValue
            let at = at.timeIntervalSince1970
            // the entry the engine counted (logged at `at`); without it on the chart the pill still says how long
            if let m = marks.filter({ $0.k == k && abs($0.t - at) < 1 }).last {
                return .taken(side, treatment: m, until: until.timeIntervalSince1970)
            }
            return .paused(side, until: until.timeIntervalSince1970)
        case .fallingBack(let minutes)?:
            return .paused(side, until: now.timeIntervalSince1970 + TimeInterval(max(1, minutes) * 60))
        case .sideMuted(let until)?:
            return .paused(side, until: until.timeIntervalSince1970)
        case .allMuted(let until)?:
            return .paused(side, until: min(until.timeIntervalSince1970, AlertStatus.never))
        case nil:
            return all.map { .paused(side, until: $0) } ?? .needed(side)
        }
    }

    /// The badge while this Mac mirrors the wearer's alerts (its engine then runs only no data, so it has no level
    /// episode): the same rules over the latest reading and the rules in use (the wearer's): a covering treatment →
    /// Taken; this Mac's Mute of the side or Mute all → paused; else Needed; in range with Mute all → muted.
    static func mirroredStatus(
        latest: GlucoseSample?, settings: AlertSettings, marks: [GraphMark], sideMutes: [AlertSide: Date],
        muteAll: Date?, now: Date
    ) -> AlertStatus {
        let t = now.timeIntervalSince1970
        let all = muteAll.map { min($0.timeIntervalSince1970, AlertStatus.never) }
        guard let kind = AlertEngine.level(of: latest, settings, at: now) else {
            return all.map { .muted(until: $0) } ?? .none
        }
        let side: AlertStatus.Side = kind.isBelowThreshold ? .low : .high
        if let by = settings.quietedBy(kind) {
            let k = by == .carbs ? Treatment.Kind.carbs.rawValue : Treatment.Kind.rapid.rawValue
            let cover = marks.filter { $0.k == k && $0.t <= t }
                .compactMap { m in muteEnd(m, settings).map { (m, $0) } }
                .filter { $0.1 > t }
                .max { $0.0.t < $1.0.t }
            if let cover { return .taken(side, treatment: cover.0, until: cover.1) }
        }
        let paused = max(sideMutes[kind.side].map(\.timeIntervalSince1970) ?? 0, all ?? 0)
        return paused > t ? .paused(side, until: paused) : .needed(side)
    }

    /// When `m`'s quiet window ends, or nil when its kind quiets nothing (the rule is off, long-acting, a note).
    static func muteEnd(_ m: GraphMark, _ s: AlertSettings) -> TimeInterval? {
        switch m.k {
        case Treatment.Kind.rapid.rawValue where s.muteAfterInsulin.on: m.t + s.quietDuration(.rapidInsulin)
        case Treatment.Kind.carbs.rawValue where s.muteAfterCarbs.on: m.t + s.quietDuration(.carbs)
        default: nil
        }
    }

    /// How long an entry stays in the last-treatment slot: rapid insulin 4 h, carbs 3 min per gram between 30 min and
    /// 3 h; long-acting and notes never.
    static func keep(_ m: GraphMark) -> TimeInterval? {
        switch m.k {
        case Treatment.Kind.rapid.rawValue: 4 * 3600
        case Treatment.Kind.carbs.rawValue: min(max(m.a * 180, 30 * 60), 3 * 3600)
        default: nil
        }
    }

    /// The slot's entry: rapid insulin whose quiet still runs, else the treatment quieting the current alert, else the
    /// newest rapid / carbs entry still kept (insulin wins a tie in the same minute); nil = nothing recent (the slot
    /// shows its fallback). Running insulin carries the newest kept carbs along.
    static func lastTreatment(
        _ marks: [GraphMark], status: AlertStatus, now: TimeInterval, muteEnd: (GraphMark) -> TimeInterval?
    ) -> LastTreatment.Info? {
        let rapid = Treatment.Kind.rapid.rawValue, carbsKind = Treatment.Kind.carbs.rawValue
        let live = marks.filter { $0.t <= now }
        func kept(_ m: GraphMark) -> Bool { keep(m).map { now - m.t < $0 } == true }
        let tr: GraphMark
        if let r = live.filter({ $0.k == rapid && (muteEnd($0) ?? 0) > now }).max(by: { $0.t < $1.t }) {
            tr = r
        } else if case .taken(_, let t, _) = status {
            tr = t
        } else {
            func key(_ m: GraphMark) -> (Int, Int) { (Int((m.t / 60).rounded(.down)), m.k == rapid ? 1 : 0) }
            guard let t = live.filter(kept).max(by: { key($0) < key($1) }) else { return nil }
            tr = t
        }
        let until = muteEnd(tr)
        var carbs: LastTreatment.Companion?
        if tr.k == rapid, (until ?? 0) > now,
            let c = live.filter({ $0.k == carbsKind && kept($0) }).max(by: { $0.t < $1.t })
        {
            carbs = LastTreatment.Companion(tr: c, until: muteEnd(c))
        }
        return LastTreatment.Info(tr: tr, until: until, carbs: carbs)
    }

    /// The live treatments as the chart's marks, oldest first.
    static func marks(_ treatments: [Treatment]) -> [GraphMark] {
        treatments.filter { !$0.del }.map {
            GraphMark(t: TimeInterval($0.t), k: $0.k.rawValue, a: $0.a, n: $0.n, id: $0.id)
        }.sorted { $0.t < $1.t }
    }
}
