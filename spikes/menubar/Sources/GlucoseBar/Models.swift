// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation

struct Reading: Equatable {
    var date: Date
    var mgdl: Double
    /// Rate reported by the source (mg/dL/min), if any. Libre 3 computes one on-sensor.
    var rate: Double? = nil
    /// Nightscout direction string, used only as a last-resort fallback.
    var direction: String? = nil
}

struct Snapshot {
    var latest: Reading
    var history: [Reading]          // ascending by date, includes latest
    var source: String

    /// Best available rate of change in mg/dL/min:
    /// 1. rate reported by the source, 2. least-squares slope over the last 15 min, 3. NS direction.
    var rate: Double? {
        if let r = latest.rate, r.isFinite { return r }
        if let r = Trend.slope(history, window: 15 * 60, endingAt: latest.date) { return r }
        return latest.direction.flatMap(Trend.nominalRate(direction:))
    }
}

enum Trend {
    /// Least-squares slope (mg/dL/min) over readings within `window` seconds before `end`.
    static func slope(_ readings: [Reading], window: TimeInterval, endingAt end: Date) -> Double? {
        let pts = readings.filter { $0.date <= end && end.timeIntervalSince($0.date) <= window }
        guard pts.count >= 3,
              let first = pts.first?.date, let last = pts.last?.date,
              last.timeIntervalSince(first) >= 4 * 60 else { return nil }
        let xs = pts.map { $0.date.timeIntervalSince(end) / 60 }
        let ys = pts.map(\.mgdl)
        let mx = xs.reduce(0, +) / Double(xs.count), my = ys.reduce(0, +) / Double(ys.count)
        let sxx = zip(xs, xs).map { ($0 - mx) * ($1 - mx) }.reduce(0, +)
        let sxy = zip(xs, ys).map { ($0 - mx) * ($1 - my) }.reduce(0, +)
        return sxx > 0 ? sxy / sxx : nil
    }

    static func nominalRate(direction: String) -> Double? {
        switch direction {
        case "DoubleUp": 3.5
        case "SingleUp": 2.5
        case "FortyFiveUp": 1.5
        case "Flat": 0
        case "FortyFiveDown": -1.5
        case "SingleDown": -2.5
        case "DoubleDown": -3.5
        default: nil
        }
    }
}

enum GlucoseUnit: String {
    case mgdl, mmol
    func format(_ mgdl: Double) -> String {
        switch self {
        case .mgdl: String(Int(mgdl.rounded()))
        case .mmol: String(format: "%.1f", mgdl / 18.0182)
        }
    }
    func formatRate(_ r: Double) -> String {
        switch self {
        case .mgdl: String(format: "%+.1f mg/dL/min", r)
        case .mmol: String(format: "%+.2f mmol/L/min", r / 18.0182)
        }
    }
}
