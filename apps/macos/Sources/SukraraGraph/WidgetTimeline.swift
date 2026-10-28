// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation

/// When the widget's picture changes without the app: the dates of its WidgetKit timeline entries. The app reloads the
/// widget only when the shown value changes (`WidgetReloadPolicy`), so between readings the timeline alone has to age
/// the card honestly: the time appears at 90 s, the value is struck through at 5 min, hidden at 20 min, the graph goes
/// at 3 h (`Freshness`, contracts/graph/freshness), a mute's countdown ticks and ends, a sensor's warm-up or life ends.
/// Each entry redraws the same snapshot with `now` = the entry's date (`WidgetSnapshot.data(at:)`).
public enum WidgetTimeline {
    /// No timeline reaches further than this or holds more entries (a snapshot with far-off ends stays small).
    static let horizon: TimeInterval = 24 * 3600
    static let maxEntries = 100
    /// The card counts a mute down in whole minutes: a tick each minute of its last 15 min, every 5 min in the 45 before
    /// (each entry is a full picture; between ticks the count is at most 4 min high, never low).
    static let countdownTicks: [Int] = Array(1...15) + stride(from: 20, through: 60, by: 5)

    /// The entry dates for `snapshot` seen at `now`: `now` first, then every later moment the card's picture changes,
    /// ascending. Without a snapshot only `now` (the "Open Sukrara" state, which nothing ages).
    public static func dates(_ snapshot: WidgetSnapshot?, now: Date) -> [Date] {
        let t0 = now.timeIntervalSince1970
        guard let snapshot else { return [now] }
        let data = snapshot.data
        var changes: [TimeInterval] = []

        // the freshness steps of the latest reading
        let latest = data.latest?.t
        var at = t0
        while let next = Freshness.nextChangeAt(latest, now: at), next <= t0 + horizon {
            changes.append(next)
            at = next
        }
        // mute ends, each with its countdown
        for end in muteEnds(data.alerts) where end > t0 && end <= t0 + horizon {
            changes.append(end)
            for k in countdownTicks { changes.append(end - TimeInterval(k * 60)) }
        }
        // the sensor's own states
        if let sensor = data.sensor { changes += [sensor.warmupEnd, sensor.end] }

        let future = Set(changes.filter { $0 > t0 && $0 <= t0 + horizon }).sorted().prefix(maxEntries - 1)
        return [now] + future.map { Date(timeIntervalSince1970: $0) }
    }

    /// When WidgetKit should ask for a new timeline: just after the last entry (nothing changes after it on its own),
    /// nil = never (the app's next write reloads the widget).
    public static func reloadAfter(_ dates: [Date]) -> Date? {
        guard dates.count > 1, let last = dates.last else { return nil }
        return last.addingTimeInterval(1)
    }

    /// Every mute end the card draws: the badge's, the last treatment's (and its carbs'), each treatment's mute line.
    private static func muteEnds(_ alerts: CardAlerts) -> [TimeInterval] {
        var ends = Array(alerts.muteEnds.values)
        if let u = alerts.status.until, u < AlertStatus.never { ends.append(u) }
        if let u = alerts.last?.until { ends.append(u) }
        if let u = alerts.last?.carbs?.until { ends.append(u) }
        return ends
    }
}

extension WidgetSnapshot {
    /// The card data as it looks at `date`: the same readings, aged to the entry's time.
    public func data(at date: Date) -> CardData {
        var d = data
        d.now = date.timeIntervalSince1970
        return d
    }
}
