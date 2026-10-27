// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation

/// The treatment log: `treatments.json` under the App Support folder (`TreatmentLog` V1, atomic writes). An
/// unreadable file (or another format version) is moved aside (`treatments.json.corrupt-<unix s>`), the log starts
/// empty and `issue` says so; the wearer's phone re-sends the last 30 days of entries on the next subscription.
public actor TreatmentFileStore: TreatmentStoring {
    public static let fileName = "treatments.json"

    public let url: URL
    private let clock: any Clock
    private var cache: TreatmentLog?
    public private(set) var issue: StoreIssue?

    public init(directory: URL, clock: any Clock = SystemClock()) {
        url = directory.appending(path: Self.fileName, directoryHint: .notDirectory)
        self.clock = clock
    }

    public func load() -> TreatmentLog {
        if let cache { return cache }
        let log = read()
        cache = log
        return log
    }

    public func save(_ log: TreatmentLog) throws {
        cache = log
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        try StoreFiles.write(try encoder.encode(log), to: url)
    }

    /// Forgets every entry (unfollow).
    public func removeAll() {
        cache = TreatmentLog()
        try? FileManager.default.removeItem(at: url)
    }

    private func read() -> TreatmentLog {
        guard let data = try? Data(contentsOf: url) else { return TreatmentLog() }
        if let log = try? JSONDecoder().decode(TreatmentLog.self, from: data), log.v == TreatmentLog.formatVersion {
            return log
        }
        issue = StoreFiles.quarantine(url, now: clock.now)
        try? FileManager.default.removeItem(at: url)
        return TreatmentLog()
    }
}
