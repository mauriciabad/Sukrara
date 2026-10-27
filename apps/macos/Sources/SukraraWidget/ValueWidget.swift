// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import SukraraCore
import SwiftUI
import WidgetKit

/// "Glucose": the value, trend, badge and time-in-range rings (small, medium).
struct ValueWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: SukraraIDs.WidgetKind.value, provider: SnapshotProvider()) {
            WidgetEntryView(kind: .value, entry: $0)
        }
        .configurationDisplayName(WidgetWords.current.valueName)
        .description(WidgetWords.current.valueDescription)
        .supportedFamilies([.systemSmall, .systemMedium])
    }
}
