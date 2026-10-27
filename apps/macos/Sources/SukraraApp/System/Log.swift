// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation
import SukraraCore
import os

/// The app's loggers: the unified system log, values private unless marked public (never a glucose value or a key in
/// public).
nonisolated enum AppLog {
    static let alerts = Logger(subsystem: SukraraIDs.appBundleID, category: "alerts")
    static let app = Logger(subsystem: SukraraIDs.appBundleID, category: "app")
    static let update = Logger(subsystem: SukraraIDs.appBundleID, category: "update")
}

/// A small text log the user can attach to a bug report: `~/Library/Logs/app.mauri.sukrara/sukrara.log`, at most
/// `SukraraPaths.logFileCount` files of `logLimitBytes` (the full one becomes `sukrara.1.log`). Event names only,
/// no glucose values or keys.
final class RotatingFileLog {
    let directory: URL
    private let limit: Int
    private let formatter = ISO8601DateFormatter()

    init(directory: URL, limit: Int = SukraraPaths.logLimitBytes) {
        self.directory = directory
        self.limit = limit
    }

    var current: URL { directory.appending(path: "sukrara.log", directoryHint: .notDirectory) }
    var previous: URL { directory.appending(path: "sukrara.1.log", directoryHint: .notDirectory) }

    func write(_ line: String, at date: Date = Date()) {
        let fm = FileManager.default
        let data = Data("\(formatter.string(from: date)) \(line)\n".utf8)
        do {
            try fm.createDirectory(at: directory, withIntermediateDirectories: true)
            let size = (try? fm.attributesOfItem(atPath: current.path)[.size] as? Int) ?? 0
            if size + data.count > limit {
                try? fm.removeItem(at: previous)
                try? fm.moveItem(at: current, to: previous)
            }
            if let handle = try? FileHandle(forWritingTo: current) {
                defer { try? handle.close() }
                try handle.seekToEnd()
                try handle.write(contentsOf: data)
            } else {
                try data.write(to: current)
            }
        } catch {
            AppLog.app.error("file log not written: \(error.localizedDescription, privacy: .public)")
        }
    }
}
