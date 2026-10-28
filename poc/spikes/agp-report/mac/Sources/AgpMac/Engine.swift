// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation

/// Swift port of spikes/agp-report/src/main/kotlin/app/mauri/agp/Agp.kt (same rules, same names). In Sukrara it would
/// sit next to SukraraGraph.swift; `selftest` proves it computes the fixtures' numbers.
struct Reading: Equatable { let t: Int64; let sgv: Int }

struct Bands {
    var veryLow = 54, low = 70, tight = 140, high = 180, veryHigh = 250
    /// 0 very low, 1 low, 2 tight, 3 target, 4 high, 5 very high
    func of(_ mg: Double) -> Int {
        if mg < Double(veryLow) { return 0 }
        if mg < Double(low) { return 1 }
        if mg <= Double(tight) { return 2 }
        if mg <= Double(high) { return 3 }
        if mg <= Double(veryHigh) { return 4 }
        return 5
    }
}

struct AgpOptions { var gridS: Int64 = 300, binMinutes = 60, maxGapS: Int64 = 900; var bins: Int { 1440 / binMinutes } }

struct Period {
    let zone: TimeZone, first: DateComponents, days: Int
    var cal: Calendar { var c = Calendar(identifier: .gregorian); c.timeZone = zone; return c }
    func midnight(_ k: Int) -> Int64 {
        let d = cal.date(byAdding: .day, value: k, to: cal.date(from: first)!)!
        return Int64(cal.startOfDay(for: d).timeIntervalSince1970)
    }
    var from: Int64 { midnight(0) }
    var to: Int64 { midnight(days) }
}

struct Metrics {
    var shares = [Double](repeating: 0, count: 6), active = 0.0, seconds = 0.0, mean = 0.0, sd = 0.0, readings = 0
    var cv: Double { mean > 0 ? sd / mean * 100 : 0 }
    var gmi: Double { 3.31 + 0.02392 * mean }
}

struct Bin { let n: Int; let p: [Double?] }   // p5, p25, p50, p75, p95

struct Day { let date: String; let from: Int64; let to: Int64; let active: Double; let inRange: Double?; let segments: Int; let points: Int; let noon: Double }

struct Sufficiency { let hours: Double; let daysWithData: Int; let best7Active: Double; let report: Bool; let profile: Bool; let consensus: Bool }

struct Report { let metrics: Metrics; let sufficiency: Sufficiency; let raw: [Bin]; let profile: [Bin]; let days: [Day] }

enum Agp {
    static func window(_ all: [Reading], _ from: Int64, _ to: Int64) -> [Reading] {
        var out: [Reading] = []
        for r in all.filter({ $0.t >= from && $0.t < to }).sorted(by: { $0.t < $1.t }) {
            if let last = out.last, r.t - last.t < 30 { out[out.count - 1] = r } else { out.append(r) }
        }
        return out
    }

    static func weights(_ rs: [Reading], _ to: Int64, _ maxGap: Int64) -> [Int64] {
        guard !rs.isEmpty else { return [] }
        let spacing = rs.count > 1 ? min(max(rs[rs.count - 1].t - rs[rs.count - 2].t, 1), maxGap) : 60
        return rs.indices.map { i in
            i < rs.count - 1 ? min(rs[i + 1].t - rs[i].t, maxGap) : max(1, min(spacing, to - rs[i].t))
        }
    }

    static func quantile(_ s: [Double], _ p: Double) -> Double {
        let h = Double(s.count - 1) * p
        let lo = Int(h.rounded(.down)), hi = min(lo + 1, s.count - 1)
        return s[lo] + (h - Double(lo)) * (s[hi] - s[lo])
    }

    static func smooth(_ bins: [Bin]) -> [Bin] {
        let n = bins.count
        return bins.indices.map { i in
            Bin(n: bins[i].n, p: (0..<5).map { k -> Double? in
                guard let c = bins[i].p[k] else { return nil }
                let p = bins[(i - 1 + n) % n].p[k], x = bins[(i + 1) % n].p[k]
                switch (p, x) {
                case (nil, nil): return c
                case (nil, let x?): return (4 * c + x) / 5
                case (let p?, nil): return (p + 4 * c) / 5
                case (let p?, let x?): return (p + 4 * c + x) / 6
                }
            })
        }
    }

