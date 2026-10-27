// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import SukraraCore
import SukraraGraph
import SwiftUI
import WidgetKit

/// The three widget kinds and the registered card surface each one draws (contracts/graph/surfaces/surfaces.json).
enum WidgetKind {
    case value, graph, combined

    var surface: CardSurface {
        switch self {
        case .value: .widgetValue
        case .graph: .widgetGraph
        case .combined: .widgetCombined
        }
    }
}

/// One widget's picture: the card of its kind's surface at the family's content size, drawn by the one renderer as a
/// single bitmap (the widget chooses only its size, its surface and the rendering mode). VoiceOver reads the card's
/// sentence and chart summary (`GlucoseCard.accessibilityLabel`). A tap opens quick Add treatment; without data, the app.
struct WidgetEntryView: View {
    let kind: WidgetKind
    let entry: SnapshotEntry
    @Environment(\.widgetRenderingMode) private var renderingMode
    @Environment(\.colorScheme) private var scheme
    @Environment(\.displayScale) private var displayScale

    /// Room around the card for what overflows it (a ring's laurel or crown): drawn into the widget's margin.
    private static let overflow: CGFloat = 12

    var body: some View {
        Group {
            if let snapshot = entry.snapshot, let data = entry.data {
                card(snapshot, data)
                    .accessibilityElement(children: .ignore)
                    .accessibilityLabel(
                        GlucoseCard.accessibilityLabel(
                            data, unit: snapshot.unit, surface: kind.surface, config: snapshot.config))
                    .widgetURL(URL(string: SukraraIDs.Link.newTreatment))
            } else {
                openApp
                    .widgetURL(URL(string: SukraraIDs.Link.panel))
            }
        }
        // the card is one image: redacted (placeholder, gallery) it would come out blank
        .unredacted()
        .containerBackground(.fill.tertiary, for: .widget)
    }

    private func card(_ snapshot: WidgetSnapshot, _ data: CardData) -> some View {
        GeometryReader { geo in
            let o = Self.overflow
            if let image = GlucoseCard.image(
                surface: kind.surface, size: geo.size, config: snapshot.config, data: data, unit: snapshot.unit,
                dark: scheme == .dark, tinted: renderingMode != .fullColor, scale: displayScale, overflow: o)
            {
                Image(decorative: image, scale: displayScale)
                    .resizable()
                    .frame(width: geo.size.width + 2 * o, height: geo.size.height + 2 * o)
                    .frame(width: geo.size.width, height: geo.size.height)
            }
        }
        // the renderer's tinted palette already maps the card to luminance: no accent split on top
        .widgetAccentable(false)
    }

    /// No snapshot (the app isn't running or set up, or wrote another format): no number at all, and where to go.
    private var openApp: some View {
        let words = WidgetWords.current
        return VStack(alignment: .leading, spacing: 4) {
            Text(GlucoseCard.noValueText)
                .font(.system(.largeTitle, design: .rounded, weight: .semibold))
            Text(words.openApp)
                .font(.callout)
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(words.openAppSpoken)
    }
}
