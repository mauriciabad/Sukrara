// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import AppKit
import CoreGraphics
import CoreText

// Investigation 16: the glucose card of every Sukrara surface, the Mac half. A port of the Android renderer
// (spikes/showcase-android/.../graph/: GlucoseStats.kt, Freshness.kt, GlucoseGraph.kt, GlucoseCard.kt) with the same
// math, layout constants and colours, drawn with CoreGraphics + CoreText into any y-down CGContext: a flipped NSView
// (menubar dropdown), a bitmap (widgets, Render CLI). Compiled into the menubar app (symlink in
// spikes/menubar/Sources/GlucoseBar), the widget extension and the Render CLI.

// MARK: - Model

/// The user's ranges, mg/dL: very low < `veryLow` ≤ low < `low` ≤ in range ≤ `high` < high ≤ `veryHigh` < very high;
/// "very good" (tight) = `low`…`tight`, inside in range.
/// `extremelyHigh` (≥, like the extremely high alert) only colours the line and the number; time in range counts it as
/// very high (LibreView's five ranges).
struct Bands: Codable, Equatable, Hashable {
    var veryLow = 54, low = 70, tight = 140, high = 180, veryHigh = 250, extremelyHigh = 300

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
        return Bands(veryLow: min(max(veryLow, 30), l - 5), low: l, tight: min(max(tight, l + 10), h), high: h,
                     veryHigh: vh, extremelyHigh: min(max(extremelyHigh, vh + 10), 450))
    }
}

/// `.target` = in range but above the tight band; "in range" = tight + target; below = veryLow + low; above =
/// high + veryHigh.
enum Band: Int, CaseIterable, Codable {
    case veryLow, low, tight, target, high, veryHigh
    /// 0 = below range, 1 = in range, 2 = above.
    var group: Int { switch self { case .veryLow, .low: 0; case .tight, .target: 1; case .high, .veryHigh: 2 } }
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
let DEFAULT_RING_ORDER: [Band] = [.tight, .target, .low, .veryLow, .high, .veryHigh]

/// "TIGHT,TARGET,…" with each of the six bands exactly once, else nil.
func parseRingOrder(_ text: String) -> [Band]? {
    let o = text.split(separator: ",").compactMap { Band(name: String($0).trimmingCharacters(in: .whitespaces)) }
    return o.count == 6 && Set(o).count == 6 ? o : nil
}
func ringOrderText(_ o: [Band]) -> String { o.map(\.name).joined(separator: ",") }

enum TirRange: String, Codable, CaseIterable {
    case inRange = "IN_RANGE", tight = "TIGHT"
    var label: String { self == .tight ? "Very good" : "In range" }
}

/// Share of time per band in a window (each reading stands for the time until the next one, capped at 15 min).
/// `low` / `high` are the LOW / HIGH bands only; `below` / `above` include the very low / very high ones.
struct TimeInRange: Codable, Equatable {
    var veryLow = 0.0, low = 0.0, tight = 0.0, target = 0.0, high = 0.0, veryHigh = 0.0, coverage = 0.0
    var inRange: Double { tight + target }
    var below: Double { veryLow + low }
    var above: Double { high + veryHigh }
    var empty: Bool { coverage <= 0 }
    func of(_ r: TirRange) -> Double { r == .tight ? tight : inRange }
    func share(_ b: Band) -> Double {
        switch b {
        case .veryLow: veryLow; case .low: low; case .tight: tight; case .target: target; case .high: high; case .veryHigh: veryHigh
        }
    }
    static let none = TimeInRange()
}

/// One reading for the graph: epoch seconds, mg/dL, rate mg/dL/min.
struct GraphReading: Equatable {
    var t: TimeInterval
    var v: Double
    var rate: Double? = nil
    var sgv: Int { Int(v.rounded()) }
}

/// A treatment as the chart draws it (rapid | long | carbs | note).
struct GraphMark: Equatable {
    var t: TimeInterval
    var k: String
    var a: Double
    var n: String = ""
}

enum GlucoseStats {
    static let maxGap: TimeInterval = 15 * 60
    static let minMg = 20, maxMg = 450

