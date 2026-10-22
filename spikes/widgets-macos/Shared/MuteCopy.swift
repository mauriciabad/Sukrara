// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation

/// THE mute / remind-less state lines (round f31), a port of Android's alerts/MuteCopy.kt (English). Every line is a
/// case of spikes/alerts/copy.json (-alerts-selftest). Wording after AOSP Do Not Disturb ("Until you turn off",
/// "For 1 hour", "Until %1$s"). Durations only through `Durations.text` (lines) and `Durations.compact` (the pill).
/// Lines are sentence case (they're text); the Mac's buttons and menu items are title-style (HIG), e.g.
/// `menuForever`.
enum MuteCopy {
    /// The side's main name ("low" covers very low, "high" very high and extremely high): "Low", "No data".
    static func side(_ side: String) -> String {
        switch side {
        case "low": "Low"
        case "high": "High"
        case "falling_fast": "Falling fast"
        case "rising_fast": "Rising fast"
        default: "No data"
        }
    }

    /// Whole minutes left of `seconds`, partial minutes rounded up ("1 min" until it is over).
    static func leftMin(_ seconds: TimeInterval) -> Int { (Int(max(0, seconds).rounded(.down)) + 59) / 60 }

    /// "Low alerts muted · 12 min left".
    static func sideMuted(_ side: String, leftMin: Int) -> String { "\(Self.side(side)) alerts muted · \(Durations.text(leftMin)) left" }
    /// "Low reminders every 30 min".
    static func sideLess(_ side: String, intervalMin: Int) -> String { "\(Self.side(side)) reminders every \(Durations.text(intervalMin))" }
    /// Mute all for a time: "Alerts muted · 1 h 12 min left".
    static func allTimed(leftMin: Int) -> String { "Alerts muted · \(Durations.text(leftMin)) left" }
    /// Mute all until turned off.
    static let allForever = "Alerts muted until you turn it off"
    /// The silent notification while a side is muted: "Low alerts muted" / "Until 10:42" (Mac notifications can't count
    /// down, so they say the end).
    static func notifTitle(_ side: String) -> String { "\(Self.side(side)) alerts muted" }
    static func notifText(until: String) -> String { "Until \(until)" }
    /// The card pill while Mute all runs in range: "Muted · 1h 12m" / "Muted".
    static func pill(leftMin: Int) -> String { "Muted · \(Durations.compact(leftMin))" }
    static let pillForever = "Muted"
    /// A Mute alerts choice: "For 30 min", "For 1 h" (units keep their case in title-style items: Apple Style Guide).
    static func choice(_ min: Int) -> String { "For \(Durations.text(min))" }
    /// The indefinite choice in a sentence ("Until you turn it off") and as a Mac menu item (title-style).
    static let choiceForever = "Until you turn it off"
    static let menuForever = "Until You Turn It Off"

    /// The line for Mute all ending at `until` (unix s; `AlertStatus.never` = indefinite), nil when it is over.
    static func allLine(until: TimeInterval, now: TimeInterval) -> String? {
        guard until > now else { return nil }
        return until >= AlertStatus.never ? allForever : allTimed(leftMin: leftMin(until - now))
    }
}
