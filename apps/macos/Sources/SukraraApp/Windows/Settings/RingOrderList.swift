// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import SukraraGraph
import SwiftUI

/// The ring order's edits, pure: a drag (`move`) and Move Up / Move Down (`step`). Every result keeps each band once.
enum RingOrder {
    /// The rows at `from` dropped before row `to` (SwiftUI `onMove` indices).
    static func move(_ order: [Band], from: IndexSet, to: Int) -> [Band] {
        guard from.allSatisfy(order.indices.contains), (0...order.count).contains(to) else { return order }
        let moving = from.map { order[$0] }
        var rest = order.enumerated().filter { !from.contains($0.offset) }.map(\.element)
        let at = to - from.filter { $0 < to }.count
        rest.insert(contentsOf: moving, at: at)
        return rest
    }

    /// Row `i` one place up (`by` -1) or down (+1); unchanged at either end.
    static func step(_ order: [Band], _ i: Int, by d: Int) -> [Band] {
        let j = i + d
        guard order.indices.contains(i), order.indices.contains(j) else { return order }
        var o = order
        o.swapAt(i, j)
        return o
    }
}

/// The ring order as a reorderable list: drag a range (the list draws the insertion line), or Move Up / Move Down
/// from its context menu and VoiceOver's actions; each row says its position.
struct RingOrderList: View {
    let order: [Band]
    let set: ([Band]) -> Void
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        List {
            ForEach(Array(order.enumerated()), id: \.element) { i, band in
                HStack {
                    Image(systemName: "line.3.horizontal").foregroundStyle(.tertiary).accessibilityHidden(true)
                    Circle().fill(Self.color(band, dark: scheme == .dark)).frame(width: 10, height: 10)
                        .accessibilityHidden(true)
                    Text(Self.name(band))
                    Spacer()
                    Text(L10n.t("\(String(i + 1)) of \(String(order.count))")).foregroundStyle(.secondary)
                        .font(.caption).accessibilityHidden(true)
                }
                .contextMenu {
                    Button(L10n.t("Move Up")) { set(RingOrder.step(order, i, by: -1)) }.disabled(i == 0)
                    Button(L10n.t("Move Down")) { set(RingOrder.step(order, i, by: 1)) }
                        .disabled(i == order.count - 1)
                }
                .accessibilityElement(children: .combine)
                .accessibilityValue(L10n.t("\(String(i + 1)) of \(String(order.count))"))
                .accessibilityAction(named: L10n.t("Move Up")) { set(RingOrder.step(order, i, by: -1)) }
                .accessibilityAction(named: L10n.t("Move Down")) { set(RingOrder.step(order, i, by: 1)) }
            }
            .onMove { from, to in
                withAnimation(.snappy) { set(RingOrder.move(order, from: from, to: to)) }
            }
        }
        .listStyle(.bordered(alternatesRowBackgrounds: false))
        .frame(height: CGFloat(order.count) * 28 + 8)
    }

    static func name(_ b: Band) -> String {
        switch b {
        case .veryLow: L10n.t("Very low")
        case .low: L10n.t("Low")
        case .tight: L10n.t("Very good")
        case .target: L10n.t("In range")
        case .high: L10n.t("High")
        case .veryHigh: L10n.t("Very high")
        }
    }

    /// The range's colour on the card, in the window's appearance.
    static func color(_ b: Band, dark: Bool) -> Color { Color(cgColor: b.color(dark: dark)) }
}
