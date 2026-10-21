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
    /// Exactly 1 when nothing is below or above (the sum of two shares can miss it; 100 % is the crown, `TirMark`).
    var inRange: Double { below <= 0 && above <= 0 && !empty ? 1 : tight + target }
    var below: Double { veryLow + low }
    var above: Double { high + veryHigh }
    var empty: Bool { coverage <= 0 }
    func of(_ r: TirRange) -> Double { r == .inRange ? inRange : below + above + target <= 0 && !empty ? 1 : tight }
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
    /// Only for the label layout's tie-break and the fixtures (TreatmentMarks.layout).
    var id: String = ""
}

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
        let src: [GraphReading] = sorted ? Array(all[lowerBound(from)..<lowerBound(to + 30)])
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
        // each band's area, then each level scaled to its band's share (no per-band lists: a scrolled chart does this every frame)
        var area = [Double](repeating: 0, count: Band.allCases.count)
        for i in 0..<n { area[b.of(Double(minMg + i)).rawValue] += d[i] }
        for i in 0..<n { let band = b.of(Double(minMg + i)); if area[band.rawValue] > 0 { d[i] *= tir.share(band) / area[band.rawValue] } }
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

    /// When the step of a reading taken at `t` changes next, seen at `now`; nil = never again / no reading.
    static func nextChangeAt(_ t: TimeInterval?, now: TimeInterval) -> TimeInterval? {
        guard let t else { return nil }
        return nextChange(age: now - t).map { t + $0 }
    }

    /// What every glucose surface shows for the latest reading (`sgv` at `t`, `rate` mg/dL/min) at `now`: the one
    /// decision behind the card (menubar dropdown, widgets) and the menubar title. Android's `Freshness.look`
    /// (graph/Freshness.kt) is the same rule; both run spikes/graph-fixtures/freshness. `ended` = the sensor has ended,
    /// `warmup` = a new sensor's warm-up time left: their own states, before any value. Surfaces ask `GlucoseCard.face`.
    /// `format` writes a value in the user's unit (mg/dL by default); LO / HI outside 40–400 mg/dL.
    static func look(sgv: Int?, t: TimeInterval?, rate: Double?, now: TimeInterval, ended: Bool = false, warmup: String? = nil,
                     format: (Int) -> String = { "\($0)" }) -> GlucoseLook {
        let fr = of(age: t.map { now - $0 })
        if let warmup { return GlucoseLook(state: .warmingUp, fresh: fr, text: nil, strike: false, trend: nil, warmup: warmup) }
        if ended { return GlucoseLook(state: .ended, fresh: fr, text: nil, strike: false, trend: nil) }
        guard let sgv, t != nil else { return GlucoseLook(state: .none, fresh: fr, text: nil, strike: false, trend: nil) }
        guard fr.showValue else { return GlucoseLook(state: .noReadings, fresh: fr, text: nil, strike: false, trend: nil) }
        return GlucoseLook(state: .value, fresh: fr, text: sgv < 40 ? "LO" : sgv > 400 ? "HI" : format(sgv), strike: fr.strike,
                           trend: rate != nil && !fr.strike ? TrendMark.arrow : TrendMark.noTrend, rate: rate)
    }

    /// "14:05" today, "Tue 14:05" this week, "12 Oct" before; the time in the Mac's 12/24-hour setting (`Clock`).
    /// A clock time doesn't tick.
    static func timeText(_ t: TimeInterval, now: TimeInterval) -> String {
        let d = Date(timeIntervalSince1970: t), n = Date(timeIntervalSince1970: now)
        let time = Clock.time(d)
        if Calendar.current.isDate(d, inSameDayAs: n) { return time }
        if now - t < 6 * 86400 { return d.formatted(.dateTime.weekday(.abbreviated)) + " " + time }
        return d.formatted(.dateTime.day().month(.abbreviated))
    }
}

/// The trend beside a value: the arrow, or `.noTrend` = no trend to show (no rate, or the value is struck through): a
/// hollow ring of the arrow's size (graph/Freshness.kt TrendMark: a circle has no direction, so it can't read as steady,
/// and unlike the old dot it doesn't look like a speck). Text places write it as `ring`.
enum TrendMark: String {
    case arrow, noTrend = "none"
    static let ring = "○"
    static func ringRadius(_ size: CGFloat) -> CGFloat { size * 0.24 }
    static func ringStroke(_ size: CGFloat) -> CGFloat { size * 0.11 }
}

/// `Freshness.look`'s answer (graph/Freshness.kt GlucoseLook): the state; for a value its text, whether it's struck
/// through and grey (5–20 min) and its trend (nil without a value).
struct GlucoseLook: Equatable {
    enum State: String {
        case value, noReadings = "no_readings", none, ended, warmingUp = "warming_up"
        /// The name the layout report uses (Android's enum names).
        var layoutName: String { rawValue.uppercased() }
    }
    var state: State
    var fresh: Freshness
    var text: String?
    var strike: Bool
    var trend: TrendMark?
    /// While warming up: the time left ("42m").
    var warmup: String? = nil
    var rate: Double? = nil
    var grey: Bool { strike }
    var shown: Bool { state == .value }
    /// SF Symbol for "no readings for 20 min" (Android: Material Symbols sensors_off): the menubar and the card.
    static let noReadingsSymbol = "antenna.radiowaves.left.and.right.slash"
}

// MARK: - Clock

/// Every clock time Sukrara shows on the Mac ("21:08" / "9:08 PM"): the menubar tooltip and dropdown, the widgets, the
/// treatments, the report, the graph's hour labels. They follow System Settings → General → Date & Time → 24-hour
/// time, not the locale's default (Android: graph/Clock.kt; shared cases in spikes/graph-fixtures/clock).
enum Clock {
    /// Self-tests: the 24-hour setting to use instead of the Mac's (nil in the app), and every time formatted with the
    /// cycle it was formatted in, as it happens.
    nonisolated(unsafe) static var hour24Override: Bool?
    nonisolated(unsafe) static var observer: ((String, Bool) -> Void)?

    /// The Mac's 24-hour setting: the ONE place it is read. Every surface (dropdown card, hour labels, widgets, menubar
    /// tooltips, settings, reports) formats through `Clock` with it, never a flag of its own (2026-10-21: Android's value
    /// widget and chart cards read it from different places). The explicit-cycle functions are for the clock fixtures.
    static var hour24: Bool {
        hour24Override ?? !(DateFormatter.dateFormat(fromTemplate: "j", options: 0, locale: .current) ?? "").contains("a")
    }

    /// What tells that the clock's look changed while Sukrara runs: System Settings › Date & Time › 24-hour time posts
    /// the current locale's change, and the time zone. The status item redraws and re-publishes to the widgets on them
    /// (`observeChanges`, `-graphselftest` posts each one).
    static let changeNotifications: [Notification.Name] = [NSLocale.currentLocaleDidChangeNotification, .NSSystemTimeZoneDidChange]

    /// Calls `f` on the main queue for each of `changeNotifications` posted on `center`; keep the tokens.
    static func observeChanges(_ center: NotificationCenter = .default, _ f: @escaping () -> Void) -> [NSObjectProtocol] {
        changeNotifications.map { center.addObserver(forName: $0, object: nil, queue: .main) { _ in f() } }
    }

    /// "21:08" / "9:08 PM" in the Mac's setting.
    static func time(_ d: Date) -> String { time(d, hour24: hour24, locale: .autoupdatingCurrent) }

    /// "21:08" / "9:08 PM" in the given hour cycle and locale.
    static func time(_ d: Date, hour24: Bool, locale: Locale, zone: TimeZone = .current) -> String {
        let f = DateFormatter()
        // the bare locale: `.autoupdatingCurrent` carries the user's forced 12/24-hour preference, which would override
        // the cycle asked for here (the setting is read once, in `hour24`)
        f.locale = Locale(identifier: locale.identifier); f.timeZone = zone
        f.setLocalizedDateFormatFromTemplate(hour24 ? "Hmm" : "hmm")
        let s = f.string(from: d)
        observer?(s, hour24)
        return s
    }

    /// "12 Oct 2026, 21:08": the locale's medium date and the time.
    static func dateTime(_ d: Date) -> String { d.formatted(date: .abbreviated, time: .omitted) + ", " + time(d) }

    /// A minute of the day (0–1439) as a clock time (the fixtures).
    static func minuteOfDay(_ m: Int, hour24: Bool, locale: Locale) -> String {
        time(Date(timeIntervalSince1970: TimeInterval(m * 60)), hour24: hour24, locale: locale, zone: TimeZone(identifier: "UTC")!)
    }

    /// The graph's day label (a zoomed-out chart): "17 Oct" (Clock.kt `dayLabel`).
    static func dayLabel(_ d: Date, locale: Locale = .autoupdatingCurrent) -> String {
        let f = DateFormatter(); f.locale = locale; f.dateFormat = "d MMM"
        return f.string(from: d)
    }

    /// The graph's hour label: "21:00" / "9 PM" (the locale's day period).
    static func hourLabel(_ h: Int, _ hour24: Bool = Clock.hour24, locale: Locale = .autoupdatingCurrent) -> String {
        let f = DateFormatter(); f.locale = locale
        let s = hour24 ? String(format: "%02d:00", h) : "\(h % 12 == 0 ? 12 : h % 12) \(h < 12 ? f.amSymbol! : f.pmSymbol!)"
        observer?(s, hour24)
        return s
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
}

/// Which parts of each kind's marks the chart draws (Settings › Graph › Treatments on the graph; schema `graph.marks`,
/// mirrored): per kind (`kinds`) the point (marker), the mute line, the amount label and the time label (`allParts`),
/// only those the kind has (`parts`: long-acting mutes nothing, a note has only its marker). The defaults are the
/// schema's `markDefault.*` (carbs: the point only, the user 2026-10-21). Android `MarkShow` (TreatmentMarks.kt).
struct MarkShow: Codable, Equatable {
    static let point = "point", line = "line", amount = "amount", time = "time"
    static let kinds: [String] = SettingsSpec.options["markKind"] ?? []
    static let allParts: [String] = SettingsSpec.options["markPart"] ?? []
    /// The parts kind `k` has, in `allParts` order.
    static func parts(_ k: String) -> [String] { SettingsSpec.options["markParts.\(k)"] ?? [] }
    private var on: [String: Set<String>]
    private init(_ m: [String: Set<String>]) {
        on = Dictionary(uniqueKeysWithValues: MarkShow.kinds.map { k in (k, Set(MarkShow.parts(k).filter { m[k]?.contains($0) == true })) })
    }
    static let defaults = MarkShow(Dictionary(uniqueKeysWithValues: kinds.map { ($0, Set(SettingsSpec.options["markDefault.\($0)"] ?? [])) }))
    /// Every part of every kind (the look before the setting existed).
    static let all = MarkShow(Dictionary(uniqueKeysWithValues: kinds.map { ($0, Set(parts($0))) }))

    func has(_ k: String, _ part: String) -> Bool { on[k]?.contains(part) == true }
    /// Anything of `k` shows.
    func any(_ k: String) -> Bool { !(on[k]?.isEmpty ?? true) }
    /// A label part (amount or time) of `k` shows.
    func label(_ k: String) -> Bool { has(k, MarkShow.amount) || has(k, MarkShow.time) }
    /// The same without any label (a chart too long for them: `GlucoseGraph.labelsMaxS`).
    func withoutLabels() -> MarkShow { MarkShow.kinds.reduce(self) { $0.with($1, MarkShow.amount, false).with($1, MarkShow.time, false) } }
    func with(_ k: String, _ part: String, _ v: Bool) -> MarkShow {
        var m = on; var p = m[k] ?? []
        if v { p.insert(part) } else { p.remove(part) }
        m[k] = p
        return MarkShow(m)
    }
    /// "rapid:point+line+amount+time,long:point+amount+time,carbs:point,note:point" (the stored and wire form).
    var text: String { MarkShow.kinds.map { k in "\(k):" + MarkShow.parts(k).filter { has(k, $0) }.joined(separator: "+") }.joined(separator: ",") }
    /// `text` back; a kind missing from it (or nil) keeps its default, unknown parts are dropped.
    static func parse(_ text: String?) -> MarkShow {
        guard let text, !text.trimmingCharacters(in: .whitespaces).isEmpty else { return defaults }
        var got: [String: Set<String>] = [:]
        for e in text.split(separator: ",") {
            let kv = e.split(separator: ":", maxSplits: 1, omittingEmptySubsequences: false)
            guard kv.count == 2 else { continue }
            got[kv[0].trimmingCharacters(in: .whitespaces)] = Set(kv[1].split(separator: "+").map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty })
        }
        return MarkShow(Dictionary(uniqueKeysWithValues: kinds.map { k in (k, got[k] ?? Set(SettingsSpec.options["markDefault.\(k)"] ?? [])) }))
    }
    // stored as its text (the widget snapshot too)
    init(from decoder: Decoder) throws { self = MarkShow.parse(try decoder.singleValueContainer().decode(String.self)) }
    func encode(to encoder: Encoder) throws { var c = encoder.singleValueContainer(); try c.encode(text) }
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
    /// Whether the ring wears its `TirMark` (`GraphConfig.ringMarks`).
    var marks = true
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
}

/// Which part of a card one `GlucoseCard.draw` call paints (CardPart in GlucoseCard.kt): a surface that puts parts of
/// the card in boxes of its own draws each part with the SAME whole-card style, so the decisions that depend on the
/// whole card are still made once, in `GlucoseCard`. `header` = value + slots, `value` = the value alone, `slots` = the
/// slots alone, `graph` = the chart alone.
enum CardPart: String { case all, header, value, slots, graph }

/// Every place on the Mac that shows the card or its value: the registry of spikes/graph-fixtures/surfaces/surfaces.json
/// (CardSurface.kt is Android's). A card surface draws only through `GlucoseCard.draw` / `image` and names itself; a
/// glyph surface (the menubar title) takes its value from `GlucoseCard.face`. tools/check (card-surface, card-api) and
/// the layout self-test (LayoutSelfTest.swift, in -graphselftest) enforce it.
enum CardSurface: String, CaseIterable {
    case menubarCard = "menubar-card"
    case menubarTitle = "menubar-title"
    case widgetValue = "widget-value"
    case widgetGraph = "widget-graph"
    case widgetCombined = "widget-combined"
    /// Picture sheets and self-tests only.
    case test = "test"

    var glyph: Bool { self == .menubarTitle }
}

/// What one `GlucoseCard.draw` call laid out (CardLayout in CardSurface.kt): every element, its box (in the call's own
/// coordinates) and what it shows. Read by the layout self-test; ignored by the app.
final class CardLayout {
    struct El { var name: String; var box: CGRect?; var info: String }
    let surface: CardSurface, part: CardPart, style: CardStyle, w: CGFloat, h: CGFloat, d: CGFloat
    var elements: [El] = []
    init(_ surface: CardSurface, _ part: CardPart, _ style: CardStyle, w: CGFloat, h: CGFloat, d: CGFloat) {
        self.surface = surface; self.part = part; self.style = style; self.w = w; self.h = h; self.d = d
    }
    func add(_ name: String, _ box: CGRect? = nil, _ info: String = "") { elements.append(El(name: name, box: box, info: info)) }
    func first(_ name: String) -> El? { elements.first { $0.name == name } }
    func all(_ name: String) -> [El] { elements.filter { $0.name == name } }
    /// `px` in dp to the nearest 0.5, as the report writes it on both platforms ("33.5", "40").
    static func dp(_ px: CGFloat, _ d: CGFloat) -> String {
        let v = (Double(px / d) * 2).rounded(.toNearestOrAwayFromZero) / 2
        return v == v.rounded(.down) ? "\(Int64(v))" : "\(v)"
    }
}


/// Where the sensor's time left goes on a card (`GlucoseCard.sensorSpot`; SensorSpot in GlucoseCard.kt).
enum SensorSpot: String { case graph, none }

/// Everything the graph settings decide (UserDefaults on the Mac; the widget gets it in its snapshot).
struct GraphConfig: Codable, Equatable {
    var bands = Bands()
    var hours = 12
    var ringCount = 2
    var slots: [RingSpec] = GraphConfig.defaultSlots
    var ringRange: TirRange = .inRange
    var ringOrder: [Band] = DEFAULT_RING_ORDER
    var side = true
    /// The crown over the 100 % beside the chart; the rings' rewards (laurel, flowers, crown).
    var columnCrown = true, ringMarks = true
    /// Extra horizontal lines on the chart, mg/dL (100 by default; the user adds and removes them).
    var lines = GraphConfig.defaultLines
    /// Hours between the vertical lines at least (`SettingsSpec.gridHours`; small charts space them further apart), 0 = none.
    var gridHours = 1
    /// The last-treatment slot beside the value (on by default, next to the number).
    var lastPlace: LastPlace = .nearValue
    /// What that slot shows while there's no recent treatment (nil = nothing): a 30-day time-in-range ring by default.
    var lastElse: RingSpec? = GraphConfig.defaultLastElse
    /// The sensor countdown ring's window, hours (`SettingsSpec.countdownHours`; Android: the Sensor settings).
    var countdownHours = SensorLife.defaultCountdownHours
    /// The sensor's time left on this place's cards (`<place>SensorTime`: menubarSensorTime, widgetSensorTime).
    var sensorTime = true
    /// Per treatment kind: the point, the mute line, the amount and the time label on the chart (carbs: the point only).
    var marks = MarkShow.defaults

