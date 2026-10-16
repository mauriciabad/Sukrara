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

/// The user's major ranges, mg/dL: low < `low` ≤ in range ≤ `high` < high; "very good" (tight) = `low`…`tight`.
struct Bands: Codable, Equatable, Hashable {
    var low = 70, tight = 140, high = 180

    func of(_ mgdl: Double) -> Band {
        if mgdl < Double(low) { return .low }
        if mgdl <= Double(tight) { return .tight }
        if mgdl <= Double(high) { return .target }
        return .high
    }

    /// Clamped so the order always holds.
    func sane() -> Bands {
        let l = min(max(low, 40), 120)
        let h = min(max(high, l + 20), 300)
        return Bands(low: l, tight: min(max(tight, l + 10), h), high: h)
    }
}

/// `.target` = in range but above the tight band; "in range" = tight + target.
enum Band: Int, CaseIterable { case low, tight, target, high }

enum TirRange: String, Codable, CaseIterable {
    case inRange = "IN_RANGE", tight = "TIGHT"
    var label: String { self == .tight ? "Very good" : "In range" }
}

/// Share of time per band in a window (each reading stands for the time until the next one, capped at 15 min).
struct TimeInRange: Codable, Equatable {
    var low = 0.0, tight = 0.0, target = 0.0, high = 0.0, coverage = 0.0
    var inRange: Double { tight + target }
    var empty: Bool { coverage <= 0 }
    func of(_ r: TirRange) -> Double { r == .tight ? tight : inRange }
    func share(_ b: Band) -> Double {
        switch b { case .low: low; case .tight: tight; case .target: target; case .high: high }
    }
    static let none = TimeInRange()
}

/// The distribution drawn on the left of the chart. Same six options as Android.
enum Profile: String, Codable, CaseIterable {
    case none = "NONE", histogram = "HISTOGRAM", kde = "KDE", kdeLog = "KDE_LOG", occupation = "OCCUPATION", bandExact = "BAND_EXACT"
    var label: String {
        switch self {
        case .none: "Bands only"
        case .histogram: "Histogram"
        case .kde: "Gaussian (fixed)"
        case .kdeLog: "Gaussian (relative)"
        case .occupation: "Time at level"
        case .bandExact: "Time at level, band-exact"
        }
    }
}

/// One reading for the graph: epoch seconds, mg/dL, rate mg/dL/min.
struct GraphReading: Equatable {
    var t: TimeInterval
    var v: Double
    var rate: Double? = nil
    var sgv: Int { Int(v.rounded()) }
}

