// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import CoreGraphics
import CoreText
import Foundation

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
        case .veryLow: veryLow
        case .low: low
        case .tight: tight
        case .target: target
        case .high: high
        case .veryHigh: veryHigh
        }
    }

    /// The colour of a glucose value: its band's, or `extremelyHigh` from `Bands.extremelyHigh` up.
    func at(_ b: Bands, _ mgdl: Double) -> CGColor { mgdl >= Double(b.extremelyHigh) ? extremelyHigh : of(b.of(mgdl)) }

    static func hex(_ v: UInt32) -> CGColor {
        CGColor(
            srgbRed: CGFloat((v >> 16) & 0xFF) / 255, green: CGFloat((v >> 8) & 0xFF) / 255,
            blue: CGFloat(v & 0xFF) / 255, alpha: 1)
    }
    static let darkPalette = GraphPalette(
        dark: true, bg: hex(0x1C1C1E), ink: hex(0xF2F2F2), inkSoft: hex(0xA3A3A8), grid: hex(0x3A3A3E),
        veryLow: hex(0xC2413B), low: hex(0xF0524F), tight: hex(0x3DD47A), target: hex(0x8CC63F),
        high: hex(0xF5A524), veryHigh: hex(0xFF7A45), extremelyHigh: hex(0xE35BD0))
    static let lightPalette = GraphPalette(
        dark: false, bg: hex(0xFFFFFF), ink: hex(0x1C1C1E), inkSoft: hex(0x6B6B70), grid: hex(0xDADADF),
        veryLow: hex(0xB3261E), low: hex(0xD92D2A), tight: hex(0x17A34A), target: hex(0x6E9E1E),
        high: hex(0xD97706), veryHigh: hex(0xC2410C), extremelyHigh: hex(0xA3238F))
    static func of(dark: Bool) -> GraphPalette { dark ? darkPalette : lightPalette }
}

/// A chart scrolled through time (the dropdown; Android's app panel; the other surfaces can't scroll): the window ends
/// at `end` (at most now) instead of now; `y` = the y range to draw while the surface animates it towards the window's
/// own (`GlucoseCard.yRange`; nil = the window's own); `now` = the "Now" pill's visibility 0…1 (0 = none). GlucoseGraph.kt `ChartView`.
struct ChartView: Equatable {
    var end: TimeInterval
    var y: ClosedRange<Double>? = nil
    var now: CGFloat = 0
    /// The plot's bottom, pt above the chart's bottom, while the surface eases it (nil = the layout's, "chart.plot.natural").
    var plotInset: CGFloat? = nil
    /// The window's length while the surface is zoomed (`minSpan`…`maxSpan`; nil = the place's "time shown", `GraphStyle.window`).
    var span: TimeInterval? = nil
    /// The narrowest and widest window a pinch zooms to (GlucoseGraph.kt `ChartView.MIN_SPAN_S` / `MAX_SPAN_S`).
    static let minSpan: TimeInterval = 2 * 3600, maxSpan: TimeInterval = 31 * 86400
}

struct GraphStyle {
    var window: TimeInterval = 12 * 3600
    var bands = Bands()
    /// The time in range beside the graph: the distribution dots and the % labels.
    var side = true
    /// The crown over a perfect 100 in the % column (`GraphConfig.columnCrown`).
    var crown = true
    var axisLabels = true
    var treatments = true
    /// Extra dashed lines, mg/dL (`GraphConfig.lines`).
    var lines = GraphConfig.defaultLines
    /// Hours between the vertical lines, at least (`GraphConfig.gridHours`); 0 = none (the time labels stay).
    var gridHours = 1
    /// Which parts of each treatment kind show: point, mute line, amount, time (`GraphConfig.marks`).
    var marks = MarkShow.defaults
    /// The hour labels' cycle: the Mac's 24-hour setting (read where the style is made, so a test fixes it).
    var hour24 = TimeText.hour24
}

