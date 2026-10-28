// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import SwiftUI
import WidgetKit

/// The WidgetKit extension (Sukrara.app/Contents/PlugIns/SukraraWidget.appex). It reads nothing but the App Group
/// snapshot the app writes (SnapshotProvider) and draws only through the one card renderer (WidgetEntryView).
@main
struct SukraraWidgetBundle: WidgetBundle {
    var body: some Widget {
        CombinedWidget()
        ValueWidget()
        GraphWidget()
    }
}