    static let defaultLastElse = RingSpec(window: 30 * 86400)
    /// `lastElse` with the ring settings applied.
    var lastElseRing: RingSpec? { lastElse.map { var r = $0; r.range = ringRange; r.order = ringOrder; r.marks = ringMarks; return r } }
    /// The card style of a surface with a value: the rings, the last-treatment slot and its fallback.
    func cardStyle(graph g: GraphStyle?, rings: [RingSpec]? = nil) -> CardStyle {
        CardStyle(graph: g, rings: rings ?? self.rings, value: true, last: lastPlace, lastElse: lastElseRing, countdownHours: countdownHours,
                  sensorTime: sensorTime, bands: bands)
    }

    static let defaultLines = [100]
    /// "100,120" → sorted distinct values the line format accepts (`SettingsSpec.line`), at most its `maxItems`.
    static func parseLines(_ text: String) -> [Int] {
        let spec = SettingsSpec.line
        return Array(Set(text.split(separator: ",").compactMap { Double($0.trimmingCharacters(in: .whitespaces)) }.filter(spec.valid).map { Int($0) })
            .sorted().prefix(spec.maxItems))
    }
    static func linesText(_ l: [Int]) -> String { l.map(String.init).joined(separator: ",") }

    /// Today and the last week as time in range; the third slot (off by default) the 14-day GMI, as on the report.
    static let defaultSlots = [RingSpec(window: 86400), RingSpec(window: 7 * 86400), RingSpec(window: 14 * 86400, kind: .gmi)]

    var rings: [RingSpec] { slots.prefix(max(0, min(3, ringCount))).map { var r = $0; r.range = ringRange; r.order = ringOrder; r.marks = ringMarks; return r } }

    /// "TIR:86400:1,TIR:604800:1,GMI:1209600:1" (kind:window s:faded).
    static func slotsText(_ s: [RingSpec]) -> String { s.map { "\($0.kind.rawValue):\(Int($0.window)):\($0.faded ? 1 : 0)" }.joined(separator: ",") }
    static func parseSlots(_ text: String) -> [RingSpec]? {
        let s = text.split(separator: ",").compactMap { parseSlot(String($0)) }
        return s.count == 3 ? s : nil
    }
    /// One slot, "TIR:2592000:1"; nil when malformed (or "" = none, for `lastElse`). The period is coerced to the
    /// schema's `ringWindow` (a preset, or whole days 1–90).
    static func parseSlot(_ e: String) -> RingSpec? {
        let p = e.split(separator: ":").map(String.init)
        guard p.count > 1, let kind = SlotKind(rawValue: p[0]), let w = TimeInterval(p[1]) else { return nil }
        return RingSpec(window: SettingsSpec.ringWindow.coerce(w), kind: kind, faded: p.count < 3 || p[2] != "0")
    }
    func graphStyle(hours h: Int? = nil) -> GraphStyle {
        GraphStyle(window: TimeInterval((h ?? hours) * 3600), bands: bands, side: side, crown: columnCrown, lines: lines, gridHours: gridHours,
                   marks: marks)
    }

    /// The mirrored settings as this Mac stores them in UserDefaults (keys of `WearerSettings.graphWire`): what
    /// "Copy the Wearer's Settings" writes.
    func mirroredDefaults() -> [String: Any] {
        ["bandVeryLow": bands.veryLow, "bandLow": bands.low, "bandTight": bands.tight, "bandHigh": bands.high, "bandVeryHigh": bands.veryHigh,
         "bandExtremelyHigh": bands.extremelyHigh, "graphHours": hours, "ringCount": ringCount, "slots": GraphConfig.slotsText(slots),
         "ringRange": ringRange.rawValue, "ringOrder": ringOrderText(ringOrder), "ringMarks": ringMarks, "side": side,
         "columnCrown": columnCrown, "lines": GraphConfig.linesText(lines), "gridHours": gridHours, "lastPlace": lastPlace.rawValue,
         "lastElse": lastElse.map { GraphConfig.slotsText([$0]) } ?? "", "countdownHours": countdownHours, "marks": marks.text]
    }