/// Which parts of each kind's marks the chart draws (Settings › Graph › Treatments on the graph; schema `graph.marks`,
/// mirrored): per kind (`kinds`) the point (marker), the mute line, the amount label and the time label (`allParts`),
/// only those the kind has (`parts`: long-acting mutes nothing, a note has only its marker). The defaults are the
/// schema's `markDefault.*` (carbs: the point only, the user 2026-10-21). Android `MarkShow` (TreatmentMarks.kt).
public struct MarkShow: Codable, Equatable, Sendable {
    static let point = "point", line = "line", amount = "amount", time = "time"
    static let kinds: [String] = MarkSchema.kinds
    static let allParts: [String] = MarkSchema.parts
    /// The parts kind `k` has, in `allParts` order.
    static func parts(_ k: String) -> [String] { MarkSchema.partsOf[k] ?? [] }
    private var on: [String: Set<String>]
    private init(_ m: [String: Set<String>]) {
        on = Dictionary(
            uniqueKeysWithValues: MarkShow.kinds.map { k in
                (k, Set(MarkShow.parts(k).filter { m[k]?.contains($0) == true }))
            })
    }
    public static let defaults = MarkShow(
        Dictionary(uniqueKeysWithValues: kinds.map { ($0, MarkSchema.defaultsOf[$0] ?? []) }))
    /// Every part of every kind (the look before the setting existed).
    public static let all = MarkShow(Dictionary(uniqueKeysWithValues: kinds.map { ($0, Set(parts($0))) }))

    func has(_ k: String, _ part: String) -> Bool { on[k]?.contains(part) == true }
    /// Anything of `k` shows.
    func any(_ k: String) -> Bool { !(on[k]?.isEmpty ?? true) }
    /// A label part (amount or time) of `k` shows.
    func label(_ k: String) -> Bool { has(k, MarkShow.amount) || has(k, MarkShow.time) }
    /// The same without any label (a chart too long for them: `GlucoseGraph.labelsMaxS`).
    func withoutLabels() -> MarkShow {
        MarkShow.kinds.reduce(self) { $0.with($1, MarkShow.amount, false).with($1, MarkShow.time, false) }
    }
    func with(_ k: String, _ part: String, _ v: Bool) -> MarkShow {
        var m = on; var p = m[k] ?? []
        if v { p.insert(part) } else { p.remove(part) }
        m[k] = p
        return MarkShow(m)
    }
    /// "rapid:point+line+amount+time,long:point+amount+time,carbs:point,note:point" (the stored and wire form).
    var text: String {
        MarkShow.kinds.map { k in "\(k):" + MarkShow.parts(k).filter { has(k, $0) }.joined(separator: "+") }.joined(
            separator: ",")
    }
    /// `text` back; a kind missing from it (or nil) keeps its default, unknown parts are dropped.
    static func parse(_ text: String?) -> MarkShow {
        guard let text, !text.trimmingCharacters(in: .whitespaces).isEmpty else { return defaults }
        var got: [String: Set<String>] = [:]
        for e in text.split(separator: ",") {
            let kv = e.split(separator: ":", maxSplits: 1, omittingEmptySubsequences: false)
            guard kv.count == 2 else { continue }
            got[kv[0].trimmingCharacters(in: .whitespaces)] = Set(
                kv[1].split(separator: "+").map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty })
        }
        return MarkShow(
            Dictionary(uniqueKeysWithValues: kinds.map { k in (k, got[k] ?? MarkSchema.defaultsOf[k] ?? []) }))
    }
    // stored as its text (the widget snapshot too)
    public init(from decoder: Decoder) throws {
        self = MarkShow.parse(try decoder.singleValueContainer().decode(String.self))
    }
    public func encode(to encoder: Encoder) throws { var c = encoder.singleValueContainer(); try c.encode(text) }
}

/// What a slot beside the value shows (investigation 18): a time-in-range ring or one of the report's numbers.
public enum SlotKind: String, Codable, CaseIterable, Sendable {
    case tir = "TIR", gmi = "GMI", average = "AVERAGE", cv = "CV"
    var label: String {
        switch self {
        case .tir: "Time in range"
        case .gmi: "GMI"
        case .average: "Average glucose"
        case .cv: "Variability (CV)"
        }
    }
    var short: String {
        switch self {
        case .tir: ""
        case .gmi: "GMI"
        case .average: "avg"
        case .cv: "CV"
        }
    }
}

