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
    // Wire format = spikes/widgets-macos/Shared/GlucoseData.swift (GlucoseSnapshot v2), dates as epoch seconds.
    private struct Sample: Codable { var t: Date; var v: Double }
    private struct Mark: Codable { var t: Date; var k: String; var a: Double; var n: String? }
    private struct Wire: Codable {
        var version = 2
        var written: Date
        var source: String
        var samples: [Sample]
        var rate: Double?
        var treatments: [Mark]?
        var unit: String?
        var graph: GraphConfig?
        var slots: [SlotMetrics]?
        var alerts: AlertSettings?
        var paused: [String: TimeInterval]?
        var allMuted: TimeInterval?
        var sensor: SensorInfo?
    }
    private struct SlotMetrics: Codable { var window: TimeInterval; var metrics: GlucoseMetrics }

    /// The longest graph window a widget can choose; older samples stay in the readings store only.
    static let span: TimeInterval = 24 * 3600
    private let file: URL?
    private var reloadedFor: Date?
    private var last: (source: String, rate: Double?, unit: GlucoseUnit)?
    private let readings: ReadingsStore
    /// The alert rules the widgets' badge, mute lines and last-treatment slot follow.
    var alerts: () -> AlertSettings = { AlertSettings.load() }
    /// This Mac's Mute of a side (side → until): the widgets' badge.
    var paused: () -> [String: TimeInterval] = { [:] }
    /// This Mac's Mute all (until, 0 = off, AlertStatus.never = no end): the widgets' muted pill.
    var allMuted: () -> TimeInterval = { 0 }

    init(readings: ReadingsStore) {
        self.readings = readings
        let group = Bundle.main.object(forInfoDictionaryKey: "GlucoseAppGroup") as? String
        file = group.flatMap { FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: $0) }?
            .appendingPathComponent("glucose.json")
        log(file.map { "widget feed: \($0.path)" } ?? "widget feed: off (no App Group: ad hoc build)")
    }

    var enabled: Bool { file != nil }

    /// Called with every published snapshot; writes and reloads only when the newest reading changed.
    func update(_ snap: Snapshot, unit: GlucoseUnit, treatments: [Treatment]) {
        guard file != nil else { return }
        guard reloadedFor != snap.latest.date else { return }
        reloadedFor = snap.latest.date
        last = (snap.source, snap.rate, unit)
        write(treatments)
    }

    /// A treatment was logged, edited or deleted, or the graph settings changed: redraw.
    func treatmentsChanged(_ treatments: [Treatment]) {
        guard file != nil, last != nil else { return }
        write(treatments)
    }

    private func write(_ treatments: [Treatment]) {
        guard let file, let last else { return }
        let newest = readings.readings.last?.t ?? Date().timeIntervalSince1970
        let from = newest - Self.span
        let samples = readings.since(from).map { Sample(t: Date(timeIntervalSince1970: $0.t), v: $0.v) }
        let marks = treatments.filter { !$0.del && Double($0.t) >= from }
            .map { Mark(t: Date(timeIntervalSince1970: TimeInterval($0.t)), k: $0.k, a: $0.a, n: $0.n.isEmpty ? nil : $0.n) }
        // The widgets' own window (`widgetHours` over `graphHours`) and each slot's numbers, computed here from 31 days.
        let cfg = GraphConfig.load(place: "widget")
        let slots = (cfg.slots + [cfg.lastElse].compactMap { $0 }).map { SlotMetrics(window: $0.window, metrics: readings.metrics(window: $0.window, bands: cfg.bands, now: newest)) }
        let wire = Wire(written: Date(), source: last.source, samples: samples, rate: last.rate, treatments: marks,
                        unit: last.unit.rawValue, graph: cfg, slots: slots, alerts: alerts(), paused: paused(), allMuted: allMuted() > 0 ? allMuted() : nil, sensor: SensorStore.current)
        do {
            try Self.encoder.encode(wire).write(to: file, options: .atomic)
            WidgetCenter.shared.reloadAllTimelines()
        } catch {
            log("widget feed: write failed: \(error.localizedDescription)")
        }
    }

    private static let encoder: JSONEncoder = { let e = JSONEncoder(); e.dateEncodingStrategy = .secondsSince1970; return e }()
}