    /// The graph settings that apply, for `place` ("menubar" / "widget") or the default. The mirrored ones (schema
    /// `mirror`: ranges, time shown, rings, lines, last treatment, countdown) come from Settings from
    /// (`WearerSettings.source`: this Mac's keys, the defaults, or the wearer's settings event); the place's own window
    /// (`<place>Hours`, 0 = same as default) and sensor time (`<place>SensorTime`) are always this Mac's. Every number
    /// goes through its schema format (`SettingsSpec`, `NumberSpec.coerce`), so any value the settings accept loads back.
    /// Keys: graphHours, menubarHours / widgetHours, ringCount, slots (see `slotsText`), ringRange IN_RANGE|TIGHT,
    /// ringOrder "TIGHT,TARGET,LOW,VERY_LOW,HIGH,VERY_HIGH", bandVeryLow/bandLow/bandTight/bandHigh/bandVeryHigh/
    /// bandExtremelyHigh, side, columnCrown, ringMarks, lines "100,120", gridHours (0 = off), lastPlace
    /// OFF|NEAR_VALUE|END, lastElse "TIR:2592000:1" or "" (same spellings as Android's GraphConfig JSON), countdownHours,
    /// marks "rapid:point+line+amount+time,long:…,carbs:point,note:point" (`MarkShow.text`).
    static func load(_ d: UserDefaults = .standard, place: String? = nil, source: WearerSettings.Source? = nil) -> GraphConfig {
        let src = source ?? WearerSettings.source(d)
        var c = GraphConfig()
        func any(_ k: String) -> Any? { WearerSettings.graphValue(k, src, d) }
        func num(_ v: Any?) -> Double? {
            if let s = v as? String { return Double(s.trimmingCharacters(in: .whitespaces)) }
            return (v as? NSNumber)?.doubleValue
        }
        func int(_ k: String, _ spec: NumberSpec) -> Int? { num(any(k)).map { Int(spec.coerce($0)) } }
        func bool(_ k: String) -> Bool? {
            if let s = any(k) as? String { return ["yes", "true", "1"].contains(s.lowercased()) }
            return (any(k) as? NSNumber)?.boolValue
        }
        func string(_ k: String) -> String? { any(k) as? String }
        let S = SettingsSpec.self
        c.bands = Bands(veryLow: int("bandVeryLow", S.bandVeryLow) ?? c.bands.veryLow, low: int("bandLow", S.bandLow) ?? c.bands.low,
                        tight: int("bandTight", S.bandTight) ?? c.bands.tight, high: int("bandHigh", S.bandHigh) ?? c.bands.high,
                        veryHigh: int("bandVeryHigh", S.bandVeryHigh) ?? c.bands.veryHigh,
                        extremelyHigh: int("bandExtremelyHigh", S.bandExtremelyHigh) ?? c.bands.extremelyHigh).sane()
        c.hours = int("graphHours", S.graphHours) ?? Int(S.graphHours.default)
        // the place's own window: this Mac's, never mirrored; 0 = same as the default
        if let p = place, let h = num(d.object(forKey: "\(p)Hours")).map(S.placeHours.coerce), h > 0 { c.hours = Int(h) }
        c.ringCount = int("ringCount", S.ringCount) ?? Int(S.ringCount.default)
        if let s = string("slots").flatMap(parseSlots) { c.slots = s }
        c.ringRange = TirRange(rawValue: string("ringRange") ?? "") ?? .inRange
        c.ringOrder = parseRingOrder(string("ringOrder") ?? "") ?? DEFAULT_RING_ORDER
        c.side = bool("side") ?? true
        c.columnCrown = bool("columnCrown") ?? true
        c.ringMarks = bool("ringMarks") ?? true
        c.lines = string("lines").map(parseLines) ?? defaultLines
        c.gridHours = int("gridHours", S.gridHours) ?? Int(S.gridHours.default)
        c.lastPlace = LastPlace(rawValue: string("lastPlace") ?? "") ?? .nearValue
        if let e = string("lastElse") { c.lastElse = parseSlot(e) }
        c.sensorTime = place.flatMap { d.object(forKey: "\($0)SensorTime") as? Bool } ?? true
        c.countdownHours = int("countdownHours", S.countdownHours) ?? Int(S.countdownHours.default)
        c.marks = MarkShow.parse(string("marks"))
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
    /// The outline of `s` (`width` wide, round joins) in the context's blend mode: a label's halo (drawn with
    /// `.destinationOut` to erase what is under the label).
    static func halo(_ ctx: CGContext, _ s: String, x: CGFloat, y: CGFloat, _ font: CTFont, width: CGFloat) {
        let attrs: [NSAttributedString.Key: Any] = [.font: font, .strokeColor: GraphPalette.hex(0),
                                                    .strokeWidth: width / CTFontGetSize(font) * 100]
        let l = CTLineCreateWithAttributedString(NSAttributedString(string: s, attributes: attrs))
        ctx.saveGState()
        ctx.textMatrix = CGAffineTransform(scaleX: 1, y: -1)
        ctx.textPosition = CGPoint(x: x, y: y)
        CTLineDraw(l, ctx)
        ctx.restoreGState()
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

/// A fixed text width model (TextModel.kt), so layout decisions never depend on the font (SF Pro here, Roboto on
/// Android): the treatment label layout and the sensor badge's room use it (the side columns measure their real labels); only the drawing measures real text
/// (centred in the modelled box). Widths in em, at or a little above the wider of SF Pro and Roboto at bold weight and
/// small sizes (SF Pro Text Bold at 9 pt: digits up to 0.71 em); `-graphselftest` checks every fixture label fits.
enum TextModel {
    static func em(_ c: Character) -> CGFloat {
        switch c {
        case "0"..."9": return 0.72
        case ".", ",", "·", ":": return 0.38
        case " ": return 0.3
        case "m": return 0.96
        case "w": return 0.88
        case "i", "l", "j": return 0.32
        case "r", "f", "t": return 0.46
        case "%": return 1.06
        case "a"..."z": return 0.68
        case "A"..."Z": return 0.78
        default: return 0.75
        }
    }
    /// Width of `s` at text size `size` (same unit as `size`).
    static func width(_ s: String, _ size: CGFloat) -> CGFloat { size * s.reduce(0) { $0 + em($1) } }
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
    /// `alerts` = the rules behind the treatments' mute lines (nil = no lines). `sensor` = the life badge to put in the
    /// hour-label row (`sensorTop`); only `GlucoseCard` decides that (`GlucoseCard.sensorSpot`), so only it calls this
    /// (tools/check `graph-surface`).
    static func draw(_ ctx: CGContext, _ area: CGRect, readings: [GraphReading], marks: [GraphMark], now: TimeInterval,
                     _ st: GraphStyle, _ pal: GraphPalette, d: CGFloat, alerts: AlertSettings?,
                     sensor: SensorBadge.Look?, rec: CardLayout, view: ChartView? = nil, latestT: TimeInterval? = nil) {
        // The window drawn: the view's (a scrolled chart) or the last `st.window` up to now. Everything the chart shows
        // (line, distribution, %, y range, treatments, hour labels) is about this window only.
        let end = min(view?.end ?? now, now)
        let from = end - st.window
        let b = st.bands.sane()
        let pts = GlucoseStats.window(readings, from: from - 60, to: end)
        let tir = GlucoseStats.timeInRange(pts, from: from, to: end, bands: b)
        let text = Ink.font(10 * d, .regular)
        let pctBig = Ink.font(13 * d, .bold), pctSmall = Ink.font(8.5 * d, .regular)

        let labels = st.axisLabels
        let p = pct3(tir.below, tir.inRange, tir.above)
        // the % labels as drawn (real font): the column is the widest of them plus `sideGap` on each side
        func pctW(_ v: Int) -> CGFloat { Ink.width("\(v)", pctBig) + Ink.width("%", pctSmall) }
        let pctLabelW = max(pctW(p.0), pctW(p.1), pctW(p.2))
        // Treatment labels go under their markers, in the hour labels' row and, when needed, a second row under it.
        // Only the parts the settings show (`st.marks`): an entry with nothing shown is left out, and the marker lane
        // is there only when a point or a mute line is drawn in it.
        // A window longer than `labelsMaxS` (zoomed far out, a long widget) shows no treatment labels: they would be
        // meaningless crowds (the user, 2026-10-21); points and mute lines follow the settings as always.
        let show = st.window > Self.labelsMaxS ? st.marks.withoutLabels() : st.marks
        let muteUntil: ((GraphMark) -> TimeInterval?)? = alerts.map { a in { AlertStatus.muteUntil($0, a) } }
        func mutes(_ m: GraphMark) -> Bool { show.has(m.k, MarkShow.line) && muteUntil?(m) != nil }
        let shownMarks = st.treatments ? marks.filter { m in
            m.t >= from && m.t <= end && show.any(m.k) &&
                (show.has(m.k, MarkShow.point) || show.label(m.k) && TreatmentMarks.amount(m) != nil || mutes(m))
        } : []
        let lane = shownMarks.contains { show.has($0.k, MarkShow.point) || mutes($0) }
        // the sensor badge's place in the hour-label row is kept free of treatment labels
        let badgeRoom: (CGFloat, CGFloat)? = labels ? sensor.map { (area.minX, area.minX + sensorWidth($0.label, d: d) + 3 * d) } : nil
        // The y range of the window (`yRange`); a scrolling surface animates it towards that and passes the current one.
        let own = yRange(pts, bands: b)
        let yMin = view?.y?.lowerBound ?? own.lowerBound, yMax = view?.y?.upperBound ?? own.upperBound
        rec.add("chart.y", nil, "\(Int(own.lowerBound.rounded()))-\(Int(own.upperBound.rounded()))")
        let split = b.tight < b.high
        // The values on the right: low and high always, then very good, very low / very high and the extra lines,
        // each only where it doesn't touch one already written (in the plot from `top` to `plotBottom`).
        let candidates = !labels ? [] : ([b.low, b.high] + (split ? [b.tight] : []) + [b.veryLow, b.veryHigh, b.extremelyHigh] + st.lines)
            .filter { Double($0) > yMin && Double($0) < yMax }
        func axisValues(_ top: CGFloat, _ plotBottom: CGFloat) -> [(Int, CGFloat)] {
            var out: [(Int, CGFloat)] = []
            for v in candidates {
                let ly = plotBottom - CGFloat((Double(v) - yMin) / (yMax - yMin)) * (plotBottom - top)
                if !out.contains(where: { abs($0.1 - ly) < 12 * d }) { out.append((v, ly)) }
            }
            return out
        }
        func valuesW(_ vs: [Int]) -> CGFloat { vs.map { Ink.width("\($0)", text) }.max() ?? 0 }
        // The plot's sides: the % column and the values column, each its widest drawn label + `sideGap` on each side.
        // Which values show depends on the plot's height, which depends (second treatment lane) on its width: start from
        // every candidate and settle on the values actually written (GlucoseGraph.kt).
        struct Geo { var valW: CGFloat; var left: CGFloat; var right: CGFloat; var layout: TreatmentMarks.Layout?
                     var lane1: CGFloat; var top: CGFloat; var bottom: CGFloat; var plotBottom: CGFloat; var values: [(Int, CGFloat)] }
        func geo(_ valW: CGFloat) -> Geo {
            let (left, right) = plotX(area.minX, area.maxX, pct: st.side && !tir.empty, labels: labels, d: d, pctW: pctLabelW, valW: valW)
            let layout = shownMarks.isEmpty ? nil
                : TreatmentMarks.layout(shownMarks, x: { left + CGFloat(($0 - from) / st.window) * (right - left) }, now: now,
                                        minX: area.minX, maxX: area.maxX, lanes: lanes(area.height, d: d), d: d, reserve: badgeRoom, show: show)
            let lane1: CGFloat = layout?.twoLanes == true ? TreatmentMarks.laneH * d : 0
            let textRow = labels || !(layout?.labels.isEmpty ?? true)
            let top = area.minY + 3 * d
            let bottom = area.maxY - (textRow ? 10 * d + 4 * d : 0) - lane1
            let plotBottom = lane ? bottom - 12 * d : bottom
            return Geo(valW: valW, left: left, right: right, layout: layout, lane1: lane1, top: top, bottom: bottom,
                       plotBottom: plotBottom, values: axisValues(top, plotBottom))
        }
        var g = geo(valuesW(candidates))
        for _ in 0..<3 {
            let w = valuesW(g.values.map(\.0))
            if w == g.valW { break }
            g = geo(w)
        }
        let left = g.left, right = g.right, layout = g.layout, top = g.top
        // A scrolling surface eases the plot's bottom (the treatment lane and the second label row come and go with the
        // marks in the window and the zoom) instead of letting the plot jump: `view.plotInset` is the distance from the
        // area's bottom it draws at; the marker lane and the label rows follow it (GlucoseGraph.kt).
        let plotBottom = view?.plotInset.map { min(max(area.maxY - $0, top + 20 * d), area.maxY) } ?? g.plotBottom
        let eased = view?.plotInset != nil
        let textRow = labels || !(layout?.labels.isEmpty ?? true)
        let lane1: CGFloat = !eased ? g.lane1
            : min(max(area.maxY - (textRow ? 14 * d : 0) - (lane ? 12 * d : 0) - plotBottom, 0), TreatmentMarks.laneH * d)
        let bottom: CGFloat = !eased ? g.bottom : lane ? plotBottom + 12 * d : plotBottom
        let values = plotBottom == g.plotBottom ? g.values : axisValues(top, plotBottom)
        let pctCol = left - area.minX
        func x(_ t: TimeInterval) -> CGFloat { left + CGFloat((t - from) / st.window) * (right - left) }
        rec.add("chart", area, "\(Int(st.window / 3600))h")
        if end < now { rec.add("chart.window", nil, "\(Int((from - now) / 60))m..\(Int((end - now) / 60))m") }
        guard right - left >= 20 * d, plotBottom - top >= 20 * d else {
            rec.add("chart.tooSmall", area)
            if let sensor { rec.add("chart.sensor", nil, "\(sensor.label) (no room)") }
            return
        }
        rec.add("chart.plot", CGRect(x: left, y: top, width: right - left, height: plotBottom - top))
        // where the plot ends without easing (what a scrolling surface eases `view.plotInset` towards)
        rec.add("chart.plot.natural", CGRect(x: left, y: top, width: right - left, height: g.plotBottom - top))
        if lane1 > 0 { rec.add("chart.lane2") }
        // The whole chart in one layer: the treatment labels' halos are erased from it (on any background). A little
        // wider than the area: a marker at the plot's edge may stick out.
        ctx.beginTransparencyLayer(in: area.insetBy(dx: -6 * d, dy: 0), auxiliaryInfo: nil)
        defer { ctx.endTransparencyLayer() }

        func y(_ v: Double) -> CGFloat { plotBottom - CGFloat((min(max(v, yMin), yMax) - yMin) / (yMax - yMin)) * (plotBottom - top) }
        let sideW: CGFloat = st.side && !tir.empty ? min((right - left) * 0.26, 130 * d) : 0

        // The in-range band across the whole plot; its very good part (low…tight) a little stronger, in its own colour.
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
        // From a day up (a zoomed-out chart) the lines fall on midnights (every day, 2 or 3 days, a week: Mondays) and the labels
        // are dates; the steps are fixed to the calendar, not to the window, so they don't shift while the chart scrolls.
        let step = Self.gridStep(pxPerHour, gridHours: st.gridHours, d: d)
        let cal = Calendar.current
        var hour = cal.dateInterval(of: .hour, for: Date(timeIntervalSince1970: from))!.start.addingTimeInterval(3600)
        while !Self.onStep(hour, step, cal) { hour.addTimeInterval(3600) }
        ctx.saveGState()
        ctx.setStrokeColor(pal.grid.a(pal.decor))
        ctx.setLineWidth(1 * d)
        ctx.setLineDash(phase: 0, lengths: [2 * d, 3 * d])
        // The sensor life badge at the start of the hour labels' row, under the % column and the distribution (the
        // user, 2026-10-19/21); hour labels start after it, treatment labels keep clear of it (badgeRoom).
        var lastLabelEnd = -CGFloat.infinity
        if let sensor, let badgeRoom {
            let sy = sensorTop(area.maxY, lane1: lane1, d: d)
            SensorBadge.draw(ctx, x: area.minX, y: sy, h: sensorDp * d, sensor, pal)
            rec.add("chart.sensor", CGRect(x: area.minX, y: sy, width: sensorWidth(sensor.label, d: d), height: sensorDp * d), sensor.label)
            lastLabelEnd = badgeRoom.1
        }
        while hour.timeIntervalSince1970 <= end {
            let gx = x(hour.timeIntervalSince1970)
            if gx > left + sideW * 0.6 {
                if st.gridHours > 0 { ctx.strokeLineSegments(between: [CGPoint(x: gx, y: top), CGPoint(x: gx, y: plotBottom)]) }
                if labels {
                    let s = step >= 24 ? Clock.dayLabel(hour) : Clock.hourLabel(cal.component(.hour, from: hour))
                    let tw = Ink.width(s, text)
                    let lx = min(max(gx - tw / 2, left), right - tw)
                    // a label squeezed against the right edge would touch its neighbour; a treatment label wins
                    if lx > lastLabelEnd + 6 * d && !(layout?.blocks(lx - 3 * d, lx + tw + 3 * d) ?? false) {
                        Ink.draw(ctx, s, x: lx, y: area.maxY - 2 * d - lane1, text, pal.inkSoft)
                        rec.add("time.text", nil, s)
                        if rec.first("chart.hours") == nil { rec.add("chart.hours") }
                        lastLabelEnd = lx + tw
                    }
                }
            }
            if step >= 24 {
                repeat { hour = cal.date(byAdding: .day, value: 1, to: hour)! } while !Self.onStep(hour, step, cal)
            } else { hour.addTimeInterval(TimeInterval(step * 3600)) }
        }
        ctx.restoreGState()
        if labels && rec.first("chart.hours") == nil { rec.add("chart.hours", nil, "(no room)") }
        // The values on the right (axisValues), left-aligned `sideGap` after the plot; the widest ends `sideGap` before
        // the area's edge.
        for (v, ly) in values {
            let s = "\(v)", base = ly + 10 * d / 3, w = Ink.width(s, text)
            Ink.draw(ctx, s, x: right + Self.sideGap * d, y: base, text, pal.inkSoft)
            // the drawn label's box (LayoutSelfTest checks the column's gaps against it: layout/rules.json "gaps")
            rec.add("chart.values.label", CGRect(x: right + Self.sideGap * d, y: base - 10 * d * 0.72, width: w, height: 10 * d * 0.72), s)
        }
        if !values.isEmpty {
            rec.add("chart.values", CGRect(x: right, y: top, width: area.maxX - right, height: plotBottom - top),
                    values.map { String($0.0) }.joined(separator: ",") + " [w" + CardLayout.dp(area.maxX - right, d) + "]")
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
        let shownLines = st.lines.filter { Double($0) > yMin && Double($0) < yMax }
        if !shownLines.isEmpty { rec.add("chart.lines", nil, shownLines.map(String.init).joined(separator: ",")) }

        // The line: a dot per reading. Zoomed far out (more than `densePerDp` readings per pt: days of 1-minute readings)
        // each 1 pt column draws one stroke from its lowest to its highest reading instead, split where it crosses a
        // band, in the band's colour (GlucoseGraph.kt).
        let spacing = pts.count > 1 ? (right - left) / CGFloat(max(1, pts.count)) : 99
        let r = min(max(spacing * 0.55, 1.3 * d), 2.2 * d)
        ctx.setLineCap(.round)
        let dense = CGFloat(pts.count) > Self.densePerDp * (right - left) / d
        if dense {
            rec.add("chart.line", nil, "columns")
            ctx.setLineWidth(1.2 * d)   // the columns just touch: an envelope, not bars
            let edges = [b.veryLow, b.low, b.tight, b.high, b.veryHigh, b.extremelyHigh]
            var k = pts.firstIndex { $0.t >= from } ?? 0
            while k < pts.count {
                let col = floor((x(pts[k].t) - left) / d)
                var lo = pts[k].sgv, hi = lo, j = k + 1
                while j < pts.count, floor((x(pts[j].t) - left) / d) == col, pts[j].t - pts[j - 1].t <= GlucoseStats.maxGap {
                    lo = min(lo, pts[j].sgv); hi = max(hi, pts[j].sgv); j += 1
                }
                let cx = left + (col + 0.5) * d
                var a = lo
                for e in edges where e > a && e <= hi {
                    ctx.setStrokeColor(pal.at(b, Double(a)))
                    ctx.strokeLineSegments(between: [CGPoint(x: cx, y: y(Double(a))), CGPoint(x: cx, y: y(Double(e)))])
                    a = e
                }
                ctx.setStrokeColor(pal.at(b, Double(a)))
                ctx.strokeLineSegments(between: [CGPoint(x: cx, y: y(Double(a))), CGPoint(x: cx, y: y(Double(hi)))])
                k = j
            }
        }
        ctx.setLineWidth(1.6 * d)
        for (i, p) in pts.enumerated() where p.t >= from && (!dense || i == pts.count - 1) {
            let px = x(p.t), py = y(Double(p.sgv))
            let c = pal.at(b, Double(p.sgv))
            if spacing > 5 * d, i > 0, p.t - pts[i - 1].t <= GlucoseStats.maxGap {
                ctx.setStrokeColor(c.a(0.75))
                ctx.strokeLineSegments(between: [CGPoint(x: x(pts[i - 1].t), y: y(Double(pts[i - 1].sgv))), CGPoint(x: px, y: py)])
            }
            if i == pts.count - 1 && p.t >= (latestT ?? -.infinity) {
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
        let rows = sideW > 0 ? profileRows(GlucoseStats.timeAtLevel(pts, from: from, to: end, bands: b, tir: tir), b, pal,
                                           left: left, sideW: sideW, top: top, bottom: plotBottom, yMin: yMin, yMax: yMax, d: d) : []
        if !rows.isEmpty {
            ctx.saveGState()
            ctx.setBlendMode(.destinationOut)
            let h = (Self.profileR + Self.profileCut) * d
            for row in rows {
                ctx.addPath(CGPath(roundedRect: CGRect(x: area.minX, y: row.cy - h, width: row.endX + Self.profileCut * d - area.minX, height: 2 * h),
                                   cornerWidth: h, cornerHeight: h, transform: nil))
            }
            ctx.fillPath()
            ctx.restoreGState()
            // the outline as erased and the dots it surrounds (LayoutSelfTest checks the margin: layout/rules.json "distribution")
            let top0 = rows.map(\.cy).min()!, bottom0 = rows.map(\.cy).max()!, endX = rows.map(\.endX).max()!
            rec.add("chart.distribution.cut", CGRect(x: area.minX, y: top0 - h, width: endX + Self.profileCut * d - area.minX, height: bottom0 - top0 + 2 * h))
            rec.add("chart.distribution.dots", CGRect(x: left, y: top0 - Self.profileR * d, width: endX - left, height: bottom0 - top0 + 2 * Self.profileR * d))
        }
        ctx.endTransparencyLayer()
        if !rows.isEmpty { rec.add("chart.distribution", CGRect(x: left, y: top, width: sideW, height: plotBottom - top)) }
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
            let lh = 13 * d * 0.95
            var c = [(top + y(Double(b.high))) / 2, (y(Double(b.high)) + y(Double(b.low))) / 2, (y(Double(b.low)) + plotBottom) / 2]
            c[0] = min(c[0], c[1] - lh)
            c[2] = max(c[2], c[1] + lh)
            c[0] = max(c[0], area.minY + lh * 0.5)
            c[2] = min(c[2], area.maxY - lh * 0.4)
            for (v, cy) in zip([p.2, p.1, p.0], c) {
                let s = "\(v)"
                let w = pctW(v)
                let x0 = left - Self.sideGap * d - w, base = cy + 13 * d * 0.36
                Ink.draw(ctx, s, x: x0, y: base, pctBig, pal.ink)
                Ink.draw(ctx, "%", x: x0 + Ink.width(s, pctBig), y: base, pctSmall, pal.inkSoft)
                // the drawn label's box (LayoutSelfTest checks the column's gaps against it: layout/rules.json "gaps")
                rec.add("chart.pct.label", CGRect(x: x0, y: base - 13 * d * 0.72, width: w, height: 13 * d * 0.72), s)
            }
            rec.add("chart.pct", CGRect(x: area.minX, y: top, width: pctCol, height: plotBottom - top), "\(p.0)/\(p.1)/\(p.2) [w" + CardLayout.dp(pctCol, d) + "]")
            // The crown over a perfect 100 (TirMark.column), where it clears the label above.
            if st.crown, TirMark.column(tir) {
                let numW = Ink.width("100", pctBig), cw = numW * 0.62
                let bottom = c[1] + 13 * d * (0.36 - 0.72) - 1.5 * d
                if bottom - cw * Crown.h >= c[0] + 13 * d * 0.36 + 2 * d {
                    Crown.fill(ctx, x: left - Self.sideGap * d - Ink.width("%", pctSmall) - numW / 2, bottom: bottom, w: cw,
                               pal.knockout ? CGColor(gray: 1, alpha: 1) : pal.tight)
                    rec.add("chart.pct.crown")
                } else { rec.add("chart.pct.crown", nil, "(no room)") }
            }
        }

        if !shownMarks.isEmpty {
            rec.add("chart.markers", nil, "\(shownMarks.filter { ["rapid", "long", "carbs", "note"].contains($0.k) && show.has($0.k, MarkShow.point) }.count)")
            // what each entry shows (layout/rules.json "marks": the same on every surface)
            rec.add("chart.marks", nil, shownMarks.sorted { $0.id < $1.id }.map { m in
                m.id + ":" + MarkShow.parts(m.k).filter { p in
                    show.has(m.k, p) && (p != MarkShow.line || muteUntil?(m) != nil) && (p != MarkShow.amount && p != MarkShow.time || TreatmentMarks.amount(m) != nil)
                }.joined(separator: "+")
            }.joined(separator: " "))
            for l in layout?.labels ?? [] {
                let lb = l.lane == 0 ? area.maxY - lane1 : area.maxY
                rec.add("chart.label", CGRect(x: l.left, y: lb - 14 * d, width: l.w, height: 14 * d), "\(l.text) lane\(l.lane)")
            }
            TreatmentMarks.draw(ctx, shownMarks, x: x, left: left, right: right, bottom: bottom, dark: pal.dark, d: d,
                                layout: layout, base0: area.maxY - 2 * d - lane1, base1: area.maxY - 2 * d, sep: pal.inkSoft,
                                now: now, muteUntil: muteUntil, show: show)
        }
        // The "Now" pill of a scrolled chart (the action pill, StatusBadge): top right in the plot, fading in and out
        // with `view.now`; the surface makes its box (chart.now) clickable.
        if let view, view.now > 0 {
            let ph = Self.nowPill * d, look = StatusBadge.backToNow
            let pw = StatusBadge.width(look, h: ph)
            let px = right - pw - Self.nowInset * d, py = top + Self.nowInset * d
            ctx.saveGState()
            ctx.setAlpha(min(max(view.now, 0), 1))
            ctx.beginTransparencyLayer(in: CGRect(x: px, y: py, width: pw, height: ph), auxiliaryInfo: nil)
            StatusBadge.draw(ctx, x: px, y: py, h: ph, maxW: pw, look, dark: pal.dark, knockout: pal.knockout)
            ctx.endTransparencyLayer()
            ctx.restoreGState()
            rec.add("chart.now", CGRect(x: px, y: py, width: pw, height: ph), look.text)
        }
    }

    /// The longest window whose treatment marks get labels (layout/rules.json "treatmentLabels"; GlucoseGraph.kt `LABELS_MAX_S`).
    static let labelsMaxS: TimeInterval = 24 * 3600

    /// Readings per pt of plot from which the line is drawn as columns (GlucoseGraph.kt `DENSE_PER_DP`).
    static let densePerDp: CGFloat = 6

    /// The step (hours) between the vertical lines and their labels: the user's step at least, else the first whose
    /// labels are ≥ 44 pt apart: 1, 2, 3, 4, 6, 12 h, then 1, 2, 3 days, a week (GlucoseGraph.kt `gridStep`).
    static func gridStep(_ pxPerHour: CGFloat, gridHours: Int, d: CGFloat) -> Int {
        [1, 2, 3, 4, 6, 12, 24, 48, 72, 168].filter { $0 >= gridHours }.first { CGFloat($0) * pxPerHour >= 44 * d } ?? 168
    }

    /// Is `t` (on the hour) a line of `step`? Hours: the hour divides by it; days: a midnight whose day number divides by
    /// the step in days, a Monday (a week).
    static func onStep(_ t: Date, _ step: Int, _ cal: Calendar) -> Bool {
        let h = cal.component(.hour, from: t)
        if step < 24 { return h % step == 0 }
        guard h == 0 else { return false }
        if step == 168 { return cal.component(.weekday, from: t) == 2 }
        if step > 24 {
            let local = t.timeIntervalSince1970 + TimeInterval(cal.timeZone.secondsFromGMT(for: t))
            return Int(floor(local / 86400)) % (step / 24) == 0
        }
        return true
    }

    /// The Now pill's height and its distance from the plot's top-right corner (pt; GlucoseGraph.kt `NOW_PILL_DP`, `NOW_INSET_DP`).
    static let nowPill: CGFloat = 22, nowInset: CGFloat = 4

    /// The y range (mg/dL) of a window with the readings `pts` in it: it always reaches the very low / very high limits,
    /// so their lines always show, and every reading with a margin. The user's own lines don't change it: one outside
    /// the range isn't drawn. GlucoseGraph.kt `yRange`.
    static func yRange(_ pts: [GraphReading], bands b: Bands) -> ClosedRange<Double> {
        let dMin = pts.map(\.sgv).min() ?? b.low, dMax = pts.map(\.sgv).max() ?? b.high
        return max(Double(GlucoseStats.minMg), Double(min(b.low - 22, b.veryLow - 6, dMin - 6)))
            ... min(Double(GlucoseStats.maxMg), Double(max(b.high + 40, b.veryHigh + 8, dMax + 8)))
    }

    /// Height of the sensor badge in the hour-label row (GlucoseGraph.kt `SENSOR_DP`).
    static let sensorDp: CGFloat = 10
    /// The top of the sensor badge (at the chart's left edge, `sensorDp` tall) in a chart ending at `bottom` with `lane1`
    /// of second label lane: centred on the hour labels' x-height (GlucoseGraph.kt `sensorTop`; fixtures graph-fixtures/place).
    static func sensorTop(_ bottom: CGFloat, lane1: CGFloat, d: CGFloat) -> CGFloat { bottom - 2 * d - lane1 - 3.6 * d - sensorDp * d / 2 }
    /// The badge's width in the `TextModel`: the room kept free of treatment labels (GlucoseGraph.kt `sensorWidth`).
    static func sensorWidth(_ label: String, d: CGFloat) -> CGFloat { sensorDp * d * 1.3 + TextModel.width(label, sensorDp * d * 0.95) }

    /// The plot's left and right edge in `areaLeft`…`areaRight` (GlucoseGraph.kt `plotX`): the % column (`pct`: time
    /// in range shown and with data; only from `pctMinWidth`) and the values on the right (`labels`). Each column is its
    /// widest label actually drawn (`pctW`, `valW`: measured with the fonts that draw them) plus `sideGap` on each side,
    /// whatever the surface, size or font (the user, 2026-10-21: "it should depend on the longest text width"; the
    /// TextModel's worst-case widths had left extra margin beside the labels).
    static func plotX(_ areaLeft: CGFloat, _ areaRight: CGFloat, pct: Bool, labels: Bool, d: CGFloat,
                      pctW: CGFloat, valW: CGFloat) -> (CGFloat, CGFloat) {
        let pctCol = pct && labels && areaRight - areaLeft >= pctMinWidth * d ? pctW + 2 * sideGap * d : 0
        return (areaLeft + pctCol, areaRight - (labels ? valW + 2 * sideGap * d : 0))
    }

    /// The side columns' margin (pt) on each side of their widest label: one constant (layout/rules.json "gaps";
    /// GlucoseGraph.kt `SIDE_GAP_DP`).
    static let sideGap: CGFloat = 3

    /// Treatment label lanes for a chart `h` tall: a second one from `TreatmentMarks.twoLanesH`.
    static func lanes(_ h: CGFloat, d: CGFloat) -> Int { h >= TreatmentMarks.twoLanesH * d ? 2 : 1 }

    /// The distribution's dots: rows `profilePitch` apart, dots of radius `profileR`; the outline erased around them is
    /// `profileCut` wider (the user, 2026-10-21: "more outline so it masks out more of the content behind"; was 1.6).
    /// The same numbers as layout/rules.json "distribution" (LayoutSelfTest, Android CardLayoutTest).
    static let profilePitch: CGFloat = 3.6, profileR: CGFloat = 1.35, profileCut: CGFloat = 3

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

    /// Three shares as whole percentages adding up to 100 (largest remainder). The middle one (in range) is 100 only
    /// when the others are nothing: that 100 wears the crown (`TirMark.column`).
    static func pct3(_ a: Double, _ b: Double, _ c: Double) -> (Int, Int, Int) {
        let raw = [a, b, c].map { $0 * 100 }
        let total = raw.reduce(0, +)
        guard total > 0 else { return (0, 0, 0) }
        var base = raw.map { Int(floor($0)) }
        var left = Int(total.rounded()) - base.reduce(0, +)
        for i in raw.indices.sorted(by: { raw[$0] - Double(base[$0]) > raw[$1] - Double(base[$1]) }) where left > 0 {
            base[i] += 1; left -= 1
        }
        if base[1] == 100 && (a > 0 || c > 0) { base[1] -= 1; base[a >= c ? 0 : 2] += 1 }
        return (base[0], base[1], base[2])
    }
}

/// Treatments along the bottom lane, as TreatmentMarks.kt: rapid = filled blue drop, long = outlined violet drop,
/// carbs = amber dot, note = grey diamond; from each rapid / carbs mark a line to the end of its alert mute. The labels
/// (amount and time since, `4.5u 12m`) go under the markers, in the hour labels' row (they give way) and, when needed,
/// a second row under it, never over the glucose line; close entries share one (`layout`).
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
        return m < 1 ? "now" : Durations.compact(Int(m))
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
        case "sensor":   // the "Replace sensor" pill (StatusBadge.replaceSensor): the Libre sensor disc, a ring with a centre dot
            ctx.setStrokeColor(c); ctx.setLineWidth(r * 0.26)
            ctx.strokeEllipse(in: CGRect(x: x - r * 0.8, y: y - r * 0.8, width: r * 1.6, height: r * 1.6))
            ctx.setFillColor(c); ctx.fillEllipse(in: CGRect(x: x - r * 0.3, y: y - r * 0.3, width: r * 0.6, height: r * 0.6))
        case "now":   // the "Now" pill over a scrolled chart (StatusBadge.backToNow): SF Symbol arrow.right (Android: Material arrow_forward)
            let size = r * 2.2
            _ = SensorBadge.symbol(ctx, "arrow.right", x: x - size / 2, y: y - size / 2, size: size, color: c)
        default:
            let p = CGMutablePath(), q = r * 0.67
            p.move(to: CGPoint(x: x, y: y - q)); p.addLine(to: CGPoint(x: x + q, y: y))
            p.addLine(to: CGPoint(x: x, y: y + q)); p.addLine(to: CGPoint(x: x - q, y: y)); p.closeSubpath()
            ctx.addPath(p); ctx.setFillColor(c); ctx.fillPath()
        }
    }

    // MARK: label layout: docs/16-graph.md "Treatment labels" (the same rules in TreatmentMarks.kt)

    /// Label text size; least space between two labels in a lane; entries this close in time (s) or on the chart (a
    /// marker's width) share a label; chart height from which a second lane may be used, and its height; how far a
    /// marker stays inside its label when the label slides (all dp).
    static let labelSize: CGFloat = 9, labelGap: CGFloat = 4, mergeS: TimeInterval = 180, mergeDp: CGFloat = 9
    static let twoLanesH: CGFloat = 90, laneH: CGFloat = 11, inset: CGFloat = 3
    static let sep = " · "

    /// One label: `members` in label order (kind, then time), the entries with a label part shown (`MarkShow.label`);
    /// `shownAmounts` = those whose amount shows; `time` = since `timeOf`, the newest member whose time shows (nil = none
    /// wants it, or left out for room); `lane` 0 (the axis row) or 1 (under it), `left` + `w` = its modelled box (TextModel).
    struct Label {
        var members: [GraphMark]; var shownAmounts: [GraphMark]; var timeOf: GraphMark?; var time: String?
        var lane: Int; var left: CGFloat; var w: CGFloat
        var amounts: [String] { shownAmounts.compactMap { TreatmentMarks.amount($0) } }
        var text: String { TreatmentMarks.text(amounts, time) }
    }

    /// "4u · 40g 1h 4m", "40g", "1h 4m": the amounts joined by " · ", then the time.
    static func text(_ amounts: [String], _ time: String?) -> String {
        [amounts.isEmpty ? nil : amounts.joined(separator: sep), time].compactMap { $0 }.joined(separator: " ")
    }

    /// Where the labels go; `dropped` = the groups left without a label (their markers stay).
    struct Layout {
        var labels: [Label]; var dropped: [[GraphMark]]
        var twoLanes: Bool { labels.contains { $0.lane == 1 } }
        /// Whether `a`…`b` touches a lane-0 label: an hour label there gives way.
        func blocks(_ a: CGFloat, _ b: CGFloat) -> Bool { labels.contains { $0.lane == 0 && $0.left < b && a < $0.left + $0.w } }
    }

    /// The label layout (TreatmentMarks.kt `layout`, same steps): group (≤ `mergeS` after the group's last entry,
    /// chained, or < `mergeDp` from the group's first marker), label = amounts in kind order joined by " · " + the time since the newest,
    /// anchored mid-group; newest group first, each in the first of lane 0 / lane 1 with the time, lane 0 / lane 1
    /// without it that has a free place (`place`), else no label.
    /// `reserve` = a box of lane 0 already taken (the sensor badge at the row's start, `GlucoseGraph.sensorTop`).
    static func layout(_ marks: [GraphMark], x: (TimeInterval) -> CGFloat, now: TimeInterval?, minX: CGFloat, maxX: CGFloat,
                       lanes: Int, d: CGFloat, reserve: (CGFloat, CGFloat)? = nil, show: MarkShow = .defaults) -> Layout {
        let items = marks.filter { amount($0) != nil && order[$0.k] != nil && show.label($0.k) }
            .sorted { ($0.t, order[$0.k]!, $0.a, $0.id) < ($1.t, order[$1.k]!, $1.a, $1.id) }
        var groups: [[GraphMark]] = []
        for m in items {
            if let g = groups.last, m.t - g.last!.t <= mergeS || x(m.t) - x(g.first!.t) < mergeDp * d { groups[groups.count - 1].append(m) }
            else { groups.append([m]) }
        }
        let size = labelSize * d, gap = labelGap * d
        var taken: [[(CGFloat, CGFloat)]] = [reserve.map { [$0] } ?? [], []]
        var labels: [Label] = [], dropped: [[GraphMark]] = []
        for g in groups.sorted(by: { $0.last!.t > $1.last!.t }) {
            let members = g.sorted { (order[$0.k]!, $0.t, $0.a, $0.id) < (order[$1.k]!, $1.t, $1.a, $1.id) }
            let shownAmounts = members.filter { show.has($0.k, MarkShow.amount) }
            let amounts = shownAmounts.compactMap { amount($0) }
            // the time once, since the newest member that wants it
            let timeOf = g.last { show.has($0.k, MarkShow.time) }
            let time = timeOf.flatMap { m in now.map { duration($0 - m.t) } }
            let cx = (x(g.first!.t) + x(g.last!.t)) / 2
            var placed: Label?
            var tries: [(Int, String?)] = []
            for (lane, t) in [(0, time), (1, time), (0, nil), (1, nil)] where lane < lanes && (!amounts.isEmpty || t != nil) {
                if !tries.contains(where: { $0.0 == lane && $0.1 == t }) { tries.append((lane, t)) }
            }
            for (lane, t) in tries {
                let w = TextModel.width(text(amounts, t), size)
                if let left = place(cx, w, taken[lane], minX: minX, maxX: maxX, gap: gap, inset: inset * d) {
                    taken[lane].append((left - gap / 2, left + w + gap / 2))
                    placed = Label(members: members, shownAmounts: shownAmounts, timeOf: timeOf, time: t, lane: lane, left: left, w: w)
                    break
                }
            }
            if let placed { labels.append(placed) } else { dropped.append(members) }
        }
        return Layout(labels: labels, dropped: dropped)
    }

    /// The left edge for a box `w` wide anchored at `cx` in a lane holding `taken`, or nil (TreatmentMarks.kt `place`):
    /// centred if free, else slid while the anchor stays `inset` inside the box; candidates in order: preferred, the
    /// slide's two ends, then right of / left of each taken box; the free one nearest the preferred wins (first on a tie).
    static func place(_ cx: CGFloat, _ w: CGFloat, _ taken: [(CGFloat, CGFloat)], minX: CGFloat, maxX: CGFloat,
                      gap: CGFloat, inset: CGFloat) -> CGFloat? {
        if w > maxX - minX { return nil }
        let pref = min(max(cx - w / 2, minX), max(maxX - w, minX))
        var lo = max(minX, cx - w + inset), hi = min(maxX - w, cx - inset)
        if lo > hi { lo = pref; hi = pref }
        func free(_ l: CGFloat) -> Bool { !taken.contains { l - gap / 2 < $0.1 - 0.01 && $0.0 + 0.01 < l + w + gap / 2 } }
        let cands = [min(max(pref, lo), hi), lo, hi] + taken.flatMap { [$0.1 + gap / 2, $0.0 - w - gap / 2] }
        var best: CGFloat?
        for c in cands where c >= lo && c <= hi && free(c) {
            if best == nil || abs(c - pref) < abs(best! - pref) { best = c }
        }
        return best
    }

    /// Draw `marks` (live entries) into the plot `left`…`right` × …`bottom`: markers, mute lines (`muteUntil`, nil =
    /// none; `now` = the plot's right edge), then `layout`'s labels with baselines `base0` / `base1`. Each label's
    /// outline (3 dp) is first erased (`.destinationOut`) from what is under it, so it reads over anything on any
    /// background (the caller draws the chart in its own transparency layer).
    static func draw(_ ctx: CGContext, _ marks: [GraphMark], x: (TimeInterval) -> CGFloat, left: CGFloat, right: CGFloat,
                     bottom: CGFloat, dark: Bool, d: CGFloat, layout: Layout?, base0: CGFloat, base1: CGFloat, sep sepColor: CGColor,
                     now: TimeInterval? = nil, muteUntil: ((GraphMark) -> TimeInterval?)? = nil, show: MarkShow = .defaults) {
        let items = marks.filter { order[$0.k] != nil }.map { ($0, x($0.t)) }.filter { $0.1 >= left - 1 && $0.1 <= right + 1 }
        guard !items.isEmpty else { return }
        let cy = bottom - 5 * d
        let back = items.sorted { order[$0.0.k]! > order[$1.0.k]! }   // notes at the back, insulin in front

        if let muteUntil {
            ctx.saveGState()
            ctx.setLineWidth(2 * d); ctx.setLineCap(.round); ctx.setLineJoin(.round)
            // Insulin's line just above the lane's middle, carbs' just below: a meal's two lines lie side by side.
            for (m, px) in back where show.has(m.k, MarkShow.line) {
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
        for (m, px) in back where show.has(m.k, MarkShow.point) { marker(ctx, m.k, x: px, y: cy, r: 4.5 * d, color: color(m.k, dark: dark)) }

        guard let layout, !layout.labels.isEmpty else { return }
        let bold = Ink.font(labelSize * d, .bold), medium = Ink.font(labelSize * d, .medium)
        // pieces (text, font, colour) and the start of the real text, centred in its modelled box
        let drawn: [(Label, [(String, CTFont, CGColor)], CGFloat)] = layout.labels.map { l in
            var pieces: [(String, CTFont, CGColor)] = []
            for (i, m) in l.shownAmounts.filter({ amount($0) != nil }).enumerated() {
                if i > 0 { pieces.append((sep, medium, sepColor.copy(alpha: 0xC0 / 255.0)!)) }
                pieces.append((amount(m)!, bold, color(m.k, dark: dark)))
            }
            if let t = l.time, let of = l.timeOf {
                pieces.append(((pieces.isEmpty ? "" : " ") + t, medium, color(of.k, dark: dark).copy(alpha: 0xC0 / 255.0)!))
            }
            let w = pieces.reduce(0) { $0 + Ink.width($1.0, $1.1) }
            return (l, pieces, l.left + (l.w - w) / 2)
        }
        func each(_ f: (String, CTFont, CGColor, CGFloat, CGFloat) -> Void) {
            for (l, pieces, x0) in drawn {
                var px = x0
                for (s, font, c) in pieces { f(s, font, c, px, l.lane == 0 ? base0 : base1); px += Ink.width(s, font) }
            }
        }
        ctx.saveGState()
        ctx.setBlendMode(.destinationOut)
        ctx.setLineJoin(.round)
        each { s, font, _, px, base in Ink.halo(ctx, s, x: px, y: base, font, width: 3 * d) }
        ctx.restoreGState()
        each { s, font, c, px, base in Ink.draw(ctx, s, x: px, y: base, font, c) }
    }
}

// MARK: - Ring

/// The mark a time-in-range ring wears (TirMark in GlucoseCard.kt; spikes/graph-fixtures/mark), by the share the ring
/// shows: a laurel from 90 %, a laurel with a flower on each branch tip from 95 %, and at 100 % (no reading in the
/// window outside the ring's range) the crown with the flowered laurel, by that share alone (the user, 2026-10-19:
/// lows don't take it away), every mark the report's 70 % of data. The 100 in the % column beside the chart wears the
/// crown on the same rule (`column`).
enum TirMark: String, Comparable {
    case none, laurel, flowers, crown

    static func < (a: TirMark, b: TirMark) -> Bool { allCases.firstIndex(of: a)! < allCases.firstIndex(of: b)! }
    static let allCases: [TirMark] = [.none, .laurel, .flowers, .crown]

    static func of(_ tir: TimeInRange, _ range: TirRange) -> TirMark {
        guard tir.coverage >= Goal.minCoverage else { return .none }
        let x = tir.of(range)
        if x >= 1 { return .crown }
        let shown = (x * 100).rounded()
        return shown >= 95 ? .flowers : shown >= 90 ? .laurel : .none
    }

    static func column(_ tir: TimeInRange) -> Bool { of(tir, .inRange) == .crown }
}

/// The flower a laurel wears from 95 % (Bloom in Blooms.kt; spikes/graph-fixtures/mark): 95 lavender, 96
/// forget-me-not, 97 daisy, 98 cherry blossom, 99 golden rosette; the crowned 100 % keeps the rosette; 99.6 counts as 99.
enum Bloom: String {
    case lavender, forgetMeNot = "forget_me_not", daisy, cherry, rosette

    static func of(_ tir: TimeInRange, _ range: TirRange) -> Bloom? {
        let m = TirMark.of(tir, range)
        guard m >= .flowers else { return nil }
        if m == .crown { return .rosette }
        switch min(99, Int((tir.of(range) * 100).rounded())) {
        case 95: return .lavender
        case 96: return .forgetMeNot
        case 97: return .daisy
        case 98: return .cherry
        default: return .rosette
        }
    }
}

/// The flower drawings (Blooms in Blooms.kt), in units of a ring's radius r: a tip flower just past a laurel stem's end
/// (lavender grows from the stem's end, tangent to it) and the piece shared where two flowered laurels meet. Fixed
/// colours ("reward", not a glucose range); tinted widgets draw them white.
enum Blooms {
    private static func hex(_ v: UInt32) -> CGColor { GraphPalette.hex(v) }
    private static let white = CGColor(gray: 1, alpha: 1)

    static func tip(_ ctx: CGContext, _ b: Bloom, cx: CGFloat, cy: CGFloat, r: CGFloat, isLeft: Bool, _ pal: GraphPalette, alpha: CGFloat) {
        faded(ctx, alpha) {
            if b == .lavender {
                spike(ctx, at(cx, cy, r * 1.25, isLeft ? 214 : -34), r, isLeft ? -56 : -124, pal)
            } else {
                flower(ctx, b, at(cx, cy, r * 1.25, isLeft ? 219 : -39), unit(r, 1), pal)
            }
        }
    }

    /// Where two flowered laurels meet (rings at `lx`, `rx`): lavender grows a spike from each meeting branch, in its
    /// own ring's fade; the others draw one centre piece between the two inner tips.
    static func center(_ ctx: CGContext, _ b: Bloom, lx: CGFloat, rx: CGFloat, cy: CGFloat, r: CGFloat, _ pal: GraphPalette,
                       la: CGFloat, ra: CGFloat) {
        if b == .lavender {
            tip(ctx, b, cx: lx, cy: cy, r: r, isLeft: false, pal, alpha: la)
            tip(ctx, b, cx: rx, cy: cy, r: r, isLeft: true, pal, alpha: ra)
            return
        }
        let p1 = at(lx, cy, r * 1.25, -39), p2 = at(rx, cy, r * 1.25, 219)
        faded(ctx, max(la, ra)) { centerPiece(ctx, b, CGPoint(x: (p1.x + p2.x) / 2, y: p1.y), unit(r, 1.3), pal) }
    }

    private static func faded(_ ctx: CGContext, _ a: CGFloat, _ block: () -> Void) {
        ctx.saveGState(); ctx.setAlpha(a); ctx.beginTransparencyLayer(auxiliaryInfo: nil)
        block()
        ctx.endTransparencyLayer(); ctx.restoreGState()
    }
    private static func at(_ x: CGFloat, _ y: CGFloat, _ d: CGFloat, _ deg: CGFloat) -> CGPoint {
        CGPoint(x: x + d * cos(deg * .pi / 180), y: y + d * sin(deg * .pi / 180))
    }
    private static func unit(_ r: CGFloat, _ k: CGFloat) -> CGFloat { r * 0.14 * k }
    private static func c(_ pal: GraphPalette, _ v: UInt32) -> CGColor { pal.knockout ? white : hex(v) }
    /// Tinted widgets keep only the opacity: there the flowers are white with their details cut out (`cut`) or half
    /// see-through, so they read as flowers rather than one solid shape.
    private static func half(_ pal: GraphPalette, _ v: UInt32) -> CGColor { pal.knockout ? CGColor(gray: 1, alpha: 0.5) : hex(v) }
    private static func cut(_ ctx: CGContext, _ pal: GraphPalette, _ block: () -> Void) {
        guard pal.knockout else { return }
        ctx.saveGState(); ctx.setBlendMode(.clear); block(); ctx.restoreGState()
    }
    private static func edge(_ pal: GraphPalette) -> CGColor {
        pal.dark ? (pal.bg.alpha > 0 ? pal.bg : CGColor(gray: 0, alpha: 1)) : hex(0xC9D3CB)
    }
    private static func circle(_ ctx: CGContext, _ p: CGPoint, _ rad: CGFloat, _ color: CGColor) {
        ctx.setFillColor(color); ctx.fillEllipse(in: CGRect(x: p.x - rad, y: p.y - rad, width: 2 * rad, height: 2 * rad))
    }
    private static func ellipse(_ ctx: CGContext, _ p: CGPoint, _ rx: CGFloat, _ ry: CGFloat, _ rot: CGFloat,
                                fill: CGColor, stroke: CGColor? = nil, lineW: CGFloat = 0) {
        ctx.saveGState()
        ctx.translateBy(x: p.x, y: p.y); ctx.rotate(by: rot * .pi / 180)
        let rect = CGRect(x: -rx, y: -ry, width: 2 * rx, height: 2 * ry)
        ctx.setFillColor(fill); ctx.fillEllipse(in: rect)
        if let stroke { ctx.setStrokeColor(stroke); ctx.setLineWidth(lineW); ctx.strokeEllipse(in: rect) }
        ctx.restoreGState()
    }
    private static func leafPair(_ ctx: CGContext, _ p: CGPoint, _ u: CGFloat, _ pal: GraphPalette) {
        for s: CGFloat in [-1, 1] {
            ellipse(ctx, CGPoint(x: p.x + s * u * 1.55, y: p.y + u * 0.95), u * 1.3, u * 0.5, s * 28,
                    fill: pal.knockout ? CGColor(gray: 1, alpha: 0.5) : pal.tight)
        }
    }

    private static func flower(_ ctx: CGContext, _ b: Bloom, _ p: CGPoint, _ u: CGFloat, _ pal: GraphPalette) {
        switch b {
        case .forgetMeNot: forgetMeNot(ctx, p, u, pal)
        case .daisy: daisy(ctx, p, u, double: false, pal)
        case .cherry: blossom(ctx, p, u, 0, pal)
        case .rosette: rosette(ctx, p, u, pal)
        case .lavender: spike(ctx, p, u / 0.14, -90, pal)
        }
    }

    private static func centerPiece(_ ctx: CGContext, _ b: Bloom, _ p: CGPoint, _ u: CGFloat, _ pal: GraphPalette) {
        switch b {
        case .forgetMeNot:
            leafPair(ctx, p, u, pal)
            for (a, bb) in [(-1.45, 0.55), (1.45, 0.55), (-0.75, -0.7), (0.75, -0.7), (0, 0.25)] as [(CGFloat, CGFloat)] {
                forgetMeNot(ctx, CGPoint(x: p.x + a * u, y: p.y + bb * u), u * 0.6, pal)
            }
        case .daisy:
            leafPair(ctx, p, u, pal)
            for (a, bb, s) in [(-1.85, 0.55, 0.5), (1.85, 0.55, 0.5), (-1.2, -1.25, 0.34), (1.2, -1.25, 0.34)] as [(CGFloat, CGFloat, CGFloat)] {
                daisy(ctx, CGPoint(x: p.x + a * u, y: p.y + bb * u), u * s, double: false, pal)
            }
            daisy(ctx, p, u * 0.85, double: true, pal)
        case .cherry:
            leafPair(ctx, p, u, pal)
            blossom(ctx, CGPoint(x: p.x - u * 1.6, y: p.y + u * 0.55), u * 0.58, 20, pal)
            blossom(ctx, CGPoint(x: p.x + u * 1.6, y: p.y + u * 0.55), u * 0.58, -20, pal)
            blossom(ctx, CGPoint(x: p.x, y: p.y - u * 0.2), u, 0, pal)
        case .rosette:
            // an award rosette with two ribbon tails hanging into the gap
            for (s, v) in [(CGFloat(1), UInt32(0xC98A12)), (-1, 0xB57A0E)] {
                let pts: [(CGFloat, CGFloat)] = [(-0.25, 0.3), (-1.35, 2.75), (-0.9, 2.45), (-0.62, 3.0), (0.35, 0.55)]
                ctx.beginPath()
                for (i, (a, bb)) in pts.enumerated() {
                    let q = CGPoint(x: p.x + s * a * u, y: p.y + bb * u)
                    if i == 0 { ctx.move(to: q) } else { ctx.addLine(to: q) }
                }
                ctx.closePath(); ctx.setFillColor(s > 0 ? c(pal, v) : half(pal, v)); ctx.fillPath()
            }
            rosette(ctx, p, u * 1.1, pal)
        case .lavender: spike(ctx, p, u / 0.14, -90, pal)
        }
    }

    /// Lavender: a bare stem continuing the branch along `dir`, then five pairs of violet buds.
    private static func spike(_ ctx: CGContext, _ p: CGPoint, _ r: CGFloat, _ dir: CGFloat, _ pal: GraphPalette) {
        let u = unit(r, 1), e = at(p.x, p.y, u * 3.6, dir)
        ctx.saveGState()
        ctx.setStrokeColor(pal.knockout ? white : pal.tight); ctx.setLineWidth(u * 0.16); ctx.setLineCap(.round)
        ctx.move(to: p); ctx.addLine(to: e); ctx.strokePath()
        ctx.restoreGState()
        for i in 0..<5 {
            let bp = at(p.x, p.y, u * (0.9 + CGFloat(i) * 0.68), dir), sz = u * (0.58 - CGFloat(i) * 0.07)
            for side: CGFloat in [-1, 1] {
                ellipse(ctx, at(bp.x, bp.y, sz * 0.55, dir + side * 90), sz, sz * 0.62, dir + side * 35, fill: i % 2 == 1 ? half(pal, 0xA98BE0) : c(pal, 0x8E6CD4))
            }
        }
    }

    private static func forgetMeNot(_ ctx: CGContext, _ p: CGPoint, _ u: CGFloat, _ pal: GraphPalette) {
        for i in 0..<5 { circle(ctx, at(p.x, p.y, u, -90 + CGFloat(i) * 72), u * 0.8, c(pal, 0x7DB8F2)) }
        if pal.knockout {
            // the white ring becomes a hole, the yellow dot stays
            cut(ctx, pal) { circle(ctx, p, u * 0.55, white) }
        } else { circle(ctx, p, u * 0.55, white) }
        circle(ctx, p, u * 0.28, c(pal, 0xF2C230))
    }

    private static func daisy(_ ctx: CGContext, _ p: CGPoint, _ u: CGFloat, double: Bool, _ pal: GraphPalette) {
        let n = double ? 16 : 10
        for i in 0..<n {
            let a = -90 + CGFloat(i) * 360 / CGFloat(n)
            ellipse(ctx, at(p.x, p.y, u * 1.15, a), u, u * (double ? 0.3 : 0.38), a, fill: white, stroke: pal.knockout ? nil : edge(pal), lineW: u * 0.12)
            if pal.knockout {   // tinted: each petal's edge cut, so the petals stay apart
                ctx.saveGState(); ctx.setBlendMode(.clear)
                ellipse(ctx, at(p.x, p.y, u * 1.15, a), u, u * (double ? 0.3 : 0.38), a, fill: CGColor(gray: 1, alpha: 0), stroke: white, lineW: u * 0.14)
                ctx.restoreGState()
                ellipse(ctx, at(p.x, p.y, u * 1.15, a), u * 0.9, u * (double ? 0.22 : 0.29), a, fill: white)
            }
        }
        if double {
            for i in 0..<10 {
                let a = -72 + CGFloat(i) * 36
                ellipse(ctx, at(p.x, p.y, u * 0.78, a), u * 0.62, u * 0.26, a, fill: pal.knockout ? white : hex(0xFFFDF4), stroke: edge(pal), lineW: u * 0.12)
            }
        }
        // tinted: a gap between the petals and the heart, and the seeds cut out of it
        cut(ctx, pal) { circle(ctx, p, u * 0.8, white) }
        circle(ctx, p, u * 0.62, c(pal, 0xF2C230))
        if double { for i in 0..<6 { circle(ctx, at(p.x, p.y, u * 0.3, -90 + CGFloat(i) * 60), u * 0.09, c(pal, 0xC9940F)) } }
        cut(ctx, pal) { if double { for i in 0..<6 { circle(ctx, at(p.x, p.y, u * 0.3, -90 + CGFloat(i) * 60), u * 0.09, white) } } }
    }

    private static func blossom(_ ctx: CGContext, _ p: CGPoint, _ u: CGFloat, _ rot: CGFloat, _ pal: GraphPalette) {
        let l = u * 2, w = u * 0.95
        for i in 0..<5 {
            ctx.saveGState()
            ctx.translateBy(x: p.x, y: p.y); ctx.rotate(by: (CGFloat(i) * 72 + rot) * .pi / 180)
            ctx.beginPath()
            ctx.move(to: .zero)
            ctx.addCurve(to: CGPoint(x: w * 0.35, y: -l), control1: CGPoint(x: w, y: -l * 0.25), control2: CGPoint(x: w * 1.05, y: -l * 0.9))
            ctx.addLine(to: CGPoint(x: 0, y: -l * 0.82)); ctx.addLine(to: CGPoint(x: -w * 0.35, y: -l))
            ctx.addCurve(to: .zero, control1: CGPoint(x: -w * 1.05, y: -l * 0.9), control2: CGPoint(x: -w, y: -l * 0.25))
            ctx.closePath(); ctx.setFillColor(c(pal, 0xF7A9C4)); ctx.fillPath()
            ctx.restoreGState()
        }
        if pal.knockout {
            // tinted: the heart and the stamens cut out of the white petals
            cut(ctx, pal) {
                circle(ctx, p, u * 0.42, white)
                for i in 0..<5 { circle(ctx, at(p.x, p.y, u * 0.55, -90 + rot + CGFloat(i) * 72), u * 0.12, white) }
                for i in 0..<5 {   // a thin line between the petals
                    let a = at(p.x, p.y, u * 2.2, -90 + rot + 36 + CGFloat(i) * 72)
                    ctx.setLineWidth(u * 0.12); ctx.move(to: p); ctx.addLine(to: a); ctx.strokePath()
                }
            }
            circle(ctx, p, u * 0.2, white)
            return
        }
        for i in 0..<5 { circle(ctx, at(p.x, p.y, u * 0.55, -90 + rot + CGFloat(i) * 72), u * 0.1, c(pal, 0xC93F70)) }
        circle(ctx, p, u * 0.42, c(pal, 0xD9487A))
    }

    private static func rosette(_ ctx: CGContext, _ p: CGPoint, _ u: CGFloat, _ pal: GraphPalette) {
        for i in 0..<8 { circle(ctx, at(p.x, p.y, u * 1.1, -90 + CGFloat(i) * 45), u * 0.62, c(pal, 0xE7B53C)) }
        // tinted: the inner row as outlined petals and a ring round the heart
        cut(ctx, pal) { for i in 0..<8 { circle(ctx, at(p.x, p.y, u * 0.62, -67.5 + CGFloat(i) * 45), u * 0.48, white) } }
        for i in 0..<8 { circle(ctx, at(p.x, p.y, u * 0.62, -67.5 + CGFloat(i) * 45), u * (pal.knockout ? 0.3 : 0.42), c(pal, 0xF6D576)) }
        cut(ctx, pal) { circle(ctx, p, u * 0.5, white) }
        circle(ctx, p, u * (pal.knockout ? 0.3 : 0.42), c(pal, 0xC98A12))
    }
}

/// The crown (Crown in GlucoseCard.kt): a 12 × 7.4 outline with rounded joins, `w` wide, bottom centre at (`x`, `bottom`).
enum Crown {
    private static let pts: [(CGFloat, CGFloat)] = [(0.7, 7.4), (0, 1.7), (3.3, 4.3), (6, 0), (8.7, 4.3), (12, 1.7), (11.3, 7.4)]
    static let h: CGFloat = 7.4 / 12

    /// `grow` > 0: the outline widened by that much on every side (a cut-out's margin).
    static func fill(_ ctx: CGContext, x: CGFloat, bottom: CGFloat, w: CGFloat, _ color: CGColor, grow: CGFloat = 0) {
        let k = w / 12
        ctx.saveGState()
        ctx.beginPath()
        for (i, p) in pts.enumerated() {
            let q = CGPoint(x: x + (p.0 - 6) * k, y: bottom + (p.1 - 7.4) * k)
            if i == 0 { ctx.move(to: q) } else { ctx.addLine(to: q) }
        }
        ctx.closePath()
        ctx.setLineJoin(.round)
        ctx.setLineWidth(w * 0.8 / 12 + 2 * grow)
        ctx.setFillColor(color); ctx.setStrokeColor(color)
        ctx.drawPath(using: .fillStroke)
        ctx.restoreGState()
    }
}

/// The laurel (Laurel in GlucoseCard.kt): two branches of leaves up the sides of a ring of outer radius `r`, open at
/// the top and bottom, the stems on a circle of 1.25 `r`. Next to a slot without a laurel a side stays whole (the slots
/// move apart, `Slots.extraGap`). Between two laurelled rings the slots sit so their facing stems touch in the middle of
/// the gap, so the wreaths meet there; those sides (`.merged`) keep their stem and inner leaves but leave out the outer
/// leaves that would cross into the other branch. A faded colour fades the whole laurel at once (a layer).
enum Laurel {
    enum Side { case full, merged }

    static func draw(_ ctx: CGContext, center c: CGPoint, r: CGFloat, _ color: CGColor, left: Side = .full, right: Side = .full) {
        ctx.saveGState()
        ctx.setAlpha(color.alpha)
        ctx.beginTransparencyLayer(auxiliaryInfo: nil)
        let col = color.copy(alpha: 1) ?? color
        branch(ctx, c, r, col, isLeft: true, left)
        branch(ctx, c, r, col, isLeft: false, right)
        ctx.endTransparencyLayer()
        ctx.restoreGState()
    }

    /// One side's branch: climbing from below the middle (135° / 45°) to above it (210° / -30°), an outer and an inner
    /// leaf per step leaning up it, the inner ones clear of the ring; a merged side keeps only its two lowest and its
    /// highest outer leaves. The flowers are `Blooms`'.
    private static func branch(_ ctx: CGContext, _ c: CGPoint, _ r: CGFloat, _ color: CGColor, isLeft: Bool, _ side: Side) {
        ctx.saveGState()
        ctx.setStrokeColor(color); ctx.setFillColor(color)
        ctx.setLineWidth(r * 0.032); ctx.setLineCap(.round)
        let a0: CGFloat = isLeft ? 131 : -34
        ctx.addArc(center: c, radius: r * 1.25, startAngle: a0 * .pi / 180, endAngle: (a0 + 83) * .pi / 180, clockwise: false)
        ctx.strokePath()
        func at(_ rad: CGFloat, _ deg: CGFloat, _ rot: CGFloat) {
            let a = deg * .pi / 180
            ctx.saveGState()
            ctx.translateBy(x: c.x + rad * cos(a), y: c.y + rad * sin(a))
            ctx.rotate(by: rot * .pi / 180)
            ctx.fillEllipse(in: CGRect(x: -r * 0.135, y: -r * 0.056, width: r * 0.27, height: r * 0.112))
            ctx.restoreGState()
        }
        let s: CGFloat = isLeft ? 1 : -1
        for k in 0..<6 {
            let a = isLeft ? 135 + 15 * CGFloat(k) : 45 - 15 * CGFloat(k)
            if side == .full || k <= 1 || k == 5 { at(r * 1.35, a, a + s * 90 - s * 32) }
            at(r * 1.17, a + s * 5, a + s * 90 + s * 32)
        }
        ctx.restoreGState()
    }
}

/// The segmented time-in-range ring (TirRing in GlucoseCard.kt), with the sensor `countdown` ring over it. A ring with
/// a `TirMark` leaves the ring its full size and may overflow the slot: the laurel up its sides (from 95 %, also at
/// 100 %; `Slots.drawUnder`, drawn for every slot before the rings, so what is next to it covers it), and at 100 % the crown
/// in the text colour set into 12 o'clock like a cap, cut out of the ring and the countdown.
enum TirRing {
    static func draw(_ ctx: CGContext, center c: CGPoint, radius r: CGFloat, tir: TimeInRange, spec: RingSpec,
                     _ pal: GraphPalette, emphasis: CGFloat, countdown: Double? = nil) {
        let mark = mark(tir, spec)
        let sw = r * 0.2
        let rr = r - sw / 2
        let crownW = r * 0.74, crownBottom = c.y - r * 0.72
        if mark == .crown { ctx.beginTransparencyLayer(auxiliaryInfo: nil) }
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
        SensorBadge.drawCountdown(ctx, center: c, radius: r, countdown, pal)
        if mark == .crown {
            ctx.saveGState()
            ctx.setBlendMode(.destinationOut)
            Crown.fill(ctx, x: c.x, bottom: crownBottom, w: crownW, CGColor(gray: 0, alpha: 1), grow: r * 0.06)
            ctx.restoreGState()
            ctx.endTransparencyLayer()
            // faded as one layer: fill and outline overlapping at partial alpha would show a darker rim
            ctx.saveGState()
            ctx.setAlpha(emphasis)
            ctx.beginTransparencyLayer(auxiliaryInfo: nil)
            Crown.fill(ctx, x: c.x, bottom: crownBottom, w: crownW, pal.knockout ? CGColor(gray: 1, alpha: 1) : pal.ink)
            ctx.endTransparencyLayer()
            ctx.restoreGState()
        }
        let num = Ink.font(r * 0.78, .heavy, rounded: true)
        let sub = Ink.font(r * 0.34, .semibold, rounded: true)
        let v = tir.empty ? "–" : text(tir, spec)
        let base = c.y + r * 0.78 * 0.28
        // "69.7", "<0.1": the decimals fit inside the ring (TirRing.draw on Android)
        var size = v.count > 3 ? r * 0.78 * 0.7 : r * 0.78
        size = min(size, size * (r - sw) * 1.5 / max(Ink.width(v, Ink.font(size, .heavy, rounded: true)), 1)) // "100" too
        Ink.draw(ctx, v, x: c.x, y: base, size == r * 0.78 ? num : Ink.font(size, .heavy, rounded: true), pal.ink.a(emphasis), align: 0.5)
        Ink.draw(ctx, spec.period, x: c.x, y: base + r * 0.34 * 1.1, sub,
                 (spec.range == .tight ? pal.tight : pal.inkSoft).a(emphasis), align: 0.5)
    }
}

extension TirRing {
    /// The ring's `TirMark` (none without data).
    static func mark(_ tir: TimeInRange, _ spec: RingSpec) -> TirMark { tir.empty || !spec.marks ? .none : TirMark.of(tir, spec.range) }
    /// The number in the middle: in range against its > 70 % goal, very good on its own (TirRing.text).
    static func text(_ tir: TimeInRange, _ spec: RingSpec) -> String { ShareText.of(tir.of(spec.range), spec.range == .inRange ? .inRange : nil) }
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

    /// The action pill on an ended sensor (round sensor-life): the same pill, kind "sensor" (neutral colour, sensor disc).
    static let replaceSensor = Look(kind: "sensor", text: "Replace sensor", short: "Sensor", tonal: false)
    /// The pill over a scrolled chart (GlucoseGraph, `ChartView.now`): the same pill, kind "now" (neutral colour, arrow).
    static let backToNow = Look(kind: "now", text: "Now", short: "Now", tonal: false)

    static func look(_ s: AlertStatus, now: TimeInterval) -> Look? {
        switch s {
        case .none: return nil
        case .needed(let k):
            return k.lowSide ? Look(kind: "carbs", text: "Eat carbs", short: "Carbs", tonal: false)
                : Look(kind: "rapid", text: "Take insulin", short: "Insulin", tonal: false)
        case .taken(_, let tr, let until):
            let d = TreatmentMarks.duration(until - now, up: true)
            return Look(kind: tr.k, text: "\(d) left", short: d, tonal: true)
        case .paused(let k, let until):     // muted on this device: the colour of what answers it
            if until >= AlertStatus.never { return Look(kind: k.lowSide ? "carbs" : "rapid", text: "Muted", short: "Muted", tonal: true) }
            let d = TreatmentMarks.duration(until - now, up: true)
            return Look(kind: k.lowSide ? "carbs" : "rapid", text: "\(d) left", short: d, tonal: true)
        case .muted(let until):             // Mute all in range: neutral, the "notifications off" bell
            if until >= AlertStatus.never { return Look(kind: "muted", text: "Alerts muted", short: "Muted", tonal: true) }
            return Look(kind: "muted", text: "Muted · \(TreatmentMarks.duration(until - now, up: true))", short: "Muted", tonal: true)
        }
    }

    /// For screen readers.
    static func describe(_ s: AlertStatus, now: TimeInterval) -> String? {
        switch s {
        case .none: nil
        case .needed: look(s, now: now)?.text
        case .paused(let k, let until) where until >= AlertStatus.never: "\(k.title): alerts muted until turned back on"
        case .taken(let k, _, let until), .paused(let k, let until):
            "\(k.title): alerts paused, \(Durations.text(seconds: until - now)) left"
        case .muted(let until) where until >= AlertStatus.never: "All alerts muted until turned back on"
        case .muted(let until): "All alerts muted, \(Durations.text(seconds: until - now)) left"
        }
    }

    /// Material Symbols "notifications_paused" (Apache 2.0), viewBox 0 -960 960 960.
    static let bellPaused = "M370-320h220v-72H480l110-136v-72H370v72h110L370-392v72ZM160-200v-80h80v-280q0-83 50-147.5" +
        "T420-792v-28q0-25 17.5-42.5T480-880q25 0 42.5 17.5T540-820v28q80 20 130 84.5T720-560v280h80v80H160Zm320-300Zm0 420q-33 " +
        "0-56.5-23.5T400-160h160q0 33-23.5 56.5T480-80ZM320-280h320v-280q0-66-47-113t-113-47q-66 0-113 47t-47 113v280Z"
    private static let bellPath: CGPath = SVGPath.parse(bellPaused)
    /// Material Symbols "notifications_off" (Apache 2.0; Android ic_sym_notifications_off): the Mute all pill.
    static let bellOff = "M160-200v-80h80v-280q0-33 8.5-65t25.5-61l60 60q-7 16-10.5 32.5T320-560v280h248L56-792l56-56 736 736-56 56-146-144H160Zm560-154" +
        "-80-80v-126q0-66-47-113t-113-47q-26 0-50 8t-44 24l-58-58q20-16 43-28t49-18v-28q0-25 17.5-42.5T480-880q25 0 42.5 17.5T540-820v28q80 20 " +
        "130 84.5T720-560v206Zm-276-50Zm36 324q-33 0-56.5-23.5T400-160h160q0 33-23.5 56.5T480-80Zm33-481Z"
    private static let bellOffPath: CGPath = SVGPath.parse(bellOff)

    /// The bell, `size` square, top-left at (`x`, `y`); `off` = the "notifications off" one (Mute all).
    static func bell(_ ctx: CGContext, x: CGFloat, y: CGFloat, size: CGFloat, color: CGColor, off: Bool = false) {
        var t = CGAffineTransform(translationX: x, y: y).scaledBy(x: size / 960, y: size / 960).translatedBy(x: 0, y: 960)
        guard let p = (off ? bellOffPath : bellPath).copy(using: &t) else { return }
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
        let pad: CGFloat = h * 0.4 + h * 0.25 + h * 0.45          // split up: CI's compiler times out on one expression
        let w: CGFloat = words.map { (s: String) -> CGFloat in pad + icon + Ink.width(s, f) } ?? h
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
        if look.tonal { bell(ctx, x: ix, y: y + (h - icon) / 2, size: icon, color: fg, off: look.kind == "muted") }
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

    /// Which of `items` is the main ring, the one the sensor countdown ring covers: the first ring, else the only
    /// slot there is; -1 = none (Slots.mainIndex in CardStatus.kt).
    static func mainIndex(_ items: [SlotItem]) -> Int {
        if let i = items.firstIndex(where: { if case .metric = $0 { true } else { false } }) { return i }
        return items.isEmpty ? -1 : 0
    }

    private static func mark(_ item: SlotItem) -> TirMark {
        if case let .metric(spec, m, _) = item, spec.kind == .tir { return TirRing.mark(m.tir, spec) }
        return .none
    }
    private static func laurel(_ item: SlotItem) -> Bool { mark(item) != .none }

    /// Extra room between `items[i]` and the next one to its left when either wears a laurel (`r` = a slot's radius;
    /// Slots.extraGap in CardStatus.kt): 0.22 r, so the gap is 0.5 r and two laurels' facing stems touch in its middle.
    static func extraGap(_ items: [SlotItem], _ i: Int, r: CGFloat) -> CGFloat {
        i + 1 < items.count && (laurel(items[i]) || laurel(items[i + 1])) ? r * 0.22 : 0
    }

    /// Room kept left of the leftmost item (the last one) when it wears a laurel (Slots.leftInset on Android): its branch
    /// reaches about half a radius past the ring, so the value's words beside it don't run into the leaves.
    static func leftInset(_ items: [SlotItem], r: CGFloat) -> CGFloat { items.last.map { laurel($0) } == true ? r * 0.3 : 0 }
    /// Room kept at the card's edge (right of the first item) when it wears a laurel: 0.3 r (Slots.edgeInset).
    static func edgeInset(_ items: [SlotItem], r: CGFloat) -> CGFloat { items.first.map { laurel($0) } == true ? r * 0.3 : 0 }

    /// All the `extraGap`s of `items` and the `edgeInset` together (Slots.extraWidth).
    static func extraWidth(_ items: [SlotItem], r: CGFloat) -> CGFloat {
        items.indices.reduce(0) { $0 + extraGap(items, $1, r: r) } + edgeInset(items, r: r)
    }

    /// What a slot shows, for the layout report (Slots.describe in CardStatus.kt, the same words): kind, window, range,
    /// the number in it, its mark, and whether it is the main one (and carries the sensor countdown ring).
    static func describe(_ item: SlotItem, main: Bool, countdown: Double?) -> String {
        var out: String
        switch item {
        case let .metric(spec, m, _):
            out = "\(spec.kind.rawValue) \(Int(spec.window))"
            if spec.kind == .tir { out += " \(spec.range.rawValue)" }
            out += " " + (m.empty ? "-" : spec.kind == .tir ? TirRing.text(m.tir, spec) : MetricGauge.text(spec.kind, m).0)
            let mk = mark(item)
            if mk != .none { out += " \(mk.rawValue)" }
        case let .last(info):
            out = "LAST \(info.tr.k)" + (info.carbs.map { "+\($0.info.tr.k)" } ?? "")
        }
        if main { out += " [main]" }
        if countdown != nil { out += " [countdown]" }
        return out
    }

    /// The slots' centres, right to left from `right` (the card's edge), `edgeInset` in, `gap` + `extraGap` apart.
    static func centers(_ items: [SlotItem], right: CGFloat, size: CGFloat, gap: CGFloat) -> [CGFloat] {
        var out: [CGFloat] = [], x = right - edgeInset(items, r: size / 2) - size / 2
        for i in items.indices { out.append(x); x -= size + gap + extraGap(items, i, r: size / 2) }
        return out
    }

    /// What goes under all the slots, before any `draw` (Slots.drawUnder in CardStatus.kt): the rings' laurels. `cx` =
    /// each item's centre (same order). Between two laurelled rings the facing sides meet (`Laurel.Side.merged`); every
    /// other side is the whole branch, which the neighbour covers where it still reaches it.
    static func drawUnder(_ ctx: CGContext, _ items: [SlotItem], cx: [CGFloat], cy: CGFloat, radius: CGFloat, _ pal: GraphPalette) {
        let colors: [CGColor?] = items.map { item in
            guard laurel(item), case let .metric(_, _, e) = item else { return nil }
            return (pal.knockout ? CGColor(gray: 1, alpha: 1) : pal.tight).a(e)
        }
        let order = items.indices.sorted { cx[$0] < cx[$1] }   // left to right
        func side(_ n: Int?) -> Laurel.Side { n.flatMap { colors[$0] } != nil ? .merged : .full }
        for (p, i) in order.enumerated() {
            guard let c = colors[i] else { continue }
            let l = p > 0 ? order[p - 1] : nil, r = p + 1 < order.count ? order[p + 1] : nil
            Laurel.draw(ctx, center: CGPoint(x: cx[i], y: cy), r: radius, c, left: side(l), right: side(r))
        }
        // The flowers: a tip flower on each side, except where two flowered laurels meet: one shared piece there, the
        // flower of the ring that isn't faded, or of the higher number when both are faded or neither is.
        let blooms: [Bloom?] = items.map { item in
            if case let .metric(spec, m, _) = item, spec.kind == .tir, spec.marks { return Bloom.of(m.tir, spec.range) }
            return nil
        }
        func fade(_ i: Int) -> CGFloat { if case let .metric(_, _, e) = items[i] { return e }; return 1 }
        func share(_ i: Int) -> Double { if case let .metric(spec, m, _) = items[i] { return m.tir.of(spec.range) }; return 0 }
        for (p, i) in order.enumerated() {
            guard let b = blooms[i] else { continue }
            let l = p > 0 ? order[p - 1] : nil, r = p + 1 < order.count ? order[p + 1] : nil
            if l.flatMap({ blooms[$0] }) == nil { Blooms.tip(ctx, b, cx: cx[i], cy: cy, r: radius, isLeft: true, pal, alpha: fade(i)) }
            if let r, let rb = blooms[r] {
                let first = fade(i) != fade(r) ? (fade(i) > fade(r) ? b : rb) : (share(i) >= share(r) ? b : rb)
                Blooms.center(ctx, first, lx: cx[i], rx: cx[r], cy: cy, r: radius, pal, la: fade(i), ra: fade(r))
            } else { Blooms.tip(ctx, b, cx: cx[i], cy: cy, r: radius, isLeft: false, pal, alpha: fade(i)) }
        }
    }

    /// Draw `item` as a circle of `radius` around `c`, with the sensor `countdown` ring over it (the main slot's; a
    /// time-in-range ring draws it under its crown).
    static func draw(_ ctx: CGContext, _ item: SlotItem, center c: CGPoint, radius: CGFloat, now: TimeInterval, _ pal: GraphPalette, d: CGFloat,
                     countdown: Double? = nil) {
        switch item {
        case let .metric(spec, m, e) where spec.kind == .tir:
            TirRing.draw(ctx, center: c, radius: radius, tir: m.tir, spec: spec, pal, emphasis: e, countdown: countdown)
            return
        case let .metric(spec, m, e): MetricGauge.draw(ctx, center: c, radius: radius, m: m, spec: spec, pal, emphasis: e, d: d)
        case let .last(info): LastSlot.draw(ctx, center: c, radius: radius, info: info, now: now, pal, d: d)
        }
        SensorBadge.drawCountdown(ctx, center: c, radius: radius, countdown, pal)
    }
}

/// The last-treatment slot: the kind's marker, the amount ("4.5u", "30g") in the kind's colour and the time since it
/// ("12m"); around it a ring in the kind's colour for the share of the alert mute still left, shrinking back to 12
/// o'clock. When the mute is over (or the kind mutes nothing) only the grey track is left.
enum LastSlot {
    /// Smallest slot radius (pt) that draws insulin + carbs concentric; smaller slots show the insulin alone (PAIR_MIN_DP).
    static let pairMin: CGFloat = 20

    static func draw(_ ctx: CGContext, center c: CGPoint, radius: CGFloat, info: LastTreatment.Info, now: TimeInterval, _ pal: GraphPalette,
                     d: CGFloat) {
        if let carbs = info.carbs, radius >= pairMin * d { return drawPair(ctx, center: c, radius: radius, info, carbs.info, now: now, pal) }
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

    /// Insulin with carbs logged while it works (the user, 2026-10-21; CardStatus.kt `LastSlot.drawPair`): two
    /// concentric rings, the outer one the insulin's mute left, the inner one the carbs', and the two amounts stacked
    /// inside in their colours ("4.5u" over "45g"); no markers or time.
    private static func drawPair(_ ctx: CGContext, center c: CGPoint, radius: CGFloat, _ ins: LastTreatment.Info, _ carbs: LastTreatment.Info,
                                 now: TimeInterval, _ pal: GraphPalette) {
        let outerSw = radius * 0.16, innerSw = radius * 0.13
        let outerR = radius - outerSw / 2
        let innerR = radius - outerSw - radius * 0.05 - innerSw / 2
        func color(_ k: String) -> CGColor { pal.knockout ? CGColor(gray: 1, alpha: 1) : TreatmentMarks.color(k, dark: pal.dark) }
        ctx.saveGState()
        ctx.setLineCap(.round)
        for (info, r, sw) in [(ins, outerR, outerSw), (carbs, innerR, innerSw)] {
            ctx.setLineWidth(sw)
            ctx.setStrokeColor(pal.knockout ? CGColor(gray: 1, alpha: 0.25) : pal.grid)
            ctx.strokeEllipse(in: CGRect(x: c.x - r, y: c.y - r, width: 2 * r, height: 2 * r))
            let left = info.left(now)
            if left > 0 {
                ctx.setStrokeColor(color(info.tr.k))
                let sweep = max(2, 360 * CGFloat(left)) * .pi / 180
                ctx.addArc(center: c, radius: r, startAngle: -.pi / 2, endAngle: -.pi / 2 + sweep, clockwise: false)
                ctx.strokePath()
            }
        }
        ctx.restoreGState()
        // the two amounts in the inner circle, one size so they read as a pair
        let room = (innerR - innerSw / 2) * 2 * 0.84
        let a = TreatmentMarks.amount(ins.tr) ?? "", b = TreatmentMarks.amount(carbs.tr) ?? ""
        var size = radius * 0.36
        let widest = max(Ink.width(a, Ink.font(size, .heavy)), Ink.width(b, Ink.font(size, .heavy)), 1)
        size = min(size, size * room / widest)
        let font = Ink.font(size, .heavy)
        let cap = size * 0.72, gap = size * 0.28
        Ink.draw(ctx, a, x: c.x, y: c.y - gap / 2, font, color(ins.tr.k), align: 0.5)
        Ink.draw(ctx, b, x: c.x, y: c.y + gap / 2 + cap, font, color(carbs.tr.k), align: 0.5)
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

// MARK: - Sensor life (SensorLife.kt, SensorBadge.kt)

/// The wearer's current sensor (unix s), from the live event's `ss`/`se`/`sw` (PROTOCOL-v2 "Sensor life").
struct SensorInfo: Codable, Equatable {
    var start: TimeInterval
    var end: TimeInterval
    var warmupEnd: TimeInterval
}

/// The sensor's remaining life and warm-up; the same rules as Android's core/SensorLife (spikes/graph-fixtures/sensor).
enum SensorLife {
    static let defaultCountdownHours = 8
    static let countdownRange = 1...24
    /// The warm-up state lasts until the first reading, at most this long past the warm-up's end.
    static let warmupGrace: TimeInterval = 15 * 60

    static func ended(end: TimeInterval, now: TimeInterval) -> Bool { now >= end }


    /// ≥ 24 h left → whole days ("13d"), ≥ 1 h → hours ("20h"), else minutes ("45m", "0m" once ended); floor.
    static func label(end: TimeInterval, now: TimeInterval) -> String {
        Durations.whole(Int(max(0, (end - now).rounded(.down))) / 60)
    }

    /// The disc's arc: the share of the sensor's life left, 1 → 0.
    static func lifeFraction(_ s: SensorInfo, now: TimeInterval) -> Double {
        min(1, max(0, (s.end - now) / max(1, s.end - s.start)))
    }

    /// The countdown ring: nil outside the last `hours` (and after the end), else the share of the window left.
    static func countdownFraction(end: TimeInterval, now: TimeInterval, hours: Int) -> Double? {
        let left = end - now, window = TimeInterval(hours * 3600)
        return left <= 0 || left > window ? nil : left / window
    }

    /// Warming up: started, no reading since the start (`latest` = the newest reading's time), ≤ 15 min past its end.
    static func warming(_ s: SensorInfo, latest: TimeInterval?, now: TimeInterval) -> Bool {
        now >= s.start && now < s.warmupEnd + warmupGrace && (latest.map { $0 < s.start } ?? true)
    }

    /// The warm-up's time left as the card's figure: whole minutes rounded up ("42m"), from 60 of them hours ("1h": one
    /// rounding, so 59:15 left is "1h" everywhere, never "60m"). From the warm-up's end until the first reading
    /// "Almost ready": a countdown never shows "0m" or less.
    static func warmupLabel(_ s: SensorInfo, now: TimeInterval) -> String {
        if waitingFirstReading(s, now: now) { return "Almost ready" }
        let min = (Int((s.warmupEnd - now).rounded(.down)) + 59) / 60
        return Durations.whole(min)
    }

    /// While warming up: the warm-up's time is over and the first reading hasn't come yet.
    static func waitingFirstReading(_ s: SensorInfo, now: TimeInterval) -> Bool { now >= s.warmupEnd }

    /// The warm-up ring: fills 0 → 1 from the start to the warm-up's end.
    static func warmupFraction(_ s: SensorInfo, now: TimeInterval) -> Double {
        min(1, max(0, (now - s.start) / max(1, s.warmupEnd - s.start)))
    }
}

/// The sensor badge beside the trend arrow (a Libre sensor disc whose outline depletes, then "13d"), the warm-up
/// figure and the ring over the main ring; the same look as Android's SensorBadge.kt.
enum SensorBadge {
    struct Look: Equatable {
        var label: String
        var life: Double
        var ended: Bool
        /// While warming up: the figure ("42m"); `fill` = the warm-up done.
        var warmup: String?
        var fill: Double
        var readyAt: TimeInterval
        var end: TimeInterval = 0

        /// Warming up past the warm-up's end: `warmup` is "Almost ready", the line under it "Waiting for the first reading".
        func waiting(now: TimeInterval) -> Bool { warmup != nil && now >= readyAt }

        /// The line under the warm-up figure: "Ready at 19:45", or "Waiting for the first reading" once that's past.
        func warmupLine(now: TimeInterval) -> String {
            waiting(now: now) ? "Waiting for the first reading" : "Ready at \(Freshness.timeText(readyAt, now: now))"
        }
    }

    static func look(_ s: SensorInfo?, now: TimeInterval, latest: TimeInterval?) -> Look? {
        guard let s else { return nil }
        let warm = SensorLife.warming(s, latest: latest, now: now)
        return Look(label: SensorLife.label(end: s.end, now: now), life: SensorLife.lifeFraction(s, now: now),
                    ended: SensorLife.ended(end: s.end, now: now), warmup: warm ? SensorLife.warmupLabel(s, now: now) : nil,
                    fill: SensorLife.warmupFraction(s, now: now), readyAt: s.warmupEnd, end: s.end)
    }

    /// The ring over the main ring: the warm-up filling up, else the end countdown draining (nil = none).
    static func ring(_ look: Look?, _ s: SensorInfo?, now: TimeInterval, hours: Int) -> Double? {
        guard let look, let s else { return nil }
        return look.warmup != nil ? look.fill : SensorLife.countdownFraction(end: s.end, now: now, hours: hours)
    }

    private static func color(_ look: Look, _ pal: GraphPalette) -> CGColor { look.ended ? pal.veryLow : pal.inkSoft }
    private static func font(_ h: CGFloat) -> CTFont { Ink.font(h * 0.95, .semibold, rounded: true) }

    static func width(_ look: Look, h: CGFloat) -> CGFloat { h * 1.3 + Ink.width(look.label, font(h)) }

    /// The badge `h` tall, top-left at (`x`, `y`); `label: false` = the disc alone (warm-up). Returns its width.
    @discardableResult
    static func draw(_ ctx: CGContext, x: CGFloat, y: CGFloat, h: CGFloat, _ look: Look, _ pal: GraphPalette, label: Bool = true) -> CGFloat {
        let c = color(look, pal), sw = h * 0.14, r = h / 2 - sw / 2
        let center = CGPoint(x: x + h / 2, y: y + h / 2)
        ctx.saveGState()
        ctx.setLineWidth(sw); ctx.setLineCap(.round)
        ctx.setStrokeColor(look.ended ? c : c.copy(alpha: 0.31) ?? c)
        ctx.strokeEllipse(in: CGRect(x: center.x - r, y: center.y - r, width: 2 * r, height: 2 * r))
        let arc = look.warmup != nil ? look.fill : look.life
        if !look.ended, arc > 0 {
            ctx.setStrokeColor(c)
            let sweep = max(4, 360 * CGFloat(arc)) * .pi / 180
            ctx.addArc(center: center, radius: r, startAngle: -.pi / 2, endAngle: -.pi / 2 + sweep, clockwise: false)
            ctx.strokePath()
        }
        ctx.setFillColor(c)
        ctx.fillEllipse(in: CGRect(x: center.x - h * 0.15, y: center.y - h * 0.15, width: h * 0.3, height: h * 0.3))
        ctx.restoreGState()
        guard label else { return h }
        let f = font(h)
        Ink.draw(ctx, look.label, x: x + h * 1.3, y: center.y + CTFontGetCapHeight(f) / 2, f, c)
        return width(look, h: h)
    }

    /// The "sensor ended" flag: SF Symbol `flag.checkered` (Android: Material Symbols `sports_score`), `size` pt tall, its
    /// top-left at (`x`, `y`) in the card's y-down context, tinted `color`. Returns its width (0 if the symbol is missing).
    @discardableResult
    static func flag(_ ctx: CGContext, x: CGFloat, y: CGFloat, size: CGFloat, color: CGColor) -> CGFloat {
        symbol(ctx, "flag.checkered", x: x, y: y, size: size, color: color)
    }

    /// An SF Symbol `name` `size` tall in `color`, top-left at (`x`, `y`) of a y-down context; returns its width.
    static func symbol(_ ctx: CGContext, _ name: String, x: CGFloat, y: CGFloat, size: CGFloat, color: CGColor) -> CGFloat {
        guard let sym = NSImage(systemSymbolName: name, accessibilityDescription: nil)?
                .withSymbolConfiguration(NSImage.SymbolConfiguration(pointSize: size, weight: .semibold)) else { return 0 }
        let k = size / max(sym.size.height, 1)
        let rect = NSRect(x: x, y: y, width: sym.size.width * k, height: size)
        let tinted = NSImage(size: sym.size, flipped: false) { r in
            sym.draw(in: r)
            (NSColor(cgColor: color) ?? .gray).set()
            r.fill(using: .sourceAtop)
            return true
        }
        NSGraphicsContext.saveGraphicsState()
        NSGraphicsContext.current = NSGraphicsContext(cgContext: ctx, flipped: true)
        tinted.draw(in: rect, from: .zero, operation: .sourceOver, fraction: 1, respectFlipped: true, hints: nil)
        NSGraphicsContext.restoreGraphicsState()
        return rect.width
    }

    /// The ring over a slot of `radius` (the slot rings' stroke is radius × 0.2): ink on a background-coloured halo
    /// (black / white where the background is transparent; cut out in tinted widgets).
    static func drawCountdown(_ ctx: CGContext, center c: CGPoint, radius: CGFloat, _ fraction: Double?, _ pal: GraphPalette) {
        guard let fraction else { return }
        let ringSw = radius * 0.2, mid = radius - ringSw / 2
        let sweep = max(3, 360 * CGFloat(fraction)) * .pi / 180
        func arc() { ctx.addArc(center: c, radius: mid, startAngle: -.pi / 2, endAngle: -.pi / 2 + sweep, clockwise: false) }
        ctx.saveGState()
        ctx.setLineCap(.round)
        ctx.setLineWidth(ringSw * 0.62)
        if pal.knockout { ctx.setBlendMode(.clear) }
        ctx.setStrokeColor(pal.bg.alpha > 0 ? pal.bg : CGColor(gray: pal.dark ? 0 : 1, alpha: 1))
        arc(); ctx.strokePath()
        ctx.setBlendMode(.normal)
        ctx.setLineWidth(ringSw * 0.3)
        ctx.setStrokeColor(pal.knockout ? CGColor(gray: 1, alpha: 1) : pal.ink)
        arc(); ctx.strokePath()
        ctx.restoreGState()
    }
}

// MARK: - Card

/// Value + trend arrow + rings + chart in one drawing (GlucoseCard.kt), adapting to the size.
enum GlucoseCard {
    static let minCoverage = 0.25
    /// Self-tests: every layout drawn and every face decided, as it happens (nil in the app).
    nonisolated(unsafe) static var observer: ((CardLayout) -> Void)?
    nonisolated(unsafe) static var faceObserver: ((CardSurface, GlucoseLook) -> Void)?

    /// Draw `part` of the card `st` for `surface` and return what was laid out. `metricsOf` gives a slot's numbers; by
    /// default computed from `readings` (enough for windows they cover). `alerts` = the alert rules behind the badge,
    /// the mute lines and the last-treatment slot (nil = none of them).
    @discardableResult
    static func draw(_ ctx: CGContext, size: CGSize, readings: [GraphReading], marks: [GraphMark], now: TimeInterval,
                     _ st: CardStyle, _ pal: GraphPalette, d: CGFloat = 1, surface: CardSurface, part: CardPart,
                     metricsOf: ((RingSpec, Bands) -> GlucoseMetrics)? = nil, alerts: AlertSettings? = nil,
                     paused: [String: TimeInterval] = [:], allMuted: TimeInterval = 0, view: ChartView? = nil) -> CardLayout {
        let w = size.width, h = size.height
        let rec = CardLayout(surface, part, st, w: w, h: h, d: d)
        defer { observer?(rec) }
        let latest = readings.max { $0.t < $1.t }
        let status = alerts.map { AlertStatus.of(latest, $0, marks, now: now, paused: paused, allMuted: allMuted) } ?? .none
        let last = st.last != .off ? alerts.flatMap { LastTreatment.of(marks, $0, status, now: now) } : nil
        let fr = Freshness.of(age: latest.map { now - $0.t })
        let sl = SensorBadge.look(st.sensor, now: now, latest: latest?.t)
        // a zoomed chart: the same style with the window's length the surface zoomed to
        var graph = fr.showGraph ? st.graph : nil
        if let s = view?.span { graph?.window = s }
        let bands = st.bands.sane()
        let metrics = metricsOf ?? { r, b in GlucoseMetrics.of(readings, from: now - r.window, to: now, bands: b) }
        let spot = sensorSpot(st, sl, fr)
        let countdown = SensorBadge.ring(sl, st.sensor, now: now, hours: st.countdownHours)
        if part == .slots {
            _ = drawRings(ctx, CGRect(x: 0, y: 0, width: w, height: h), st, last, now, bands, pal, d: d, metrics, countdown: countdown,
                          rec: rec, dedicated: true)
            return rec
        }
        // one part in a box of its own: that box is the part's whole area
        let sideBySide = part == .all && graph != nil && st.value && h < 100 * d && w > 2.6 * h
        // A chart card with no chart (no reading for 3 h, none yet): the value's face takes the chart's place; a card tall
        // enough for a chart keeps the slots in the header's row and puts the face where the chart was (GlucoseCard.kt).
        let noChart = st.graph != nil && graph == nil && part == .all
        let rowH = min(max(h * 0.34, 30 * d), 80 * d)
        let stacked = noChart && st.value && h - rowH - 6 * d >= 48 * d
        let headerH: CGFloat = (part == .value || part == .header) ? h : (part == .graph || !st.value) ? 0
            : stacked ? rowH : (graph == nil || sideBySide) ? h : rowH
        let valueRight = sideBySide ? min(w * 0.32, 120 * d) : w

        let chart = sideBySide ? CGRect(x: valueRight + 6 * d, y: 0, width: w - valueRight - 6 * d, height: h)
            : part == .graph ? CGRect(x: 0, y: 0, width: w, height: h)
            : CGRect(x: 0, y: headerH + (st.value ? 6 * d : 0), width: w, height: h - headerH - (st.value ? 6 * d : 0))
        if part == .all || part == .graph, let graph {
            GlucoseGraph.draw(ctx, chart, readings: readings, marks: marks, now: now, graph, pal, d: d, alerts: alerts,
                              sensor: spot == .graph ? sl : nil, rec: rec, view: view, latestT: latest?.t)
        }
        // a graph-only card without its chart: the value's face alone
        if noChart && !st.value {
            drawValue(ctx, chart, latest, fr, now, bands, pal, d: d, look: nil, sensor: sl, rec: rec)
        }
        if st.value && part != .graph {
            let box = CGRect(x: 0, y: 0, width: valueRight, height: sideBySide ? h : headerH)
            let ringsW = sideBySide || part == .value ? 0
                : drawRings(ctx, box, st, last, now, bands, pal, d: d, metrics, countdown: countdown, rec: rec, dedicated: false)
            let look = st.badge ? StatusBadge.look(status, now: now) : nil
            drawValue(ctx, stacked ? chart : CGRect(x: box.minX, y: box.minY, width: box.width - ringsW, height: box.height), latest, fr, now,
                      bands, pal, d: d, look: look, sensor: sl, rec: rec)
        }
        return rec
    }

    /// The y range (mg/dL) the chart of `st` draws for the window ending at `end` (`GlucoseGraph.yRange`): a scrolling
    /// surface animates `ChartView.y` towards it, so the scale never jumps (GlucoseCard.kt `yRange`).
    static func yRange(_ readings: [GraphReading], end: TimeInterval, _ st: GraphStyle) -> ClosedRange<Double> {
        GlucoseGraph.yRange(GlucoseStats.window(readings, from: end - st.window - 60, to: end), bands: st.bands.sane())
    }

    /// The value's decision for the newest reading `latest` (GlucoseCard.face on Android): a warming-up or ended
    /// sensor says so first, then the reading's `Freshness` (struck when stale, gone when old).
    static func face(_ latest: GraphReading?, now: TimeInterval, sensor: SensorBadge.Look?, surface: CardSurface,
                     format: (Int) -> String = { "\($0)" }) -> GlucoseLook {
        let f = Freshness.look(sgv: latest?.sgv, t: latest?.t, rate: latest?.rate, now: now, ended: sensor?.ended == true,
                               warmup: sensor?.warmup, format: format)
        faceObserver?(surface, f)
        return f
    }

    /// Where the sensor's time left goes on a card `st` (the WHOLE card, also when its parts are drawn in separate boxes):
    /// the one decision every surface uses (GlucoseCard.kt `sensorSpot`, both checked by spikes/graph-fixtures/place).
    /// In the chart's hour-label row whenever the card shows a chart with hour labels (with or without the % column);
    /// nowhere else: a card without a labelled chart shows just the number and its trend (the user, 2026-10-21: beside the
    /// arrow it turned "120 ○" into "120°"), nor when the place's setting hides it, nothing is known, or the sensor warms
    /// up / has ended (the value's place shows those states).
    static func sensorSpot(_ st: CardStyle, _ look: SensorBadge.Look?, _ fr: Freshness) -> SensorSpot {
        guard let look, st.sensorTime, look.warmup == nil, !look.ended else { return .none }
        if let g = st.graph, fr.showGraph, g.axisLabels { return .graph }
        return .none
    }

    /// Slots (`Slots.arrange`) right-aligned in `box` (main rightmost); returns the width used. Beside the value
    /// (`dedicated` false) they take at most 55 % of the row; in a box of their own (`.slots`) as many as fit whole.
    private static func drawRings(_ ctx: CGContext, _ box: CGRect, _ st: CardStyle, _ last: LastTreatment.Info?, _ now: TimeInterval,
                                  _ bands: Bands, _ pal: GraphPalette, d: CGFloat,
                                  _ metricsOf: (RingSpec, Bands) -> GlucoseMetrics, countdown: Double? = nil,
                                  rec: CardLayout, dedicated: Bool) -> CGFloat {
        let extra = st.last != .off && (last != nil || st.lastElse != nil)
        guard !st.rings.isEmpty || extra else { return 0 }
        let size = min(box.height * 0.96, 84 * d)
        guard size >= 30 * d else { return 0 }
        let gap = size * 0.14
        let fit = min(Int((box.width * (dedicated ? 1 : 0.55) + gap) / (size + gap)), 4)
        guard fit >= 1 else { return 0 }
        let rings = st.rings.prefix(3).enumerated().compactMap { (i, spec) -> (Int, RingSpec, GlucoseMetrics)? in
            let m = metricsOf(spec, bands)
            return m.tir.coverage >= minCoverage ? (i, spec, m) : nil
        }
        let fallback: (RingSpec, GlucoseMetrics)? = st.last != .off && last == nil
            ? st.lastElse.flatMap { s in let m = metricsOf(s, bands); return m.tir.coverage >= minCoverage ? (s, m) : nil } : nil
        let x = box.maxX
        var items = Slots.arrange(rings, place: st.last, last: last, fallback: fallback, max: fit)
        // in a box of its own every slot fits whole, the laurels' extra room included
        if dedicated {
            while !items.isEmpty && CGFloat(items.count) * size + CGFloat(items.count - 1) * gap + Slots.extraWidth(items, r: size / 2) > box.width + 0.5 {
                items = Slots.arrange(rings, place: st.last, last: last, fallback: fallback, max: items.count - 1)
            }
        }
        guard !items.isEmpty else { return 0 }
        let main = Slots.mainIndex(items)
        let cx = Slots.centers(items, right: x, size: size, gap: gap)
        Slots.drawUnder(ctx, items, cx: cx, cy: box.midY, radius: size / 2, pal)
        for (i, item) in items.enumerated() {
            Slots.draw(ctx, item, center: CGPoint(x: cx[i], y: box.midY), radius: size / 2, now: now, pal, d: d,
                       countdown: i == main ? countdown : nil)
            rec.add("slot", CGRect(x: cx[i] - size / 2, y: box.midY - size / 2, width: size, height: size),
                    Slots.describe(item, main: i == main, countdown: i == main ? countdown : nil))
        }
        rec.add("slots", CGRect(x: cx.last! - size / 2, y: box.minY, width: x - (cx.last! - size / 2), height: box.height), "\(items.count)")
        rec.add("slots.box", box)
        // a laurel on the leftmost slot reaches past its ring: what is left of the slots keeps clear of it too
        return cx.last.map { x - ($0 - size / 2) + gap + Slots.leftInset(items, r: size / 2) } ?? 0
    }

    /// The number + arrow, and under it the late reading's time and/or the status badge (`look`). The badge takes a
    /// row under the number when the box is tall enough to keep the number at least 26 pt; otherwise it shrinks to its
    /// icon beside the arrow, or is left out when there's no room there either.
    private static func drawValue(_ ctx: CGContext, _ box: CGRect, _ r: GraphReading?, _ fr: Freshness, _ now: TimeInterval,
                                  _ bands: Bands, _ pal: GraphPalette, d: CGFloat, look: StatusBadge.Look?,
                                  sensor: SensorBadge.Look?, rec: CardLayout) {
        let small = Ink.font(max(11 * d, box.height * 0.17), .regular)
        let face = face(r, now: now, sensor: sensor, surface: rec.surface)
        if face.state == .warmingUp, let sensor { return drawWarmup(ctx, box, sensor, small, now, pal, d: d, rec: rec) }
        // an ended sensor says so as soon as it has ended, instead of a value or "No reading since" (not an error)
        if face.state == .ended { return drawEnded(ctx, box, pal, d: d, rec: rec) }
        guard let r, face.shown, let s = face.text else {
            // Fit the longer line into the space left of the rings; no readings for 20 min: the menubar's "no readings"
            // symbol before the words, as tall as both lines.
            let lines = r.map { ["No reading since", Freshness.timeText($0.t, now: now)] } ?? ["No readings yet"]
            var size = min(box.height * 0.26, 20 * d)
            let iconW: (CGFloat) -> CGFloat = { r == nil ? 0 : $0 * 2.1 + $0 * 0.35 }
            let widest = lines.map { Ink.width($0, Ink.font(size, .semibold)) }.max() ?? 0
            if widest + iconW(size) > box.width - 8 * d, widest > 0 { size *= max(0.4, (box.width - 8 * d) / (widest + iconW(size))) }
            let big = Ink.font(size, .semibold)
            let y = box.midY - (lines.count == 1 ? -size / 3 : size * 0.15)
            if r != nil { _ = SensorBadge.symbol(ctx, GlucoseLook.noReadingsSymbol, x: box.minX, y: y - size * 0.95, size: size * 2.1, color: pal.inkSoft) }
            let x = box.minX + iconW(size)
            Ink.draw(ctx, lines[0], x: x, y: y, big, pal.inkSoft)
            if lines.count > 1 { Ink.draw(ctx, lines[1], x: x, y: y + size * 1.25, big, pal.inkSoft); rec.add("time.text", nil, lines[1]) }
            rec.add("value.none", CGRect(x: box.minX, y: y - size, width: box.width, height: size * CGFloat(lines.count) * 1.25), face.state.layoutName)
            return
        }
        let color = fr.strike ? pal.inkSoft : pal.at(bands, Double(r.sgv))
        // which colour, by name, for the cross-surface test (layout rules "value-color")
        rec.add("value.color", nil, fr.strike ? "grey" : r.sgv >= bands.extremelyHigh ? "EXTREMELY_HIGH" : bands.of(Double(r.sgv)).name)
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
        func need() -> CGFloat { Ink.width(s, Ink.font(numSize, .bold, rounded: true)) + numSize * arrowFactor * 1.1 }
        for _ in 0..<3 { let n = need(); if n > box.width { numSize *= box.width / n * 0.995 } }
        let num = Ink.font(numSize, .bold, rounded: true)
        // no row for the badge: its icon goes beside the arrow if there's room
        let iconH = min(numBox.height * 0.5, 24 * d)
        let iconBadge = look != nil && !badgeRow && box.width - (Ink.width(s, num) + numSize * arrowFactor * 1.2) >= iconH
        let capH = CTFontGetCapHeight(num)
        let baseY = numBox.midY + capH / 2
        Ink.draw(ctx, s, x: box.minX - numSize * 0.04, y: baseY, num, color, strike: fr.strike)
        rec.add("value.number", CGRect(x: box.minX, y: numBox.midY - capH / 2, width: Ink.width(s, num), height: capH), fr.strike ? "\(s) struck" : s)
        let x = box.minX + Ink.width(s, num) + numSize * 0.08
        let arrowSize = numSize * arrowFactor
        if face.trend == .arrow, let rate = r.rate {
            ctx.saveGState()
            ctx.translateBy(x: x + arrowSize / 2, y: numBox.midY)
            ctx.rotate(by: CGFloat(-max(-1, min(1, rate / 3))) * .pi / 2)
            drawArrow(ctx, scale: arrowSize * 1.05, color: color, double: abs(rate) >= 3)
            ctx.restoreGState()
            rec.add("value.trend", CGRect(x: x, y: numBox.midY - arrowSize / 2, width: arrowSize, height: arrowSize), abs(rate) >= 3 ? "arrow double" : "arrow single")
        } else {
            // no trend (no rate, or the value is struck through): the hollow ring in the arrow's place
            ctx.setStrokeColor(color)
            ctx.setLineWidth(TrendMark.ringStroke(arrowSize))
            let rr = TrendMark.ringRadius(arrowSize)
            ctx.strokeEllipse(in: CGRect(x: x + arrowSize / 2 - rr, y: numBox.midY - rr, width: 2 * rr, height: 2 * rr))
            rec.add("value.trend", CGRect(x: x, y: numBox.midY - arrowSize / 2, width: arrowSize, height: arrowSize), "ring")
        }
        if badgeRow, let look {
            let top = numBox.maxY + badgeH * 0.25
            let bw = StatusBadge.draw(ctx, x: box.minX, y: top, h: badgeH, maxW: box.width, look, dark: pal.dark, knockout: pal.knockout)
            rec.add("value.status", CGRect(x: box.minX, y: top, width: bw, height: badgeH), "\(look.kind) [row]")
            if fr.showTime {
                let t = Ink.font(badgeH * 0.56, .regular)
                let tx = box.minX + bw + badgeH * 0.35
                if tx + Ink.width(time, t) <= box.maxX {
                    Ink.draw(ctx, time, x: tx, y: top + badgeH / 2 + badgeH * 0.56 * 0.36, t, pal.inkSoft)
                    rec.add("value.time", CGRect(x: tx, y: top, width: Ink.width(time, t), height: badgeH), "[badge row]")
                    rec.add("time.text", nil, time)
                } else { rec.add("value.time", nil, "[badge row] (no room)") }
            }
        } else {
            if iconBadge, let look {
                StatusBadge.draw(ctx, x: x + arrowSize * 1.15, y: numBox.midY - iconH / 2, h: iconH, maxW: iconH, look, dark: pal.dark,
                                 knockout: pal.knockout)
                rec.add("value.status", CGRect(x: x + arrowSize * 1.15, y: numBox.midY - iconH / 2, width: iconH, height: iconH), "\(look.kind) [icon]")
            } else if let look { rec.add("value.status", nil, "\(look.kind) (no room)") }
            if fr.showTime {
                Ink.draw(ctx, time, x: box.minX, y: baseY + CTFontGetSize(small) * 1.25, small, pal.inkSoft)
                rec.add("value.time", CGRect(x: box.minX, y: baseY, width: Ink.width(time, small), height: CTFontGetSize(small) * 1.25), "[own row]")
                rec.add("time.text", nil, time)
            }
        }
    }

    /// The sensor has ended: "Sensor ended" in the secondary colour (a state, not an error) and under it the action pill
    /// "Replace sensor" (StatusBadge.replaceSensor, the same pill as "Eat carbs": full, short or icon only as room
    /// allows); a box too short for the pill's row gets the icon-only pill beside the words (GlucoseCard.drawEnded).
    private static func drawEnded(_ ctx: CGContext, _ box: CGRect, _ pal: GraphPalette, d: CGFloat, rec: CardLayout) {
        let words = "Sensor ended"
        let badgeH = min(max(box.height * 0.24, 14 * d), 24 * d)
        let row = box.height - badgeH * 1.25 >= 20 * d
        let textBox = row ? CGRect(x: box.minX, y: box.minY, width: box.width, height: box.height - badgeH * 1.25) : box
        var size = min(textBox.height * 0.5, 20 * d)
        let iconRoom: CGFloat = row ? 0 : badgeH * 1.3
        // the checkered flag before the words: the menubar's "sensor ended" glyph, so the two read as one thing
        let tw = Ink.width(words, Ink.font(size, .semibold)) + size * 1.25
        if tw > box.width - iconRoom, tw > 0 { size *= max(0.45, (box.width - iconRoom) / tw) }
        let f = Ink.font(size, .semibold)
        let base = textBox.midY + size * 0.36, fs = size * 1.05, cap = CTFontGetCapHeight(f)
        SensorBadge.flag(ctx, x: box.minX, y: base - cap - (fs - cap) / 2, size: fs, color: pal.inkSoft)
        Ink.draw(ctx, words, x: box.minX + size * 1.25, y: base, f, pal.inkSoft)
        rec.add("value.ended", CGRect(x: box.minX, y: textBox.minY, width: size * 1.25 + Ink.width(words, f), height: textBox.height))
        if row {
            let pw = StatusBadge.draw(ctx, x: box.minX, y: textBox.maxY + badgeH * 0.25, h: badgeH, maxW: box.width, StatusBadge.replaceSensor,
                             dark: pal.dark, knockout: pal.knockout)
            rec.add("value.pill", CGRect(x: box.minX, y: textBox.maxY + badgeH * 0.25, width: pw, height: badgeH), "[row]")
        } else {
            let x = box.minX + size * 1.25 + Ink.width(words, f) + badgeH * 0.3
            if x + badgeH <= box.maxX {
                _ = StatusBadge.draw(ctx, x: x, y: box.midY - badgeH / 2, h: badgeH, maxW: badgeH, StatusBadge.replaceSensor, dark: pal.dark, knockout: pal.knockout)
                rec.add("value.pill", CGRect(x: x, y: box.midY - badgeH / 2, width: badgeH, height: badgeH), "[icon]")
            } else { rec.add("value.pill", nil, "(no room)") }
        }
    }

    /// A new sensor warming up: the time left ("42m") where the number goes, in the secondary colour, the sensor disc
    /// beside it filling up, and "Ready at 19:45" under it (GlucoseCard.drawWarmup on Android).
    private static func drawWarmup(_ ctx: CGContext, _ box: CGRect, _ look: SensorBadge.Look, _ small: CTFont, _ now: TimeInterval,
                                   _ pal: GraphPalette, d: CGFloat, rec: CardLayout) {
        guard let s = look.warmup else { return }
        let line = look.warmupLine(now: now)
        let numBox = CGRect(x: box.minX, y: box.minY, width: box.width, height: box.height - CTFontGetSize(small) * 1.25)
        var numSize = numBox.height * 1.18
        let need = Ink.width(s, Ink.font(numSize, .bold, rounded: true)) + numSize * 0.6
        if need > box.width { numSize *= box.width / need }
        let num = Ink.font(numSize, .bold, rounded: true)
        let capH = CTFontGetCapHeight(num)
        let baseY = numBox.midY + capH / 2
        Ink.draw(ctx, s, x: box.minX - numSize * 0.04, y: baseY, num, pal.inkSoft)
        let dh = capH * 0.5
        SensorBadge.draw(ctx, x: box.minX + Ink.width(s, num) + numSize * 0.12, y: numBox.midY - dh / 2, h: dh, look, pal, label: false)
        var t = small
        let lw = Ink.width(line, t)
        if lw > box.width, lw > 0 { t = Ink.font(CTFontGetSize(small) * box.width / lw, .regular) }
        Ink.draw(ctx, line, x: box.minX, y: baseY + CTFontGetSize(small) * 1.25, t, pal.inkSoft)
        if !look.waiting(now: now) { rec.add("time.text", nil, line) }
        rec.add("value.warmup", CGRect(x: box.minX, y: numBox.midY - capH / 2, width: need, height: capH), s)
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
    /// `overflow`: room around the card in the picture (it is `overflow` larger on every side) for what overflows the
    /// card, such as a ring's laurel or crown; the card itself keeps `size`.
    static func image(size: CGSize, scale: CGFloat = 2, readings: [GraphReading], marks: [GraphMark], now: TimeInterval,
                      _ st: CardStyle, _ pal: GraphPalette, background: CGColor? = nil, surface: CardSurface,
                      metricsOf: ((RingSpec, Bands) -> GlucoseMetrics)? = nil, alerts: AlertSettings? = nil,
                      paused: [String: TimeInterval] = [:], allMuted: TimeInterval = 0, overflow: CGFloat = 0) -> CGImage? {
        let pw = max(16, Int((size.width + 2 * overflow) * scale)), ph = max(16, Int((size.height + 2 * overflow) * scale))
        guard let ctx = CGContext(data: nil, width: pw, height: ph, bitsPerComponent: 8, bytesPerRow: 0,
                                  space: CGColorSpace(name: CGColorSpace.sRGB)!,
                                  bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return nil }
        ctx.translateBy(x: 0, y: CGFloat(ph))
        ctx.scaleBy(x: scale, y: -scale)
        ctx.translateBy(x: overflow, y: overflow)
        if let background { ctx.setFillColor(background); ctx.fill(CGRect(origin: .zero, size: size)) }
        draw(ctx, size: size, readings: readings, marks: marks, now: now, st, pal, surface: surface, part: .all, metricsOf: metricsOf,
             alerts: alerts, paused: paused, allMuted: allMuted)
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
