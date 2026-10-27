// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation
import SukraraCore
import SukraraGraph
import WidgetKit
import os

/// When to ask WidgetKit to reload. A background app's reloads count against a daily budget (about 40–70), so only a
/// change people would see triggers one: a new link state at once, a new value at most once every 5 minutes (one
/// deferred reload carries the last value). Staleness never triggers a reload: the widget's timeline ages the value
/// by itself from the reading time in the snapshot.
public struct WidgetReloadPolicy: Sendable {
    /// What the widget shows: the value text and the link state (following, not following, …).
    public struct Shown: Equatable, Sendable {
        public var value: String
        public var state: String

        public init(value: String, state: String) {
            self.value = value
            self.state = state
        }
    }

    public enum Decision: Equatable, Sendable {
        case none
        case now
        /// Reload at this time unless something else reloads first.
        case at(Date)
    }

    public static let minInterval: TimeInterval = 300

    private var reloaded: Shown?
    private var lastReload = Date.distantPast

    public init() {}

    /// The widget would now show `shown`.
    public mutating func decide(_ shown: Shown, now: Date) -> Decision {
        guard shown != reloaded else { return .none }
        if shown.state != reloaded?.state || now.timeIntervalSince(lastReload) >= Self.minInterval {
            return .now
        }
        return .at(lastReload.addingTimeInterval(Self.minInterval))
    }

    /// A reload of `shown` happened at `now`.
    public mutating func didReload(_ shown: Shown, now: Date) {
        reloaded = shown
        lastReload = now
    }
}

/// Hands the card data to the widget: writes `glucose.json` into the App Group container (atomic) and reloads the
/// widget kinds per `WidgetReloadPolicy`. No App Group container (unsigned / wrongly signed bundle) is an `issue`,
/// never "no data".
public final class WidgetFeed {
    public typealias Reloader = @MainActor (_ kinds: [String]) -> Void

    private static let log = Logger(subsystem: SukraraIDs.appBundleID, category: "widget")
    private let file: URL?
    private let clock: any TimeSource
    private let reload: Reloader
    private var policy = WidgetReloadPolicy()
    private var pending: Task<Void, Never>?
    private var latest: WidgetReloadPolicy.Shown?
    public private(set) var reloadCount = 0
    public private(set) var issue: String?

    /// `file`: the snapshot file (default: the App Group container from Info.plist `SukraraAppGroup`).
    public init(
        file: URL? = WidgetFeed.appGroupFile(), clock: any TimeSource = SystemClock(),
        reload: @escaping Reloader = { kinds in kinds.forEach { WidgetCenter.shared.reloadTimelines(ofKind: $0) } }
    ) {
        self.file = file
        self.clock = clock
        self.reload = reload
        if file == nil { issue = "No App Group container: the widget can't get readings (check the app's signature)" }
    }

    public static func appGroupFile(bundle: Bundle = .main) -> URL? {
        let group = bundle.object(forInfoDictionaryKey: SukraraIDs.appGroupInfoKey) as? String ?? SukraraIDs.appGroup
        return SukraraPaths.appGroupSnapshot(group: group)
    }

    /// The app's current card data and what the widget would show for it.
    public func update(_ snapshot: WidgetSnapshot, shown: WidgetReloadPolicy.Shown) {
        guard let file else { return }
        do {
            try FileManager.default.createDirectory(
                at: file.deletingLastPathComponent(), withIntermediateDirectories: true)
            try snapshot.encoded().write(to: file, options: [.atomic])
        } catch {
            issue = "Can't write the widget data"
            Self.log.error("widget snapshot not written: \(error.localizedDescription, privacy: .public)")
            return
        }
        latest = shown
        switch policy.decide(shown, now: clock.now) {
        case .none: return
        case .now: reloadNow()
        case .at(let date):
            guard pending == nil else { return }
            let delay = max(0, date.timeIntervalSince(clock.now))
            pending = Task { [weak self] in
                try? await Task.sleep(for: .seconds(delay))
                guard !Task.isCancelled else { return }
                self?.pending = nil
                self?.reloadNow()
            }
        }
    }

    private func reloadNow() {
        guard let latest else { return }
        pending?.cancel()
        pending = nil
        policy.didReload(latest, now: clock.now)
        reloadCount += 1
        reload(SukraraIDs.WidgetKind.all)
        Self.log.info("widget reload #\(self.reloadCount, privacy: .public)")
    }
}
