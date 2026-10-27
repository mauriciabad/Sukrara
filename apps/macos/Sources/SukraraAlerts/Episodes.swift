// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation

/// A stretch of time on one side of a group (SPEC §1 "Episodes"): it starts when a kind of the side matches and ends
/// once nothing of the side has matched for the hold time (the kind's repeat, at least 5 min).
public struct Episode: Codable, Equatable, Sendable {
    public let side: AlertSide
    public let start: Date
    /// The kind matching now (or last).
    public var kind: AlertKind
    /// The condition held at the last evaluation, and when it last did.
    public var isOn = true
    public var lastIn: Date
    /// The firing whose notification is up (gone when the condition goes, it's quieted, or the kind changes).
    public var shown: AlertFiring?
    public var n = 0
    public var firedAt: Date?
    public var firedKind: AlertKind
    /// Why it is quiet now (nil = not quiet).
    public var quiet: QuietReason?
    /// When the next alert is due (nil = never), from the last evaluation.
    public var next: Date?
    /// *Unmute* / *Alert me anyway* at this time: treatments logged up to then and the falling rule don't quiet it.
    public var liftedAt: Date?
    /// Lifted since the last alert: the next match alerts at once.
    public var liftPending = false

    init(_ kind: AlertKind, at t: Date) {
        side = kind.side
        start = t
        self.kind = kind
        lastIn = t
        firedKind = kind
        next = t
    }

    /// Minimum hold time: an episode lasts at least this long after its side last matched.
    static let minHoldMin = 5

    func holdTime(_ s: AlertSettings) -> TimeInterval { TimeInterval(max(s[kind].repeatMin, Self.minHoldMin) * 60) }
}

extension AlertEngine {
    /// What survives a relaunch (SPEC: an update must not take an ongoing alert for a new one and ring again): the open
    /// episodes, the sides' Mute / Remind less and Mute all. Not the settings: they are stored on their own (schema key
    /// `alerts`, SPEC §3 JSON) and may come from the wearer.
    public struct Snapshot: Codable, Equatable, Sendable {
        public static let formatVersion = 1
        public var v = Snapshot.formatVersion
        public var episodes: [Episode]
        public var snoozes: SideSnoozes
        public var muteAllUntil: Date?

        public init(episodes: [Episode], snoozes: SideSnoozes, muteAllUntil: Date?) {
            self.episodes = episodes
            self.snoozes = snoozes
            self.muteAllUntil = muteAllUntil
        }

        public func encoded() -> Data {
            let e = JSONEncoder()
            e.outputFormatting = [.sortedKeys]
            e.dateEncodingStrategy = .secondsSince1970
            return (try? e.encode(self)) ?? Data()
        }

        /// nil when unreadable or of another format version: the caller starts fresh (alerts active, nothing muted).
        public static func decode(_ data: Data) -> Snapshot? {
            let d = JSONDecoder()
            d.dateDecodingStrategy = .secondsSince1970
            guard let s = try? d.decode(Snapshot.self, from: data), s.v == formatVersion else { return nil }
            return s
        }
    }

    public var snapshot: Snapshot {
        Snapshot(episodes: AlertGroup.allCases.compactMap { episodes[$0] }, snoozes: snoozes, muteAllUntil: muteAllUntil)
    }

    /// An engine back in the `snapshot` state at `t` (no evaluation, so nothing rings). Episodes over by `t` are dropped,
    /// untrustworthy mutes sanitized. The effects are the firings whose notification was up (`.resume`: show again
    /// silently, same id and n, never republish) and the next timer (`.schedule`).
    public static func restore(_ snapshot: Snapshot?, context: AlertContext, at t: Date) -> (AlertEngine, [AlertEffect]) {
        var e = AlertEngine(context: context)
        guard let snapshot else { return (e, [.schedule(e.nextCheck(after: t))]) }
        e.snoozes = snapshot.snoozes
        e.snoozes.sanitize(at: t)
        if let all = snapshot.muteAllUntil, all > t {
            e.muteAllUntil = all >= muteAllForever ? muteAllForever : min(all, t.addingTimeInterval(AlertLimits.muteAll.max * 60))
        }
        for ep in snapshot.episodes where ep.lastIn <= t && t.timeIntervalSince(ep.lastIn) < ep.holdTime(context.settings) {
            e.episodes[ep.kind.group] = ep
        }
        let resumed = AlertGroup.allCases.compactMap { e.episodes[$0]?.shown }.map(AlertEffect.resume)
        return (e, resumed + [.schedule(e.nextCheck(after: t))])
    }
}
