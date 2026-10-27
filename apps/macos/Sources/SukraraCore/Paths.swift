// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation

/// Where the V1 app keeps its files, relative to a home directory (injected so tests never touch the real one).
/// Pure path math: nothing here creates or reads a file. App only: the sandboxed widget reads nothing but
/// `appGroupSnapshot()`. Dev and snapshot runs get an isolated home from `DataScope` (SukraraApp).
public struct SukraraPaths: Sendable, Equatable {
    public let home: URL

    public init(home: URL) {
        self.home = home
    }

    /// `~/Library/Application Support/app.mauri.sukrara/`: readings, treatments, follower state.
    public var appSupport: URL {
        home.appending(path: "Library/Application Support/\(SukraraIDs.appBundleID)", directoryHint: .isDirectory)
    }

    /// `~/Library/Logs/app.mauri.sukrara/`: the bounded file log (see `logLimitBytes`, `logFileCount`).
    public var logs: URL {
        home.appending(path: "Library/Logs/\(SukraraIDs.appBundleID)", directoryHint: .isDirectory)
    }

    /// `~/Library/Sounds/`: Notification Center only plays custom sounds from here (or the app bundle, which it
    /// did not find for an LSUIElement app in the PoC), so alert sounds are copied here before use.
    public var sounds: URL {
        home.appending(path: "Library/Sounds", directoryHint: .isDirectory)
    }

    /// `~/Library/Sounds/app.mauri.sukrara.<name>.caf`: the prefix keeps V1's sounds apart from the PoC's and others'.
    public func soundFile(named name: String) -> URL {
        sounds.appending(path: Self.soundFileName(name), directoryHint: .notDirectory)
    }

    /// The file name a bundled sound gets in `~/Library/Sounds` (also the name `UNNotificationSound` takes).
    public static func soundFileName(_ name: String) -> String {
        "\(SukraraIDs.appBundleID).\(name).caf"
    }

    /// The glucose snapshot the app writes into the App Group container for the widget.
    public static let appGroupSnapshotName = "glucose.json"

    /// The App Group snapshot file, or nil when the system grants no container (unsigned / wrongly signed bundle:
    /// callers show that as an error state, never as "no data"). Always asked from the system, never derived from
    /// a home directory: inside the sandboxed widget the home directory is the widget's own container.
    public static func appGroupSnapshot(group: String = SukraraIDs.appGroup) -> URL? {
        FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: group)?
            .appending(path: appGroupSnapshotName, directoryHint: .notDirectory)
    }

    /// File log rotation: at most `logFileCount` files of `logLimitBytes` each.
    public static let logLimitBytes = 1_000_000
    public static let logFileCount = 2

    /// The current user's paths.
    public static var user: SukraraPaths {
        SukraraPaths(home: FileManager.default.homeDirectoryForCurrentUser)
    }
}
