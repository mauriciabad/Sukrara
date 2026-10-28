// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation

/// Which rules run here: all of them, or only `no_data` (a follower mirroring the wearer checks its own link).
public enum AlertScope: String, Codable, Sendable {
    case all
    case noDataOnly
}

/// Everything outside the engine the rules read: settings, the treatment log, the sensor, the newest reading.
public struct AlertContext: Equatable, Sendable {
    public var settings: AlertSettings
    public var treatments: [AlertTreatment]
    public var sensor: SensorWindow?
    public var latest: GlucoseSample?
    public var scope: AlertScope

    public init(
        settings: AlertSettings = AlertSettings(), treatments: [AlertTreatment] = [], sensor: SensorWindow? = nil,
        latest: GlucoseSample? = nil, scope: AlertScope = .all
    ) {
        self.settings = settings
        self.treatments = treatments
        self.sensor = sensor
        self.latest = latest
        self.scope = scope
    }
}

/// What happens to the engine. Every input re-evaluates the rules at once.
public enum AlertInput: Equatable, Sendable {
    /// A reading (older than the newest one: ignored, but the rules still run).
    case reading(GlucoseSample)
    /// The treatment log changed (live carbs / rapid insulin entries, no deleted ones).
    case treatments([AlertTreatment])
    case sensor(SensorWindow?)
    case settings(AlertSettings)
    case scope(AlertScope)
    /// The timer (`schedule`), a wake from sleep, or anything else that only needs a re-check.
    case tick
    /// The notification's *Mute…* for `minutes` on a side (on a muted side: Change).
    case mute(AlertSide, minutes: Int)
    /// *Remind less*: repeats at least every `notificationActions.relaxMin`.
    case remindLess(AlertSide)
    /// *Unmute* / *Alert me anyway*.
    case unmute(AlertSide)
    /// *Remind as usual*.
    case remindAsUsual(AlertSide)
    /// *Mute all* until then (`muteAllForever` = until turned off).
    case muteAll(until: Date)
    case unmuteAll
}

/// What the shell must do, in order.
public enum AlertEffect: Equatable, Sendable {
    /// Post a notification (sound, Time Sensitive if `alarm`), replacing the group's previous one.
    case fire(AlertFiring)
    /// Remove the notification of that firing.
    case clear(AlertFiring)
    /// After a relaunch: show that firing again without sound (it was up before).
    case resume(AlertFiring)
    /// The side's Mute / Remind less ended because glucose is back by the margin (history line).
    case back(AlertSide)
    /// Wake the engine with `.tick` at this time (nil = no timer needed). Always the last effect.
    case schedule(Date?)
}

/// The alert rules of `contracts/alerts/SPEC.md` §1–§2 as a pure state machine: no I/O, no clock, no globals.
///
/// Three groups (level, rate, no data), each with at most one `Episode`. Within an episode: the first match alerts at
/// once, a more severe kind of the side at once, then every `repeatMin` from the last alert (0 = once). A treatment
/// that answers it quiets it for its window and alerts at once when the window ends if it still matches; the falling
/// rule only postpones. This device's *Mute* / *Remind less* act on one side, outlive the episode and end at their time
/// or back by the margin; *Mute all* quiets everything. The ways out (`unmute`, `remindAsUsual`, `unmuteAll`) alert at
/// once when something still matches.
public struct AlertEngine: Sendable {
    /// Readings older than this never alert (backfill, a late chunk).
    public static let freshness: TimeInterval = 15 * 60

    public private(set) var context: AlertContext
    var episodes: [AlertGroup: Episode] = [:]
    public internal(set) var snoozes = SideSnoozes()
    /// *Mute all* until then (nil = off, `muteAllForever` = until turned off).
    public internal(set) var muteAllUntil: Date?

    public init(context: AlertContext) { self.context = context }

    public var settings: AlertSettings { context.settings }

    /// Handles `input` at `now`; firing ids come from `rng`.
    public mutating func handle(_ input: AlertInput, at now: Date, using rng: inout some RandomNumberGenerator)
        -> [AlertEffect]
    {
        var out: [AlertEffect] = []
        apply(input, at: now)
        evaluate(at: now, rng: &rng, out: &out)
        out.append(.schedule(nextCheck(after: now)))
        return out
    }

    public mutating func handle(_ input: AlertInput, at now: Date) -> [AlertEffect] {
        var rng = SystemRandomNumberGenerator()
        return handle(input, at: now, using: &rng)
    }

    /// Mute all's end in force at `t` (nil = off).
    public func muteAllEnd(at t: Date) -> Date? { muteAllUntil.flatMap { $0 > t ? $0 : nil } }

    /// The level kind `reading` is in at `t`, or nil (stale, in range, alerts off). Lows `<`, highs `>=`.
    public static func level(of reading: GlucoseSample?, _ s: AlertSettings, at t: Date) -> AlertKind? {
        guard let r = reading, s.on, t.timeIntervalSince(r.at) <= freshness else { return nil }
        return AlertKind.levelsByPriority.first { k in
            s[k].on && (k.isBelowThreshold ? r.mgdl < s[k].threshold : r.mgdl >= s[k].threshold)
        }
    }

