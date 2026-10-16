// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation
import WidgetKit

/// Desktop / Notification Center widgets (spikes/widgets-macos, docs/13). Sukrara is the widget's host app: every
/// new reading or treatment change goes to the App Group as the widget's `GlucoseSnapshot` JSON, then WidgetKit
/// reloads. The group comes from Info.plist `GlucoseAppGroup`, which bundle.sh writes only for a Team-ID build;
/// an ad hoc build has no group (its widget can't load anyway) and this is a no-op.
@MainActor final class WidgetFeed {
    // Wire format = spikes/widgets-macos/Shared/GlucoseData.swift (GlucoseSnapshot), dates as epoch seconds.
    private struct Sample: Codable { var t: Date; var v: Double }
    private struct Mark: Codable { var t: Date; var k: String; var a: Double; var n: String? }
    private struct Wire: Codable {
        var version = 1
        var written: Date
        var source: String
        var samples: [Sample]
        var rate: Double?
        var treatments: [Mark]?
        var unit: String?
        var low: Double?
        var high: Double?
    }

    /// The extra-large widget draws 12 h, more than the menu's 3 h history, so the feed keeps its own buffer
    /// (seeded from the last file on launch).
    static let span: TimeInterval = 12 * 3600
    private let file: URL?
    private var samples: [Sample] = []
    private var reloadedFor: Date?
    private var last: (source: String, rate: Double?, unit: GlucoseUnit, thresholds: Thresholds)?

    init() {
        let group = Bundle.main.object(forInfoDictionaryKey: "GlucoseAppGroup") as? String
        file = group.flatMap { FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: $0) }?
            .appendingPathComponent("glucose.json")
        if let f = file, let d = try? Data(contentsOf: f), let w = try? Self.decoder.decode(Wire.self, from: d) {
            samples = w.samples
        }
        log(file.map { "widget feed: \($0.path) (\(samples.count) samples kept)" } ?? "widget feed: off (no App Group: ad hoc build)")
    }

    var enabled: Bool { file != nil }

    /// Called with every published snapshot; writes and reloads only when the newest reading changed.
    func update(_ snap: Snapshot, unit: GlucoseUnit, thresholds: Thresholds, treatments: [Treatment]) {
        guard file != nil else { return }
        for r in snap.history { merge(Sample(t: r.date, v: r.mgdl)) }
        guard reloadedFor != snap.latest.date else { return }
        reloadedFor = snap.latest.date
        last = (snap.source, snap.rate, unit, thresholds)
        write(treatments)
    }

    /// A treatment was logged, edited or deleted: redraw the chart markers.
    func treatmentsChanged(_ treatments: [Treatment]) {
        guard file != nil, last != nil else { return }
        write(treatments)
    }

    private func merge(_ s: Sample) {
        if let i = samples.firstIndex(where: { abs($0.t.timeIntervalSince(s.t)) < 20 }) {
            samples[i] = s
        } else {
            samples.append(s)
            samples.sort { $0.t < $1.t }
        }
        if let newest = samples.last?.t { samples.removeAll { newest.timeIntervalSince($0.t) > Self.span } }
    }

    private func write(_ treatments: [Treatment]) {
        guard let file, let last else { return }
        let from = (samples.last?.t ?? Date()).addingTimeInterval(-Self.span).timeIntervalSince1970
        let marks = treatments.filter { !$0.del && Double($0.t) >= from }
            .map { Mark(t: Date(timeIntervalSince1970: TimeInterval($0.t)), k: $0.k, a: $0.a, n: $0.n.isEmpty ? nil : $0.n) }
        let wire = Wire(written: Date(), source: last.source, samples: samples, rate: last.rate, treatments: marks,
                        unit: last.unit.rawValue, low: last.thresholds.low, high: last.thresholds.high)
        do {
            try Self.encoder.encode(wire).write(to: file, options: .atomic)
            WidgetCenter.shared.reloadAllTimelines()
        } catch {
            log("widget feed: write failed: \(error.localizedDescription)")
        }
    }

    private static let encoder: JSONEncoder = { let e = JSONEncoder(); e.dateEncodingStrategy = .secondsSince1970; return e }()
    private static let decoder: JSONDecoder = { let d = JSONDecoder(); d.dateDecodingStrategy = .secondsSince1970; return d }()
}
