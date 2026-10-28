// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import SukraraCore
import SukraraGraph
import SwiftUI

/// Rings: the rings and numbers beside the value (how many, each one's kind, period and Fainter), what a ring's centre
/// counts, the rewards, the ring order and the last-treatment slot. Outside Custom: a read-only summary.
struct RingsPane: View {
    @Bindable var model: SettingsModel

    private var g: GraphConfig { model.graph }
    private static let slotNames = [L10n.t("Main"), L10n.t("Second"), L10n.t("Third")]

    var body: some View {
        Form {
            SettingsFromSection(model: model, rows: summary)
            if model.editable {
                Section(L10n.t("Beside the value")) {
                    NumberSettingRow(
                        label: L10n.t("Rings and numbers"), format: MacSettings.ringCount.format,
                        value: Binding(
                            get: { model.number(MacSettings.ringCount) },
                            set: { model.setNumber($0, MacSettings.ringCount) }))
                    ForEach(0..<min(g.ringCount, g.slots.count), id: \.self) { i in  // settings-ok: slot rows
                        slotRows(
                            Self.slotNames[i], g.slots[i], fainter: i > 0,
                            set: { r in model.updateGraph { $0.slots[i] = r } })
                    }
                    Picker(L10n.t("A ring's centre counts"), selection: graphValue(\.ringRange)) {
                        Text(L10n.t("In range")).tag(TirRange.inRange)
                        Text(L10n.t("Very good")).tag(TirRange.tight)
                    }
                    Toggle(L10n.t("Rewards on the rings (laurel, flowers, crown)"), isOn: graphValue(\.ringMarks))
                }
                Section {
                    RingOrderList(order: g.ringOrder) { o in model.updateGraph { $0.ringOrder = o } }
                    HStack {
                        Spacer()
                        Button(L10n.t("Reset Order")) { model.resetRingOrder() }.disabled(!model.ringOrderChanged)
                    }
                } header: {
                    Text(L10n.t("Ring order"))
                } footer: {
                    Text(
                        L10n.t(
                            "Clockwise from 12 o'clock. Drag a range to move it, or Control-click it for Move Up and Move Down."
                        )
                    )
                    .font(.callout).foregroundStyle(.secondary)
                }
                Section(L10n.t("Last treatment")) {
                    Picker(L10n.t("Position"), selection: graphValue(\.lastPlace)) {
                        ForEach(LastPlace.allCases, id: \.self) { Text(Self.placeName($0)).tag($0) }
                    }
                    Picker(L10n.t("Without a recent treatment"), selection: elseKind) {
                        Text(L10n.t("Nothing")).tag(SlotKind?.none)
                        ForEach(SlotKind.allCases, id: \.self) { Text(Self.kindName($0)).tag(Optional($0)) }
                    }
                    .disabled(g.lastPlace == .off)
                    if let r = g.lastElse, g.lastPlace != .off {
                        NumberSettingRow(
                            label: L10n.t("Period"), format: SettingsSpec.ringWindow,
                            value: Binding(
                                get: { r.window },
                                set: { v in model.updateGraph { $0.lastElse?.window = v } })
                        )
                        .padding(.leading, 16)
                        Toggle(
                            L10n.t("Fainter"),
                            isOn: Binding(
                                get: { r.faded }, set: { v in model.updateGraph { $0.lastElse?.faded = v } })
                        )
                        .padding(.leading, 16)
                    }
                }
            }
        }
        .formStyle(.grouped)
    }

    /// One slot: what it shows, its period and (not the main one) Fainter.
    @ViewBuilder
    private func slotRows(_ name: String, _ r: RingSpec, fainter: Bool, set: @escaping (RingSpec) -> Void)
        -> some View
    {
        Picker(
            name,
            selection: Binding(
                get: { r.kind },
                set: {
                    var x = r; x.kind = $0; set(x)
                })
        ) {
            ForEach(SlotKind.allCases, id: \.self) { Text(Self.kindName($0)).tag($0) }
        }
        NumberSettingRow(
            label: L10n.t("Period"), format: SettingsSpec.ringWindow,
            value: Binding(
                get: { r.window },
                set: {
                    var x = r; x.window = $0; set(x)
                })
        )
        .padding(.leading, 16)
        if fainter {
            Toggle(
                L10n.t("Fainter"),
                isOn: Binding(
                    get: { r.faded },
                    set: {
                        var x = r; x.faded = $0; set(x)
                    })
            )
            .padding(.leading, 16)
        }
    }

    /// The fallback's kind; nil = nothing.
    private var elseKind: Binding<SlotKind?> {
        Binding(
            get: { g.lastElse?.kind },
            set: { k in
                model.updateGraph { c in
                    guard let k else { return c.lastElse = nil }
                    var r = c.lastElse ?? GraphConfig.defaultLastElseSlot
                    r.kind = k
                    c.lastElse = r
                }
            })
    }

    private func graphValue<V>(_ path: WritableKeyPath<GraphConfig, V>) -> Binding<V> {
        Binding(get: { g[keyPath: path] }, set: { v in model.updateGraph { $0[keyPath: path] = v } })
    }

    private var summary: [(String, String)] {
        let rings = g.slots.prefix(g.ringCount).map {
            "\(Self.kindName($0.kind)) \(NumberSettingRow.word(SettingsSpec.ringWindow, $0.window))"
        }
        let last =
            g.lastPlace == .off
            ? Self.placeName(.off)
            : Self.placeName(g.lastPlace)
                + (g.lastElse.map {
                    ", "
                        + L10n.t(
                            "else \(Self.kindName($0.kind)) \(NumberSettingRow.word(SettingsSpec.ringWindow, $0.window))"
                        )
                } ?? "")
        return [
            (L10n.t("Rings and numbers"), rings.isEmpty ? L10n.t("None") : rings.joined(separator: ", ")),
            (L10n.t("A ring's centre counts"), g.ringRange == .tight ? L10n.t("Very good") : L10n.t("In range")),
            (L10n.t("Ring order"), g.ringOrder.map(RingOrderList.name).joined(separator: ", ")),
            (L10n.t("Rewards on the rings"), g.ringMarks ? L10n.t("On") : L10n.t("Off")),
            (L10n.t("Last treatment"), last),
        ]
    }

    static func kindName(_ k: SlotKind) -> String {
        switch k {
        case .tir: L10n.t("Time in range")
        case .gmi: L10n.t("GMI")
        case .average: L10n.t("Average glucose")
        case .cv: L10n.t("Variability (CV)")
        }
    }

    static func placeName(_ p: LastPlace) -> String {
        switch p {
        case .off: L10n.t("Off")
        case .nearValue: L10n.t("Next to the number")
        case .end: L10n.t("At the right end")
        }
    }
}
