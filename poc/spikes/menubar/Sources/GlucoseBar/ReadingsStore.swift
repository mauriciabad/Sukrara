// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation

/// The Mac's own glucose history for the graph and the time-in-range rings (investigation 16): every reading any
/// transport delivers, merged (±20 s), kept 31 days (the 30-day report and slots) in
/// ~/Library/Application Support/Sukrara/readings.csv ("epoch s,mg/dL" per line). Written at most every 5 minutes;
/// slot and report numbers are cached for 5 minutes too, so a redraw never walks 45 000 rows.
@MainActor final class ReadingsStore {
    static let keep: TimeInterval = 31 * 86400
    private let file: URL
    private(set) var readings: [GraphReading] = []
    private var savedAt = Date.distantPast
    private var dirty = false
    private var cache: [String: (Date, GlucoseMetrics)] = [:]

    init(directory: URL = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("Sukrara")) {
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        file = directory.appendingPathComponent("readings.csv")
        if let s = try? String(contentsOf: file, encoding: .utf8) {
            readings = s.split(separator: "\n").compactMap { line in
                let p = line.split(separator: ",")
                guard p.count >= 2, let t = TimeInterval(p[0]), let v = Double(p[1]) else { return nil }
                return GraphReading(t: t, v: v)
            }.sorted { $0.t < $1.t }
        }
    }

    /// Merge readings (any order); returns true if anything changed.
    @discardableResult
    func merge(_ new: [Reading]) -> Bool {
        var changed = false
        for r in new {
            let g = GraphReading(t: r.date.timeIntervalSince1970, v: r.mgdl, rate: r.rate)
            // `readings` stays sorted: binary search for the slot, then look at its two neighbours.
            var lo = 0, hi = readings.count
            while lo < hi { let mid = (lo + hi) / 2; if readings[mid].t < g.t { lo = mid + 1 } else { hi = mid } }
            if let i = [lo - 1, lo].first(where: { $0 >= 0 && $0 < readings.count && abs(readings[$0].t - g.t) < 20 }) {
                if readings[i].v != g.v || (g.rate != nil && readings[i].rate == nil) { readings[i] = g; changed = true }
            } else {
                readings.insert(g, at: lo)
                changed = true
            }
        }
        guard changed else { return false }
        if let newest = readings.last?.t { readings.removeAll { newest - $0.t > Self.keep } }
        dirty = true
        if Date().timeIntervalSince(savedAt) > 300 { save() }
        return true
    }

    func since(_ t: TimeInterval) -> [GraphReading] { readings.filter { $0.t >= t } }

    func metrics(window: TimeInterval, bands: Bands, now: TimeInterval = Date().timeIntervalSince1970) -> GlucoseMetrics {
        let key = "\(Int(window))|\(bands.veryLow)|\(bands.low)|\(bands.tight)|\(bands.high)|\(bands.veryHigh)"
        if let (at, v) = cache[key], Date().timeIntervalSince(at) < 300 { return v }
        let v = GlucoseMetrics.of(readings, from: now - window, to: now, bands: bands)
        cache[key] = (Date(), v)
        return v
    }

    func save() {
        guard dirty else { return }
        let body = readings.map { "\(Int($0.t)),\(Int($0.v.rounded()))" }.joined(separator: "\n")
        try? body.write(to: file, atomically: true, encoding: .utf8)
        savedAt = Date()
        dirty = false
    }
}
