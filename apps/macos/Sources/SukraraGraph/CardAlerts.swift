// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation
import SukraraCore

// What the alert rules decided, as the card draws it: the badge under the value, the last-treatment slot and the mute
// lines on the chart. The card never decides these itself: the composition (AppModel) computes them with the alert
// engine's rules (SukraraAlerts) and hands them in, so the badge and the alerts can't disagree and this extension-safe
// library needs no alert settings.

/// What the alert rules say about the latest reading, for the badge under the value.
public enum AlertStatus: Sendable, Equatable, Codable {
    /// The side of a level alert, which decides the badge's colour (carbs answer lows, insulin highs).
    public enum Side: String, Sendable, Equatable, Codable {
        case low, high
    }

    /// In range, stale, or alerts off: no badge.
    case none
    /// Out of range and nothing logged that covers it: tell the user what to do.
    case needed(Side)
    /// Out of range, but `treatment`'s mute window covers it until `until` (epoch s).
    case taken(Side, treatment: GraphMark, until: TimeInterval)
    /// Out of range, muted on this device (the side's Mute, or Mute all) until `until` (`never` = until turned back on).
    case paused(Side, until: TimeInterval)
    /// In range (or no current reading) while Mute all is on, until `until` (`never` = until turned back on).
    case muted(until: TimeInterval)

    /// Mute all without an end: far in the future, finite so it encodes in JSON (the alert engine's NEVER).
    public static let never: TimeInterval = Date.distantFuture.timeIntervalSince1970

    /// When the decision's mute ends, for the kinds that have one.
    var until: TimeInterval? {
        switch self {
        case .none, .needed: nil
        case .taken(_, _, let until), .paused(_, let until), .muted(let until): until
        }
    }

    /// Whether this decision still holds at `now`: a mute that has run out (an old widget snapshot drawn later) no
    /// longer says anything, rather than counting "0m left" or guessing what the alert engine would say now.
    func current(at now: TimeInterval) -> AlertStatus { until.map { $0 > now } == false ? .none : self }
}

/// The entry in the last-treatment slot beside the value.
public enum LastTreatment {
    public struct Info: Sendable, Equatable, Codable {
        public var tr: GraphMark
        /// Its mute's end (epoch s); nil = its kind mutes nothing in the settings.
        public var until: TimeInterval?
        /// While `tr` is rapid insulin whose mute runs: the newest recent carbs (drawn concentric where there's room).
        public var carbs: Companion?

        public init(tr: GraphMark, until: TimeInterval?, carbs: Companion? = nil) {
            self.tr = tr
            self.until = until
            self.carbs = carbs
        }

        /// Share of the mute window left at `now`, 0 when over or none.
        func left(_ now: TimeInterval) -> Double {
            guard let u = until else { return 0 }
            return min(max((u - now) / max(u - tr.t, 1), 0), 1)
        }
    }

    /// The carbs drawn with running insulin (an `Info` without a companion of its own).
    public struct Companion: Sendable, Equatable, Codable {
        public var tr: GraphMark
        public var until: TimeInterval?

        public init(tr: GraphMark, until: TimeInterval?) {
            self.tr = tr
            self.until = until
        }

        var info: Info { Info(tr: tr, until: until) }
    }
}

/// Everything alert-related a card draws. `none` = alerts off or not known yet: no badge, no slot entry, no mute lines.
public struct CardAlerts: Sendable, Equatable, Codable {
    public var status: AlertStatus
    public var last: LastTreatment.Info?
    /// Each treatment's mute end (epoch s) by `GraphMark.id`; a treatment missing here mutes nothing (no mute line).
    public var muteEnds: [String: TimeInterval]

    public init(status: AlertStatus = .none, last: LastTreatment.Info? = nil, muteEnds: [String: TimeInterval] = [:]) {
        self.status = status
        self.last = last
        self.muteEnds = muteEnds
    }

    public static let none = CardAlerts()

    func muteUntil(_ m: GraphMark) -> TimeInterval? { muteEnds[m.id] }
}

/// The badge's mute wording (contracts/alerts/copy.json, English; the screen reader's is `CardWords`).
enum BadgeCopy {
    /// Whole minutes left of `seconds`, partial minutes rounded up ("1 min" until it is over).
    static func leftMin(_ seconds: TimeInterval) -> Int { (Int(max(0, seconds).rounded(.down)) + 59) / 60 }
    /// The pill while Mute all runs in range: "Muted · 1h 12m".
    static func pill(leftMin: Int) -> String { "Muted · \(Durations.compact(leftMin))" }
    static let pillForever = "Muted"
}

/// The treatment-mark options of the settings schema (`contracts/settings/settings.json` "options": markKind,
/// markPart, markParts.*, markDefault.*). Replaced by the generated `SettingsSpec` once it carries options.
enum MarkSchema {
    static let kinds = ["rapid", "long", "carbs", "note"]
    static let parts = ["point", "line", "amount", "time"]
    static let partsOf: [String: [String]] = [
        "rapid": ["point", "line", "amount", "time"], "long": ["point", "amount", "time"],
        "carbs": ["point", "line", "amount", "time"], "note": ["point"],
    ]
    static let defaultsOf: [String: Set<String>] = [
        "rapid": ["point", "line", "amount", "time"], "long": ["point", "amount", "time"], "carbs": ["point"],
        "note": ["point"],
    ]
}
