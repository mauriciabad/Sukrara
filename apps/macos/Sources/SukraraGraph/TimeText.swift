// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import AppKit
import CoreGraphics
import CoreText
import Foundation

/// Every clock time Sukrara shows on the Mac ("21:08" / "9:08 PM"): the menubar tooltip and dropdown, the widgets, the
/// treatments, the report, the graph's hour labels. They follow System Settings → General → Date & Time → 24-hour
/// time, not the locale's default (Android: graph/Clock.kt; shared cases in contracts/graph/clock).
public enum TimeText {
    /// The Mac's 24-hour setting: the ONE place it is read. Every surface (dropdown card, hour labels, widgets, menubar
    /// tooltips, settings, reports) formats through `TimeText` with it (cards get it in `CardStyle.hour24` / `GraphStyle.hour24`), never a flag of its own (2026-10-21: Android's value
    /// widget and chart cards read it from different places). The explicit-cycle functions are for the clock fixtures.
    public static var hour24: Bool {
        !(DateFormatter.dateFormat(fromTemplate: "j", options: 0, locale: .current) ?? "").contains("a")
    }

    /// What tells that the clock's look changed while Sukrara runs: System Settings › Date & Time › 24-hour time posts
    /// the current locale's change, and the time zone: the app redraws and re-publishes to the widget on them.
    public static let changeNotifications: [Notification.Name] = [
        NSLocale.currentLocaleDidChangeNotification, .NSSystemTimeZoneDidChange,
    ]

    /// "21:08" / "9:08 PM" in the Mac's setting.
    static func time(_ d: Date) -> String { time(d, hour24: hour24, locale: .autoupdatingCurrent) }

    /// "21:08" / "9:08 PM" in the given hour cycle and locale.
    static func time(_ d: Date, hour24: Bool, locale: Locale, zone: TimeZone = .current) -> String {
        let f = DateFormatter()
        // the bare locale: `.autoupdatingCurrent` carries the user's forced 12/24-hour preference, which would override
        // the cycle asked for here (the setting is read once, in `hour24`)
        f.locale = Locale(identifier: locale.identifier); f.timeZone = zone
        f.setLocalizedDateFormatFromTemplate(hour24 ? "Hmm" : "hmm")
        let s = f.string(from: d)
        return s
    }

    /// "12 Oct 2026, 21:08": the locale's medium date and the time.
    static func dateTime(_ d: Date) -> String { d.formatted(date: .abbreviated, time: .omitted) + ", " + time(d) }

    /// A minute of the day (0–1439) as a clock time (the fixtures).
    static func minuteOfDay(_ m: Int, hour24: Bool, locale: Locale) -> String {
        time(
            Date(timeIntervalSince1970: TimeInterval(m * 60)), hour24: hour24, locale: locale,
            zone: TimeZone(identifier: "UTC")!)
    }

    /// The graph's day label (a zoomed-out chart): "17 Oct" (TimeText.kt `dayLabel`).
    static func dayLabel(_ d: Date, locale: Locale = .autoupdatingCurrent) -> String {
        let f = DateFormatter(); f.locale = locale; f.dateFormat = "d MMM"
        return f.string(from: d)
    }

    /// The graph's hour label: "21:00" / "9 PM" (the locale's day period).
    static func hourLabel(_ h: Int, _ hour24: Bool, locale: Locale = .autoupdatingCurrent) -> String {
        let f = DateFormatter(); f.locale = locale
        let s =
            hour24 ? String(format: "%02d:00", h) : "\(h % 12 == 0 ? 12 : h % 12) \(h < 12 ? f.amSymbol! : f.pmSymbol!)"
        return s
    }
}
