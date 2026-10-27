// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import CoreGraphics
import CoreText
import Foundation

enum GlucoseStats {
    static let maxGap: TimeInterval = 15 * 60
    static let minMg = 20, maxMg = 450

    /// Readings in [from, to + 30 s), sorted, deduplicated (±30 s, the later one wins). A sorted history (the store's
    /// order) is cut by binary search, so a scrolled chart over weeks of readings only touches its window each frame.
    static func window(_ all: [GraphReading], from: TimeInterval, to: TimeInterval) -> [GraphReading] {
        var out: [GraphReading] = []
        let sorted = zip(all, all.dropFirst()).allSatisfy { $0.t <= $1.t }
        func lowerBound(_ t: TimeInterval) -> Int {
            var lo = 0, hi = all.count
            while lo < hi { let m = (lo + hi) / 2; if all[m].t < t { lo = m + 1 } else { hi = m } }
            return lo
        }
        let src: [GraphReading] =
            sorted
            ? Array(all[lowerBound(from)..<lowerBound(to + 30)])
            : all.filter({ $0.t >= from && $0.t < to + 30 }).sorted(by: { $0.t < $1.t })
        out.reserveCapacity(src.count)
        for r in src {
            if let last = out.last, r.t - last.t < 30 { out[out.count - 1] = r } else { out.append(r) }
        }
        return out
    }

    /// Seconds each reading stands for: until the next one (≤ 15 min); the last one the spacing just before it
    /// (1 s…15 min, 60 s if it's alone), at most until `to`, at least 1 s.
    static func weights(_ rs: [GraphReading], to: TimeInterval) -> [Double] {
        guard !rs.isEmpty else { return [] }
        let n = rs.count
        let before = n > 1 ? min(max(rs[n - 1].t - rs[n - 2].t, 1), maxGap) : 60
        return rs.indices.map { i in
            i < n - 1 ? min(rs[i + 1].t - rs[i].t, maxGap) : max(1, min(before, max(0, to - rs[i].t)))
        }
    }

    static func timeInRange(_ all: [GraphReading], from: TimeInterval, to: TimeInterval, bands b: Bands) -> TimeInRange
    {
        let rs = window(all, from: from, to: to)
        let w = weights(rs, to: to)
        var sum = Array(repeating: 0.0, count: Band.allCases.count)
        for (i, r) in rs.enumerated() { sum[b.of(Double(r.sgv)).rawValue] += w[i] }
        let total = sum.reduce(0, +)
        guard total > 0 else { return .none }
        return TimeInRange(
            veryLow: sum[0] / total, low: sum[1] / total, tight: sum[2] / total, target: sum[3] / total,
            high: sum[4] / total, veryHigh: sum[5] / total, coverage: min(1, total / (to - from)))
    }

    /// The distribution beside the graph ("time at level, band-exact"): between two readings ≤ 15 min apart glucose is
    /// taken to move linearly, so their time is spread over every level passed; lightly smoothed (σ 4 mg/dL), then each
    /// band rescaled so its area equals its time share. Index = mg/dL − minMg; sums to 1.
    static func timeAtLevel(
        _ all: [GraphReading], from: TimeInterval, to: TimeInterval, bands b: Bands, tir: TimeInRange
    ) -> [Double] {
        let n = maxMg - minMg + 1
        let rs = window(all, from: from, to: to)
        if rs.isEmpty { return Array(repeating: 0, count: n) }
        var d = smooth(occupation(rs), s: 4)
        let s = d.reduce(0, +)
        if s > 0 { d = d.map { $0 / s } }
        // each band's area, then each level scaled to its band's share (no per-band lists: a scrolled chart does this every frame)
        var area = [Double](repeating: 0, count: Band.allCases.count)
        for i in 0..<n { area[b.of(Double(minMg + i)).rawValue] += d[i] }
        for i in 0..<n {
            let band = b.of(Double(minMg + i))
            if area[band.rawValue] > 0 { d[i] *= tir.share(band) / area[band.rawValue] }
        }
        return d
    }

    private static func occupation(_ rs: [GraphReading]) -> [Double] {
        var out = Array(repeating: 0.0, count: maxMg - minMg + 1)
        for i in 0..<(rs.count - 1) {
            let a = rs[i], c = rs[i + 1]
            let dt = c.t - a.t
            if dt <= 0 || dt > maxGap { if let k = idx(a.sgv) { out[k] += 60 }; continue }
            let lo = min(a.sgv, c.sgv), hi = max(a.sgv, c.sgv)
            let levels = Double(hi - lo + 1)
            for g in lo...hi { if let k = idx(g) { out[k] += dt / levels } }
        }
        if let k = idx(rs[rs.count - 1].sgv) { out[k] += 60 }
        return out
    }

    private static func idx(_ mg: Int) -> Int? { (0...(maxMg - minMg)).contains(mg - minMg) ? mg - minMg : nil }

    private static func smooth(_ d: [Double], s: Double) -> [Double] {
        let r = Int(3 * s)
        let k = (0...(2 * r)).map { i -> Double in
            let z = Double(i - r) / s; return exp(-0.5 * z * z)
        }
        return d.indices.map { i in
            var acc = 0.0, ws = 0.0
            for j in -r...r { let ii = i + j; if ii >= 0 && ii < d.count { acc += d[ii] * k[j + r]; ws += k[j + r] } }
            return acc / ws
        }
    }
}