    // MARK: inputs

    private mutating func apply(_ input: AlertInput, at t: Date) {
        switch input {
        case .reading(let r):
            if context.latest.map({ r.at > $0.at }) ?? true { context.latest = r }
        case .treatments(let list): context.treatments = list
        case .sensor(let s): context.sensor = s
        case .settings(let s): context.settings = s
        case .scope(let s): context.scope = s
        case .tick: break
        case .mute(let side, let minutes):
            guard minutes > 0 else { return }
            snoozes.mute(side, until: t.addingTimeInterval(TimeInterval(minutes * 60)))
        case .remindLess(let side):
            snoozes.relax(side, relaxMin: settings.notificationActions.relaxMin)
        case .unmute(let side):
            let muted = snoozes.unmute(side, at: t)
            let group = side.mainKind.group
            guard var e = episodes[group], e.side == side else { return }
            let quiet = e.quiet != nil || cover(e, e.kind, at: t) != nil
            guard muted || quiet else { return }
            e.liftedAt = t
            e.liftPending = true
            episodes[group] = e
        case .remindAsUsual(let side):
            snoozes.unrelax(side)
        case .muteAll(let until):
            guard until > t else { return }
            muteAllUntil = until
        case .unmuteAll:
            guard muteAllEnd(at: t) != nil else { return }
            muteAllUntil = nil
        }
    }

    // MARK: rules

    /// No reading is expected now: the sensor has ended or warms up.
    private func expectsNoReading(at t: Date) -> Bool {
        context.sensor?.expectsNoReading(at: t, latestReading: context.latest?.at) ?? false
    }

    private mutating func evaluate(at t: Date, rng: inout some RandomNumberGenerator, out: inout [AlertEffect]) {
        let s = settings
        guard s.on else {
            for g in AlertGroup.allCases {
                if var e = episodes[g] { hide(&e, at: t, out: &out) }
            }
            episodes = [:]
            return
        }
        let r = context.latest
        let fresh = r.map { t.timeIntervalSince($0.at) <= Self.freshness } ?? false
        let rules = context.scope == .all
        var rate: AlertKind?
        if rules, fresh, let v = r?.rate {
            if s[.fallingFast].on, v <= -s[.fallingFast].threshold {
                rate = .fallingFast
            } else if s[.risingFast].on, v >= s[.risingFast].threshold {
                rate = .risingFast
            }
        }
        let nd = s[.noData]
        let noData =
            (r.map { nd.on && t.timeIntervalSince($0.at) >= nd.threshold * 60 } ?? false) && !expectsNoReading(at: t)
        let backSides = snoozes.advance(at: t, backMin: s.notificationActions.backMin) { side -> Bool? in
            switch side {
            case .noData: return !noData
            case _ where !fresh: return nil
            case .low, .high: return r.map { isBackByMargin(side, $0, s) } ?? false
            case .fallingFast, .risingFast: return rate?.side != side
            }
        }
        out += backSides.map(AlertEffect.back)
        step(.level, rules ? Self.level(of: r, s, at: t) : nil, at: t, rng: &rng, out: &out)
        step(.rate, rate, at: t, rng: &rng, out: &out)
        step(.noData, noData ? .noData : nil, at: t, rng: &rng, out: &out)
    }

    private func hide(_ e: inout Episode, at t: Date, out: inout [AlertEffect]) {
        if var f = e.shown {
            f.at = t
            out.append(.clear(f))
        }
        e.shown = nil
    }

    private mutating func step(
        _ g: AlertGroup, _ want: AlertKind?, at t: Date, rng: inout some RandomNumberGenerator, out: inout [AlertEffect]
    ) {
        let s = settings
        guard let want else {
            guard var e = episodes[g] else { return }
            hide(&e, at: t, out: &out)
            e.isOn = false
            e.quiet = nil
            episodes[g] = t.timeIntervalSince(e.lastIn) >= e.holdTime(s) ? nil : e
            return
        }
        var found = episodes[g]
        if var e = found, e.side != want.side || (!e.isOn && t.timeIntervalSince(e.lastIn) >= e.holdTime(s)) {
            hide(&e, at: t, out: &out)
            found = nil
        }
        var e = found ?? Episode(want, at: t)
        defer { episodes[g] = e }
        e.isOn = true
        e.lastIn = t
        e.kind = want
        // a last alert in the future (the clock was set back) counts as now, so repeats never stall
        if let f = e.firedAt, f > t { e.firedAt = t }
        if let sh = e.shown, sh.kind != want { hide(&e, at: t, out: &out) }

        if let by = s.quietedBy(want), let tc = cover(e, want, at: t) {
            hide(&e, at: t, out: &out)
            let until = tc.addingTimeInterval(s.quietDuration(by))
            e.quiet = .treatment(by, at: tc, until: until)
            e.next = until
            return
        }
        if e.liftedAt == nil, let minutes = fallingBackMinutes(want) {
            hide(&e, at: t, out: &out)
            e.quiet = .fallingBack(inMinutes: minutes)
            e.next = nil  // the falling rule has no end of its own: re-judged on every reading
            return
        }
        let all = muteAllEnd(at: t)
        let side = snoozes.muteEnd(e.side, at: t)
        if let q = [side, all].compactMap({ $0 }).max() {
            hide(&e, at: t, out: &out)
            e.quiet = q == all ? .allMuted(until: q) : .sideMuted(until: q)
            e.next = q
            return
        }
        e.quiet = nil
        e.next = due(e, want, at: t)
        guard let next = e.next, t >= next else { return }
        e.n += 1
        e.firedAt = t
        e.firedKind = want
        e.liftPending = false
        let f = AlertFiring(
            id: AlertFiring.newID(using: &rng), kind: want, at: t, reading: context.latest,
            threshold: s[want].threshold,
            n: e.n, treated: treated(e, want, at: t))
        e.shown = f
        e.next = due(e, want, at: t)
        out.append(.fire(f))
    }

