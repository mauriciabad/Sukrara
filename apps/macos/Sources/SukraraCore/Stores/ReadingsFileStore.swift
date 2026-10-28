// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation

/// The readings history: `readings.csv` under the App Support folder, 31 days.
///
/// Format: a header line `t_ms,mgdl,rate`, then one line per reading, oldest first — Unix epoch milliseconds,
/// mg/dL, mg/dL per minute or empty. CSV rather than JSONL: half the size, appendable, the same columns as the
/// export. The file is read once; a merge of readings newer than the newest stored appends (the common case: one
/// reading a minute), anything else rewrites the file atomically. A last line cut short by a crash during an append is
/// dropped; any other unreadable line moves the file aside (`readings.csv.corrupt-<unix s>`), keeps the good lines
/// and sets `issue`.
public actor ReadingsFileStore: ReadingsStoring {
    public static let fileName = "readings.csv"
    public static let retention: TimeInterval = 31 * 86_400
    static let header = "t_ms,mgdl,rate"
    /// Retention is applied on load and then at most this often.
    private static let compactEvery: TimeInterval = 3600

    public let url: URL
    private let clock: any TimeSource
    private var cache: [Reading]?
    private var lastCompaction = Date.distantPast
    public private(set) var issue: StoreIssue?

    public init(directory: URL, clock: any TimeSource = SystemClock()) {
        url = directory.appending(path: Self.fileName, directoryHint: .notDirectory)
        self.clock = clock
    }

    public func load(since: Date) throws -> [Reading] {
        let all = try readings()
        let first = all.firstIndex { $0.t >= since } ?? all.endIndex
        return Array(all[first...])
    }

    public func merge(_ new: [Reading]) throws {
        var all = try readings()
        let cutoff = clock.now.addingTimeInterval(-Self.retention)
        var known = Set(all.map(\.t))
        var added: [Reading] = []
        for reading in new.sorted(by: { $0.t < $1.t }) where reading.t >= cutoff && known.insert(reading.t).inserted {
            added.append(reading)
        }
        guard !added.isEmpty else { return }
        let appendable = all.last.map { added[0].t > $0.t } ?? false
        all.append(contentsOf: added)
        if appendable, clock.now.timeIntervalSince(lastCompaction) < Self.compactEvery {
            cache = all
            try append(added)
        } else {
            all.sort { $0.t < $1.t }
            cache = all
            try rewrite()
        }
    }

    public func removeAll() throws {
        cache = []
        try? FileManager.default.removeItem(at: url)
    }

    // MARK: - file

    private func readings() throws -> [Reading] {
        if let cache { return cache }
        let loaded = parse()
        cache = loaded.readings
        if loaded.needsRewrite { try rewrite() }
        return loaded.readings
    }

    private func parse() -> (readings: [Reading], needsRewrite: Bool) {
        guard let text = try? String(contentsOf: url, encoding: .utf8) else { return ([], false) }
        let cutoff = clock.now.addingTimeInterval(-Self.retention)
        var lines = text.split(separator: "\n", omittingEmptySubsequences: true)
        if lines.first == Substring(Self.header) { lines.removeFirst() }
        var out: [Reading] = []
        var bad = 0
        var truncatedTail = false
        var seen = Set<Date>()
        for (i, line) in lines.enumerated() {
            guard let reading = Self.decode(line) else {
                if i == lines.count - 1 && !text.hasSuffix("\n") { truncatedTail = true } else { bad += 1 }
                continue
            }
            if reading.t >= cutoff, seen.insert(reading.t).inserted { out.append(reading) }
        }
        if bad > 0 { issue = StoreFiles.quarantine(url, now: clock.now) }
        out.sort { $0.t < $1.t }
        lastCompaction = clock.now
        let expired = out.count < lines.count - bad - (truncatedTail ? 1 : 0)
        return (out, bad > 0 || truncatedTail || expired)
    }

    private func rewrite() throws {
        let cutoff = clock.now.addingTimeInterval(-Self.retention)
        let kept = (cache ?? []).filter { $0.t >= cutoff }
        cache = kept
        lastCompaction = clock.now
        let body = ([Self.header] + kept.map(Self.encode)).joined(separator: "\n") + "\n"
        try StoreFiles.write(Data(body.utf8), to: url)
    }

    private func append(_ readings: [Reading]) throws {
        guard let handle = try? FileHandle(forWritingTo: url) else { return try rewrite() }
        defer { try? handle.close() }
        try handle.seekToEnd()
        try handle.write(contentsOf: Data((readings.map(Self.encode).joined(separator: "\n") + "\n").utf8))
    }

    static func encode(_ reading: Reading) -> String {
        let millis = Int64((reading.t.timeIntervalSince1970 * 1000).rounded())
        let rate = reading.rate.map { String($0) } ?? ""
        return "\(millis),\(reading.mgdl),\(rate)"  // card-ok: readings.csv storage format, never shown
    }

    static func decode(_ line: Substring) -> Reading? {
        let fields = line.split(separator: ",", omittingEmptySubsequences: false)
        guard fields.count == 3, let millis = Int64(fields[0]), let mgdl = Int(fields[1]), mgdl > 0 else { return nil }
        let rate: Double?
        if fields[2].isEmpty {
            rate = nil
        } else {
            guard let value = Double(fields[2]), value.isFinite else { return nil }
            rate = value
        }
        return Reading(t: Date(timeIntervalSince1970: Double(millis) / 1000), mgdl: mgdl, rate: rate)
    }
}
