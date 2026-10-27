// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation

/// Shared between the host app (writer), the widget extension (reader) and the PNG renderer.
/// Wire format = what the host writes to disk / serves on 127.0.0.1. Keep it dumb and versioned.
struct GlucoseSample: Codable, Hashable {
    var t: Date      // reading time
    var v: Double    // mg/dL
}

/// Insulin / carbs / note as the charts draw them (libreview SPEC §1, reduced: time, kind, amount, text).
struct WidgetTreatment: Codable, Hashable {
    var t: Date
    var k: String        // rapid | long | carbs | note
    var a: Double        // units or grams; 0 for notes
    var n: String? = nil
}

/// A slot's numbers (time in range, mean, SD), computed by the host from its 31-day store (the widget only sees
/// `samples`).
struct SlotMetrics: Codable {
    var window: TimeInterval
    var metrics: GlucoseMetrics
}

struct GlucoseSnapshot: Codable {
    var version = 2
    var written: Date            // when the host wrote this file
    var source: String           // "synthetic", "juggluco", "nostr", …
    var samples: [GlucoseSample] // ascending, ~1/min, up to 24 h (the longest graph window)
    var rate: Double?            // mg/dL/min reported by the source (nil → computed)
    var treatments: [WidgetTreatment]? = nil   // optional: older hosts don't write it
    var unit: String? = nil      // "mgdl" | "mmol"; nil → mg/dL (the host app's display unit)
    var graph: GraphConfig? = nil   // investigation 16: ranges, window (the widgets' own), rings, profile
    var slots: [SlotMetrics]? = nil // the numbers of each of graph.slots' windows (and graph.lastElse's)
    var alerts: AlertSettings? = nil // the host's alert rules: badge, mute lines, last-treatment slot
    var paused: [String: TimeInterval]? = nil // this Mac's Mute of a side: side → until (unix s), the badge
    var allMuted: TimeInterval? = nil         // this Mac's Mute all: until (unix s; AlertStatus.never = no end), the muted pill
    var sensor: SensorInfo? = nil    // the wearer's sensor (PROTOCOL-v2 "Sensor life"): badge, warm-up, countdown ring

    var config: GraphConfig { graph ?? GraphConfig() }
    /// The samples for the card; the newest carries the trend (the arrow's angle).
    var graphReadings: [GraphReading] {
        var r = samples.map { GraphReading(t: $0.t.timeIntervalSince1970, v: $0.v) }
        if !r.isEmpty { r[r.count - 1].rate = trendRate }
        return r
    }
    var graphMarks: [GraphMark] { (treatments ?? []).map { GraphMark(t: $0.t.timeIntervalSince1970, k: $0.k, a: $0.a, n: $0.n ?? "") } }
    /// The host's numbers for a slot's window when it has them, else from the samples.
    func metrics(_ spec: RingSpec, _ b: Bands, now: TimeInterval) -> GlucoseMetrics {
        slots?.first { $0.window == spec.window }?.metrics
            ?? GlucoseMetrics.of(graphReadings, from: now - spec.window, to: now, bands: b)
    }

    var latest: GlucoseSample? { samples.last }

    /// Rate of change, mg/dL/min. Prefer the source's value, else least squares over the last 5 min.
    var trendRate: Double? {
        if let rate { return rate }
        guard let last = samples.last else { return nil }
        let win = samples.filter { last.t.timeIntervalSince($0.t) <= 5 * 60 + 1 }
        guard win.count >= 3 else { return nil }
        let xs = win.map { $0.t.timeIntervalSince(last.t) / 60 }, ys = win.map(\.v)
        let mx = xs.reduce(0, +) / Double(xs.count), my = ys.reduce(0, +) / Double(ys.count)
        let sxx = xs.map { ($0 - mx) * ($0 - mx) }.reduce(0, +)
        guard sxx > 0 else { return nil }
        return zip(xs, ys).map { ($0 - mx) * ($1 - my) }.reduce(0, +) / sxx
    }

    /// Change vs. the reading ~5 min before the latest one.
    var delta5: Double? {
        guard let last = samples.last,
              let prev = samples.last(where: { last.t.timeIntervalSince($0.t) >= 4.5 * 60 }) else { return nil }
        return last.v - prev.v
    }

    func window(hours: Double, until: Date) -> [GlucoseSample] {
        let from = until.addingTimeInterval(-hours * 3600)
        return samples.filter { $0.t >= from }
    }
}

enum GlucoseRange {
    /// The user's major ranges (investigation 16). The widget overwrites them from the snapshot (`apply`).
    static var low = 70.0, high = 180.0
    static var mmol = false
    static func apply(_ s: GlucoseSnapshot) {
        let b = s.config.bands.sane()
        low = Double(b.low); high = Double(b.high); mmol = s.unit == "mmol"
    }
    /// The number as the host app shows it: "123" mg/dL or "6.8" mmol/L.
    static func format(_ mgdl: Double) -> String {
        mmol ? String(format: "%.1f", mgdl / 18.0182) : String(Int(mgdl.rounded()))
    }
    static func formatDelta(_ mgdl: Double) -> String {
        mmol ? String(format: "%+.1f", mgdl / 18.0182) : String(format: "%+.0f", mgdl)
    }

    enum Level { case low, inRange, high }
    static func level(_ v: Double) -> Level { v < low ? .low : (v > high ? .high : .inRange) }
}

