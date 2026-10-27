// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation
import SukraraCore

/// A follower that mirrors the wearer's alerts (SPEC §2, §4, source "wearer"): which of the wearer's `alert` events
/// show on this device, and this device's own notification actions on them. Strictly local: the wearer never hears of
/// them and keeps publishing what its rules decide.
/// - *Mute* drops the wearer's alerts of that side while it lasts, *Mute all* every alert;
/// - *Remind less* shows a repeat only once `relaxMin` has passed since the last one shown here (a more severe kind of
///   the side always shows);
/// - both end at their time or back by the margin on the readings this device gets (`reading`);
/// - *Unmute*, *Remind as usual* and *Unmute all* show the wearer's newest firing still up (dropped or skipped here).
///
/// Codable so this device's Mute / Mute all survive a relaunch (the app stores it next to the engine snapshot and
/// calls `sanitize(at:)` after decoding).
public struct AlertMirror: Codable, Equatable, Sendable {
    private struct Shown: Codable, Equatable, Sendable {
        var at: Date
        var kind: AlertKind
    }

    private struct Newest: Codable, Equatable, Sendable {
        var id: String
        var kind: AlertKind
    }

    public private(set) var snoozes = SideSnoozes()
    /// *Mute all* on this device until then (nil = off, `muteAllForever` = until turned off).
    public private(set) var muteAllUntil: Date?
    /// group → when and which kind this device last showed (Remind less spaces the wearer's repeats).
    private var lastShown: [AlertGroup: Shown] = [:]
    /// group → id of the wearer's firing whose notification is up here.
    private var shown: [AlertGroup: Newest] = [:]
    /// group → the wearer's newest firing, shown here or not.
    private var newest: [AlertGroup: Newest] = [:]
    /// groups whose newest firing the wearer has cleared.
    private var newestCleared: Set<AlertGroup> = []

    public init() {}

    public func muteAllEnd(at t: Date) -> Date? { muteAllUntil.flatMap { $0 > t ? $0 : nil } }
    public func isShowing(_ group: AlertGroup) -> Bool { shown[group] != nil }

    /// The wearer fired `kind` (firing `id`) at `at`: true = show it here.
    public mutating func fire(_ kind: AlertKind, id: String, at: Date) -> Bool {
        let side = kind.side
        let g = kind.group
        newest[g] = Newest(id: id, kind: kind)
        newestCleared.remove(g)
        let sn = snoozes[side]
        let muted = (sn?.isMuted(at: at) ?? false) || muteAllEnd(at: at) != nil
        let relaxed =
            sn.map { sn in
                sn.relaxMin > 0
                    && lastShown[g].map { last in
                        last.kind.side == side && kind.severity <= last.kind.severity
                            && at.timeIntervalSince(last.at) < TimeInterval(sn.relaxMin * 60)
                    } ?? false
            } ?? false
        if muted || relaxed { return false }
        shown[g] = Newest(id: id, kind: kind)
        lastShown[g] = Shown(at: at, kind: kind)
        return true
    }

    /// The wearer cleared its firing `id` of `kind`'s group: true = the notification shown here goes. That's the firing
    /// shown here, or the wearer's newest one (a repeat skipped here left the earlier one up). A late older clear
    /// changes nothing.
    public mutating func clear(_ kind: AlertKind, id: String) -> Bool {
        let g = kind.group
        if newest[g]?.id == id { newestCleared.insert(g) }
        guard let cur = shown[g], cur.id == id || newest[g]?.id == id else { return false }
        shown[g] = nil
        return true
    }

    /// A reading this device got at `t` (nil = none recent): ends a side's Mute / Remind less back by the margin.
    public mutating func reading(_ r: GlucoseSample?, _ s: AlertSettings, at t: Date) -> [AlertSide] {
        snoozes.advance(at: t, backMin: s.notificationActions.backMin) { side in
            guard let r, t.timeIntervalSince(r.at) <= AlertEngine.freshness else { return nil }
            return isBackByMargin(side, r, s)
        }
    }

    /// This device's *Mute* on `side` until `until`: true = an alert of it was showing (it goes).
    public mutating func mute(_ side: AlertSide, until: Date) -> Bool {
        snoozes.mute(side, until: until)
        let g = side.mainKind.group
        guard shown[g]?.kind.side == side else { return false }
        shown[g] = nil
        return true
    }

    public mutating func remindLess(_ side: AlertSide, relaxMin: Int) { snoozes.relax(side, relaxMin: relaxMin) }

    /// *Mute all* until `until`: the groups whose alert was showing (they go).
    public mutating func muteAll(until: Date) -> [AlertGroup] {
        muteAllUntil = until
        let gone = AlertGroup.allCases.filter { shown[$0] != nil }
        shown = [:]
        return gone
    }

    /// *Unmute* on `side` at `t`: the id of the wearer's newest firing to show at once, or nil.
    public mutating func unmute(_ side: AlertSide, at t: Date) -> String? {
        guard snoozes.unmute(side, at: t) else { return nil }
        return muteAllEnd(at: t) != nil ? nil : showAgain(side.mainKind.group, side: side, at: t)
    }

    /// *Remind as usual* on `side`: a skipped repeat still up shows at once.
    public mutating func remindAsUsual(_ side: AlertSide, at t: Date) -> String? {
        guard snoozes.unrelax(side) else { return nil }
        if snoozes[side]?.isMuted(at: t) == true || muteAllEnd(at: t) != nil { return nil }
        return showAgain(side.mainKind.group, side: side, at: t)
    }

    /// *Unmute all* at `t`: the ids of the wearer's newest firings still up to show at once (not muted otherwise).
    public mutating func unmuteAll(at t: Date) -> [String] {
        guard muteAllEnd(at: t) != nil else { return [] }
        muteAllUntil = nil
        return AlertGroup.allCases.compactMap { g in
            guard let n = newest[g], snoozes[n.kind.side]?.isMuted(at: t) != true else { return nil }
            return showAgain(g, side: n.kind.side, at: t)
        }
    }

    /// After a restore at `t`: drops mutes that ended or reach implausibly far (clock changes, a damaged file), like
    /// `AlertEngine.restore` does for the engine's own.
    public mutating func sanitize(at t: Date) {
        snoozes.sanitize(at: t)
        if let all = muteAllUntil {
            muteAllUntil =
                all <= t
                ? nil
                : all >= muteAllForever ? muteAllForever : min(all, t.addingTimeInterval(SettingsSpec.muteAll.max * 60))
        }
    }

    /// Not mirroring any more (own rules, alerts off): nothing of the wearer's shows here.
    public mutating func forgetShown() { shown = [:] }

    /// Shows the wearer's newest firing of `g` again if it is of `side`, still up on the wearer and not showing here.
    private mutating func showAgain(_ g: AlertGroup, side: AlertSide, at t: Date) -> String? {
        guard let n = newest[g], n.kind.side == side, !newestCleared.contains(g), shown[g]?.id != n.id else {
            return nil
        }
        shown[g] = n
        lastShown[g] = Shown(at: t, kind: n.kind)
        return n.id
    }
}