    static func report(_ all: [Reading], _ p: Period, _ b: Bands, _ o: AgpOptions) -> Report {
        let from = p.from, to = p.to
        let rs = window(all, from, to)
        let w = weights(rs, to, o.maxGapS)
        var m = Metrics()
        var total = 0.0, sum = 0.0
        for (i, r) in rs.enumerated() { m.shares[b.of(Double(r.sgv))] += Double(w[i]); total += Double(w[i]); sum += Double(r.sgv) * Double(w[i]) }
        if total > 0 {
            m.mean = sum / total
            m.sd = (rs.indices.reduce(0.0) { $0 + pow(Double(rs[$1].sgv) - m.mean, 2) * Double(w[$1]) } / total).squareRoot()
            m.shares = m.shares.map { $0 / total }
            m.active = min(1, total / Double(to - from)); m.seconds = total; m.readings = rs.count
        }
        // grid → modal-day bins
        var bins = [[Double]](repeating: [], count: o.bins)
        let cal = p.cal
        var j = -1
        var g = from
        while g < to {
            while j + 1 < rs.count && rs[j + 1].t <= g { j += 1 }
            if j >= 0 && g - rs[j].t < w[j] {
                let c = cal.dateComponents([.hour, .minute], from: Date(timeIntervalSince1970: TimeInterval(g)))
                bins[(c.hour! * 60 + c.minute!) / o.binMinutes].append(Double(rs[j].sgv))
            }
            g += o.gridS
        }
        let raw = bins.map { v -> Bin in
            guard !v.isEmpty else { return Bin(n: 0, p: [nil, nil, nil, nil, nil]) }
            let s = v.sorted()
            return Bin(n: v.count, p: [0.05, 0.25, 0.5, 0.75, 0.95].map { quantile(s, $0) })
        }
        // days
        let fmt = DateFormatter(); fmt.calendar = cal; fmt.timeZone = p.zone; fmt.dateFormat = "yyyy-MM-dd"; fmt.locale = Locale(identifier: "en_US_POSIX")
        var days: [Day] = []
        for k in 0..<p.days {
            let a = p.midnight(k), z = p.midnight(k + 1)
            let len = Double(z - a)
            var secs = 0.0, inR = 0.0, segs = 0, pts = 0
            var prev: Int64? = nil
            for (i, r) in rs.enumerated() where r.t >= a && r.t < z {
                let s = Double(min(w[i], z - r.t))
                secs += s
                if r.sgv >= b.low && r.sgv <= b.high { inR += s }
                if prev == nil || r.t - prev! > o.maxGapS { segs += 1 }
                pts += 1; prev = r.t
            }
            let dayDate = Date(timeIntervalSince1970: TimeInterval(a))
            let noon = cal.date(bySettingHour: 12, minute: 0, second: 0, of: dayDate)!
            days.append(Day(date: fmt.string(from: dayDate), from: a, to: z, active: secs / len, inRange: secs > 0 ? inR / secs : nil,
                            segments: segs, points: pts, noon: (noon.timeIntervalSince1970 - Double(a)) / len))
        }
        let withData = days.filter { $0.active * Double($0.to - $0.from) >= 3600 }.map { $0.active }
        let best7 = Array(withData.sorted(by: >).prefix(7))
        let best7m = best7.isEmpty ? 0 : best7.reduce(0, +) / Double(best7.count)
        let hours = m.seconds / 3600
        let suff = Sufficiency(hours: hours, daysWithData: withData.count, best7Active: best7m, report: hours >= 24,
                               profile: hours >= 24 && best7.count >= 7 && best7m >= 0.70, consensus: p.days >= 14 && m.active >= 0.70)
        return Report(metrics: m, sufficiency: suff, raw: raw, profile: smooth(raw), days: days)
    }
}
