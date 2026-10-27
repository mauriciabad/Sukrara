// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation

/// THE duration formatter and parser: every user-visible duration goes through here (formats and cases:
/// `contracts/settings/durations.json`, the same file Android's `Durations` is tested against).
public enum Durations {
    /// In words and settings (notifications, menus, presets, history): "45 min", "1 h", "1 h 30 min", "12 h".
    public static func text(_ minutes: Int) -> String {
        let m = max(0, minutes)
        if m < 60 { return "\(m) min" }
        return m % 60 == 0 ? "\(m / 60) h" : "\(m / 60) h \(m % 60) min"
    }

    /// `text` of a span in seconds, partial minutes rounded up (time left: "1 min" until it is over).
    public static func text(seconds: TimeInterval) -> String { text(minutesLeft(seconds)) }

    /// Whole minutes left of `seconds`, a partial minute counting as one ("1 min" until it is over).
    public static func minutesLeft(_ seconds: TimeInterval) -> Int { (Int(max(0, seconds).rounded(.down)) + 59) / 60 }

    /// The card's compact convention: "12m", "1h 5m", "3h" (from 6 h whole hours only), "2d" from two days.
    public static func compact(_ minutes: Int) -> String {
        let m = max(0, minutes)
        switch m {
        case ..<60: return "\(m)m"
        case ..<(6 * 60): return m % 60 == 0 ? "\(m / 60)h" : "\(m / 60)h \(m % 60)m"
        case ..<(48 * 60): return "\(m / 60)h"
        default: return "\(m / 1440)d"
        }
    }

    /// One unit, rounded down: "45m", from 60 min "20h", from 24 h "13d".
    public static func whole(_ minutes: Int) -> String {
        let m = max(0, minutes)
        if m >= 1440 { return "\(m / 1440)d" }
        if m >= 60 { return "\(m / 60)h" }
        return "\(m)m"
    }

    /// Typed minutes: "45", "45 min", "1h", "1 h 30", "1h 30m", "1:30", "1,5 h" → minutes; nil when it isn't a
    /// duration. The caller checks the range.
    public static func parse(_ input: String) -> Int? {
        let s = input.trimmingCharacters(in: .whitespaces).lowercased()
        guard !s.isEmpty else { return nil }
        if let m = s.wholeMatch(of: /(\d{1,3}):(\d{2})/) {
            guard let h = Int(m.1), let mm = Int(m.2), mm < 60 else { return nil }
            return h * 60 + mm
        }
        guard let m = s.wholeMatch(of: /(?:(\d{1,3}(?:[.,]\d+)?)\s*h)?\s*(?:(\d{1,4})\s*(?:min|m)?)?/) else {
            return nil
        }
        let hours = m.1.map(String.init)
        let mins = m.2.map(String.init)
        if hours == nil && mins == nil { return nil }
        let fromHours = hours.flatMap { Double($0.replacingOccurrences(of: ",", with: ".")) }.map { $0 * 60 } ?? 0
        return Int((fromHours + Double(mins.flatMap(Int.init) ?? 0)).rounded())
    }
}
