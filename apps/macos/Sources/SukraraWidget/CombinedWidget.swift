// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import SukraraCore
import SwiftUI
import WidgetKit

/// "Glucose + graph": the whole card, value, rings and chart (small to extra large).
struct CombinedWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: SukraraIDs.WidgetKind.combined, provider: SnapshotProvider()) {
            WidgetEntryView(kind: .combined, entry: $0)
        }
        .configurationDisplayName(WidgetWords.current.combinedName)
        .description(WidgetWords.current.combinedDescription)
        .supportedFamilies([.systemSmall, .systemMedium, .systemLarge, .systemExtraLarge])
    }
}
