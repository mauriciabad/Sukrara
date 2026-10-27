// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation

/// The treatment log: `treatments.json` under the App Support folder (`FollowedTreatments` V1, atomic writes). An
/// unreadable file (or another format version) is moved aside (`treatments.json.corrupt-<unix s>`), the log starts
/// empty and `issue` says so; the wearer's phone re-sends the last 30 days of entries on the next subscription.
public actor TreatmentFileStore: TreatmentStoring {
    public static let fileName = "treatments.json"

    public let url: URL
    private let clock: any TimeSource
    private var cache: FollowedTreatments?
    public private(set) var issue: StoreIssue?

    public init(directory: URL, clock: any TimeSource = SystemClock()) {
        url = directory.appending(path: Self.fileName, directoryHint: .notDirectory)
        self.clock = clock
    }

    public func load() -> FollowedTreatments {
        if let cache { return cache }
        let log = read()
        cache = log
        return log
    }

    public func save(_ log: FollowedTreatments) throws {
        cache = log
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        try StoreFiles.write(try encoder.encode(log), to: url)
    }

    /// Forgets every entry (unfollow).
    public func removeAll() {
        cache = FollowedTreatments()
        try? FileManager.default.removeItem(at: url)
    }

    private func read() -> FollowedTreatments {
        guard let data = try? Data(contentsOf: url) else { return FollowedTreatments() }
        if let log = try? JSONDecoder().decode(FollowedTreatments.self, from: data),
            log.v == FollowedTreatments.formatVersion
        {
            return log
        }
        issue = StoreFiles.quarantine(url, now: clock.now)
        try? FileManager.default.removeItem(at: url)
        return FollowedTreatments()
    }
}
