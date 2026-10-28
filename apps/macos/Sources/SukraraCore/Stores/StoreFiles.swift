// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation

/// File helpers shared by the stores: atomic replace, and moving an unreadable file aside.
enum StoreFiles {
    /// Writes `data` to `url` atomically (temp file + rename), creating the directory.
    static func write(_ data: Data, to url: URL) throws {
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try data.write(to: url, options: [.atomic])
    }

    /// Copies the unreadable `url` to `<name>.corrupt-<unix s>` next to it and returns the issue to show.
    static func quarantine(_ url: URL, now: Date) -> StoreIssue {
        let target = url.deletingLastPathComponent()
            .appending(path: "\(url.lastPathComponent).corrupt-\(Int(now.timeIntervalSince1970))")
        let copied = (try? FileManager.default.copyItem(at: url, to: target)) != nil
        return StoreIssue(file: url.lastPathComponent, quarantined: copied ? target : nil)
    }
}

/// A small Codable value in one JSON file (`sensor.json`, `wearer-settings.json`): nil when absent. An unreadable
/// file is quarantined and reported (`issue`), then treated as absent.
public actor JSONFileStore<Value: Codable & Sendable> {
    public let url: URL
    private let clock: any TimeSource
    public private(set) var issue: StoreIssue?

    public init(url: URL, clock: any TimeSource = SystemClock()) {
        self.url = url
        self.clock = clock
    }

    public func load() -> Value? {
        guard let data = try? Data(contentsOf: url) else { return nil }
        do {
            return try JSONDecoder().decode(Value.self, from: data)
        } catch {
            issue = StoreFiles.quarantine(url, now: clock.now)
            try? FileManager.default.removeItem(at: url)
            return nil
        }
    }

    /// Stores `value`; nil removes the file.
    public func save(_ value: Value?) throws {
        guard let value else {
            try? FileManager.default.removeItem(at: url)
            return
        }
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        try StoreFiles.write(try encoder.encode(value), to: url)
    }
}
