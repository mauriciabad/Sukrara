// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation
import SukraraCore
import SukraraGraph
import WidgetKit

/// One timeline entry: the app's snapshot as it looks at `date` (nil = nothing to show: "Open Sukrara").
struct SnapshotEntry: TimelineEntry {
    let date: Date
    let snapshot: WidgetSnapshot?

    /// The card's data aged to this entry's date, so the renderer decides late / struck / hidden from the real age.
    var data: CardData? { snapshot?.data(at: date) }
}

/// Reads the snapshot the app writes into the App Group container (`glucose.json`; the group from this bundle's
/// Info.plist `SukraraAppGroup`) and nothing else: no network, no other path, no fallback. An unreadable file or one
/// of another format version is "no snapshot", never a guessed value. The timeline carries an entry at every moment
/// the card changes on its own (`WidgetTimeline`); the app reloads it when the shown value changes.
struct SnapshotProvider: TimelineProvider {
    /// The redacted stand-in WidgetKit draws while loading: no value.
    func placeholder(in context: Context) -> SnapshotEntry { SnapshotEntry(date: .now, snapshot: nil) }

    /// The gallery and transient previews: the real snapshot when there is one. Never a made-up sample (the release
    /// app never shows a glucose value that isn't real), so without one the gallery shows "Open Sukrara".
    func getSnapshot(in context: Context, completion: @escaping (SnapshotEntry) -> Void) {
        completion(SnapshotEntry(date: .now, snapshot: Self.read()))
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<SnapshotEntry>) -> Void) {
        let snapshot = Self.read()
        let dates = WidgetTimeline.dates(snapshot, now: .now)
        let policy: TimelineReloadPolicy = WidgetTimeline.reloadAfter(dates).map { .after($0) } ?? .never
        completion(Timeline(entries: dates.map { SnapshotEntry(date: $0, snapshot: snapshot) }, policy: policy))
    }

    private static func read() -> WidgetSnapshot? {
        let group = Bundle.main.object(forInfoDictionaryKey: SukraraIDs.appGroupInfoKey) as? String
        guard let group, let url = SukraraPaths.appGroupSnapshot(group: group),
            let bytes = try? Data(contentsOf: url)
        else { return nil }
        return WidgetSnapshot.decode(bytes)
    }
}
