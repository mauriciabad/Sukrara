// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import AppIntents
import Foundation
import os
import SwiftUI
import WidgetKit

let log = Logger(subsystem: "dev.spike.GlucoseWidgets", category: "widget")

struct GlucoseEntry: TimelineEntry {
    var date: Date
    var content: GlucoseEntryContent
    var via: String
    var overrides = WidgetOverrides()
}

// MARK: - Per-widget settings (investigation 16): "Edit Widget" on each placement

enum WindowChoice: String, AppEnum {
    case app, h3, h6, h12, h24
    static let typeDisplayRepresentation: TypeDisplayRepresentation = "Graph window"
    static let caseDisplayRepresentations: [WindowChoice: DisplayRepresentation] = [
        .app: "Sukrara's setting", .h3: "3 hours", .h6: "6 hours", .h12: "12 hours", .h24: "24 hours"]
    var hours: Int? { switch self { case .app: nil; case .h3: 3; case .h6: 6; case .h12: 12; case .h24: 24 } }
}

enum RingsChoice: String, AppEnum {
    case app, none, one, two, three
    static let typeDisplayRepresentation: TypeDisplayRepresentation = "Time in range rings"
    static let caseDisplayRepresentations: [RingsChoice: DisplayRepresentation] = [
        .app: "Sukrara's setting", .none: "None", .one: "1", .two: "2", .three: "3"]
    var count: Int? { switch self { case .app: nil; case .none: 0; case .one: 1; case .two: 2; case .three: 3 } }
}

enum RangeChoice: String, AppEnum {
    case app, inRange, tight
    static let typeDisplayRepresentation: TypeDisplayRepresentation = "Ring number"
    static let caseDisplayRepresentations: [RangeChoice: DisplayRepresentation] = [
        .app: "Sukrara's setting", .inRange: "In range", .tight: "Very good"]
    var range: TirRange? { switch self { case .app: nil; case .inRange: .inRange; case .tight: .tight } }
}

struct GlucoseWidgetIntent: WidgetConfigurationIntent {
    static let title: LocalizedStringResource = "Glucose widget"
    static let description = IntentDescription("This widget's graph window and time-in-range rings.")

    @Parameter(title: "Graph window", default: .app) var window: WindowChoice
    @Parameter(title: "Time in range rings", default: .app) var rings: RingsChoice
    @Parameter(title: "Ring number", default: .app) var ringRange: RangeChoice
    @Parameter(title: "Time in range beside the graph", default: true) var side: Bool

    var overrides: WidgetOverrides {
        WidgetOverrides(hours: window.hours, rings: rings.count, ringRange: ringRange.range, side: side)
    }
}

struct Provider: AppIntentTimelineProvider {
    let kind: WidgetKind

    func placeholder(in context: Context) -> GlucoseEntry {
        GlucoseEntry(date: Date(), content: GlucoseEntryContent(snapshot: Synthetic.realistic(), date: Date()), via: "placeholder")
    }

    func snapshot(for configuration: GlucoseWidgetIntent, in context: Context) async -> GlucoseEntry {
        let (s, via, _) = await Loader.load()
        let now = Date()
        // Gallery preview: show synthetic data if the host isn't running.
        return GlucoseEntry(date: now, content: GlucoseEntryContent(snapshot: s ?? Synthetic.realistic(), date: now), via: via,
                            overrides: configuration.overrides)
    }

    func timeline(for configuration: GlucoseWidgetIntent, in context: Context) async -> Timeline<GlucoseEntry> {
        await withCheckedContinuation { cont in getTimeline(in: context, overrides: configuration.overrides) { cont.resume(returning: $0) } }
    }

