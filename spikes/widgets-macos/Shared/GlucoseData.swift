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

struct GlucoseSnapshot: Codable {
    var version = 1
    var written: Date            // when the host wrote this file
    var source: String           // "synthetic", "juggluco", "nostr", …
    var samples: [GlucoseSample] // ascending, ~1/min, up to 24 h
    var rate: Double?            // mg/dL/min reported by the source (nil → computed)
    var treatments: [WidgetTreatment]? = nil   // optional: older hosts don't write it
    var unit: String? = nil      // "mgdl" | "mmol"; nil → mg/dL (the host app's display unit)
    var low: Double? = nil       // colour thresholds, mg/dL; nil → GlucoseRange defaults
    var high: Double? = nil

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
    /// Colour thresholds (user's settings). The widget overwrites them from the snapshot (`apply`).
    static var low = 65.0, high = 185.0
    static var mmol = false
    static func apply(_ s: GlucoseSnapshot) {
        low = s.low ?? 65; high = s.high ?? 185; mmol = s.unit == "mmol"
    }
    /// The number as the host app shows it: "123" mg/dL or "6.8" mmol/L.
    static func format(_ mgdl: Double) -> String {
        mmol ? String(format: "%.1f", mgdl / 18.0182) : String(Int(mgdl.rounded()))
    }
    static func formatDelta(_ mgdl: Double) -> String {
        mmol ? String(format: "%+.1f", mgdl / 18.0182) : String(format: "%+.0f", mgdl)
    }
    static let targetLow = 70.0, targetHigh = 180.0 // target band on the graph
    static let staleAfter: TimeInterval = 5 * 60

    enum Level { case low, inRange, high }
    static func level(_ v: Double) -> Level { v < low ? .low : (v > high ? .high : .inRange) }
}

enum TrendAngle {
    /// Same mapping as spikes/menubar TrendArrow: 0 → flat, ±3 mg/dL/min → vertical, linear between.
    static let fullScaleRate = 3.0
    /// Degrees, counter-clockwise positive (up = rising).
    static func degrees(forRate rate: Double) -> Double { max(-1, min(1, rate / fullScaleRate)) * 90 }
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
