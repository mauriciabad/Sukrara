// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation
import os
import SwiftUI
import WidgetKit

let log = Logger(subsystem: "dev.spike.GlucoseWidgets", category: "widget")

struct GlucoseEntry: TimelineEntry {
    var date: Date
    var content: GlucoseEntryContent
    var via: String
}

struct Provider: TimelineProvider {
    let kind: WidgetKind

    func placeholder(in context: Context) -> GlucoseEntry {
        GlucoseEntry(date: Date(), content: GlucoseEntryContent(snapshot: Synthetic.snapshot(hours: 12), date: Date()), via: "placeholder")
    }

    func getSnapshot(in context: Context, completion: @escaping (GlucoseEntry) -> Void) {
        Task {
            let (s, via, _) = await Loader.load()
            let now = Date()
            // Gallery preview: show synthetic data if the host isn't running.
            completion(GlucoseEntry(date: now, content: GlucoseEntryContent(snapshot: s ?? Synthetic.snapshot(hours: 12), date: now), via: via))
        }
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<GlucoseEntry>) -> Void) {
        Task {
            let (s, via, report) = await Loader.load()
            let now = Date()
            log.notice("getTimeline kind=\(kind.rawValue, privacy: .public) family=\(String(describing: context.family), privacy: .public) via=\(via, privacy: .public) latest=\(s?.latest?.t.timeIntervalSince1970 ?? 0, privacy: .public) [\(report.joined(separator: " "), privacy: .public)]")
            Self.appendLocalLog("getTimeline \(kind.rawValue) \(context.family) via=\(via) \(report.joined(separator: " "))")

            var entries = [GlucoseEntry(date: now, content: GlucoseEntryContent(snapshot: s, date: now,
                error: s == nil ? "Host app not running" : nil), via: via)]
            // Without any reload, the only thing that changes is "stale": add an entry exactly when the
            // latest reading crosses 5 min, so the strikethrough appears on time. "x min ago" ticks by itself.
            if let l = s?.latest {
                let staleAt = l.t.addingTimeInterval(GlucoseRange.staleAfter + 1)
                if staleAt > now {
                    entries.append(GlucoseEntry(date: staleAt, content: GlucoseEntryContent(snapshot: s, date: staleAt), via: via))
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
        GlucoseWidgetView(kind: kind, family: family, c: entry.content, mode: RenderMode(renderingMode))
            .containerBackground(.fill.tertiary, for: .widget)
            .widgetURL(SharedLocation.urlScheme.isEmpty ? nil : URL(string: "\(SharedLocation.urlScheme)://open?from=\(kind.rawValue)"))
    }
}

struct ValueWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "dev.spike.glucose.value", provider: Provider(kind: .value)) {
            EntryView(kind: .value, entry: $0)
        }
        .configurationDisplayName("Glucose")
        .description("Current glucose, trend and age.")
        .supportedFamilies([.systemSmall, .systemMedium])
    }
}

struct GraphWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "dev.spike.glucose.graph", provider: Provider(kind: .graph)) {
            EntryView(kind: .graph, entry: $0)
        }
        .configurationDisplayName("Glucose graph")
        .description("Last 3–12 h with the 70–180 target band.")
        .supportedFamilies([.systemSmall, .systemMedium, .systemLarge, .systemExtraLarge])
    }
}

struct CombinedWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "dev.spike.glucose.combined", provider: Provider(kind: .combined)) {
            EntryView(kind: .combined, entry: $0)
        }
        .configurationDisplayName("Glucose + graph")
        .description("Current value with the recent graph.")
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
