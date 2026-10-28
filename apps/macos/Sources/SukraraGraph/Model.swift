// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import CoreGraphics
import CoreText
import Foundation

// MARK: - Model

/// The user's ranges, mg/dL: very low < `veryLow` ≤ low < `low` ≤ in range ≤ `high` < high ≤ `veryHigh` < very high
/// "very good" (tight) = `low`…`tight`, inside in range.
/// `extremelyHigh` (≥, like the extremely high alert) only colours the line and the number; time in range counts it as
/// very high (LibreView's five ranges).
public struct Bands: Codable, Equatable, Hashable, Sendable {
    public var veryLow = 54, low = 70, tight = 140, high = 180, veryHigh = 250, extremelyHigh = 300

    func of(_ mgdl: Double) -> Band {
        if mgdl < Double(veryLow) { return .veryLow }
        if mgdl < Double(low) { return .low }
        if mgdl <= Double(tight) { return .tight }
        if mgdl <= Double(high) { return .target }
        if mgdl <= Double(veryHigh) { return .high }
        return .veryHigh
    }

    /// Clamped so the order always holds.
    func sane() -> Bands {
        let l = min(max(low, 40), 120)
        let h = min(max(high, l + 20), 300)
        let vh = min(max(veryHigh, h + 10), 400)
        return Bands(
            veryLow: min(max(veryLow, 30), l - 5), low: l, tight: min(max(tight, l + 10), h), high: h,
            veryHigh: vh, extremelyHigh: min(max(extremelyHigh, vh + 10), 450))
    }
    public init(
        veryLow: Int = 54, low: Int = 70, tight: Int = 140, high: Int = 180, veryHigh: Int = 250,
        extremelyHigh: Int = 300
    ) {
        self.veryLow = veryLow
        self.low = low
        self.tight = tight
        self.high = high
        self.veryHigh = veryHigh
        self.extremelyHigh = extremelyHigh
    }
}

/// `.target` = in range but above the tight band; "in range" = tight + target; below = veryLow + low; above =
/// high + veryHigh.
public enum Band: Int, CaseIterable, Codable, Sendable {
    case veryLow, low, tight, target, high, veryHigh
    /// 0 = below range, 1 = in range, 2 = above.
    var group: Int {
        switch self {
        case .veryLow, .low: 0
        case .tight, .target: 1
        case .high, .veryHigh: 2
        }
    }
    /// The Kotlin enum spelling (settings, `ringOrder`).
    var name: String { ["VERY_LOW", "LOW", "TIGHT", "TARGET", "HIGH", "VERY_HIGH"][rawValue] }
    var label: String { ["Very low", "Low", "Very good", "In range", "High", "Very high"][rawValue] }
    init?(name: String) {
        guard let b = Band.allCases.first(where: { $0.name == name }) else { return nil }
        self = b
    }
}

/// The time-in-range ring's segments clockwise from 12 o'clock (the user's order): the good part first, then down
/// through the lows and back up through the highs.
public let DEFAULT_RING_ORDER: [Band] = [.tight, .target, .low, .veryLow, .high, .veryHigh]

/// "TIGHT,TARGET,…" with each of the six bands exactly once, else nil.
func parseRingOrder(_ text: String) -> [Band]? {
    let o = text.split(separator: ",").compactMap { Band(name: String($0).trimmingCharacters(in: .whitespaces)) }
    return o.count == 6 && Set(o).count == 6 ? o : nil
}
func ringOrderText(_ o: [Band]) -> String { o.map(\.name).joined(separator: ",") }

public enum TirRange: String, Codable, CaseIterable, Sendable {
    case inRange = "IN_RANGE", tight = "TIGHT"
    var label: String { self == .tight ? "Very good" : "In range" }
}

/// Share of time per band in a window (each reading stands for the time until the next one, capped at 15 min).
/// `low` / `high` are the LOW / HIGH bands only; `below` / `above` include the very low / very high ones.
struct TimeInRange: Codable, Equatable, Sendable {
    var veryLow = 0.0, low = 0.0, tight = 0.0, target = 0.0, high = 0.0, veryHigh = 0.0, coverage = 0.0
    /// Exactly 1 when nothing is below or above (the sum of two shares can miss it; 100 % is the crown, `TirMark`).
    var inRange: Double { below <= 0 && above <= 0 && !empty ? 1 : tight + target }
    var below: Double { veryLow + low }
    var above: Double { high + veryHigh }
    var empty: Bool { coverage <= 0 }
    func of(_ r: TirRange) -> Double { r == .inRange ? inRange : below + above + target <= 0 && !empty ? 1 : tight }
    func share(_ b: Band) -> Double {
        switch b {
        case .veryLow: veryLow
        case .low: low
        case .tight: tight
        case .target: target
        case .high: high
        case .veryHigh: veryHigh
        }
    }
    static let none = TimeInRange()
}

/// One reading for the graph: epoch seconds, mg/dL, rate mg/dL/min.
public struct GraphReading: Equatable, Sendable, Codable {
    public var t: TimeInterval
    public var v: Double
    public var rate: Double? = nil
    public var sgv: Int { Int(v.rounded()) }
    public init(t: TimeInterval, v: Double, rate: Double? = nil) {
        self.t = t
        self.v = v
        self.rate = rate
    }
}

/// A treatment as the chart draws it (rapid | long | carbs | note).
public struct GraphMark: Sendable, Equatable, Codable {
    public var t: TimeInterval
    public var k: String
    public var a: Double
    public var n: String
    /// The treatment's id: the label layout's tie-break, and the key of `CardAlerts.muteEnds`.
    public var id: String

    public init(t: TimeInterval, k: String, a: Double, n: String = "", id: String = "") {
        self.t = t
        self.k = k
        self.a = a
        self.n = n
        self.id = id
    }
}