/// A treatment as the chart draws it (rapid | long | carbs | note).
struct GraphMark {
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
        var sum = [0.0, 0.0, 0.0, 0.0]
        for (i, r) in rs.enumerated() { sum[b.of(Double(r.sgv)).rawValue] += w[i] }
        let total = sum.reduce(0, +)
        guard total > 0 else { return .none }
        return TimeInRange(low: sum[0] / total, tight: sum[1] / total, target: sum[2] / total, high: sum[3] / total,
                           coverage: min(1, total / (to - from)))
    }

    /// Relative time at each level, index = mg/dL − minMg, normalised to sum 1.
    static func profile(_ kind: Profile, _ all: [GraphReading], from: TimeInterval, to: TimeInterval, bands b: Bands,
                        tir: TimeInRange) -> [Double] {
        let n = maxMg - minMg + 1
        let rs = window(all, from: from, to: to)
        if rs.isEmpty || kind == .none { return Array(repeating: 0, count: n) }
        let w = weights(rs, to: to)
        var d = Array(repeating: 0.0, count: n)
        switch kind {
        case .histogram:
            var bins: [Int: Double] = [:]
            for (i, r) in rs.enumerated() { bins[Int(floor(Double(r.sgv) / 10)), default: 0] += w[i] }
            for (bin, m) in bins { for k in 0..<10 { if let i = idx(bin * 10 + k) { d[i] += m / 10 } } }
        case .kde:
            for (i, r) in rs.enumerated() { addGauss(&d, mu: Double(r.sgv), s: 10, w: w[i]) }
        case .kdeLog:
            let s = 0.07
            for (i, r) in rs.enumerated() {
                let mu = Foundation.log(Double(r.sgv))
                for k in 0..<n {
                    let g = Double(minMg + k)
                    let z = (Foundation.log(g) - mu) / s
                    if abs(z) < 4 { d[k] += w[i] * exp(-0.5 * z * z) / g }
                }
            }
        case .occupation, .bandExact:
            d = smooth(occupation(rs), s: 4)
        case .none: break
        }
        let s = d.reduce(0, +)
        if s > 0 { d = d.map { $0 / s } }
        if kind == .bandExact {
            for band in Band.allCases {
                let ks = (0..<n).filter { b.of(Double(minMg + $0)) == band }
                let area = ks.reduce(0) { $0 + d[$1] }
                if area > 0 { for k in ks { d[k] *= tir.share(band) / area } }
            }
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

    private static func addGauss(_ out: inout [Double], mu: Double, s: Double, w: Double) {
        let lo = max(minMg, Int(mu - 4 * s)), hi = min(maxMg, Int((mu + 4 * s).rounded()))
        guard lo <= hi else { return }
        for g in lo...hi { let z = (Double(g) - mu) / s; out[g - minMg] += w * exp(-0.5 * z * z) }
    }

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
    var bg, ink, inkSoft, grid, low, tight, target, high: CGColor

    func of(_ b: Band) -> CGColor {
        switch b { case .low: low; case .tight: tight; case .target: target; case .high: high }
    }

    static func hex(_ v: UInt32) -> CGColor {
        CGColor(srgbRed: CGFloat((v >> 16) & 0xFF) / 255, green: CGFloat((v >> 8) & 0xFF) / 255, blue: CGFloat(v & 0xFF) / 255, alpha: 1)
    }
    static let darkPalette = GraphPalette(dark: true, bg: hex(0x1C1C1E), ink: hex(0xF2F2F2), inkSoft: hex(0xA3A3A8), grid: hex(0x3A3A3E),
                                          low: hex(0xF0524F), tight: hex(0x3DD47A), target: hex(0x8CC63F), high: hex(0xF5A524))
    static let lightPalette = GraphPalette(dark: false, bg: hex(0xFFFFFF), ink: hex(0x1C1C1E), inkSoft: hex(0x6B6B70), grid: hex(0xDADADF),
                                           low: hex(0xD92D2A), tight: hex(0x17A34A), target: hex(0x6E9E1E), high: hex(0xD97706))
    static func of(dark: Bool) -> GraphPalette { dark ? darkPalette : lightPalette }
}

struct GraphStyle {
    var window: TimeInterval = 12 * 3600
    var bands = Bands()
    var profile: Profile = .bandExact
    var profileDots = true
    var side = true
    var axisLabels = true
    var treatments = true
    var hour24 = GraphStyle.systemHour24

    static var systemHour24: Bool {
        !(DateFormatter.dateFormat(fromTemplate: "j", options: 0, locale: .current) ?? "").contains("a")
    }
}

struct RingSpec: Equatable {
    var window: TimeInterval
    var range: TirRange = .inRange
    var period: String { periodLabel(window) }
}

func periodLabel(_ s: TimeInterval) -> String {
    let i = Int(s)
    return i % 86400 == 0 ? "\(i / 86400)d" : "\(i / 3600)h"
}

struct CardStyle {
    var graph: GraphStyle? = GraphStyle()
    var rings: [RingSpec] = [RingSpec(window: 86400), RingSpec(window: 7 * 86400)]
    var value = true
}

/// Everything the graph settings decide (UserDefaults on the Mac; the widget gets it in its snapshot).
struct GraphConfig: Codable, Equatable {
    var bands = Bands()
    var hours = 12
    var ringCount = 2
    var ringWindows: [TimeInterval] = [86400, 7 * 86400, 14 * 86400]
    var ringRange: TirRange = .inRange
    var profile: Profile = .bandExact
    var profileDots = true

    static let windowHours = [3, 6, 12, 24]
    static let ringChoices: [TimeInterval] = [12 * 3600, 86400, 3 * 86400, 7 * 86400, 14 * 86400]

    var rings: [RingSpec] { Array(ringWindows.prefix(max(0, min(3, ringCount)))).map { RingSpec(window: $0, range: ringRange) } }
    func graphStyle(hours h: Int? = nil) -> GraphStyle {
        GraphStyle(window: TimeInterval((h ?? hours) * 3600), bands: bands, profile: profile, profileDots: profileDots)
    }

    /// Keys: graphHours (or a place's own `<place>Hours`), ringCount, ringWindows "86400,604800,1209600", ringRange
    /// IN_RANGE|TIGHT, bandLow/bandTight/bandHigh, profile (Profile raw value), profileDots.
    static func load(_ d: UserDefaults = .standard, place: String? = nil) -> GraphConfig {
        var c = GraphConfig()
        func int(_ k: String) -> Int? { d.object(forKey: k) != nil ? d.integer(forKey: k) : nil }
        c.bands = Bands(low: int("bandLow") ?? 70, tight: int("bandTight") ?? 140, high: int("bandHigh") ?? 180).sane()
        let h = place.flatMap { int("\($0)Hours") } ?? int("graphHours") ?? 12
        c.hours = windowHours.contains(h) ? h : 12
        c.ringCount = max(0, min(3, int("ringCount") ?? 2))
        let w = (d.string(forKey: "ringWindows") ?? "").split(separator: ",").compactMap { TimeInterval($0) }
        if w.count == 3, w.allSatisfy(ringChoices.contains) { c.ringWindows = w }
        c.ringRange = TirRange(rawValue: d.string(forKey: "ringRange") ?? "") ?? .inRange
        c.profile = Profile(rawValue: d.string(forKey: "profile") ?? "") ?? .bandExact
        c.profileDots = d.object(forKey: "profileDots") == nil ? true : d.bool(forKey: "profileDots")
        return c
    }
}

// MARK: - Drawing helpers (y-down CGContext)

private enum Ink {
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

/// The chart with the time-in-range side profile (GlucoseGraph.kt). `d` = points per dp (1 on the Mac).
enum GlucoseGraph {
    static func draw(_ ctx: CGContext, _ area: CGRect, readings: [GraphReading], marks: [GraphMark], now: TimeInterval,
                     _ st: GraphStyle, _ pal: GraphPalette, d: CGFloat = 1) {
        let from = now - st.window
        let b = st.bands.sane()
        let pts = GlucoseStats.window(readings, from: from - 60, to: now)
        let tir = GlucoseStats.timeInRange(readings, from: from, to: now, bands: b)
        let text = Ink.font(10 * d, .regular)
        let pctBig = Ink.font(13 * d, .bold), pctSmall = Ink.font(8.5 * d, .regular)

        let labels = st.axisLabels
        let pctCol = st.side && labels && !tir.empty ? Ink.width("100", pctBig) + Ink.width("%", pctSmall) + 6 * d : 0
        let left = area.minX + pctCol
        let right = area.maxX - (labels ? Ink.width("180", text) + 5 * d : 0)
        let top = area.minY + 3 * d
        let bottom = area.maxY - (labels ? 10 * d + 4 * d : 0)
        let shownMarks = st.treatments ? marks.filter { $0.t >= from && $0.t <= now } : []
        let plotBottom = shownMarks.isEmpty ? bottom : bottom - 12 * d
        guard right - left >= 20 * d, plotBottom - top >= 20 * d else { return }

        let dMin = pts.map(\.sgv).min() ?? b.low, dMax = pts.map(\.sgv).max() ?? b.high
        let yMin = max(Double(GlucoseStats.minMg), Double(min(b.low - 22, dMin - 6)))
        let yMax = min(Double(GlucoseStats.maxMg), Double(max(b.high + 40, dMax + 8)))
        func x(_ t: TimeInterval) -> CGFloat { left + CGFloat((t - from) / st.window) * (right - left) }
        func y(_ v: Double) -> CGFloat { plotBottom - CGFloat((min(max(v, yMin), yMax) - yMin) / (yMax - yMin)) * (plotBottom - top) }
        let sideW: CGFloat = st.side && !tir.empty ? min((right - left) * 0.26, 130 * d) : 0

        // Main in-range band across the whole plot.
        ctx.setFillColor(pal.tight.a(pal.dark ? 0x1E : 0x18))
        ctx.fill(CGRect(x: left, y: y(Double(b.high)), width: right - left, height: y(Double(b.low)) - y(Double(b.high))))

        // Hour grid + labels, ≥ ~44 dp apart.
        let pxPerHour = (right - left) / CGFloat(st.window / 3600)
        let step = [1, 2, 3, 4, 6, 12, 24].first { CGFloat($0) * pxPerHour >= 44 * d } ?? 24
        let cal = Calendar.current
        var hour = cal.dateInterval(of: .hour, for: Date(timeIntervalSince1970: from))!.start.addingTimeInterval(3600)
        while cal.component(.hour, from: hour) % step != 0 { hour.addTimeInterval(3600) }
        ctx.saveGState()
        ctx.setStrokeColor(pal.grid)
        ctx.setLineWidth(1 * d)
        ctx.setLineDash(phase: 0, lengths: [2 * d, 3 * d])
        var lastLabelEnd = -CGFloat.infinity
        while hour.timeIntervalSince1970 <= now {
            let gx = x(hour.timeIntervalSince1970)
            if gx > left + sideW * 0.6 {
                ctx.strokeLineSegments(between: [CGPoint(x: gx, y: top), CGPoint(x: gx, y: plotBottom)])
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
        if labels {
            for v in [b.low, b.high] { Ink.draw(ctx, "\(v)", x: right + 4 * d, y: y(Double(v)) + 10 * d / 3, text, pal.inkSoft) }
        }

        // Time-in-range rectangles from the left edge.
        if sideW > 0 {
            let a = pal.dark ? 0x38 : 0x2C
            func rect(_ share: Double, _ c: CGColor, _ y0: CGFloat, _ y1: CGFloat, _ alpha: Int) {
                guard share > 0 else { return }
                ctx.setFillColor(c.a(alpha))
                ctx.fill(CGRect(x: left, y: y0, width: sideW * CGFloat(share), height: y1 - y0))
            }
            rect(tir.high, pal.high, top, y(Double(b.high)), a)
            rect(tir.inRange, pal.target, y(Double(b.high)), y(Double(b.low)), a)
            rect(tir.tight, pal.tight, y(Double(b.tight)), y(Double(b.low)), pal.dark ? 0x40 : 0x34)
            rect(tir.low, pal.low, y(Double(b.low)), plotBottom, a)
        }

        // The line: a dot per reading, fading out towards the side overlay.
        let spacing = pts.count > 1 ? (right - left) / CGFloat(max(1, pts.count)) : 99
        let r = min(max(spacing * 0.55, 1.3 * d), 2.2 * d)
        let fadeEnd = left + sideW * 1.25
        func fade(_ px: CGFloat) -> CGFloat {
            guard sideW > 0 else { return 1 }
            let u = min(max((px - left) / (fadeEnd - left), 0), 1)
            return 0.10 + 0.90 * u * u * (3 - 2 * u)
        }
        ctx.setLineCap(.round)
        ctx.setLineWidth(1.6 * d)
        for (i, p) in pts.enumerated() where p.t >= from {
            let px = x(p.t), py = y(Double(p.sgv))
            let c = pal.of(b.of(Double(p.sgv)))
            if spacing > 5 * d, i > 0, p.t - pts[i - 1].t <= GlucoseStats.maxGap {
                ctx.setStrokeColor(c.a(fade(px) * 0.75))
                ctx.strokeLineSegments(between: [CGPoint(x: x(pts[i - 1].t), y: y(Double(pts[i - 1].sgv))), CGPoint(x: px, y: py)])
            }
            if i == pts.count - 1 {
                ctx.setFillColor(pal.bg); ctx.fillEllipse(in: CGRect(x: px - r - 2.6 * d, y: py - r - 2.6 * d, width: 2 * (r + 2.6 * d), height: 2 * (r + 2.6 * d)))
                ctx.setFillColor(c); ctx.fillEllipse(in: CGRect(x: px - r - 1.4 * d, y: py - r - 1.4 * d, width: 2 * (r + 1.4 * d), height: 2 * (r + 1.4 * d)))
            } else {
                ctx.setFillColor(c.a(fade(px)))
                ctx.fillEllipse(in: CGRect(x: px - r, y: py - r, width: 2 * r, height: 2 * r))
            }
        }

        if sideW > 0 && st.profile != .none {
            let prof = GlucoseStats.profile(st.profile, readings, from: from, to: now, bands: b, tir: tir)
            drawProfile(ctx, prof, b, pal, left: left, sideW: sideW, top: top, bottom: plotBottom, yMin: yMin, yMax: yMax, dots: st.profileDots, d: d)
        }

        // % per major band, centred on its band; a band too short for its label (often low, sometimes high) pushes
        // it outwards so the three never overlap, and it may hang a little past the plot.
        if pctCol > 0 {
            let p = pct3(tir.low, tir.inRange, tir.high)
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

        if !shownMarks.isEmpty { TreatmentMarks.draw(ctx, shownMarks, x: x, left: left, right: right, bottom: bottom, dark: pal.dark, d: d) }
    }

    private static func drawProfile(_ ctx: CGContext, _ prof: [Double], _ b: Bands, _ pal: GraphPalette, left: CGFloat, sideW: CGFloat,
                                    top: CGFloat, bottom: CGFloat, yMin: Double, yMax: Double, dots: Bool, d: CGFloat) {
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
        if dots {
            let pitch = 3.6 * d, r = 1.35 * d
            let rows = Int((bottom - top) / pitch)
            guard rows > 0 else { return }
            let mass = (0..<rows).map { i -> Double in
                let yb = Double(bottom - CGFloat(i) * pitch)
                return massBetween(yMin + (Double(bottom) - yb) / pxPerMg, yMin + (Double(bottom) - yb + Double(pitch)) / pxPerMg)
            }
            guard let peak = mass.max(), peak > 0 else { return }
            let maxDots = Int(floor(sideW * 0.9 / pitch))
            for i in 0..<rows {
                var n = Int((mass[i] / peak * Double(maxDots)).rounded())
                if n == 0 && mass[i] > peak * 0.004 { n = 1 }
                guard n > 0 else { continue }
                let cy = bottom - CGFloat(i) * pitch - pitch / 2
                let g = yMin + Double(bottom - cy) / pxPerMg
                ctx.setFillColor(pal.of(b.of(g)))
                for k in 0..<n {
                    let cx = left + pitch / 2 + CGFloat(k) * pitch
                    ctx.fillEllipse(in: CGRect(x: cx - r, y: cy - r, width: 2 * r, height: 2 * r))
                }
            }
        } else {
            let slices = Int((bottom - top) / d)
            guard slices > 0 else { return }
            let mass = (0..<slices).map { i in massBetween(yMin + Double(CGFloat(i) * d) / pxPerMg, yMin + Double(CGFloat(i + 1) * d) / pxPerMg) }
            guard let peak = mass.max(), peak > 0 else { return }
            for band in Band.allCases {
                let path = CGMutablePath()
                var open = false
                for i in 0..<slices {
                    let g = yMin + Double((CGFloat(i) + 0.5) * d) / pxPerMg
                    let yy = bottom - CGFloat(i) * d
                    if b.of(g) != band { if open { path.addLine(to: CGPoint(x: left, y: yy)); open = false }; continue }
                    let w = CGFloat(mass[i] / peak) * sideW * 0.9
                    if !open { path.move(to: CGPoint(x: left, y: yy)); open = true }
                    path.addLine(to: CGPoint(x: left + w, y: yy))
                    path.addLine(to: CGPoint(x: left + w, y: yy - d))
                }
                if open { path.addLine(to: CGPoint(x: left, y: bottom - CGFloat(slices) * d)) }
                path.closeSubpath()
                ctx.setFillColor(pal.of(band).a(0xE0))
                ctx.addPath(path)
                ctx.fillPath()
            }
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
/// carbs = amber dot, note = grey diamond; the amount above, one row, overlapping labels dropped (marker stays).
enum TreatmentMarks {
    static func color(_ k: String, dark: Bool) -> CGColor {
        switch k {
        case "rapid": GraphPalette.hex(dark ? 0x8AB4FF : 0x1A62D6)
        case "long": GraphPalette.hex(dark ? 0xC9B0FF : 0x6D45C4)
        case "carbs": GraphPalette.hex(dark ? 0xFFB74D : 0xC86E00)
        default: GraphPalette.hex(dark ? 0xB4B4B4 : 0x707070)
        }
    }

    static func label(_ m: GraphMark) -> String? {
        switch m.k {
        case "rapid", "long": (m.a == m.a.rounded() ? String(Int(m.a)) : String(format: "%.1f", m.a)) + "U"
        case "carbs": "\(Int(m.a.rounded()))g"
        default: nil
        }
    }

    static func draw(_ ctx: CGContext, _ marks: [GraphMark], x: (TimeInterval) -> CGFloat, left: CGFloat, right: CGFloat,
                     bottom: CGFloat, dark: Bool, d: CGFloat) {
        let order = ["rapid": 0, "long": 1, "carbs": 2, "note": 3]
        let items = marks.filter { order[$0.k] != nil }.map { ($0, x($0.t)) }.filter { $0.1 >= left - 1 && $0.1 <= right + 1 }
        let cy = bottom - 5 * d
        for (m, px) in items.sorted(by: { order[$0.0.k]! > order[$1.0.k]! }) {
            let c = color(m.k, dark: dark)
            switch m.k {
            case "rapid", "long":
                let p = CGMutablePath()
                p.move(to: CGPoint(x: px - 4.5 * d, y: cy - 3.5 * d)); p.addLine(to: CGPoint(x: px + 4.5 * d, y: cy - 3.5 * d))
                p.addLine(to: CGPoint(x: px, y: cy + 4 * d)); p.closeSubpath()
                ctx.addPath(p)
                if m.k == "rapid" { ctx.setFillColor(c); ctx.fillPath() } else { ctx.setStrokeColor(c); ctx.setLineWidth(1.5 * d); ctx.strokePath() }
            case "carbs":
                ctx.setFillColor(c); ctx.fillEllipse(in: CGRect(x: px - 3.6 * d, y: cy - 3.6 * d, width: 7.2 * d, height: 7.2 * d))
            default:
                let p = CGMutablePath()
                p.move(to: CGPoint(x: px, y: cy - 3 * d)); p.addLine(to: CGPoint(x: px + 3 * d, y: cy))
                p.addLine(to: CGPoint(x: px, y: cy + 3 * d)); p.addLine(to: CGPoint(x: px - 3 * d, y: cy)); p.closeSubpath()
                ctx.addPath(p); ctx.setFillColor(c); ctx.fillPath()
            }
        }
        let font = Ink.font(9 * d, .bold)
        var placed: [CGRect] = []
        for (m, px) in items.sorted(by: { (order[$0.0.k]!, -$0.0.t) < (order[$1.0.k]!, -$1.0.t) }) {
            guard let s = label(m) else { continue }
            let w = Ink.width(s, font)
            let lx = min(max(px - w / 2, left), max(left, right - w))
            let baseline = cy - 5.5 * d - 2 * d
            let r = CGRect(x: lx - d, y: baseline - 9 * d, width: w + 2 * d, height: 11 * d)
            if placed.contains(where: { $0.intersects(r) }) { continue }
            placed.append(r)
            Ink.draw(ctx, s, x: lx, y: baseline, font, color(m.k, dark: dark))
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
        let shares = [(Band.low, tir.low), (.tight, tir.tight), (.target, tir.target), (.high, tir.high)].filter { $0.1 >= 0.004 }
        func gapAfter(_ i: Int) -> Bool {
            guard shares.count > 1 else { return false }
            let a = shares[i].0, b = shares[(i + 1) % shares.count].0
            return !((a == .tight && b == .target) || (a == .target && b == .tight))
        }
        if shares.isEmpty {
            ctx.setStrokeColor(pal.grid.a(emphasis))
            ctx.strokeEllipse(in: CGRect(x: c.x - rr, y: c.y - rr, width: 2 * rr, height: 2 * rr))
        } else {
            let gap: CGFloat = shares.count > 1 ? 7 : 0
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

// MARK: - Card

/// Value + trend arrow + rings + chart in one drawing (GlucoseCard.kt), adapting to the size.
enum GlucoseCard {
    static let ringEmphasis: [CGFloat] = [1, 0.55, 0.35]
    static let minCoverage = 0.25

    /// `tirOf` gives a ring's numbers; by default computed from `readings` (enough for windows they cover).
    static func draw(_ ctx: CGContext, size: CGSize, readings: [GraphReading], marks: [GraphMark], now: TimeInterval,
                     _ st: CardStyle, _ pal: GraphPalette, d: CGFloat = 1,
                     tirOf: ((RingSpec, Bands) -> TimeInRange)? = nil) {
        let w = size.width, h = size.height
        let latest = readings.max { $0.t < $1.t }
        let fr = Freshness.of(age: latest.map { now - $0.t })
        let graph = fr.showGraph ? st.graph : nil
        let bands = (st.graph?.bands ?? Bands()).sane()
        let sideBySide = graph != nil && st.value && h < 100 * d && w > 2.6 * h
        let headerH: CGFloat = !st.value ? 0 : (graph == nil || sideBySide) ? h : min(max(h * 0.34, 30 * d), 80 * d)
        let valueRight = sideBySide ? min(w * 0.32, 120 * d) : w
        let tir = tirOf ?? { r, b in GlucoseStats.timeInRange(readings, from: now - r.window, to: now, bands: b) }

        if st.value {
            let box = CGRect(x: 0, y: 0, width: valueRight, height: sideBySide ? h : headerH)
            let ringsW = sideBySide ? 0 : drawRings(ctx, box, st.rings, bands, pal, d: d, tir)
            drawValue(ctx, CGRect(x: box.minX, y: box.minY, width: box.width - ringsW, height: box.height), latest, fr, now, bands, pal, d: d)
        }
        let chart = sideBySide ? CGRect(x: valueRight + 6 * d, y: 0, width: w - valueRight - 6 * d, height: h)
            : CGRect(x: 0, y: headerH + (st.value ? 6 * d : 0), width: w, height: h - headerH - (st.value ? 6 * d : 0))
        if let graph {
            GlucoseGraph.draw(ctx, chart, readings: readings, marks: marks, now: now, graph, pal, d: d)
        } else if st.graph != nil && chart.height > 24 * d {
            let msg = latest.map { "No readings since \(Freshness.timeText($0.t, now: now))" } ?? "No readings yet"
            Ink.draw(ctx, msg, x: chart.midX, y: chart.midY, Ink.font(12 * d, .regular), pal.inkSoft, align: 0.5)
        }
    }

    private static func drawRings(_ ctx: CGContext, _ box: CGRect, _ rings: [RingSpec], _ bands: Bands, _ pal: GraphPalette,
                                  d: CGFloat, _ tirOf: (RingSpec, Bands) -> TimeInRange) -> CGFloat {
        guard !rings.isEmpty else { return 0 }
        let size = min(box.height * 0.96, 84 * d)
        guard size >= 30 * d else { return 0 }
        let gap = size * 0.14
        let fit = min(Int((box.width * 0.55 + gap) / (size + gap)), min(3, rings.count))
        var x = box.maxX, used: CGFloat = 0
        for (i, spec) in rings.prefix(max(0, fit)).enumerated() {
            let t = tirOf(spec, bands)
            if t.coverage < minCoverage { continue }
            TirRing.draw(ctx, center: CGPoint(x: x - size / 2, y: box.midY), radius: size / 2, tir: t, spec: spec, pal, emphasis: ringEmphasis[i])
            x -= size + gap
            used += size + gap
        }
        return used
    }

    private static func drawValue(_ ctx: CGContext, _ box: CGRect, _ r: GraphReading?, _ fr: Freshness, _ now: TimeInterval,
                                  _ bands: Bands, _ pal: GraphPalette, d: CGFloat) {
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
        let color = fr.strike ? pal.inkSoft : pal.of(bands.of(Double(r.sgv)))
        let s = "\(r.sgv)"
        var numSize = box.height * 1.18
        let arrowFactor: CGFloat = 0.62
        let time = fr.showTime ? Freshness.timeText(r.t, now: now) : ""
        let timeW = fr.showTime ? Ink.width(time, small) + 8 * d : 0
        let need = Ink.width(s, Ink.font(numSize, .bold, rounded: true)) + numSize * arrowFactor * 1.1 + timeW
        if need > box.width { numSize *= box.width / need }
        let num = Ink.font(numSize, .bold, rounded: true)
        let capH = CTFontGetCapHeight(num)
        Ink.draw(ctx, s, x: box.minX - numSize * 0.04, y: box.midY + capH / 2, num, color, strike: fr.strike)
        var x = box.minX + Ink.width(s, num) + numSize * 0.08
        let arrowSize = numSize * arrowFactor
        if !fr.strike, let rate = r.rate {
            ctx.saveGState()
            ctx.translateBy(x: x + arrowSize / 2, y: box.midY)
            ctx.rotate(by: CGFloat(-max(-1, min(1, rate / 3))) * .pi / 2)
            drawArrow(ctx, scale: arrowSize * 1.05, color: color, double: abs(rate) >= 3)
            ctx.restoreGState()
        }
        x += arrowSize + 6 * d
        if fr.showTime { Ink.draw(ctx, time, x: x, y: box.midY + CTFontGetSize(small) / 3, small, pal.inkSoft) }
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
                      tirOf: ((RingSpec, Bands) -> TimeInRange)? = nil) -> CGImage? {
        let pw = max(16, Int(size.width * scale)), ph = max(16, Int(size.height * scale))
        guard let ctx = CGContext(data: nil, width: pw, height: ph, bitsPerComponent: 8, bytesPerRow: 0,
                                  space: CGColorSpace(name: CGColorSpace.sRGB)!,
                                  bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return nil }
        ctx.translateBy(x: 0, y: CGFloat(ph))
        ctx.scaleBy(x: scale, y: -scale)
        if let background { ctx.setFillColor(background); ctx.fill(CGRect(origin: .zero, size: size)) }
        draw(ctx, size: size, readings: readings, marks: marks, now: now, st, pal, tirOf: tirOf)
        return ctx.makeImage()
    }
}

/// A believable history (meals with bolus, dawn rise, an afternoon dip, noise), seeded: the same picture every run.
/// Port of the Android gallery's Synthetic (GraphGallery.kt), for widget previews and evidence renders.
enum RealisticSynthetic {
    static func readings(until now: TimeInterval, days: Int) -> [GraphReading] {
        var rng = SplitMix(seed: 16)
        let start = now - Double(days) * 86400
        let tz = TimeZone.current
        struct Meal { var t: TimeInterval; var size: Double; var peak: Double }
        var meals: [Meal] = [], dips: [(TimeInterval, Double)] = []
        var day = start - (start + Double(tz.secondsFromGMT(for: Date(timeIntervalSince1970: start)))).truncatingRemainder(dividingBy: 86400)
        while day < now + 86400 {
            let off = Double(tz.secondsFromGMT(for: Date(timeIntervalSince1970: day)))
            func at(_ h: Double) -> TimeInterval { day + h * 3600 - off }
            meals.append(Meal(t: at(8.0 + rng.gauss() * 0.4), size: 45 + rng.next() * 50, peak: 45 + rng.next() * 20))
            meals.append(Meal(t: at(13.8 + rng.gauss() * 0.5), size: 55 + rng.next() * 75, peak: 55 + rng.next() * 25))
            meals.append(Meal(t: at(20.6 + rng.gauss() * 0.4), size: 50 + rng.next() * 70, peak: 60 + rng.next() * 25))
            if rng.next() < 0.6 { dips.append((at(17.0 + rng.gauss() * 0.6), 25 + rng.next() * 30)) }
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

    struct SplitMix {
        var state: UInt64
        init(seed: UInt64) { state = seed }
        mutating func nextU() -> UInt64 {
            state &+= 0x9E3779B97F4A7C15
            var z = state
            z = (z ^ (z >> 30)) &* 0xBF58476D1CE4E5B9
            z = (z ^ (z >> 27)) &* 0x94D049BB133111EB
            return z ^ (z >> 31)
        }
        mutating func next() -> Double { Double(nextU() >> 11) / Double(1 << 53) }
        mutating func gauss() -> Double { sqrt(-2 * Foundation.log(max(1e-12, next()))) * cos(2 * .pi * next()) }
    }
}
