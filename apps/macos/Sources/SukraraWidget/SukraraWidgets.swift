// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import SukraraCore
import SukraraGraph
import SwiftUI
import WidgetKit

// The WidgetKit extension. Placeholder until chunk 22: one widget of kind `value` that shows the no-value mark, so
// the bundle has a real, loadable widget. It reads only the App Group snapshot (SukraraPaths.appGroupSnapshot()).

@main
struct SukraraWidgets: WidgetBundle {
    var body: some Widget {
        ValueWidget()
    }
}

struct ValueWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: SukraraIDs.WidgetKind.value, provider: PlaceholderProvider()) { _ in
            Text(GlucoseCard.noValueText)
                .font(.largeTitle)
                .containerBackground(.background, for: .widget)
                .accessibilityLabel("No glucose value")
        }
        .configurationDisplayName("Glucose")
        .description("The current glucose value.")
        .supportedFamilies([.systemSmall])
    }
}

struct PlaceholderEntry: TimelineEntry {
    let date: Date
}

struct PlaceholderProvider: TimelineProvider {
    func placeholder(in context: Context) -> PlaceholderEntry { PlaceholderEntry(date: .now) }

    func getSnapshot(in context: Context, completion: @escaping (PlaceholderEntry) -> Void) {
        completion(PlaceholderEntry(date: .now))
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<PlaceholderEntry>) -> Void) {
        completion(Timeline(entries: [PlaceholderEntry(date: .now)], policy: .never))
    }
}
