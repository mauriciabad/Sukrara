// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation

/// This device's notification actions on one side (SPEC §2): muted until `until` (nil = not muted), repeats relaxed
/// to `relaxMin` (0 = not relaxed). `backSince`: when the side was first back by the margin without crossing out again.
public struct Snooze: Codable, Equatable, Sendable {
    public var until: Date?
    public var relaxMin: Int
    public var backSince: Date?

    public init(until: Date? = nil, relaxMin: Int = 0, backSince: Date? = nil) {
        self.until = until
        self.relaxMin = relaxMin
        self.backSince = backSince
    }

    public var isRelaxed: Bool { relaxMin > 0 }
    public func isMuted(at t: Date) -> Bool { until.map { $0 > t } ?? false }
}

/// *Mute all* without an end ("until you turn it off"): far in the future, finite so it encodes in JSON.
public let muteAllForever = Date.distantFuture

/// The sides' Mute / Remind less on one device. They outlive episodes and end at their time (Mute) or once the side is
/// back by the margin for `backMin` minutes (both). One implementation for the engine and the mirroring follower.
public struct SideSnoozes: Codable, Equatable, Sendable {
    private var bySide: [AlertSide: Snooze] = [:]

    public init() {}

    public subscript(side: AlertSide) -> Snooze? { bySide[side] }

    /// The sides with an action in force at `t`, an expired Mute's `until` cleared.
    public func active(at t: Date) -> [AlertSide: Snooze] {
        bySide.filter { $0.value.isMuted(at: t) || $0.value.isRelaxed }
            .mapValues { sn in
                var s = sn
                if let u = s.until, u <= t { s.until = nil }
                return s
            }
    }

    public var all: [AlertSide: Snooze] { bySide }

    /// The side's Mute end in force at `t`, or nil.
    public func muteEnd(_ side: AlertSide, at t: Date) -> Date? { bySide[side]?.until.flatMap { $0 > t ? $0 : nil } }

    public func relaxMin(_ side: AlertSide) -> Int { bySide[side]?.relaxMin ?? 0 }

    /// Mute `side` until `until` (a new Mute on a muted side changes its end).
    public mutating func mute(_ side: AlertSide, until: Date) {
        var sn = bySide[side] ?? Snooze()
        sn.until = until
        sn.backSince = nil
        bySide[side] = sn
    }

    public mutating func relax(_ side: AlertSide, relaxMin: Int) {
        var sn = bySide[side] ?? Snooze()
        sn.relaxMin = relaxMin
        sn.backSince = nil
        bySide[side] = sn
    }

    /// Ends the side's Mute; true if it was muted at `t`.
    @discardableResult
    public mutating func unmute(_ side: AlertSide, at t: Date) -> Bool {
        guard var sn = bySide[side], sn.isMuted(at: t) else { return false }
        sn.until = nil
        put(side, sn)
        return true
    }

    /// Ends the side's Remind less; true if it was relaxed.
    @discardableResult
    public mutating func unrelax(_ side: AlertSide) -> Bool {
        guard var sn = bySide[side], sn.isRelaxed else { return false }
        sn.relaxMin = 0
        put(side, sn)
        return true
    }

    /// Advances to `t`: a Mute whose time is over ends; a side judged back by the margin (`isBack` true; nil = nothing
    /// to judge by, e.g. no fresh reading) for `backMin` minutes in a row loses its Mute and Remind less. Returns those
    /// sides, in side order.
    public mutating func advance(at t: Date, backMin: Int, isBack: (AlertSide) -> Bool?) -> [AlertSide] {
        var ended: [AlertSide] = []
        for side in bySide.keys.sorted() {
            guard var cur = bySide[side] else { continue }
            if let u = cur.until, u <= t {
                cur.until = nil
                put(side, cur)
            }
            guard var sn = bySide[side], let back = isBack(side) else { continue }
            guard back else {
                sn.backSince = nil
                bySide[side] = sn
                continue
            }
            let since = sn.backSince ?? t
            if t.timeIntervalSince(since) >= TimeInterval(backMin * 60) {
                bySide[side] = nil
                ended.append(side)
            } else {
                sn.backSince = since
                bySide[side] = sn
            }
        }
        return ended
    }

    /// The times a timer should wake for: a Mute ending, the margin's time running out.
    public func wakeTimes(after t: Date, backMin: Int) -> [Date] {
        bySide.values.flatMap { sn -> [Date] in
            var out: [Date] = []
            if let u = sn.until, u > t { out.append(u) }
            if let b = sn.backSince { out.append(b.addingTimeInterval(TimeInterval(backMin * 60))) }
            return out
        }
    }

    /// Drops what a restored snapshot can't be trusted with: Mutes already over, Mutes longer than the longest a Mute
    /// accepts (clamped), relax intervals outside the schema.
    mutating func sanitize(at t: Date) {
        let longest = t.addingTimeInterval(AlertLimits.actionMute.max * 60)
        for side in Array(bySide.keys) {
            guard var sn = bySide[side] else { continue }
            if let u = sn.until { sn.until = u <= t ? nil : min(u, longest) }
            if sn.relaxMin != 0 { sn.relaxMin = Int(AlertLimits.actionRelax.coerce(Double(sn.relaxMin))) }
            if let b = sn.backSince, b > t { sn.backSince = nil }
            put(side, sn)
        }
    }

    private mutating func put(_ side: AlertSide, _ sn: Snooze) {
        bySide[side] = sn.until == nil && sn.relaxMin == 0 ? nil : sn
    }

    private enum Key: String, CodingKey { case sides }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: Key.self)
        let raw = try c.decode([String: Snooze].self, forKey: .sides)
        bySide = Dictionary(uniqueKeysWithValues: raw.compactMap { k, v in AlertSide(rawValue: k).map { ($0, v) } })
    }

    public func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: Key.self)
        try c.encode(Dictionary(uniqueKeysWithValues: bySide.map { ($0.key.rawValue, $0.value) }), forKey: .sides)
    }
}

/// The margin rule (SPEC §2): `reading` is back in range by the margin for `side`. Lows at least `backMgdl` above the
/// highest enabled low-side threshold, highs below the lowest enabled high-side threshold minus `backMgdl`, a rate
/// side while its rate doesn't match; no data always (a reading came).
public func isBackByMargin(_ side: AlertSide, _ reading: GlucoseSample, _ s: AlertSettings) -> Bool {
    let m = Double(s.notificationActions.backMgdl)
    switch side {
    case .low:
        guard let edge = [AlertKind.veryLow, .low].filter({ s[$0].on }).map({ s[$0].threshold }).max() else { return true }
        return reading.mgdl >= edge + m
    case .high:
        guard let edge = [AlertKind.high, .veryHigh, .extremelyHigh].filter({ s[$0].on }).map({ s[$0].threshold }).min() else {
            return true
        }
        return reading.mgdl < edge - m
    case .fallingFast: return !s[.fallingFast].on || (reading.rate ?? 0) > -s[.fallingFast].threshold
    case .risingFast: return !s[.risingFast].on || (reading.rate ?? 0) < s[.risingFast].threshold
    case .noData: return true
    }
}
