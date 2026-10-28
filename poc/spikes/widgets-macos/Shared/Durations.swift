// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation

/// THE duration formatter (round f30; the user, 2026-10-21: "Showing 60 min and 120 min is long, 1 h and 2 h is
/// better. This applies everywhere in the UI"), a 1:1 port of Android's core/Durations.kt. Every user-visible duration
/// goes through here; the formats and their cases are spikes/durations/durations.json (-settingsselftest). tools/check
/// rule `durations` fails on a duration formatted anywhere else.
enum Durations {
    /// In words and settings (notifications, menus, presets, history): "45 min", "1 h", "1 h 30 min", "12 h".
    static func text(_ min: Int) -> String {
        let m = max(0, min)
        if m < 60 { return "\(m) min" }
        return m % 60 == 0 ? "\(m / 60) h" : "\(m / 60) h \(m % 60) min"
    }

    /// `text` of a span in seconds, partial minutes rounded up (time left: "1 min" until it is over).
    static func text(seconds s: TimeInterval) -> String { text((Int(max(0, s).rounded(.down)) + 59) / 60) }

    /// The card's compact convention (treatment labels, badges "Xm left"): "12m", "1h 5m", "3h" (whole hours drop the
    /// minutes; from 6 h only hours), "2d" from two days.
    static func compact(_ min: Int) -> String {
        let m = max(0, min)
        switch m {
        case ..<60: return "\(m)m"
        case ..<(6 * 60): return m % 60 == 0 ? "\(m / 60)h" : "\(m / 60)h \(m % 60)m"
        case ..<(48 * 60): return "\(m / 60)h"
        default: return "\(m / 1440)d"
        }
    }

    /// One unit, rounded down: "45m", from 60 min "20h", from 24 h "13d" (the sensor-life badge, the warm-up).
    static func whole(_ min: Int) -> String {
        let m = max(0, min)
        if m >= 1440 { return "\(m / 1440)d" }
        if m >= 60 { return "\(m / 60)h" }
        return "\(m)m"
    }

    private static let hmm = try! Regex(#"(\d{1,3}):(\d{2})"#)
    private static let hm = try! Regex(#"(?:(\d{1,3}(?:[.,]\d+)?)\s*h)?\s*(?:(\d{1,4})\s*(?:min|m)?)?"#)

    /// Typed minutes (Mute…, a custom value): "45", "45 min", "1h", "1 h 30", "1h 30m", "1:30", "1,5 h" → minutes; nil
    /// when it isn't a duration. The caller checks the range.
    static func parse(_ input: String) -> Int? {
        let s = input.trimmingCharacters(in: .whitespaces).lowercased()
        guard !s.isEmpty else { return nil }
        func group(_ m: Regex<AnyRegexOutput>.Match, _ i: Int) -> String? { m.output[i].substring.map(String.init) }
        if let m = try? hmm.wholeMatch(in: s) {
            guard let h = group(m, 1).flatMap(Int.init), let mm = group(m, 2).flatMap(Int.init), mm < 60 else { return nil }
            return h * 60 + mm
        }
        guard let m = try? hm.wholeMatch(in: s) else { return nil }
        let h = group(m, 1), mins = group(m, 2)
        if h == nil && mins == nil { return nil }
        let hm = h.flatMap { Double($0.replacingOccurrences(of: ",", with: ".")) }.map { $0 * 60 } ?? 0
        return Int((hm + Double(mins.flatMap(Int.init) ?? 0)).rounded())
    }
}
