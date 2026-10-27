// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation
import Synchronization

/// Every clock time Sukrara shows on the Mac ("21:08" / "9:08 PM"): the menu bar, the card, the widgets, the
/// treatments, the graph's hour labels. They follow System Settings › General › Date & Time › 24-hour time, not the
/// locale's default (Android: graph/Clock.kt; shared cases in contracts/graph/clock). THE one place the 24-hour
/// setting is read (tools/check `clock`): nothing else keeps a flag of its own (2026-10-21: Android's value widget and
/// chart cards read it from different places and disagreed).
public enum Clock {
    private static let source = Mutex<(@Sendable () -> Bool)?>(nil)

    /// Where the 24-hour setting comes from: nil = the Mac's setting. Tests set it to draw both cycles.
    public static var hour24Source: (@Sendable () -> Bool)? {
        get { source.withLock { $0 } }
        set { source.withLock { $0 = newValue } }
    }

    /// The 24-hour setting in force.
    public static var hour24: Bool { hour24Source?() ?? systemHour24 }

    /// The Mac's setting, read where it lives.
    private static var systemHour24: Bool {
        !(DateFormatter.dateFormat(fromTemplate: "j", options: 0, locale: .current) ?? "").contains("a")
    }

    /// What tells that the clock's look changed while Sukrara runs: System Settings › Date & Time › 24-hour time posts
    /// the current locale's change, and the time zone: the app redraws and re-publishes to the widget on them.
    public static let changeNotifications: [Notification.Name] = [
        NSLocale.currentLocaleDidChangeNotification, .NSSystemTimeZoneDidChange,
    ]

    /// "21:08" / "9:08 PM" in the setting in force.
    public static func time(_ d: Date) -> String { time(d, hour24: hour24, locale: .autoupdatingCurrent) }

    /// "21:08" / "9:08 PM" in the given hour cycle and locale.
    public static func time(_ d: Date, hour24: Bool, locale: Locale, zone: TimeZone = .current) -> String {
        let f = DateFormatter()
        // the bare locale: `.autoupdatingCurrent` carries the user's forced 12/24-hour preference, which would override
        // the cycle asked for here (the setting is read once, in `hour24`)
        f.locale = Locale(identifier: locale.identifier)
        f.timeZone = zone
        f.setLocalizedDateFormatFromTemplate(hour24 ? "Hmm" : "hmm")
        return f.string(from: d)
    }

    /// "12 Oct 2026, 21:08": the locale's medium date and the time.
    public static func dateTime(_ d: Date) -> String {
        d.formatted(date: .abbreviated, time: .omitted) + ", " + time(d)
    }

    /// A minute of the day (0–1439) as a clock time (the fixtures).
    public static func minuteOfDay(_ m: Int, hour24: Bool, locale: Locale) -> String {
        time(
            Date(timeIntervalSince1970: TimeInterval(m * 60)), hour24: hour24, locale: locale,
            zone: TimeZone(identifier: "UTC")!)
    }

    /// The graph's day label (a zoomed-out chart): "17 Oct".
    public static func dayLabel(_ d: Date, locale: Locale = .autoupdatingCurrent) -> String {
        let f = DateFormatter()
        f.locale = locale
        f.dateFormat = "d MMM"
        return f.string(from: d)
    }

    /// The graph's hour label: "21:00" / "9 PM" (the locale's day period).
    public static func hourLabel(_ h: Int, _ hour24: Bool = hour24, locale: Locale = .autoupdatingCurrent) -> String {
        let f = DateFormatter()
        f.locale = locale
        return hour24
            ? String(format: "%02d:00", h) : "\(h % 12 == 0 ? 12 : h % 12) \(h < 12 ? f.amSymbol! : f.pmSymbol!)"
    }
}
