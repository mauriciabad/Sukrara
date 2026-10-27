// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation
import SukraraCore

/// THE mute / remind-less state lines (SPEC §2 "Copy"): every case of `contracts/alerts/copy.json` (en; the Mac is
/// English only). Durations only through `Durations`. Lines are sentence case; Mac menu items are title-style.
public enum MuteCopy {
    /// The side's name ("low" covers very low, "high" very high and extremely high).
    public static func sideName(_ side: AlertSide) -> String {
        switch side {
        case .low: "Low"
        case .high: "High"
        case .fallingFast: "Falling fast"
        case .risingFast: "Rising fast"
        case .noData: "No data"
        }
    }

    /// "Low alerts muted · 12 min left".
    public static func sideMuted(_ side: AlertSide, leftMin: Int) -> String {
        "\(sideName(side)) alerts muted · \(Durations.text(leftMin)) left"
    }

    /// "Low alerts muted after carbs · 12 min left", "High alerts muted after insulin · 1 h left".
    public static func sideAfter(_ side: AlertSide, treatment: TreatmentKind, leftMin: Int) -> String {
        "\(sideName(side)) alerts muted after \(treatment == .carbs ? "carbs" : "insulin") · \(Durations.text(leftMin)) left"
    }

    /// "High alerts muted while falling back into range" (no end of its own).
    public static func sideFalling(_ side: AlertSide) -> String {
        "\(sideName(side)) alerts muted while falling back into range"
    }

    /// "Low reminders every 30 min".
    public static func sideLess(_ side: AlertSide, intervalMin: Int) -> String {
        "\(sideName(side)) reminders every \(Durations.text(intervalMin))"
    }

    /// Mute all for a time: "Alerts muted · 1 h 12 min left".
    public static func allTimed(leftMin: Int) -> String { "Alerts muted · \(Durations.text(leftMin)) left" }
    public static let allForever = "Alerts muted until you turn it off"

    /// The silent notification while a side is muted (Mac notifications can't count down: they say the end).
    public static func notificationTitle(_ side: AlertSide) -> String { "\(sideName(side)) alerts muted" }
    public static func notificationText(until: String) -> String { "Until \(until)" }

    /// The card pill while Mute all runs in range: "Muted · 1h 12m" / "Muted".
    public static func pill(leftMin: Int) -> String { "Muted · \(Durations.compact(leftMin))" }
    public static let pillForever = "Muted"

    /// A Mute choice: "For 30 min", "For 1 h".
    public static func choice(_ minutes: Int) -> String { "For \(Durations.text(minutes))" }
    public static let choiceForever = "Until you turn it off"
    public static let menuForever = "Until You Turn It Off"

    /// The line for Mute all ending at `until` (`muteAllForever` = indefinite), nil once it is over.
    public static func allLine(until: Date, now: Date) -> String? {
        guard until > now else { return nil }
        return until >= muteAllForever
            ? allForever : allTimed(leftMin: Durations.minutesLeft(until.timeIntervalSince(now)))
    }

    /// The line for a quiet reason on `side`, counting down at `now`.
    public static func line(_ reason: QuietReason, side: AlertSide, now: Date) -> String {
        switch reason {
        case .treatment(let kind, _, let until):
            sideAfter(side, treatment: kind, leftMin: Durations.minutesLeft(until.timeIntervalSince(now)))
        case .fallingBack: sideFalling(side)
        case .sideMuted(let until): sideMuted(side, leftMin: Durations.minutesLeft(until.timeIntervalSince(now)))
        case .allMuted(let until): allLine(until: until, now: now) ?? allForever
        }
    }
}