    /// Readings in [from, to + 30 s), sorted, deduplicated (±30 s, the later one wins).
    static func window(_ all: [GraphReading], from: TimeInterval, to: TimeInterval) -> [GraphReading] {
        var out: [GraphReading] = []
        for r in all.filter({ $0.t >= from && $0.t < to + 30 }).sorted(by: { $0.t < $1.t }) {
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

    static func timeInRange(_ all: [GraphReading], from: TimeInterval, to: TimeInterval, bands b: Bands) -> TimeInRange {
        let rs = window(all, from: from, to: to)
        let w = weights(rs, to: to)
        var sum = Array(repeating: 0.0, count: Band.allCases.count)
        for (i, r) in rs.enumerated() { sum[b.of(Double(r.sgv)).rawValue] += w[i] }
        let total = sum.reduce(0, +)
        guard total > 0 else { return .none }
        return TimeInRange(veryLow: sum[0] / total, low: sum[1] / total, tight: sum[2] / total, target: sum[3] / total,
                           high: sum[4] / total, veryHigh: sum[5] / total, coverage: min(1, total / (to - from)))
    }

    /// The distribution beside the graph ("time at level, band-exact"): between two readings ≤ 15 min apart glucose is
    /// taken to move linearly, so their time is spread over every level passed; lightly smoothed (σ 4 mg/dL), then each
    /// band rescaled so its area equals its time share. Index = mg/dL − minMg; sums to 1.
    static func timeAtLevel(_ all: [GraphReading], from: TimeInterval, to: TimeInterval, bands b: Bands, tir: TimeInRange) -> [Double] {
        let n = maxMg - minMg + 1
        let rs = window(all, from: from, to: to)
        if rs.isEmpty { return Array(repeating: 0, count: n) }
        var d = smooth(occupation(rs), s: 4)
        let s = d.reduce(0, +)
        if s > 0 { d = d.map { $0 / s } }
        for band in Band.allCases {
            let ks = (0..<n).filter { b.of(Double(minMg + $0)) == band }
            let area = ks.reduce(0) { $0 + d[$1] }
            if area > 0 { for k in ks { d[k] *= tir.share(band) / area } }
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
        let k = (0...(2 * r)).map { i -> Double in let z = Double(i - r) / s; return exp(-0.5 * z * z) }
        return d.indices.map { i in
            var acc = 0.0, ws = 0.0
            for j in -r...r { let ii = i + j; if ii >= 0 && ii < d.count { acc += d[ii] * k[j + r]; ws += k[j + r] } }
            return acc / ws
        }
    }
}

/// How old the latest reading is decides what a surface shows (see Freshness.kt for the table).
enum Freshness {
    case fresh, late, stale, old, gone
    static let lateS: TimeInterval = 90, staleS: TimeInterval = 5 * 60, oldS: TimeInterval = 20 * 60, goneS: TimeInterval = 3 * 3600

    static func of(age: TimeInterval?) -> Freshness {
        guard let a = age else { return .gone }
        if a <= lateS { return .fresh }
        if a <= staleS { return .late }
        if a <= oldS { return .stale }
        if a <= goneS { return .old }
        return .gone
    }
    var showValue: Bool { self == .fresh || self == .late || self == .stale }
    var showTime: Bool { self != .fresh }
    var strike: Bool { self == .stale }
    var showGraph: Bool { self != .gone }

    /// The next age at which `of` changes (for a one-shot timer / timeline entry).
    static func nextChange(age: TimeInterval) -> TimeInterval? { [lateS, staleS, oldS, goneS].first { $0 >= age }.map { $0 + 1 } }

    /// "14:05" today, "Tue 14:05" this week, "12 Oct" before. A clock time doesn't tick.
    static func timeText(_ t: TimeInterval, now: TimeInterval) -> String {
        let d = Date(timeIntervalSince1970: t), n = Date(timeIntervalSince1970: now)
        let time = d.formatted(date: .omitted, time: .shortened)
        if Calendar.current.isDate(d, inSameDayAs: n) { return time }
        if now - t < 6 * 86400 { return d.formatted(.dateTime.weekday(.abbreviated)) + " " + time }
        return d.formatted(.dateTime.day().month(.abbreviated))
    }
}

// MARK: - Style

/// Colours (same hex values as GraphPalette.kt).
struct GraphPalette {
    var dark: Bool
    var bg, ink, inkSoft, grid, veryLow, low, tight, target, high, veryHigh, extremelyHigh: CGColor
    /// How strong the chart's decoration is (grid, dashed 54/250 lines, the in-range band): 1 normally. A tinted
    /// (accented / vibrant) macOS widget turns the bitmap's luminance into opacity, so a faint grey line comes out
    /// nearly solid there; `tinted()` lowers it.
    var decor: CGFloat = 1
    /// Tinted rendering: the system keeps a pixel's opacity (accented) or its luminance × opacity (vibrant) and drops
    /// its hue, so dark text on a filled pill comes out as solid as the pill. With `knockout` the text and icons on
    /// filled shapes are cut out of them instead (transparent), and the "background-coloured" gaps are cut too.
    var knockout = false

    /// For the tinted widget rendering modes.
    func tinted() -> GraphPalette { var p = self; p.decor = 0.25; p.knockout = true; return p }

    func of(_ b: Band) -> CGColor {
        switch b {
        case .veryLow: veryLow; case .low: low; case .tight: tight; case .target: target; case .high: high; case .veryHigh: veryHigh
        }
    }

    /// The colour of a glucose value: its band's, or `extremelyHigh` from `Bands.extremelyHigh` up.
    func at(_ b: Bands, _ mgdl: Double) -> CGColor { mgdl >= Double(b.extremelyHigh) ? extremelyHigh : of(b.of(mgdl)) }

    static func hex(_ v: UInt32) -> CGColor {
        CGColor(srgbRed: CGFloat((v >> 16) & 0xFF) / 255, green: CGFloat((v >> 8) & 0xFF) / 255, blue: CGFloat(v & 0xFF) / 255, alpha: 1)
    }
    static let darkPalette = GraphPalette(dark: true, bg: hex(0x1C1C1E), ink: hex(0xF2F2F2), inkSoft: hex(0xA3A3A8), grid: hex(0x3A3A3E),
                                          veryLow: hex(0xC2413B), low: hex(0xF0524F), tight: hex(0x3DD47A), target: hex(0x8CC63F),
                                          high: hex(0xF5A524), veryHigh: hex(0xFF7A45), extremelyHigh: hex(0xE35BD0))
    static let lightPalette = GraphPalette(dark: false, bg: hex(0xFFFFFF), ink: hex(0x1C1C1E), inkSoft: hex(0x6B6B70), grid: hex(0xDADADF),
                                           veryLow: hex(0xB3261E), low: hex(0xD92D2A), tight: hex(0x17A34A), target: hex(0x6E9E1E),
                                           high: hex(0xD97706), veryHigh: hex(0xC2410C), extremelyHigh: hex(0xA3238F))
    static func of(dark: Bool) -> GraphPalette { dark ? darkPalette : lightPalette }
}

struct GraphStyle {
    var window: TimeInterval = 12 * 3600
    var bands = Bands()
    /// The time in range beside the graph: the distribution dots and the % labels.
    var side = true
    var axisLabels = true
    var treatments = true
    var hour24 = GraphStyle.systemHour24
    /// Extra dashed lines, mg/dL (`GraphConfig.lines`).
    var lines = GraphConfig.defaultLines
    /// Hours between the vertical lines, at least (`GraphConfig.gridHours`); 0 = none (the time labels stay).
    var gridHours = 1

    static var systemHour24: Bool {
        !(DateFormatter.dateFormat(fromTemplate: "j", options: 0, locale: .current) ?? "").contains("a")
    }
}

/// What a slot beside the value shows (investigation 18): a time-in-range ring or one of the report's numbers.
enum SlotKind: String, Codable, CaseIterable {
    case tir = "TIR", gmi = "GMI", average = "AVERAGE", cv = "CV"
    var label: String {
        switch self { case .tir: "Time in range"; case .gmi: "GMI"; case .average: "Average glucose"; case .cv: "Variability (CV)" }
    }
    var short: String { switch self { case .tir: ""; case .gmi: "GMI"; case .average: "avg"; case .cv: "CV" } }
}

/// One slot beside the value: its kind, window and, for a ring, which range its centre counts. `faded` = drawn
/// fainter by position (second, third); off = full strength wherever it sits.
struct RingSpec: Codable, Equatable {
    var window: TimeInterval
    var range: TirRange = .inRange
    var kind: SlotKind = .tir
    var faded = true
    /// A ring's segment order (the app-wide `ringOrder`).
    var order: [Band] = DEFAULT_RING_ORDER
    var period: String { periodLabel(window) }
}

/// The doctor's report numbers for one window (GlucoseMetrics.kt): time in the five ranges, time-weighted mean and
/// SD, CV, GMI.
struct GlucoseMetrics: Codable, Equatable {
    var tir: TimeInRange
    var mean: Double
    var sd: Double
    var readings: Int
    var cv: Double { mean > 0 ? sd / mean * 100 : 0 }
    /// Glucose Management Indicator, % (Bergenstal 2018).
    var gmi: Double { 3.31 + 0.02392 * mean }
    var gmiMmolMol: Double { (gmi - 2.152) * 10.929 }
    var empty: Bool { readings == 0 }

    static func of(_ all: [GraphReading], from: TimeInterval, to: TimeInterval, bands b: Bands) -> GlucoseMetrics {
        let rs = GlucoseStats.window(all, from: from, to: to)
        let w = GlucoseStats.weights(rs, to: to)
        let total = w.reduce(0, +)
        let tir = GlucoseStats.timeInRange(all, from: from, to: to, bands: b)
        guard total > 0 else { return GlucoseMetrics(tir: tir, mean: 0, sd: 0, readings: 0) }
        let mean = rs.indices.reduce(0.0) { $0 + Double(rs[$1].sgv) * w[$1] } / total
        let variance = rs.indices.reduce(0.0) { let d = Double(rs[$1].sgv) - mean; return $0 + d * d * w[$1] } / total
        return GlucoseMetrics(tir: tir, mean: mean, sd: variance.squareRoot(), readings: rs.count)
    }
}

/// The international consensus targets (Battelino 2019) LibreView prints as "Goals for type 1 and type 2 diabetes".
enum Goal: CaseIterable {
    case veryHigh, above, inRange, below, veryLow, average, gmi, cv
    static let minCoverage = 0.70
    var text: String {
        switch self {
        case .veryHigh: "< 5%"; case .above: "< 25%"; case .inRange: "> 70%"; case .below: "< 4%"; case .veryLow: "< 1%"
        case .average: "< 154 mg/dL"; case .gmi: "< 7%"; case .cv: "≤ 36%"
        }
    }
    func met(_ m: GlucoseMetrics) -> Bool {
        switch self {
        case .veryHigh: m.tir.veryHigh < 0.05
        case .above: m.tir.above < 0.25
        case .inRange: m.tir.inRange > 0.70
        case .below: m.tir.below < 0.04
        case .veryLow: m.tir.veryLow < 0.01
        case .average: m.mean < 154
        case .gmi: m.gmi < 7.0
        case .cv: m.cv <= 36.0
        }
    }
}

func periodLabel(_ s: TimeInterval) -> String {
    let i = Int(s)
    return i % 86400 == 0 ? "\(i / 86400)d" : "\(i / 3600)h"
}

/// What a whole glucose card shows: value header and/or chart, up to three rings (main first), the last-treatment slot
/// (`last`, with `lastElse` while there's none) and the status badge under the value (`badge`).
struct CardStyle {
    var graph: GraphStyle? = GraphStyle()
    var rings: [RingSpec] = [RingSpec(window: 86400), RingSpec(window: 7 * 86400)]
    var value = true
    var last: LastPlace = .off
    var lastElse: RingSpec? = nil
    var badge = true
}

/// Everything the graph settings decide (UserDefaults on the Mac; the widget gets it in its snapshot).
struct GraphConfig: Codable, Equatable {
    var bands = Bands()
    var hours = 12
    var ringCount = 2
    var slots: [RingSpec] = GraphConfig.defaultSlots
    var ringRange: TirRange = .inRange
    var ringOrder: [Band] = DEFAULT_RING_ORDER
    var side = true
    /// Extra horizontal lines on the chart, mg/dL (100 by default; the user adds and removes them).
    var lines = GraphConfig.defaultLines
    /// Hours between the vertical lines at least (`gridChoices`; small charts space them further apart), 0 = none.
    var gridHours = 1
    /// The last-treatment slot beside the value (on by default, next to the number).
    var lastPlace: LastPlace = .nearValue
    /// What that slot shows while there's no recent treatment (nil = nothing): a 30-day time-in-range ring by default.
    var lastElse: RingSpec? = GraphConfig.defaultLastElse

    static let defaultLastElse = RingSpec(window: 30 * 86400)
    /// `lastElse` with the ring settings applied.
    var lastElseRing: RingSpec? { lastElse.map { var r = $0; r.range = ringRange; r.order = ringOrder; return r } }
    /// The card style of a surface with a value: the rings, the last-treatment slot and its fallback.
    func cardStyle(graph g: GraphStyle?, rings: [RingSpec]? = nil) -> CardStyle {
        CardStyle(graph: g, rings: rings ?? self.rings, value: true, last: lastPlace, lastElse: lastElseRing)
    }

    static let gridChoices = [0, 1, 2, 3, 4, 6, 12]
    static let defaultLines = [100]
    static let maxLines = 6
    static let lineRange = 40...400
    /// "100,120" → sorted distinct values inside `lineRange`, at most `maxLines`.
    static func parseLines(_ text: String) -> [Int] {
        Array(Set(text.split(separator: ",").compactMap { Int($0.trimmingCharacters(in: .whitespaces)) }.filter(lineRange.contains))
            .sorted().prefix(maxLines))
    }
    static func linesText(_ l: [Int]) -> String { l.map(String.init).joined(separator: ",") }

    static let windowHours = [3, 6, 12, 24]
    static let ringChoices: [TimeInterval] = [12 * 3600, 86400, 3 * 86400, 7 * 86400, 14 * 86400, 30 * 86400]
    /// A slot window the settings accept: a preset, or a custom whole number of days 1–90 (Android `validRingWindow`).
    static func validRingWindow(_ w: TimeInterval) -> Bool {
        ringChoices.contains(w) || (w.truncatingRemainder(dividingBy: 86400) == 0 && (1...90).contains(Int(w) / 86400))
    }
    /// Today and the last week as time in range; the third slot (off by default) the 14-day GMI, as on the report.
    static let defaultSlots = [RingSpec(window: 86400), RingSpec(window: 7 * 86400), RingSpec(window: 14 * 86400, kind: .gmi)]

    var rings: [RingSpec] { slots.prefix(max(0, min(3, ringCount))).map { var r = $0; r.range = ringRange; r.order = ringOrder; return r } }

    /// "TIR:86400:1,TIR:604800:1,GMI:1209600:1" (kind:window s:faded).
    static func slotsText(_ s: [RingSpec]) -> String { s.map { "\($0.kind.rawValue):\(Int($0.window)):\($0.faded ? 1 : 0)" }.joined(separator: ",") }
    static func parseSlots(_ text: String) -> [RingSpec]? {
        let s = text.split(separator: ",").compactMap { parseSlot(String($0)) }
        return s.count == 3 ? s : nil
    }
    /// One slot, "TIR:2592000:1"; nil when malformed (or "" = none, for `lastElse`).
    static func parseSlot(_ e: String) -> RingSpec? {
        let p = e.split(separator: ":").map(String.init)
        guard p.count > 1, let kind = SlotKind(rawValue: p[0]), let w = TimeInterval(p[1]), validRingWindow(w) else { return nil }
        return RingSpec(window: w, kind: kind, faded: p.count < 3 || p[2] != "0")
    }
    func graphStyle(hours h: Int? = nil) -> GraphStyle {
        GraphStyle(window: TimeInterval((h ?? hours) * 3600), bands: bands, side: side, lines: lines, gridHours: gridHours)
    }

    /// Keys: graphHours (or a place's own `<place>Hours`), ringCount, slots (see `slotsText`), ringRange
    /// IN_RANGE|TIGHT, ringOrder "TIGHT,TARGET,LOW,VERY_LOW,HIGH,VERY_HIGH", bandVeryLow/bandLow/bandTight/bandHigh/
    /// bandVeryHigh/bandExtremelyHigh, side (time in range beside the graph), lines "100,120" (extra dashed lines,
    /// mg/dL), gridHours 0|1|2|3|4|6|12 (vertical lines, 0 = off), lastPlace OFF|NEAR_VALUE|END (the last-treatment
    /// slot), lastElse "TIR:2592000:1" or "" (its fallback; same spellings as Android's GraphConfig JSON).
    static func load(_ d: UserDefaults = .standard, place: String? = nil) -> GraphConfig {
        var c = GraphConfig()
        func int(_ k: String) -> Int? { d.object(forKey: k) != nil ? d.integer(forKey: k) : nil }
        c.bands = Bands(veryLow: int("bandVeryLow") ?? 54, low: int("bandLow") ?? 70, tight: int("bandTight") ?? 140,
                        high: int("bandHigh") ?? 180, veryHigh: int("bandVeryHigh") ?? 250,
                        extremelyHigh: int("bandExtremelyHigh") ?? 300).sane()
        let h = place.flatMap { int("\($0)Hours") } ?? int("graphHours") ?? 12
        c.hours = windowHours.contains(h) ? h : 12
        c.ringCount = max(0, min(3, int("ringCount") ?? 2))
        if let s = parseSlots(d.string(forKey: "slots") ?? "") { c.slots = s }
        c.ringRange = TirRange(rawValue: d.string(forKey: "ringRange") ?? "") ?? .inRange
        c.ringOrder = parseRingOrder(d.string(forKey: "ringOrder") ?? "") ?? DEFAULT_RING_ORDER
        c.side = d.object(forKey: "side") == nil ? true : d.bool(forKey: "side")
        c.lines = d.object(forKey: "lines") == nil ? defaultLines : parseLines(d.string(forKey: "lines") ?? "")
        c.gridHours = int("gridHours").flatMap { gridChoices.contains($0) ? $0 : nil } ?? 1
        c.lastPlace = LastPlace(rawValue: d.string(forKey: "lastPlace") ?? "") ?? .nearValue
        if let e = d.string(forKey: "lastElse") { c.lastElse = parseSlot(e) }
        return c
    }
}

// MARK: - Drawing helpers (y-down CGContext)

enum Ink {
    static func font(_ size: CGFloat, _ weight: NSFont.Weight, rounded: Bool = false) -> CTFont {
        var f = NSFont.systemFont(ofSize: size, weight: weight)
        if rounded, let desc = f.fontDescriptor.withDesign(.rounded) { f = NSFont(descriptor: desc, size: size) ?? f }
        return f as CTFont
    }
    static func line(_ s: String, _ font: CTFont, _ color: CGColor, strike: Bool = false) -> CTLine {
        var attrs: [NSAttributedString.Key: Any] = [.font: font, .foregroundColor: color]
        if strike { attrs[.strikethroughStyle] = NSUnderlineStyle.single.rawValue; attrs[.strikethroughColor] = color }
        return CTLineCreateWithAttributedString(NSAttributedString(string: s, attributes: attrs))
    }
    static func width(_ s: String, _ font: CTFont) -> CGFloat {
        CGFloat(CTLineGetTypographicBounds(line(s, font, GraphPalette.hex(0)), nil, nil, nil))
    }
    /// Draws with the baseline at `y` (y-down context). Strikethrough is drawn by hand (CTLineDraw doesn't).
    static func draw(_ ctx: CGContext, _ s: String, x: CGFloat, y: CGFloat, _ font: CTFont, _ color: CGColor,
                     align: CGFloat = 0, strike: Bool = false) {
        let l = line(s, font, color)
        let w = CGFloat(CTLineGetTypographicBounds(l, nil, nil, nil))
        let x0 = x - w * align
        ctx.saveGState()
        ctx.textMatrix = CGAffineTransform(scaleX: 1, y: -1)
        ctx.textPosition = CGPoint(x: x0, y: y)
        CTLineDraw(l, ctx)
        ctx.restoreGState()
        if strike {
            let sy = y - CTFontGetXHeight(font) * 0.55
            ctx.setStrokeColor(color)
            ctx.setLineWidth(max(1.5, CTFontGetSize(font) * 0.06))
            ctx.strokeLineSegments(between: [CGPoint(x: x0, y: sy), CGPoint(x: x0 + w, y: sy)])
        }
    }
}

private extension CGColor {
    func a(_ alpha: Int) -> CGColor { copy(alpha: CGFloat(alpha) / 255) ?? self }
    func a(_ alpha: CGFloat) -> CGColor { copy(alpha: alpha) ?? self }
}

// MARK: - Chart

/// The chart with the time-in-range side profile (GlucoseGraph.kt). `d` = points per dp (1 on the Mac). The level
/// lines and the glucose line sit in one transparency layer; the distribution's outline is erased from it
/// (`.destinationOut`), so the lines stop short of each row of dots on any background, transparent widgets included.
enum GlucoseGraph {
    /// Narrowest chart (pt) that still gets the % labels beside it.
    static let pctMinWidth: CGFloat = 240
    /// `alerts` = the rules behind the treatments' mute lines (nil = no lines).
    static func draw(_ ctx: CGContext, _ area: CGRect, readings: [GraphReading], marks: [GraphMark], now: TimeInterval,
                     _ st: GraphStyle, _ pal: GraphPalette, d: CGFloat = 1, alerts: AlertSettings? = nil) {
        let from = now - st.window
        let b = st.bands.sane()
        let pts = GlucoseStats.window(readings, from: from - 60, to: now)
        let tir = GlucoseStats.timeInRange(readings, from: from, to: now, bands: b)
        let text = Ink.font(10 * d, .regular)
        let pctBig = Ink.font(13 * d, .bold), pctSmall = Ink.font(8.5 * d, .regular)

        let labels = st.axisLabels
        // The % labels need room: none on small placements (a 1×1 / systemSmall widget), the dots stay.
        let pctCol = st.side && labels && !tir.empty && area.width >= Self.pctMinWidth * d
            ? Ink.width("100", pctBig) + Ink.width("%", pctSmall) + 6 * d : 0
        let left = area.minX + pctCol
        let right = area.maxX - (labels ? Ink.width("180", text) + 5 * d : 0)
        let top = area.minY + 3 * d
        let bottom = area.maxY - (labels ? 10 * d + 4 * d : 0)
        let shownMarks = st.treatments ? marks.filter { $0.t >= from && $0.t <= now } : []
        let plotBottom = shownMarks.isEmpty ? bottom : bottom - 12 * d
        guard right - left >= 20 * d, plotBottom - top >= 20 * d else { return }

        let dMin = pts.map(\.sgv).min() ?? b.low, dMax = pts.map(\.sgv).max() ?? b.high
        // The y range always reaches the very low / very high limits, so their lines always show. The user's own lines
        // don't change it: one outside the range isn't drawn.
        let yMin = max(Double(GlucoseStats.minMg), Double(min(b.low - 22, b.veryLow - 6, dMin - 6)))
        let yMax = min(Double(GlucoseStats.maxMg), Double(max(b.high + 40, b.veryHigh + 8, dMax + 8)))
        func x(_ t: TimeInterval) -> CGFloat { left + CGFloat((t - from) / st.window) * (right - left) }
        func y(_ v: Double) -> CGFloat { plotBottom - CGFloat((min(max(v, yMin), yMax) - yMin) / (yMax - yMin)) * (plotBottom - top) }
        let sideW: CGFloat = st.side && !tir.empty ? min((right - left) * 0.26, 130 * d) : 0

        // The in-range band across the whole plot; its very good part (low…tight) a little stronger, in its own colour.
        let split = b.tight < b.high
        let tightTop = y(Double(split ? b.tight : b.high))
        let bandDecor: CGFloat = pal.decor < 1 ? 0.5 : 1
        ctx.setFillColor(pal.tight.a(CGFloat(split ? (pal.dark ? 0x2A : 0x24) : (pal.dark ? 0x1E : 0x18)) / 255 * bandDecor))
        ctx.fill(CGRect(x: left, y: tightTop, width: right - left, height: y(Double(b.low)) - tightTop))
        if split {
            ctx.setFillColor(pal.target.a(CGFloat(pal.dark ? 0x16 : 0x14) / 255 * bandDecor))
            ctx.fill(CGRect(x: left, y: y(Double(b.high)), width: right - left, height: tightTop - y(Double(b.high))))
        }

        // Hour grid + labels: the user's step, or a longer one where labels would come closer than ~44 dp.
        let pxPerHour = (right - left) / CGFloat(st.window / 3600)
        let step = [1, 2, 3, 4, 6, 12, 24].filter { $0 >= st.gridHours }.first { CGFloat($0) * pxPerHour >= 44 * d } ?? 24
        let cal = Calendar.current
        var hour = cal.dateInterval(of: .hour, for: Date(timeIntervalSince1970: from))!.start.addingTimeInterval(3600)
        while cal.component(.hour, from: hour) % step != 0 { hour.addTimeInterval(3600) }
        ctx.saveGState()
        ctx.setStrokeColor(pal.grid.a(pal.decor))
        ctx.setLineWidth(1 * d)
        ctx.setLineDash(phase: 0, lengths: [2 * d, 3 * d])
        var lastLabelEnd = -CGFloat.infinity
        while hour.timeIntervalSince1970 <= now {
            let gx = x(hour.timeIntervalSince1970)
            if gx > left + sideW * 0.6 {
                if st.gridHours > 0 { ctx.strokeLineSegments(between: [CGPoint(x: gx, y: top), CGPoint(x: gx, y: plotBottom)]) }
                if labels {
                    let s = hourLabel(cal.component(.hour, from: hour), st.hour24)
                    let tw = Ink.width(s, text)
                    let lx = min(max(gx - tw / 2, left), right - tw)
                    if lx > lastLabelEnd + 6 * d {
                        Ink.draw(ctx, s, x: lx, y: area.maxY - 2 * d, text, pal.inkSoft)
                        lastLabelEnd = lx + tw
                    }
                }
            }
            hour.addTimeInterval(TimeInterval(step * 3600))
        }
        ctx.restoreGState()
        // The values on the right: low and high always, then very good, very low / very high and the extra lines,
        // each only where it doesn't touch one already written.
        var written: [CGFloat] = []
        for v in [b.low, b.high] + (split ? [b.tight] : []) + [b.veryLow, b.veryHigh, b.extremelyHigh] + st.lines
        where labels && Double(v) > yMin && Double(v) < yMax {
            let ly = y(Double(v))
            if written.contains(where: { abs($0 - ly) < 12 * d }) { continue }
            written.append(ly)
            Ink.draw(ctx, "\(v)", x: right + 4 * d, y: ly + 10 * d / 3, text, pal.inkSoft)
        }

        // Everything the distribution cuts goes into one layer: the dashed level lines and the glucose line.
        ctx.beginTransparencyLayer(in: area, auxiliaryInfo: nil)
        // Very low / very high / extremely high: faint dashed lines where the y range reaches them. The user's lines (100 by default) a
        // little stronger, so they still show on the in-range band.
        ctx.saveGState()
        ctx.setLineWidth(1 * d)
        ctx.setLineDash(phase: 0, lengths: [4 * d, 4 * d])
        for (vs, color) in [([b.veryLow, b.veryHigh, b.extremelyHigh], pal.grid.a(pal.decor)), (st.lines, pal.inkSoft.a(0.4 * pal.decor))] {
            ctx.setStrokeColor(color)
            for v in vs where Double(v) > yMin && Double(v) < yMax {
                ctx.strokeLineSegments(between: [CGPoint(x: left, y: y(Double(v))), CGPoint(x: right, y: y(Double(v)))])
            }
        }
        ctx.restoreGState()

        // The line: a dot per reading.
        let spacing = pts.count > 1 ? (right - left) / CGFloat(max(1, pts.count)) : 99
        let r = min(max(spacing * 0.55, 1.3 * d), 2.2 * d)
        ctx.setLineCap(.round)
        ctx.setLineWidth(1.6 * d)
        for (i, p) in pts.enumerated() where p.t >= from {
            let px = x(p.t), py = y(Double(p.sgv))
            let c = pal.at(b, Double(p.sgv))
            if spacing > 5 * d, i > 0, p.t - pts[i - 1].t <= GlucoseStats.maxGap {
                ctx.setStrokeColor(c.a(0.75))
                ctx.strokeLineSegments(between: [CGPoint(x: x(pts[i - 1].t), y: y(Double(pts[i - 1].sgv))), CGPoint(x: px, y: py)])
            }
            if i == pts.count - 1 {
                // the gap around the latest dot: background-coloured, or (tinted) cut out of the lines and band below
                ctx.saveGState()
                if pal.knockout { ctx.setBlendMode(.destinationOut) }
                ctx.setFillColor(pal.knockout ? GraphPalette.hex(0) : pal.bg)
                ctx.fillEllipse(in: CGRect(x: px - r - 2.6 * d, y: py - r - 2.6 * d, width: 2 * (r + 2.6 * d), height: 2 * (r + 2.6 * d)))
                ctx.restoreGState()
                ctx.setFillColor(c); ctx.fillEllipse(in: CGRect(x: px - r - 1.4 * d, y: py - r - 1.4 * d, width: 2 * (r + 1.4 * d), height: 2 * (r + 1.4 * d)))
            } else {
                ctx.setFillColor(c)
                ctx.fillEllipse(in: CGRect(x: px - r, y: py - r, width: 2 * r, height: 2 * r))
            }
        }

        // The distribution: its outline erased from the lines (the widgets are transparent, so no background-colour
        // stroke), then its dots.
        let rows = sideW > 0 ? profileRows(GlucoseStats.timeAtLevel(readings, from: from, to: now, bands: b, tir: tir), b, pal,
                                           left: left, sideW: sideW, top: top, bottom: plotBottom, yMin: yMin, yMax: yMax, d: d) : []
        if !rows.isEmpty {
            ctx.saveGState()
            ctx.setBlendMode(.destinationOut)
            let h = (Self.profileR + Self.profileGap) * d
            for row in rows {
                ctx.addPath(CGPath(roundedRect: CGRect(x: area.minX, y: row.cy - h, width: row.endX + Self.profileGap * d - area.minX, height: 2 * h),
                                   cornerWidth: h, cornerHeight: h, transform: nil))
            }
            ctx.fillPath()
            ctx.restoreGState()
        }
        ctx.endTransparencyLayer()
        for row in rows {
            ctx.setFillColor(row.color)
            for k in 0..<row.n {
                let cx = left + Self.profilePitch * d * (CGFloat(k) + 0.5), pr = Self.profileR * d
                ctx.fillEllipse(in: CGRect(x: cx - pr, y: row.cy - pr, width: 2 * pr, height: 2 * pr))
            }
        }

        // % per major band, centred on its band; a band too short for its label (often low, sometimes high) pushes
        // it outwards so the three never overlap, and it may hang a little past the plot.
        if pctCol > 0 {
            let p = pct3(tir.below, tir.inRange, tir.above)
            let lh = 13 * d * 0.95
            var c = [(top + y(Double(b.high))) / 2, (y(Double(b.high)) + y(Double(b.low))) / 2, (y(Double(b.low)) + plotBottom) / 2]
            c[0] = min(c[0], c[1] - lh)
            c[2] = max(c[2], c[1] + lh)
            c[0] = max(c[0], area.minY + lh * 0.5)
            c[2] = min(c[2], area.maxY - lh * 0.4)
            for (v, cy) in zip([p.2, p.1, p.0], c) {
                let s = "\(v)"
                let w = Ink.width(s, pctBig) + Ink.width("%", pctSmall)
                let x0 = left - 4 * d - w
                Ink.draw(ctx, s, x: x0, y: cy + 13 * d * 0.36, pctBig, pal.ink)
                Ink.draw(ctx, "%", x: x0 + Ink.width(s, pctBig), y: cy + 13 * d * 0.36, pctSmall, pal.inkSoft)
            }
        }

        if !shownMarks.isEmpty {
            TreatmentMarks.draw(ctx, shownMarks, x: x, left: left, right: right, bottom: bottom, dark: pal.dark, d: d, now: now,
                                muteUntil: alerts.map { a in { AlertStatus.muteUntil($0, a) } })
        }
    }

    /// The distribution's dots: rows `profilePitch` apart, dots of radius `profileR`; its outline is `profileGap` wider.
    private static let profilePitch: CGFloat = 3.6, profileR: CGFloat = 1.35, profileGap: CGFloat = 1.6

    /// One row of the distribution: `n` dots centred on `cy`, the last one ending at `endX`.
    private struct ProfileRow { var cy: CGFloat; var n: Int; var color: CGColor; var endX: CGFloat }

    private static func profileRows(_ prof: [Double], _ b: Bands, _ pal: GraphPalette, left: CGFloat, sideW: CGFloat,
                                    top: CGFloat, bottom: CGFloat, yMin: Double, yMax: Double, d: CGFloat) -> [ProfileRow] {
        let pxPerMg = Double(bottom - top) / (yMax - yMin)
        func massBetween(_ g0: Double, _ g1: Double) -> Double {
            var s = 0.0
            var g = Int(floor(g0))
            while Double(g) < g1 {
                let k = g - GlucoseStats.minMg
                let overlap = min(Double(g + 1), g1) - max(Double(g), g0)
                if k >= 0 && k < prof.count && overlap > 0 { s += prof[k] * overlap }
                g += 1
            }
            return s
        }
        // Rows of dots the size of the line's; dots per row ∝ the row's share of time.
        let pitch = profilePitch * d, r = profileR * d
        let rows = Int((bottom - top) / pitch)
        guard rows > 0 else { return [] }
        let mass = (0..<rows).map { i -> Double in
            let yb = Double(bottom - CGFloat(i) * pitch)
            return massBetween(yMin + (Double(bottom) - yb) / pxPerMg, yMin + (Double(bottom) - yb + Double(pitch)) / pxPerMg)
        }
        guard let peak = mass.max(), peak > 0 else { return [] }
        let maxDots = Int(floor(sideW * 0.9 / pitch))
        return (0..<rows).compactMap { i in
            var n = Int((mass[i] / peak * Double(maxDots)).rounded())
            if n == 0 && mass[i] > peak * 0.004 { n = 1 }
            guard n > 0 else { return nil }
            let cy = bottom - CGFloat(i) * pitch - pitch / 2
            let g = yMin + Double(bottom - cy) / pxPerMg
            return ProfileRow(cy: cy, n: n, color: pal.at(b, g), endX: left + pitch / 2 + CGFloat(n - 1) * pitch + r)
        }
    }

    /// Three shares as whole percentages adding up to 100 (largest remainder).
    static func pct3(_ a: Double, _ b: Double, _ c: Double) -> (Int, Int, Int) {
        let raw = [a, b, c].map { $0 * 100 }
        let total = raw.reduce(0, +)
        guard total > 0 else { return (0, 0, 0) }
        var base = raw.map { Int(floor($0)) }
        var left = Int(total.rounded()) - base.reduce(0, +)
        for i in raw.indices.sorted(by: { raw[$0] - Double(base[$0]) > raw[$1] - Double(base[$1]) }) where left > 0 {
            base[i] += 1; left -= 1
        }
        return (base[0], base[1], base[2])
    }

    static func hourLabel(_ h: Int, _ hour24: Bool) -> String {
        hour24 ? String(format: "%02d:00", h) : "\(h % 12 == 0 ? 12 : h % 12) \(h < 12 ? "AM" : "PM")"
    }
}

/// Treatments along the bottom lane, as TreatmentMarks.kt: rapid = filled blue drop, long = outlined violet drop,
/// carbs = amber dot, note = grey diamond; above them the amount and the time since (`4.5u 12m`, once per meal), up to
/// two rows, and from each rapid / carbs mark a line to the end of its alert mute.
enum TreatmentMarks {
    static func color(_ k: String, dark: Bool) -> CGColor {
        switch k {
        case "rapid": GraphPalette.hex(dark ? 0x8AB4FF : 0x1A62D6)
        case "long": GraphPalette.hex(dark ? 0xC9B0FF : 0x6D45C4)
        case "carbs": GraphPalette.hex(dark ? 0xFFB74D : 0xC86E00)
        default: GraphPalette.hex(dark ? 0xB4B4B4 : 0x707070)
        }
    }

    /// Text on a filled chip of a kind's colour: near-black on the light dark-theme colours, white on the deep light ones.
    static func onColor(dark: Bool) -> CGColor { GraphPalette.hex(dark ? 0x1C1C1E : 0xFFFFFF) }

    /// "4.5u", "12u", "30g" (lowercase, no space: the user, 2026-10-18); nil for notes.
    static func amount(k: String, a: Double) -> String? {
        switch k {
        case "rapid", "long": (a == a.rounded() ? String(Int(a)) : String(format: "%.1f", a)) + "u"
        case "carbs": "\(Int(a.rounded()))g"
        default: nil
        }
    }
    static func amount(_ m: GraphMark) -> String? { amount(k: m.k, a: m.a) }

    /// A short duration: "now" under a minute, "12m", "1h 5m", "3h" (whole hours drop the minutes; from 6 h only
    /// hours), "2d" from two days. `up` rounds partial minutes up (time left: "1m" until it is over).
    static func duration(_ s: TimeInterval, up: Bool = false) -> String {
        let sec = Int64(max(0, s))
        let m = up ? (sec + 59) / 60 : sec / 60
        switch m {
        case ..<1: return "now"
        case ..<60: return "\(m)m"
        case ..<(6 * 60): return m % 60 == 0 ? "\(m / 60)h" : "\(m / 60)h \(m % 60)m"
        case ..<(48 * 60): return "\(m / 60)h"
        default: return "\(m / 1440)d"
        }
    }

    private static let order = ["rapid": 0, "long": 1, "carbs": 2, "note": 3]

    /// One marker of kind `k` centred on (`x`, `y`), `r` = its half-size (4.5 on the chart). `hole` fills the
    /// outlined long-acting drop (the background colour, or nil for none).
    static func marker(_ ctx: CGContext, _ k: String, x: CGFloat, y: CGFloat, r: CGFloat, color c: CGColor, hole: CGColor? = nil) {
        ctx.saveGState()
        defer { ctx.restoreGState() }
        switch k {
        case "rapid", "long":
            let p = CGMutablePath()
            p.move(to: CGPoint(x: x - r, y: y - r * 0.78)); p.addLine(to: CGPoint(x: x + r, y: y - r * 0.78))
            p.addLine(to: CGPoint(x: x, y: y + r * 0.89)); p.closeSubpath()
            if k == "rapid" { ctx.addPath(p); ctx.setFillColor(c); ctx.fillPath(); return }
            if let hole { ctx.addPath(p); ctx.setFillColor(hole); ctx.fillPath() }
            ctx.addPath(p); ctx.setStrokeColor(c); ctx.setLineWidth(r / 3); ctx.setLineJoin(.round); ctx.strokePath()
        case "carbs":
            ctx.setFillColor(c); ctx.fillEllipse(in: CGRect(x: x - r * 0.8, y: y - r * 0.8, width: r * 1.6, height: r * 1.6))
        default:
            let p = CGMutablePath(), q = r * 0.67
            p.move(to: CGPoint(x: x, y: y - q)); p.addLine(to: CGPoint(x: x + q, y: y))
            p.addLine(to: CGPoint(x: x, y: y + q)); p.addLine(to: CGPoint(x: x - q, y: y)); p.closeSubpath()
            ctx.addPath(p); ctx.setFillColor(c); ctx.fillPath()
        }
    }

    /// Draw `marks` (live entries) into the plot `left`…`right` × …`bottom`. `now` (the plot's right edge) adds the
    /// time since each entry to its label; `muteUntil` gives the end of an entry's alert mute (nil = none) for its line.
    static func draw(_ ctx: CGContext, _ marks: [GraphMark], x: (TimeInterval) -> CGFloat, left: CGFloat, right: CGFloat,
                     bottom: CGFloat, dark: Bool, d: CGFloat, textSize: CGFloat = 9, rows: Int = 2,
                     now: TimeInterval? = nil, muteUntil: ((GraphMark) -> TimeInterval?)? = nil) {
        let items = marks.filter { order[$0.k] != nil }.map { ($0, x($0.t)) }.filter { $0.1 >= left - 1 && $0.1 <= right + 1 }
        guard !items.isEmpty else { return }
        let cy = bottom - 5 * d
        let back = items.sorted { order[$0.0.k]! > order[$1.0.k]! }   // notes at the back, insulin in front

        if let muteUntil {
            ctx.saveGState()
            ctx.setLineWidth(2 * d); ctx.setLineCap(.round); ctx.setLineJoin(.round)
            // Insulin's line just above the lane's middle, carbs' just below: a meal's two lines lie side by side.
            for (m, px) in back {
                guard let until = muteUntil(m) else { continue }
                let ly = cy + (m.k == "carbs" ? 1.5 : -1.5) * d
                let running = now.map { until > $0 } ?? false
                let ex = running ? right : min(x(until), right)
                if ex <= px + d { continue }
                ctx.setStrokeColor(color(m.k, dark: dark).copy(alpha: 0xB0 / 255.0)!)
                ctx.strokeLineSegments(between: [CGPoint(x: px, y: ly), CGPoint(x: ex, y: ly)])
                if running {
                    if ex - px > 8 * d {   // still muting: a chevron at the edge
                        ctx.move(to: CGPoint(x: ex - 3 * d, y: ly - 3 * d)); ctx.addLine(to: CGPoint(x: ex, y: ly))
                        ctx.addLine(to: CGPoint(x: ex - 3 * d, y: ly + 3 * d)); ctx.strokePath()
                    }
                } else {
                    ctx.strokeLineSegments(between: [CGPoint(x: ex, y: ly - 3 * d), CGPoint(x: ex, y: ly + 3 * d)])   // over: a tick
                }
            }
            ctx.restoreGState()
        }
        for (m, px) in back { marker(ctx, m.k, x: px, y: cy, r: 4.5 * d, color: color(m.k, dark: dark)) }

        // Labels, stacked up to `rows` rows above the lane; with the time first, then without it, else skipped.
        let font = Ink.font(textSize * d, .bold), agoFont = Ink.font(textSize * d, .medium)
        let ascent = CTFontGetAscent(font), descent = CTFontGetDescent(font)
        let lineH = ascent + descent
        var placed: [CGRect] = []
        var timed: [TimeInterval] = []   // entries whose label carries the time: one per meal (entries ≤ 3 min apart)
        let gap = 2 * d
        for (m, px) in items.sorted(by: { (order[$0.0.k]!, -$0.0.t) < (order[$1.0.k]!, -$1.0.t) }) {
            guard let s = amount(m) else { continue }
            let t = now.flatMap { n in timed.contains { abs($0 - m.t) <= 180 } ? nil : " " + duration(n - m.t) }
            func place(_ withTime: Bool) -> Bool {
                guard !withTime || t != nil else { return false }
                let tail = withTime ? t! : ""
                let sw = Ink.width(s, font), w = sw + (tail.isEmpty ? 0 : Ink.width(tail, agoFont))
                let lx = min(max(px - w / 2, left), max(left, right - w))
                for row in 0..<rows {
                    let baseline = cy - 5.5 * d - descent - CGFloat(row) * lineH
                    let r = CGRect(x: lx - gap / 2, y: baseline - ascent, width: w + gap, height: lineH)
                    if placed.contains(where: { $0.intersects(r) }) { continue }
                    placed.append(r)
                    let c = color(m.k, dark: dark)
                    Ink.draw(ctx, s, x: lx, y: baseline, font, c)
                    if !tail.isEmpty {
                        Ink.draw(ctx, tail, x: lx + sw, y: baseline, agoFont, c.copy(alpha: 0xC0 / 255.0)!)
                        timed.append(m.t)
                    }
                    return true
                }
                return false
            }
            if !place(true) { _ = place(false) }
        }
    }
}

// MARK: - Ring

/// The segmented time-in-range ring (TirRing in GlucoseCard.kt).
enum TirRing {
    static func draw(_ ctx: CGContext, center c: CGPoint, radius: CGFloat, tir: TimeInRange, spec: RingSpec,
                     _ pal: GraphPalette, emphasis: CGFloat) {
        let sw = radius * 0.2
        let rr = radius - sw / 2
        ctx.saveGState()
        ctx.setLineWidth(sw)
        ctx.setLineCap(.butt)
        // Clockwise from 12 o'clock in the user's order (`ringOrder`); gaps between below | very good | in range | above.
        let order = Set(spec.order).count == 6 ? spec.order : DEFAULT_RING_ORDER
        let shares = order.map { ($0, tir.share($0)) }.filter { $0.1 >= 0.004 }
        func gapAfter(_ i: Int) -> Bool {
            guard shares.count > 1 else { return false }
            let (x, y) = (shares[i].0, shares[(i + 1) % shares.count].0)
            // also between very good and the rest of in range (the user, 0.11.9); the two lows / two highs touch
            return x.group != y.group || Set([x, y]) == Set([Band.tight, Band.target])
        }
        if shares.isEmpty {
            ctx.setStrokeColor(pal.grid.a(emphasis))
            ctx.strokeEllipse(in: CGRect(x: c.x - rr, y: c.y - rr, width: 2 * rr, height: 2 * rr))
        } else {
            let gap: CGFloat = 7
            let gaps = CGFloat(shares.indices.filter(gapAfter).count) * gap
            var a: CGFloat = -90
            for (i, s) in shares.enumerated() {
                let sweep = max(1.5, (360 - gaps) * CGFloat(s.1))
                ctx.setStrokeColor(pal.of(s.0).a(emphasis))
                // y-down: increasing angle runs clockwise on screen, like Android's drawArc.
                ctx.addArc(center: c, radius: rr, startAngle: a * .pi / 180, endAngle: (a + sweep) * .pi / 180, clockwise: false)
                ctx.strokePath()
                a += sweep + (gapAfter(i) ? gap : 0)
            }
        }
        ctx.restoreGState()
        let num = Ink.font(radius * 0.78, .heavy, rounded: true)
        let sub = Ink.font(radius * 0.34, .semibold, rounded: true)
        let v = tir.empty ? "–" : "\(Int((tir.of(spec.range) * 100).rounded()))"
        let base = c.y + radius * 0.78 * 0.28
        Ink.draw(ctx, v, x: c.x, y: base, num, pal.ink.a(emphasis), align: 0.5)
        Ink.draw(ctx, spec.period, x: c.x, y: base + radius * 0.34 * 1.1, sub,
                 (spec.range == .tight ? pal.tight : pal.inkSoft).a(emphasis), align: 0.5)
    }
}

/// A report number in a slot (MetricGauge in GlucoseCard.kt): the value in the middle, kind + window under it, an arc
/// on the number's scale (green = consensus goal met, orange = not) and a tick at the goal.
enum MetricGauge {
    static func scale(_ k: SlotKind) -> (Double, Double, Double) {
        switch k { case .gmi: (5, 10, 7); case .average: (70, 250, 154); default: (0, 60, 36) }
    }
    static func value(_ k: SlotKind, _ m: GlucoseMetrics) -> Double {
        switch k { case .gmi: m.gmi; case .average: m.mean; default: m.cv }
    }
    static func met(_ k: SlotKind, _ m: GlucoseMetrics) -> Bool {
        switch k { case .gmi: Goal.gmi.met(m); case .average: Goal.average.met(m); default: Goal.cv.met(m) }
    }
    static func text(_ k: SlotKind, _ m: GlucoseMetrics) -> (String, String) {
        switch k {
        case .gmi: (String(format: "%.1f", m.gmi), "%")
        case .average: ("\(Int(m.mean.rounded()))", "")
        default: ("\(Int(m.cv.rounded()))", "%")
        }
    }

    static func draw(_ ctx: CGContext, center c: CGPoint, radius: CGFloat, m: GlucoseMetrics, spec: RingSpec,
                     _ pal: GraphPalette, emphasis: CGFloat, d: CGFloat) {
        let sw = radius * 0.2, rr = radius - sw / 2
        ctx.saveGState()
        ctx.setLineWidth(sw)
        ctx.setLineCap(.round)
        ctx.setStrokeColor(pal.grid.a(emphasis))
        ctx.strokeEllipse(in: CGRect(x: c.x - rr, y: c.y - rr, width: 2 * rr, height: 2 * rr))
        let (lo, hi, goal) = scale(spec.kind)
        if !m.empty {
            let f = min(max((value(spec.kind, m) - lo) / (hi - lo), 0.02), 1)
            ctx.setStrokeColor((met(spec.kind, m) ? pal.tight : pal.high).a(emphasis))
            ctx.addArc(center: c, radius: rr, startAngle: -.pi / 2, endAngle: -.pi / 2 + 2 * .pi * CGFloat(f), clockwise: false)
            ctx.strokePath()
        }
        // goal tick across the track
        let ga = -Double.pi / 2 + 2 * .pi * (goal - lo) / (hi - lo)
        let r0 = radius - sw * 1.15, r1 = radius + sw * 0.15
        ctx.setLineCap(.butt)
        ctx.setLineWidth(1.6 * d)
        ctx.setStrokeColor(pal.ink.a(emphasis))
        ctx.strokeLineSegments(between: [CGPoint(x: c.x + r0 * CGFloat(cos(ga)), y: c.y + r0 * CGFloat(sin(ga))),
                                         CGPoint(x: c.x + r1 * CGFloat(cos(ga)), y: c.y + r1 * CGFloat(sin(ga)))])
        ctx.restoreGState()
        let num = Ink.font(radius * 0.62, .heavy, rounded: true)
        let unit = Ink.font(radius * 0.3, .semibold, rounded: true)
        let (v, u) = m.empty ? ("–", "") : text(spec.kind, m)
        let w = Ink.width(v, num) + Ink.width(u, unit)
        let base = c.y + radius * 0.62 * 0.3
        Ink.draw(ctx, v, x: c.x - w / 2, y: base, num, pal.ink.a(emphasis))
        Ink.draw(ctx, u, x: c.x - w / 2 + Ink.width(v, num), y: base, unit, pal.ink.a(emphasis))
        Ink.draw(ctx, "\(spec.kind.short) \(spec.period)", x: c.x, y: base + radius * 0.3 * 1.25, unit, pal.inkSoft.a(emphasis), align: 0.5)
    }
}

// MARK: - Status badge and last-treatment slot (CardStatus.kt)

/// The badge under the glucose value, from `AlertStatus`: none → nothing; needed → a filled pill in the colour of what
/// to log, with its marker ("Eat carbs" / "Take insulin"); taken → the same pill tonal, with the "notifications
/// paused" bell and the time left of the mute ("45m left").
enum StatusBadge {
    /// `kind` = the treatment kind whose colour and marker the badge uses; `tonal` = taken.
    struct Look: Equatable { var kind: String; var text: String; var short: String; var tonal: Bool }

    static func look(_ s: AlertStatus, now: TimeInterval) -> Look? {
        switch s {
        case .none: return nil
        case .needed(let k):
            return k.lowSide ? Look(kind: "carbs", text: "Eat carbs", short: "Carbs", tonal: false)
                : Look(kind: "rapid", text: "Take insulin", short: "Insulin", tonal: false)
        case .taken(_, let tr, let until):
            let d = TreatmentMarks.duration(until - now, up: true)
            return Look(kind: tr.k, text: "\(d) left", short: d, tonal: true)
        }
    }

    /// For screen readers.
    static func describe(_ s: AlertStatus, now: TimeInterval) -> String? {
        switch s {
        case .none: nil
        case .needed: look(s, now: now)?.text
        case .taken(let k, _, let until): "\(k.title): alerts paused, \(TreatmentMarks.duration(until - now, up: true)) left"
        }
    }

    /// Material Symbols "notifications_paused" (Apache 2.0), viewBox 0 -960 960 960.
    static let bellPaused = "M370-320h220v-72H480l110-136v-72H370v72h110L370-392v72ZM160-200v-80h80v-280q0-83 50-147.5" +
        "T420-792v-28q0-25 17.5-42.5T480-880q25 0 42.5 17.5T540-820v28q80 20 130 84.5T720-560v280h80v80H160Zm320-300Zm0 420q-33 " +
        "0-56.5-23.5T400-160h160q0 33-23.5 56.5T480-80ZM320-280h320v-280q0-66-47-113t-113-47q-66 0-113 47t-47 113v280Z"
    private static let bellPath: CGPath = SVGPath.parse(bellPaused)

    /// The bell, `size` square, top-left at (`x`, `y`).
    static func bell(_ ctx: CGContext, x: CGFloat, y: CGFloat, size: CGFloat, color: CGColor) {
        var t = CGAffineTransform(translationX: x, y: y).scaledBy(x: size / 960, y: size / 960).translatedBy(x: 0, y: 960)
        guard let p = bellPath.copy(using: &t) else { return }
        ctx.saveGState(); ctx.addPath(p); ctx.setFillColor(color); ctx.fillPath(); ctx.restoreGState()
    }

    private static func colors(_ look: Look, dark: Bool, knockout: Bool) -> (bg: CGColor, fg: CGColor) {
        // Tinted: white (full opacity in either tinted mode); needed = solid pill with the words cut out, taken = a
        // faint pill under solid words, so the two stay apart as in full colour.
        if knockout { return look.tonal ? (CGColor(gray: 1, alpha: 0.22), CGColor(gray: 1, alpha: 1)) : (CGColor(gray: 1, alpha: 1), CGColor(gray: 0, alpha: 1)) }
        let c = TreatmentMarks.color(look.kind, dark: dark)
        return look.tonal ? (c.copy(alpha: (dark ? 0x3D : 0x2E) / 255.0)!, c) : (c, TreatmentMarks.onColor(dark: dark))
    }
    private static func font(_ h: CGFloat) -> CTFont { Ink.font(h * 0.56, .semibold) }

    /// Width of the full badge `h` tall; `short` = the shorter words.
    static func width(_ look: Look, h: CGFloat, short: Bool = false) -> CGFloat {
        h * 0.4 + h * 0.62 + h * 0.25 + Ink.width(short ? look.short : look.text, font(h)) + h * 0.45
    }

    /// Draw the badge `h` tall with its top-left at (`x`, `y`), at most `maxW` wide: the full words, the short ones, or
    /// (no room for words) only the icon in a circle. Returns the width used, 0 if not even the circle fits.
    /// `knockout` (tinted widgets): see `GraphPalette.knockout`.
    @discardableResult
    static func draw(_ ctx: CGContext, x: CGFloat, y: CGFloat, h: CGFloat, maxW: CGFloat, _ look: Look, dark: Bool,
                     knockout: Bool = false) -> CGFloat {
        let (bg, fg) = colors(look, dark: dark, knockout: knockout)
        let cut = knockout && !look.tonal
        let words: String?
        if width(look, h: h) <= maxW { words = look.text }
        else if width(look, h: h, short: true) <= maxW { words = look.short }
        else if h <= maxW { words = nil }
        else { return 0 }
        let icon = h * 0.62, f = font(h)
        let w = words.map { h * 0.4 + icon + h * 0.25 + Ink.width($0, f) + h * 0.45 } ?? h
        if cut { ctx.beginTransparencyLayer(in: CGRect(x: x, y: y, width: w, height: h), auxiliaryInfo: nil) }
        defer { if cut { ctx.endTransparencyLayer() } }
        ctx.saveGState()
        ctx.addPath(CGPath(roundedRect: CGRect(x: x, y: y, width: w, height: h), cornerWidth: h / 2, cornerHeight: h / 2, transform: nil))
        ctx.setFillColor(bg); ctx.fillPath()
        ctx.restoreGState()
        ctx.saveGState()
        defer { ctx.restoreGState() }
        if cut { ctx.setBlendMode(.destinationOut) }
        let ix = words == nil ? x + (h - icon) / 2 : x + h * 0.4
        if look.tonal { bell(ctx, x: ix, y: y + (h - icon) / 2, size: icon, color: fg) }
        else { TreatmentMarks.marker(ctx, look.kind, x: ix + icon / 2, y: y + h / 2, r: icon * 0.42, color: fg) }
        if let words {
            Ink.draw(ctx, words, x: ix + icon + h * 0.25, y: y + h / 2 + (CTFontGetAscent(f) - CTFontGetDescent(f)) / 2, f, fg)
        }
        return w
    }
}

/// Where the last-treatment slot sits among the slots beside the value: one more slot, not a slot kind, so the user's
/// rings stay as they are. `nearValue` = leftmost, next to the number; `end` = rightmost. Raw values = Android's.
enum LastPlace: String, Codable, CaseIterable {
    case off = "OFF", nearValue = "NEAR_VALUE", end = "END"
    var label: String { switch self { case .off: "Off"; case .nearValue: "Next to the number"; case .end: "At the right end" } }
}

/// One slot beside the value, ready to draw.
enum SlotItem: Equatable {
    case metric(RingSpec, GlucoseMetrics, emphasis: CGFloat)
    case last(LastTreatment.Info)
}

enum Slots {
    private static let ringEmphasis: [CGFloat] = [1, 0.55, 0.35]
    /// The fallback ring (no recent treatment) when it is faded: like a second slot.
    private static let fallbackEmphasis: CGFloat = 0.55

    /// What to draw, right to left (main first), at most `max` slots. `rings` = the user's slots (index, spec,
    /// metrics; main first) already without the ones lacking data. When room is short: main ring, recent treatment,
    /// second ring, fallback ring, third ring.
    static func arrange(_ rings: [(Int, RingSpec, GlucoseMetrics)], place: LastPlace, last: LastTreatment.Info?,
                        fallback: (RingSpec, GlucoseMetrics)?, max n: Int) -> [SlotItem] {
        let metric = rings.map { (i, s, m) in (i, SlotItem.metric(s, m, emphasis: s.faded ? ringEmphasis[min(max(i, 0), 2)] : 1)) }
        let extra: SlotItem? = place == .off ? nil
            : last.map { .last($0) } ?? fallback.map { (s, m) in .metric(s, m, emphasis: s.faded ? fallbackEmphasis : 1) }
        var isLast = false
        if case .last = extra { isLast = true }
        var priority: [SlotItem] = []
        if let m = metric.first(where: { $0.0 == 0 }) { priority.append(m.1) }
        if isLast, let extra { priority.append(extra) }
        if let m = metric.first(where: { $0.0 == 1 }) { priority.append(m.1) }
        if !isLast, let extra { priority.append(extra) }
        priority += metric.filter { $0.0 >= 2 }.map(\.1)
        let keep = Array(priority.prefix(Swift.max(n, 0)))
        let ordered = metric.map(\.1).filter { keep.contains($0) }
        guard let extra, keep.contains(extra) else { return ordered }
        return place == .end ? [extra] + ordered : ordered + [extra]
    }

    /// Draw `item` as a circle of `radius` around `c`.
    static func draw(_ ctx: CGContext, _ item: SlotItem, center c: CGPoint, radius: CGFloat, now: TimeInterval, _ pal: GraphPalette, d: CGFloat) {
        switch item {
        case let .metric(spec, m, e):
            if spec.kind == .tir { TirRing.draw(ctx, center: c, radius: radius, tir: m.tir, spec: spec, pal, emphasis: e) }
            else { MetricGauge.draw(ctx, center: c, radius: radius, m: m, spec: spec, pal, emphasis: e, d: d) }
        case let .last(info): LastSlot.draw(ctx, center: c, radius: radius, info: info, now: now, pal)
        }
    }
}

/// The last-treatment slot: the kind's marker, the amount ("4.5u", "30g") in the kind's colour and the time since it
/// ("12m"); around it a ring in the kind's colour for the share of the alert mute still left, shrinking back to 12
/// o'clock. When the mute is over (or the kind mutes nothing) only the grey track is left.
enum LastSlot {
    static func draw(_ ctx: CGContext, center c: CGPoint, radius: CGFloat, info: LastTreatment.Info, now: TimeInterval, _ pal: GraphPalette) {
        let sw = radius * 0.2, rr = radius - sw / 2
        ctx.saveGState()
        ctx.setLineWidth(sw); ctx.setLineCap(.round)
        // Tinted: hue is lost, so the grey track would be as solid as the coloured arc; a faint white track, white arc.
        ctx.setStrokeColor(pal.knockout ? CGColor(gray: 1, alpha: 0.25) : pal.grid)
        ctx.strokeEllipse(in: CGRect(x: c.x - rr, y: c.y - rr, width: 2 * rr, height: 2 * rr))
        let col = pal.knockout ? CGColor(gray: 1, alpha: 1) : TreatmentMarks.color(info.tr.k, dark: pal.dark)
        let left = info.left(now)
        if left > 0 {
            ctx.setStrokeColor(col)
            let sweep = max(2, 360 * CGFloat(left)) * .pi / 180
            ctx.addArc(center: c, radius: rr, startAngle: -.pi / 2, endAngle: -.pi / 2 + sweep, clockwise: false)
            ctx.strokePath()
        }
        ctx.restoreGState()
        TreatmentMarks.marker(ctx, info.tr.k, x: c.x, y: c.y - radius * 0.4, r: radius * 0.14, color: col,
                              hole: pal.knockout ? nil : pal.bg.alpha > 0 ? pal.bg : nil)
        let inner = (radius - sw) * 2 * 0.86
        let s = TreatmentMarks.amount(info.tr) ?? ""
        var size = radius * 0.5
        size = min(size, size * inner / max(Ink.width(s, Ink.font(size, .heavy)), 1))
        Ink.draw(ctx, s, x: c.x, y: c.y + radius * 0.17, Ink.font(size, .heavy), col, align: 0.5)
        let sub = radius * 0.3
        Ink.draw(ctx, TreatmentMarks.duration(now - info.tr.t), x: c.x, y: c.y + radius * 0.17 + sub * 1.25,
                 Ink.font(sub, .semibold), pal.inkSoft, align: 0.5)
    }
}

/// A minimal SVG path-data parser (M L H V Q T Z, absolute and relative) for the Material Symbols icons.
enum SVGPath {
    static func parse(_ d: String) -> CGPath {
        let p = CGMutablePath()
        var cmd: Character = "M"
        var nums: [CGFloat] = []
        var tokens: [(Character, [CGFloat])] = []
        var num = ""
        func flushNum() { if let v = Double(num) { nums.append(CGFloat(v)) }; num = "" }
        for ch in d {
            if ch.isLetter && ch != "e" {
                flushNum(); tokens.append((cmd, nums)); nums = []; cmd = ch
            } else if ch == "-" && !num.isEmpty && num.last != "e" {
                flushNum(); num = "-"
            } else if ch == "." && num.contains(".") {
                flushNum(); num = "."
            } else if ch == " " || ch == "," {
                flushNum()
            } else { num.append(ch) }
        }
        flushNum(); tokens.append((cmd, nums))
        var cur = CGPoint.zero, start = CGPoint.zero, lastCtrl: CGPoint? = nil
        for (c, a) in tokens {
            let rel = c.isLowercase
            func pt(_ i: Int) -> CGPoint { rel ? CGPoint(x: cur.x + a[i], y: cur.y + a[i + 1]) : CGPoint(x: a[i], y: a[i + 1]) }
            switch c.uppercased() {
            case "M":
                var i = 0
                while i + 1 < a.count {
                    let q = pt(i)
                    if i == 0 { p.move(to: q); start = q } else { p.addLine(to: q) }
                    cur = q; i += 2
                }
                lastCtrl = nil
            case "L":
                var i = 0
                while i + 1 < a.count { let q = pt(i); p.addLine(to: q); cur = q; i += 2 }
                lastCtrl = nil
            case "H":
                for v in a { cur = CGPoint(x: rel ? cur.x + v : v, y: cur.y); p.addLine(to: cur) }
                lastCtrl = nil
            case "V":
                for v in a { cur = CGPoint(x: cur.x, y: rel ? cur.y + v : v); p.addLine(to: cur) }
                lastCtrl = nil
            case "Q":
                var i = 0
                while i + 3 < a.count {
                    let ctrl = pt(i), end = pt(i + 2)
                    p.addQuadCurve(to: end, control: ctrl); lastCtrl = ctrl; cur = end; i += 4
                }
            case "T":
                var i = 0
                while i + 1 < a.count {
                    let ctrl = lastCtrl.map { CGPoint(x: 2 * cur.x - $0.x, y: 2 * cur.y - $0.y) } ?? cur
                    let end = pt(i)
                    p.addQuadCurve(to: end, control: ctrl); lastCtrl = ctrl; cur = end; i += 2
                }
            case "Z":
                p.closeSubpath(); cur = start; lastCtrl = nil
            default: break
            }
        }
        return p
    }
}

// MARK: - Card

/// Value + trend arrow + rings + chart in one drawing (GlucoseCard.kt), adapting to the size.
enum GlucoseCard {
    static let minCoverage = 0.25

    /// `metricsOf` gives a slot's numbers; by default computed from `readings` (enough for windows they cover).
    /// `alerts` = the alert rules behind the badge, the mute lines and the last-treatment slot (nil = none of them).
    static func draw(_ ctx: CGContext, size: CGSize, readings: [GraphReading], marks: [GraphMark], now: TimeInterval,
                     _ st: CardStyle, _ pal: GraphPalette, d: CGFloat = 1,
                     metricsOf: ((RingSpec, Bands) -> GlucoseMetrics)? = nil, alerts: AlertSettings? = nil) {
        let w = size.width, h = size.height
        let latest = readings.max { $0.t < $1.t }
        let status = alerts.map { AlertStatus.of(latest, $0, marks, now: now) } ?? .none
        let last = st.last != .off ? alerts.flatMap { LastTreatment.of(marks, $0, status, now: now) } : nil
        let fr = Freshness.of(age: latest.map { now - $0.t })
        let graph = fr.showGraph ? st.graph : nil
        let bands = (st.graph?.bands ?? Bands()).sane()
        let sideBySide = graph != nil && st.value && h < 100 * d && w > 2.6 * h
        let headerH: CGFloat = !st.value ? 0 : (graph == nil || sideBySide) ? h : min(max(h * 0.34, 30 * d), 80 * d)
        let valueRight = sideBySide ? min(w * 0.32, 120 * d) : w
        let metrics = metricsOf ?? { r, b in GlucoseMetrics.of(readings, from: now - r.window, to: now, bands: b) }

        if st.value {
            let box = CGRect(x: 0, y: 0, width: valueRight, height: sideBySide ? h : headerH)
            let ringsW = sideBySide ? 0 : drawRings(ctx, box, st, last, now, bands, pal, d: d, metrics)
            let look = st.badge ? StatusBadge.look(status, now: now) : nil
            drawValue(ctx, CGRect(x: box.minX, y: box.minY, width: box.width - ringsW, height: box.height), latest, fr, now, bands, pal,
                      d: d, look: look)
        }
        let chart = sideBySide ? CGRect(x: valueRight + 6 * d, y: 0, width: w - valueRight - 6 * d, height: h)
            : CGRect(x: 0, y: headerH + (st.value ? 6 * d : 0), width: w, height: h - headerH - (st.value ? 6 * d : 0))
        if let graph {
            GlucoseGraph.draw(ctx, chart, readings: readings, marks: marks, now: now, graph, pal, d: d, alerts: alerts)
        } else if st.graph != nil && chart.height > 24 * d {
            let msg = latest.map { "No readings since \(Freshness.timeText($0.t, now: now))" } ?? "No readings yet"
            Ink.draw(ctx, msg, x: chart.midX, y: chart.midY, Ink.font(12 * d, .regular), pal.inkSoft, align: 0.5)
        }
    }

    /// Slots (`Slots.arrange`) right-aligned in `box` (main rightmost); returns the width used.
    private static func drawRings(_ ctx: CGContext, _ box: CGRect, _ st: CardStyle, _ last: LastTreatment.Info?, _ now: TimeInterval,
                                  _ bands: Bands, _ pal: GraphPalette, d: CGFloat,
                                  _ metricsOf: (RingSpec, Bands) -> GlucoseMetrics) -> CGFloat {
        let extra = st.last != .off && (last != nil || st.lastElse != nil)
        guard !st.rings.isEmpty || extra else { return 0 }
        let size = min(box.height * 0.96, 84 * d)
        guard size >= 30 * d else { return 0 }
        let gap = size * 0.14
        let fit = min(Int((box.width * 0.55 + gap) / (size + gap)), 4)
        guard fit >= 1 else { return 0 }
        let rings = st.rings.prefix(3).enumerated().compactMap { (i, spec) -> (Int, RingSpec, GlucoseMetrics)? in
            let m = metricsOf(spec, bands)
            return m.tir.coverage >= minCoverage ? (i, spec, m) : nil
        }
        let fallback: (RingSpec, GlucoseMetrics)? = st.last != .off && last == nil
            ? st.lastElse.flatMap { s in let m = metricsOf(s, bands); return m.tir.coverage >= minCoverage ? (s, m) : nil } : nil
        var x = box.maxX, used: CGFloat = 0
        for item in Slots.arrange(rings, place: st.last, last: last, fallback: fallback, max: fit) {
            Slots.draw(ctx, item, center: CGPoint(x: x - size / 2, y: box.midY), radius: size / 2, now: now, pal, d: d)
            x -= size + gap
            used += size + gap
        }
        return used
    }

    /// The number + arrow, and under it the late reading's time and/or the status badge (`look`). The badge takes a
    /// row under the number when the box is tall enough to keep the number at least 26 pt; otherwise it shrinks to its
    /// icon beside the arrow, or is left out when there's no room there either.
    private static func drawValue(_ ctx: CGContext, _ box: CGRect, _ r: GraphReading?, _ fr: Freshness, _ now: TimeInterval,
                                  _ bands: Bands, _ pal: GraphPalette, d: CGFloat, look: StatusBadge.Look? = nil) {
        let small = Ink.font(max(11 * d, box.height * 0.17), .regular)
        guard let r, fr.showValue else {
            // Fit the longer line into the space left of the rings.
            let lines = r.map { ["No reading since", Freshness.timeText($0.t, now: now)] } ?? ["No readings yet"]
            var size = min(box.height * 0.26, 20 * d)
            let widest = lines.map { Ink.width($0, Ink.font(size, .semibold)) }.max() ?? 0
            if widest > box.width - 8 * d, widest > 0 { size *= max(0.4, (box.width - 8 * d) / widest) }
            let big = Ink.font(size, .semibold)
            let y = box.midY - (r == nil ? -size / 3 : size * 0.15)
            Ink.draw(ctx, r == nil ? "No readings yet" : "No reading since", x: box.minX, y: y, big, pal.inkSoft)
            if let r { Ink.draw(ctx, Freshness.timeText(r.t, now: now), x: box.minX, y: y + size * 1.25, big, pal.inkSoft) }
            return
        }
        let color = fr.strike ? pal.inkSoft : pal.at(bands, Double(r.sgv))
        let s = "\(r.sgv)"
        // A late reading's time goes under the number (the user: beside it, it took the rings' room).
        let time = fr.showTime ? Freshness.timeText(r.t, now: now) : ""
        let smallH = fr.showTime ? CTFontGetSize(small) * 1.25 : 0
        let badgeH = min(max(box.height * 0.24, 14 * d), 24 * d)
        let badgeRow = look != nil && box.height - badgeH * 1.25 - smallH >= 26 * d
        // the late time shares the badge's row (after the badge) when there is one
        let timeH = badgeRow ? badgeH * 1.25 : smallH
        let numBox = CGRect(x: box.minX, y: box.minY, width: box.width, height: box.height - timeH)
        var numSize = numBox.height * 1.18
        let arrowFactor: CGFloat = 0.62
        let need = Ink.width(s, Ink.font(numSize, .bold, rounded: true)) + numSize * arrowFactor * 1.1
        if need > box.width { numSize *= box.width / need }
        let num = Ink.font(numSize, .bold, rounded: true)
        // no row for the badge: its icon goes beside the arrow if there's room
        let iconH = min(numBox.height * 0.5, 24 * d)
        let iconBadge = look != nil && !badgeRow && box.width - (Ink.width(s, num) + numSize * arrowFactor * 1.2) >= iconH
        let capH = CTFontGetCapHeight(num)
        let baseY = numBox.midY + capH / 2
        Ink.draw(ctx, s, x: box.minX - numSize * 0.04, y: baseY, num, color, strike: fr.strike)
        let x = box.minX + Ink.width(s, num) + numSize * 0.08
        let arrowSize = numSize * arrowFactor
        if !fr.strike, let rate = r.rate {
            ctx.saveGState()
            ctx.translateBy(x: x + arrowSize / 2, y: numBox.midY)
            ctx.rotate(by: CGFloat(-max(-1, min(1, rate / 3))) * .pi / 2)
            drawArrow(ctx, scale: arrowSize * 1.05, color: color, double: abs(rate) >= 3)
            ctx.restoreGState()
        }
        if badgeRow, let look {
            let top = numBox.maxY + badgeH * 0.25
            let bw = StatusBadge.draw(ctx, x: box.minX, y: top, h: badgeH, maxW: box.width, look, dark: pal.dark, knockout: pal.knockout)
            if fr.showTime {
                let t = Ink.font(badgeH * 0.56, .regular)
                let tx = box.minX + bw + badgeH * 0.35
                if tx + Ink.width(time, t) <= box.maxX { Ink.draw(ctx, time, x: tx, y: top + badgeH / 2 + badgeH * 0.56 * 0.36, t, pal.inkSoft) }
            }
        } else {
            if iconBadge, let look {
                StatusBadge.draw(ctx, x: x + arrowSize * 1.15, y: numBox.midY - iconH / 2, h: iconH, maxW: iconH, look, dark: pal.dark,
                                 knockout: pal.knockout)
            }
            if fr.showTime { Ink.draw(ctx, time, x: box.minX, y: baseY + CTFontGetSize(small) * 1.25, small, pal.inkSoft) }
        }
    }

    /// Same geometry as GlucoseNotification.drawArrow / the menubar TrendArrow: unit arrow pointing +x.
    static func drawArrow(_ ctx: CGContext, scale s: CGFloat, color: CGColor, double: Bool) {
        ctx.setFillColor(color)
        ctx.fill(CGRect(x: -0.42 * s, y: -0.07 * s, width: 0.67 * s, height: 0.14 * s))
        func head(_ tip: CGFloat) {
            let p = CGMutablePath()
            p.move(to: CGPoint(x: tip * s, y: 0)); p.addLine(to: CGPoint(x: (tip - 0.40) * s, y: 0.30 * s))
            p.addLine(to: CGPoint(x: (tip - 0.30) * s, y: 0)); p.addLine(to: CGPoint(x: (tip - 0.40) * s, y: -0.30 * s))
            p.closeSubpath()
            ctx.addPath(p); ctx.fillPath()
        }
        head(0.46)
        if double { head(0.12) }
    }

    /// The card as a bitmap (widgets, PNG evidence): `scale` px per point, y-down.
    static func image(size: CGSize, scale: CGFloat = 2, readings: [GraphReading], marks: [GraphMark], now: TimeInterval,
                      _ st: CardStyle, _ pal: GraphPalette, background: CGColor? = nil,
                      metricsOf: ((RingSpec, Bands) -> GlucoseMetrics)? = nil, alerts: AlertSettings? = nil) -> CGImage? {
        let pw = max(16, Int(size.width * scale)), ph = max(16, Int(size.height * scale))
        guard let ctx = CGContext(data: nil, width: pw, height: ph, bitsPerComponent: 8, bytesPerRow: 0,
                                  space: CGColorSpace(name: CGColorSpace.sRGB)!,
                                  bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return nil }
        ctx.translateBy(x: 0, y: CGFloat(ph))
        ctx.scaleBy(x: scale, y: -scale)
        if let background { ctx.setFillColor(background); ctx.fill(CGRect(origin: .zero, size: size)) }
        draw(ctx, size: size, readings: readings, marks: marks, now: now, st, pal, metricsOf: metricsOf, alerts: alerts)
        return ctx.makeImage()
    }
}

/// A believable history (meals with bolus, dawn rise, an afternoon dip, noise), seeded: the same picture every run.
/// Port of the Android gallery's Synthetic (GraphGallery.kt), for widget previews and evidence renders.
enum RealisticSynthetic {
    static func readings(until now: TimeInterval, days: Int) -> [GraphReading] {
        var rng = JavaRandom(seed: 16)
        let start = now - Double(days) * 86400
        let tz = TimeZone.current
        struct Meal { var t: TimeInterval; var size: Double; var peak: Double }
        var meals: [Meal] = [], dips: [(TimeInterval, Double)] = []
        var day = start - (start + Double(tz.secondsFromGMT(for: Date(timeIntervalSince1970: start)))).truncatingRemainder(dividingBy: 86400)
        while day < now + 86400 {
            let off = Double(tz.secondsFromGMT(for: Date(timeIntervalSince1970: day)))
            func at(_ h: Double) -> TimeInterval { day + TimeInterval(Int64(h * 3600)) - off }
            meals.append(Meal(t: at(8.0 + rng.gauss() * 0.4), size: 45 + rng.next() * 50, peak: 45 + rng.next() * 20))
            meals.append(Meal(t: at(13.8 + rng.gauss() * 0.5), size: 55 + rng.next() * 75, peak: 55 + rng.next() * 25))
            meals.append(Meal(t: at(20.6 + rng.gauss() * 0.4), size: 50 + rng.next() * 115, peak: 60 + rng.next() * 25))
            if rng.next() < 0.6 { dips.append((at(17.0 + rng.gauss() * 0.6), 25 + rng.next() * 45)) }
            day += 86400
        }
        var walk = 0.0
        var out: [GraphReading] = []
        var t = (start / 60).rounded(.down) * 60
        while t <= now {
            let local = t + Double(tz.secondsFromGMT(for: Date(timeIntervalSince1970: t)))
            let hour = local.truncatingRemainder(dividingBy: 86400) / 3600
            var v = 104 + 18 * exp(-((hour - 6.5) * (hour - 6.5)) / 2)
            for m in meals {
                let k = (t - m.t) / 60
                if k > 0 && k < 900 { v += m.size * (k / m.peak) * exp(1 - k / m.peak) - 8 * exp(-((k - 160) * (k - 160)) / 1800) }
            }
            for (dt, depth) in dips { let k = (t - dt) / 60; if k > -60 && k < 120 { v -= depth * exp(-(k * k) / 1600) } }
            walk = walk * 0.995 + rng.gauss() * 0.6
            v += walk + 3 * sin(t / 1300)
            out.append(GraphReading(t: t, v: max(40, v.rounded())))
            t += 60
        }
        for i in 5..<out.count { out[i].rate = (out[i].v - out[i - 5].v) / 5 }
        return out
    }

    /// java.util.Random (48-bit LCG, polar Gaussian), so the days match the Android gallery's `Random(16)` exactly.
    struct JavaRandom {
        private var seed: UInt64
        private var nextGaussian: Double? = nil
        init(seed: Int64) { self.seed = (UInt64(bitPattern: seed) ^ 0x5DEECE66D) & ((1 << 48) - 1) }
        private mutating func bits(_ n: Int) -> Int64 {
            seed = (seed &* 0x5DEECE66D &+ 0xB) & ((1 << 48) - 1)
            return Int64(Int32(truncatingIfNeeded: Int64(seed >> UInt64(48 - n))))
        }
        mutating func next() -> Double {
            Double((bits(26) << 27) + bits(27)) * 0x1.0p-53
        }
        mutating func gauss() -> Double {
            if let g = nextGaussian { nextGaussian = nil; return g }
            var v1 = 0.0, v2 = 0.0, s = 0.0
            repeat {
                v1 = 2 * next() - 1; v2 = 2 * next() - 1; s = v1 * v1 + v2 * v2
            } while s >= 1 || s == 0
            let m = (-2 * Foundation.log(s) / s).squareRoot()
            nextGaussian = v2 * m
            return v1 * m
        }
    }
}