/// One slot beside the value: its kind, window and, for a ring, which range its centre counts. `faded` = drawn
/// fainter by position (second, third); off = full strength wherever it sits.
public struct RingSpec: Codable, Equatable, Sendable {
    public var window: TimeInterval
    public var range: TirRange = .inRange
    public var kind: SlotKind = .tir
    public var faded = true
    /// A ring's segment order (the app-wide `ringOrder`).
    public var order: [Band] = DEFAULT_RING_ORDER
    /// Whether the ring wears its `TirMark` (`GraphConfig.ringMarks`).
    public var marks = true
    public var period: String { periodLabel(window) }
    public init(
        window: TimeInterval, range: TirRange = .inRange, kind: SlotKind = .tir, faded: Bool = true,
        order: [Band] = DEFAULT_RING_ORDER, marks: Bool = true
    ) {
        self.window = window
        self.range = range
        self.kind = kind
        self.faded = faded
        self.order = order
        self.marks = marks
    }
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
        let variance =
            rs.indices.reduce(0.0) {
                let d = Double(rs[$1].sgv) - mean; return $0 + d * d * w[$1]
            } / total
        return GlucoseMetrics(tir: tir, mean: mean, sd: variance.squareRoot(), readings: rs.count)
    }
}

/// The international consensus targets (Battelino 2019) LibreView prints as "Goals for type 1 and type 2 diabetes".
enum Goal: CaseIterable {
    case veryHigh, above, inRange, below, veryLow, average, gmi, cv
    static let minCoverage = 0.70
    var text: String {
        switch self {
        case .veryHigh: "< 5%"
        case .above: "< 25%"
        case .inRange: "> 70%"
        case .below: "< 4%"
        case .veryLow: "< 1%"
        case .average: "< 154 mg/dL"
        case .gmi: "< 7%"
        case .cv: "≤ 36%"
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
    /// For the time-in-range goals: whether a share (0...1) meets it; nil for the goals that aren't a share.
    func metShare(_ x: Double) -> Bool? {
        switch self {
        case .veryHigh: x < 0.05
        case .above: x < 0.25
        case .inRange: x > 0.70
        case .below: x < 0.04
        case .veryLow: x < 0.01
        default: nil
        }
    }
}

/// A time share as the number of a percentage, without the "%": "64", "5.2", "70.2" (ShareText in GlucoseMetrics.kt,
/// cases in spikes/graph-fixtures/share). Whole percent from 10 %, one decimal under 10 %, and more decimals (up to two)
/// whenever rounding would put the number on the other side of its goal than the value or show "100" for a share that
/// isn't all (100 is the crown's number, TirMark); a non-zero share too small to show is "<0.1", one too close to all ">99.9". [locale] nil = "." (the Mac's texts are English, like its other numbers).
enum ShareText {
    private static func format(_ x: Double, _ met: ((Double) -> Bool)?, _ locale: Locale?) -> String {
        func shown(_ d: Int) -> Double { let k = pow(10.0, Double(d + 2)); return (x * k).rounded() / k }
        var d = x < 0.095 ? 1 : 0
        // more decimals while rounding crosses the goal, or turns a share that isn't all into "100" (the crown's number)
        while d < 2, (met.map { $0(shown(d)) != $0(x) } ?? false) || (x < 1 && shown(d) >= 1) { d += 1 }
        if x > 0 && shown(d) == 0 { return "<" + String(format: "%.1f", locale: locale, 0.1) }
        if x < 1 && shown(d) >= 1 { return ">" + String(format: "%.1f", locale: locale, 99.9) }
        return String(format: "%.\(d)f", locale: locale, shown(d) * 100)
    }
    static func of(_ x: Double, _ goal: Goal? = nil, locale: Locale? = nil) -> String {
        format(x, goal.map { g in { g.metShare($0) ?? true } }, locale)
    }
    /// Coverage against the report's 70 % (the "only … % has data" note).
    static func coverage(_ x: Double, locale: Locale? = nil) -> String { format(x, { $0 >= Goal.minCoverage }, locale) }
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
    /// The sensor life badge beside the arrow and the countdown ring over the main ring (nil = nothing known).
    var sensor: SensorInfo? = nil
    /// The countdown window: the ring shows in the sensor's last N hours (`countdownHours`, 1–24, default 8).
    var countdownHours = SensorLife.defaultCountdownHours
    /// The sensor's time left on this card (`GraphConfig.sensorTime`); warm-up and ended show regardless. No default:
    /// every place passes its own setting.
    var sensorTime: Bool
    /// The ranges the value's colour and the slots count in: the user's (`GraphConfig.cardStyle` sets them), also on a
    /// card without a chart (the value widget used the built-in 180 after the high bound moved, 2026-10-21).
    var bands = Bands()
    /// The clock times' cycle (a late reading's time, "No reading since"): the Mac's 24-hour setting.
    var hour24 = TimeText.hour24
}

/// Everything the graph settings decide (UserDefaults on the Mac; the widget gets it in its snapshot).
public struct GraphConfig: Codable, Equatable, Sendable {
    public var bands = Bands()
    public var hours = 12
    public var ringCount = 2
    public var slots: [RingSpec] = GraphConfig.defaultSlots
    public var ringRange: TirRange = .inRange
    public var ringOrder: [Band] = DEFAULT_RING_ORDER
    public var side = true
    /// The crown over the 100 % beside the chart; the rings' rewards (laurel, flowers, crown).
    public var columnCrown = true, ringMarks = true
    /// Extra horizontal lines on the chart, mg/dL (100 by default; the user adds and removes them).
    public var lines = GraphConfig.defaultLines
    /// Hours between the vertical lines at least (`SettingsSpec.gridHours`; small charts space them further apart), 0 = none.
    public var gridHours = 1
    /// The last-treatment slot beside the value (on by default, next to the number).
    public var lastPlace: LastPlace = .nearValue
    /// What that slot shows while there's no recent treatment (nil = nothing): a 30-day time-in-range ring by default.
    public var lastElse: RingSpec? = GraphConfig.defaultLastElse
    /// The sensor countdown ring's window, hours (`SettingsSpec.countdownHours`; Android: the Sensor settings).
    public var countdownHours = SensorLife.defaultCountdownHours
    /// The sensor's time left on this place's cards (`<place>SensorTime`: menubarSensorTime, widgetSensorTime).
    public var sensorTime = true
    /// Per treatment kind: the point, the mute line, the amount and the time label on the chart (carbs: the point only).
    public var marks = MarkShow.defaults

    static let defaultLastElse = RingSpec(window: 30 * 86400)
    /// `lastElse` with the ring settings applied.
    public var lastElseRing: RingSpec? {
        lastElse.map {
            var r = $0; r.range = ringRange; r.order = ringOrder; r.marks = ringMarks; return r
        }
    }
    /// The card style of a surface with a value: the rings, the last-treatment slot and its fallback.
    func cardStyle(graph g: GraphStyle?, rings: [RingSpec]? = nil) -> CardStyle {
        CardStyle(
            graph: g, rings: rings ?? self.rings, value: true, last: lastPlace, lastElse: lastElseRing,
            countdownHours: countdownHours,
            sensorTime: sensorTime, bands: bands)
    }

    static let defaultLines = [100]

    /// Today and the last week as time in range; the third slot (off by default) the 14-day GMI, as on the report.
    static let defaultSlots = [
        RingSpec(window: 86400), RingSpec(window: 7 * 86400), RingSpec(window: 14 * 86400, kind: .gmi),
    ]

    public var rings: [RingSpec] {
        slots.prefix(max(0, min(3, ringCount))).map {
            var r = $0; r.range = ringRange; r.order = ringOrder; r.marks = ringMarks; return r
        }
    }

    func graphStyle(hours h: Int? = nil) -> GraphStyle {
        GraphStyle(
            window: TimeInterval((h ?? hours) * 3600), bands: bands, side: side, crown: columnCrown, lines: lines,
            gridHours: gridHours,
            marks: marks)
    }
    public init() {}
}