/// Where the host publishes and where the widget looks. Order = preference.
enum SharedLocation {
    /// build.sh writes `GlucoseAppGroup` into both Info.plists: `<TEAMID>.dev.spike.glucosewidgets` when signed
    /// with a Team ID (macOS team-prefixed group, no provisioning profile needed), else the old ad hoc name.
    static let appGroup = Bundle.main.object(forInfoDictionaryKey: "GlucoseAppGroup") as? String
        ?? "group.dev.spike.glucosewidgets"
    static let widgetBundleID = "dev.spike.GlucoseWidgets.widget"
    static let fileName = "glucose.json"
    /// Info.plist `GlucoseHTTPPort` (0 = the host serves no HTTP, e.g. Sukrara: App Group only).
    static let httpPort = UInt16(Bundle.main.object(forInfoDictionaryKey: "GlucoseHTTPPort") as? Int ?? 17591)
    /// Info.plist `GlucoseWidgetURLScheme`: tap → `<scheme>://open?from=<kind>`. Empty → no widgetURL (a tap just
    /// opens the containing app).
    static let urlScheme = Bundle.main.object(forInfoDictionaryKey: "GlucoseWidgetURLScheme") as? String ?? "glucosewidgets"
    static var httpURL: URL { URL(string: "http://127.0.0.1:\(httpPort)/glucose.json")! }

    /// ~/Library/Group Containers/<group>/glucose.json (real home, not the sandbox one).
    static func groupFile(home: String) -> URL {
        URL(fileURLWithPath: home).appendingPathComponent("Library/Group Containers/\(appGroup)/\(fileName)")
    }
    /// The widget's own sandbox container, written from outside by the (unsandboxed) host.
    static func widgetContainerFile(home: String) -> URL {
        URL(fileURLWithPath: home).appendingPathComponent("Library/Containers/\(widgetBundleID)/Data/Library/Application Support/\(fileName)")
    }
    /// World-readable path, reachable from the sandbox via a temporary-exception entitlement.
    static let sharedFile = URL(fileURLWithPath: "/Users/Shared/GlucoseWidgets/\(fileName)")
}

extension JSONEncoder {
    static let glucose: JSONEncoder = { let e = JSONEncoder(); e.dateEncodingStrategy = .secondsSince1970; return e }()
}
extension JSONDecoder {
    static let glucose: JSONDecoder = { let d = JSONDecoder(); d.dateDecodingStrategy = .secondsSince1970; return d }()
}

/// Synthetic CGM: 1 sample/min, slow sine (period 3 h) + a faster wobble, crossing low and high.
enum Synthetic {
    static func value(at t: Date) -> Double {
        let m = t.timeIntervalSince1970 / 60
        return 125 + 72 * sin(2 * .pi * m / 180) + 8 * sin(2 * .pi * m / 29)
    }
    static func snapshot(now: Date = Date(), hours: Double = 24) -> GlucoseSnapshot {
        let lastMinute = floor(now.timeIntervalSince1970 / 60) * 60
        let n = Int(hours * 60)
        let samples = (0..<n).reversed().map { i -> GlucoseSample in
            let t = Date(timeIntervalSince1970: lastMinute - Double(i) * 60)
            return GlucoseSample(t: t, v: value(at: t).rounded())
        }
        return GlucoseSnapshot(written: now, source: "synthetic", samples: samples, rate: nil,
                               treatments: treatments(until: Date(timeIntervalSince1970: lastMinute)))
    }

    /// Previews: a believable day ([RealisticSynthetic]) with the slots' numbers from 15 days of it (14-day windows at most).
    static func realistic(now: Date = Date(), config: GraphConfig = GraphConfig()) -> GlucoseSnapshot {
        let end = floor(now.timeIntervalSince1970 / 60) * 60
        let all = RealisticSynthetic.readings(until: end, days: 15)
        let day = all.filter { $0.t > end - 24 * 3600 }
        let slots = config.slots.map { SlotMetrics(window: $0.window, metrics: GlucoseMetrics.of(all, from: end - $0.window, to: end, bands: config.bands)) }
        let marks = [(-8 * 3600.0 - 600, "rapid", 6.0), (-8 * 3600.0, "carbs", 60.0), (-90 * 60.0, "rapid", 4.5), (-85 * 60.0, "carbs", 45.0)]
        return GlucoseSnapshot(written: now, source: "synthetic", samples: day.map { GlucoseSample(t: Date(timeIntervalSince1970: $0.t), v: $0.v) },
                               rate: day.last?.rate,
                               treatments: marks.map { WidgetTreatment(t: Date(timeIntervalSince1970: end + $0.0), k: $0.1, a: $0.2) },
                               graph: config, slots: slots)
    }
    /// A meal, a correction, the daily long-acting dose and a note, every 6 h.
    static func treatments(until end: Date, hours: Double = 24) -> [WidgetTreatment] {
        stride(from: 0.0, to: hours, by: 6).flatMap { h -> [WidgetTreatment] in
            let base = end.addingTimeInterval(-h * 3600)
            return [WidgetTreatment(t: base.addingTimeInterval(-150 * 60), k: "rapid", a: 4.5),
                    WidgetTreatment(t: base.addingTimeInterval(-145 * 60), k: "carbs", a: 45),
                    WidgetTreatment(t: base.addingTimeInterval(-95 * 60), k: "note", a: 0, n: "walk"),
                    WidgetTreatment(t: base.addingTimeInterval(-50 * 60), k: h == 0 ? "long" : "rapid", a: h == 0 ? 12 : 1.5),
                    WidgetTreatment(t: base.addingTimeInterval(-25 * 60), k: "carbs", a: 15)]
        }
    }
}