    private func times(_ k: TreatmentKind) -> [Date] { context.treatments.filter { $0.kind == k }.map(\.at) }

    /// The newest treatment that quiets `k` in `e` at `t` (logged after an *Alert me anyway*), or nil.
    func cover(_ e: Episode, _ k: AlertKind, at t: Date) -> Date? {
        let s = settings
        guard let tk = s.quietedBy(k) else { return nil }
        return times(tk).filter { x in
            x <= t && t.timeIntervalSince(x) < s.quietDuration(tk) && (e.liftedAt.map { x > $0 } ?? true)
        }.max()
    }

    /// When `e` alerts next for `k` (nil = never).
    private func due(_ e: Episode, _ k: AlertKind, at t: Date) -> Date? {
        let s = settings
        guard let fired = e.firedAt else { return e.start }
        if k.side == e.firedKind.side, k.severity > e.firedKind.severity { return t }
        if e.liftPending { return t }
        // a treatment, the side's Mute or Mute all after the last alert: at once when its window ends
        var ends: [Date] = []
        if let by = s.quietedBy(k),
            let tc = times(by).filter({ x in x >= fired && x <= t && (e.liftedAt.map { x > $0 } ?? true) }).max()
        {
            ends.append(tc.addingTimeInterval(s.quietDuration(by)))
        }
        if let q = snoozes[e.side]?.until, q > fired { ends.append(q) }
        if let a = muteAllUntil, a > fired, a != muteAllForever { ends.append(a) }
        if let end = ends.max() { return max(end, fired.addingTimeInterval(1)) }
        let base = s[k].repeatMin
        let relax = snoozes.relaxMin(e.side)
        let interval = relax > 0 && base > 0 ? max(base, relax) : base
        return interval > 0 ? fired.addingTimeInterval(TimeInterval(interval * 60)) : nil
    }

    /// Entries of the answering treatment logged in the episode or within the quiet length before it: 0, 1, 2 (2+).
    private func treated(_ e: Episode, _ k: AlertKind, at t: Date) -> Int {
        guard k.group == .level, let tk = k.treatment else { return 0 }
        let from = e.start.addingTimeInterval(-settings.quietDuration(tk))
        return min(2, times(tk).filter { $0 >= from && $0 <= t }.count)
    }

    /// Minutes until back below the high threshold, if the current fall gets there within the falling rule's window.
    private func fallingBackMinutes(_ k: AlertKind) -> Int? {
        let s = settings
        guard let r = context.latest, let v = r.rate, s.muteFallingHigh.on, k == .high || k == .veryHigh, v < 0 else {
            return nil
        }
        let high = s[.high].threshold
        guard r.mgdl + v * s.muteFallingHigh.min < high else { return nil }
        return max(1, Int(((r.mgdl - high) / -v).rounded(.up)))
    }

    /// When the next timer check is useful (no-data deadline, freshness end, a repeat, a mute ending, the margin), or nil.
    public func nextCheck(after now: Date) -> Date? {
        let s = settings
        guard s.on else { return nil }
        var c: [Date] = []
        if let r = context.latest {
            let nd = s[.noData]
            if nd.on, episodes[.noData]?.isOn != true { c.append(r.at.addingTimeInterval(nd.threshold * 60)) }
            c.append(r.at.addingTimeInterval(Self.freshness + 1))
        }
        if let w = context.sensor, w.isWarmingUp(at: now, latestReading: context.latest?.at) {
            c.append(w.warmupEnd.addingTimeInterval(SensorWindow.warmupGrace))
        }
        for e in episodes.values where e.isOn {
            if let n = e.next, n < muteAllForever { c.append(n) }
        }
        c += snoozes.wakeTimes(after: now, backMin: s.notificationActions.backMin)
        if let a = muteAllUntil, a > now, a < muteAllForever { c.append(a) }
        return c.filter { $0 > now }.min()
    }
}