    func getTimeline(in context: Context, overrides: WidgetOverrides, completion: @escaping (Timeline<GlucoseEntry>) -> Void) {
        Task {
            let (s, via, report) = await Loader.load()
            let now = Date()
            log.notice("getTimeline kind=\(kind.rawValue, privacy: .public) family=\(String(describing: context.family), privacy: .public) via=\(via, privacy: .public) latest=\(s?.latest?.t.timeIntervalSince1970 ?? 0, privacy: .public) [\(report.joined(separator: " "), privacy: .public)]")
            Self.appendLocalLog("getTimeline \(kind.rawValue) \(context.family) via=\(via) \(report.joined(separator: " "))")

            var entries = [GlucoseEntry(date: now, content: GlucoseEntryContent(snapshot: s, date: now,
                error: s == nil ? "Host app not running" : nil), via: via, overrides: overrides)]
            // Without any reload, only the freshness step changes (investigation 16: time shown → struck through →
            // no value → no graph): one entry at each step, nothing ticks in between.
            if let l = s?.latest {
                var age = now.timeIntervalSince(l.t)
                while let next = Freshness.nextChange(age: age) {
                    let at = l.t.addingTimeInterval(next)
                    entries.append(GlucoseEntry(date: at, content: GlucoseEntryContent(snapshot: s, date: at), via: via, overrides: overrides))
                    age = next
                }
            }
            // Ask to be called again one minute after the latest reading (the next one is due then).
            // The system treats this as a hint; the host's reloadTimelines() is the real trigger.
            let next = max(now.addingTimeInterval(60), (s?.latest?.t ?? now).addingTimeInterval(65))
            completion(Timeline(entries: entries, policy: .after(next)))
        }
    }

    /// Also keep a log inside the container (readable from outside without `log show`).
    static func appendLocalLog(_ line: String) {
        let url = URL(fileURLWithPath: NSHomeDirectory()).appendingPathComponent("Library/Caches/timeline.log")
        try? FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        let s = "\(ISO8601DateFormatter().string(from: Date())) \(line)\n"
        if let h = try? FileHandle(forWritingTo: url) {
            h.seekToEndOfFile(); h.write(Data(s.utf8)); try? h.close()
        } else {
            try? Data(s.utf8).write(to: url)
        }
    }
}

struct EntryView: View {
    let kind: WidgetKind
    let entry: GlucoseEntry
    @Environment(\.widgetFamily) var family
    @Environment(\.widgetRenderingMode) var renderingMode

    var body: some View {
        GlucoseWidgetView(kind: kind, family: family, c: entry.content, mode: RenderMode(renderingMode), overrides: entry.overrides)
            .containerBackground(.fill.tertiary, for: .widget)
            .widgetURL(SharedLocation.urlScheme.isEmpty ? nil : URL(string: "\(SharedLocation.urlScheme)://open?from=\(kind.rawValue)"))
    }
}

struct ValueWidget: Widget {
    var body: some WidgetConfiguration {
        AppIntentConfiguration(kind: "dev.spike.glucose.value", intent: GlucoseWidgetIntent.self, provider: Provider(kind: .value)) {
            EntryView(kind: .value, entry: $0)
        }
        .configurationDisplayName("Glucose")
        .description("Current glucose, trend and time in range.")
        .supportedFamilies([.systemSmall, .systemMedium])
    }
}

struct GraphWidget: Widget {
    var body: some WidgetConfiguration {
        AppIntentConfiguration(kind: "dev.spike.glucose.graph", intent: GlucoseWidgetIntent.self, provider: Provider(kind: .graph)) {
            EntryView(kind: .graph, entry: $0)
        }
        .configurationDisplayName("Glucose graph")
        .description("The graph with time in range beside it.")
        .supportedFamilies([.systemSmall, .systemMedium, .systemLarge, .systemExtraLarge])
    }
}

struct CombinedWidget: Widget {
    var body: some WidgetConfiguration {
        AppIntentConfiguration(kind: "dev.spike.glucose.combined", intent: GlucoseWidgetIntent.self, provider: Provider(kind: .combined)) {
            EntryView(kind: .combined, entry: $0)
        }
        .configurationDisplayName("Glucose + graph")
        .description("Value, time-in-range rings and the graph.")
        .supportedFamilies([.systemSmall, .systemMedium, .systemLarge, .systemExtraLarge])
    }
}

@main
struct GlucoseWidgets: WidgetBundle {
    var body: some Widget {
        CombinedWidget()
        ValueWidget()
        GraphWidget()
    }
}
