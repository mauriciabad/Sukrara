// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import SukraraCore
import SwiftUI
import WidgetKit

/// "Glucose graph": the chart with time in range beside it (small to extra large).
struct GraphWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: SukraraIDs.WidgetKind.graph, provider: SnapshotProvider()) {
            WidgetEntryView(kind: .graph, entry: $0)
        }
        .configurationDisplayName(WidgetWords.current.graphName)
        .description(WidgetWords.current.graphDescription)
        .supportedFamilies([.systemSmall, .systemMedium, .systemLarge, .systemExtraLarge])
    }
}
