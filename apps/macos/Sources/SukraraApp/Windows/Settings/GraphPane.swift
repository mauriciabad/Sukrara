// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import SukraraCore
import SukraraGraph
import SwiftUI

/// Graph: the time shown (default + each place's own), the distribution and crown beside the chart, the vertical
/// lines, treatments on the graph, lines across it, the ranges, the sensor countdown and time left per place. Outside
/// Custom the mirrored ones show as a read-only summary with the way to change them.
struct GraphPane: View {
    @Bindable var model: SettingsModel

    private var g: GraphConfig { model.graph }

    var body: some View {
        Form {
            SettingsFromSection(model: model, rows: summary)
            Section {
                if model.editable {
                    NumberSettingRow(
                        label: L10n.t("Default"), format: MacSettings.graphHours.format,
                        value: number(MacSettings.graphHours))
                }
                ForEach(GraphPlace.allCases, id: \.self) { place in
                    NumberSettingRow(
                        label: Self.placeName(place), format: place.hours.format,
                        value: Binding(
                            get: { model.placeHours(place) }, set: { model.setPlaceHours($0, place) }))
                }
            } header: {
                Text(L10n.t("Time shown"))
            } footer: {
                note(L10n.t("Each place can show a window of its own; Same as default follows the default."))
            }
            if model.editable {
                Section(L10n.t("Graph")) {
                    Toggle(L10n.t("Time in range distribution"), isOn: graphBool(\.side))
                    Toggle(L10n.t("Crown beside the graph when all of it is in range"), isOn: graphBool(\.columnCrown))
                    NumberSettingRow(
                        label: L10n.t("Vertical lines"), format: MacSettings.gridHours.format,
                        value: number(MacSettings.gridHours))
                }
                marksSection
                linesSection
                Section {
                    ForEach(MacSettings.bands, id: \.id) { band in
                        NumberSettingRow(label: Self.bandName(band.id), format: band.format, value: number(band))
                    }
                    HStack {
                        Spacer()
                        Button(L10n.t("Reset Ranges")) { model.resetRanges() }.disabled(!model.rangesChanged)
                    }
                } header: {
                    Text(L10n.t("Ranges (mg/dL)"))
                } footer: {
                    note(L10n.t("Used by the graph, the rings, the report and the menu bar colour, and kept in order."))
                }
            }
            Section {
                if model.editable {
                    NumberSettingRow(
                        label: L10n.t("Sensor countdown (last hours)"), format: MacSettings.countdownHours.format,
                        value: number(MacSettings.countdownHours))
                }
                Toggle(
                    L10n.t("Time left in the menu bar panel"),
                    isOn: Binding(get: { model.sensorTime(.menubar) }, set: { model.setSensorTime($0, .menubar) }))
                Toggle(
                    L10n.t("Time left on widgets"),
                    isOn: Binding(get: { model.sensorTime(.widget) }, set: { model.setSensorTime($0, .widget) }))
            } header: {
                Text(L10n.t("Sensor"))
            } footer: {
                note(
                    L10n.t(
                        "A ring over the main ring counts down the sensor's last hours. The warm-up and Sensor ended show either way."
                    ))
            }
            if model.editable {
                Section {
                    HStack {
                        Spacer()
                        Button(L10n.t("Reset All Graph Settings…"), role: .destructive) {
                            model.confirmingResetAll = true
                        }
                        .disabled(!model.graphChanged)
                    }
                }
            }
        }
        .formStyle(.grouped)
        .confirmationDialog(L10n.t("Reset all graph settings?"), isPresented: $model.confirmingResetAll) {
            Button(L10n.t("Reset"), role: .destructive) { model.resetGraph() }
            Button(L10n.t("Cancel"), role: .cancel) {}
        } message: {
            Text(
                L10n.t(
                    "The time shown, the rings, the lines, the ranges, the last treatment and the sensor settings go back to their defaults."
                ))
        }
    }

    /// Treatments on the graph: a row per kind, a checkbox per part it has (HIG: checkboxes for independent options).
    private var marksSection: some View {
        Section {
            Grid(alignment: .leading, horizontalSpacing: 14, verticalSpacing: 8) {
                GridRow {
                    Text(verbatim: "")
                    ForEach(MarkShow.partIds, id: \.self) { p in
                        Text(Self.partName(p)).foregroundStyle(.secondary).font(.caption)
                    }
                }
                ForEach(MarkShow.kindIds, id: \.self) { k in
                    GridRow {
                        Text(Self.kindName(k))
                        ForEach(MarkShow.partIds, id: \.self) { p in
                            if MarkShow.partsOf(k).contains(p) {
                                Toggle(
                                    Self.partName(p),
                                    isOn: Binding(
                                        get: { g.marks.shows(k, p) },
                                        set: { v in model.updateGraph { $0.marks = $0.marks.setting(k, p, v) } })
                                )
                                .toggleStyle(.checkbox).labelsHidden()
                                .accessibilityLabel(L10n.t("\(Self.kindName(k)): \(Self.partName(p))"))
                            } else {
                                Text(verbatim: "—").foregroundStyle(.tertiary).accessibilityHidden(true)
                            }
                        }
                    }
                }
            }
            HStack {
                Spacer()
                Button(L10n.t("Reset Treatments")) { model.resetMarks() }.disabled(!model.marksChanged)
            }
        } header: {
            Text(L10n.t("Treatments on the graph"))
        } footer: {
            note(L10n.t("Close treatments share one label. Hidden amounts leave it; the time shows once."))
        }
    }

    private var linesSection: some View {
        Section {
            ForEach(g.lines, id: \.self) { l in
                LabeledContent(L10n.t("\(String(l)) mg/dL")) {
                    Button(role: .destructive) {
                        model.removeLine(l)
                    } label: {
                        Image(systemName: "minus.circle")
                    }
                    .buttonStyle(.borderless)
                    .help(L10n.t("Remove this line"))
                    .accessibilityLabel(L10n.t("Remove the line at \(String(l)) mg/dL"))
                }
            }
            if g.lines.count < SettingsSpec.line.maxItems {
                AddLineRow(existing: g.lines) { model.addLine($0) }
            }
        } header: {
            Text(L10n.t("Lines across the graph"))
        } footer: {
            note(L10n.t("Dashed lines at these levels, up to six."))
        }
    }

    private var summary: [(String, String)] {
        let b = g.bands
        let beside = [
            g.side ? L10n.t("time in range distribution") : nil,
            g.columnCrown ? L10n.t("crown when all in range") : nil,
        ].compactMap { $0 }
        return [
            (L10n.t("Time shown"), NumberSettingRow.word(SettingsSpec.graphHours, Double(g.hours))),
            (
                L10n.t("Ranges"),
                "\(b.veryLow) · \(b.low) · \(b.tight) · \(b.high) · \(b.veryHigh) · \(b.extremelyHigh) mg/dL"
            ),
            (
                L10n.t("Lines across the graph"),
                g.lines.isEmpty ? L10n.t("None") : g.lines.map(String.init).joined(separator: ", ") + " mg/dL"
            ),
            (L10n.t("Vertical lines"), NumberSettingRow.word(SettingsSpec.gridHours, Double(g.gridHours))),
            (L10n.t("Beside the graph"), beside.isEmpty ? L10n.t("Nothing") : beside.joined(separator: ", ")),
            (
                L10n.t("Treatments on the graph"),
                MarkShow.kindIds.map { k in
                    let parts = MarkShow.partsOf(k).filter { g.marks.shows(k, $0) }.map(Self.partName)
                    return "\(Self.kindName(k)): " + (parts.isEmpty ? L10n.t("nothing") : parts.joined(separator: ", "))
                }.joined(separator: "; ")
            ),
            (
                L10n.t("Sensor countdown (last hours)"),
                NumberSettingRow.word(SettingsSpec.countdownHours, Double(g.countdownHours))
            ),
        ]
    }

    private func number(_ n: MacSettings.Number) -> Binding<Double> {
        Binding(get: { model.number(n) }, set: { model.setNumber($0, n) })
    }

    private func graphBool(_ path: WritableKeyPath<GraphConfig, Bool>) -> Binding<Bool> {
        Binding(get: { g[keyPath: path] }, set: { v in model.updateGraph { $0[keyPath: path] = v } })
    }

    private func note(_ s: String) -> some View { Text(s).font(.callout).foregroundStyle(.secondary) }

    static func placeName(_ p: GraphPlace) -> String {
        switch p {
        case .menubar: L10n.t("Menu bar panel")
        case .widget: L10n.t("Widgets")
        }
    }

    static func kindName(_ k: String) -> String {
        switch k {
        case "rapid": L10n.t("Rapid insulin")
        case "long": L10n.t("Long-acting insulin")
        case "carbs": L10n.t("Carbs")
        default: L10n.t("Notes")
        }
    }

    static func partName(_ p: String) -> String {
        switch p {
        case "point": L10n.t("Point")
        case "line": L10n.t("Mute line")
        case "amount": L10n.t("Amount")
        default: L10n.t("Time")
        }
    }

    static func bandName(_ id: String) -> String {
        switch id {
        case "graph.band.veryLow": L10n.t("Very low below")
        case "graph.band.low": L10n.t("Low below")
        case "graph.band.tight": L10n.t("Very good up to")
        case "graph.band.high": L10n.t("High from")
        case "graph.band.veryHigh": L10n.t("Very high from")
        default: L10n.t("Extremely high from")
        }
    }
}

/// "Add a line at": the one number input with the line format, and Add.
private struct AddLineRow: View {
    /// The lines already there: adding one of them again does nothing, so Add is dimmed.
    let existing: [Int]
    let add: (Double) -> Void
    @State private var value = SettingsSpec.line.default

    var body: some View {
        HStack(alignment: .firstTextBaseline) {
            NumberSettingRow(label: L10n.t("Add a line at"), format: SettingsSpec.line, value: $value)
            Button(L10n.t("Add")) { add(value) }.disabled(existing.contains(Int(value)))
        }
    }
}

/// The pane's first section: where its settings come from now and, outside Custom, what's in use (read-only) with the
/// ways to change it.
struct SettingsFromSection: View {
    @Bindable var model: SettingsModel
    var rows: [(String, String)] = []

    var body: some View {
        if !model.editable {
            Section {
                LabeledContent(L10n.t("Settings from"), value: SettingsFromPicker.name(model.settingsFrom, model))
                ForEach(rows, id: \.0) { r in
                    LabeledContent(r.0) { Text(r.1).multilineTextAlignment(.trailing) }
                }
            } footer: {
                HStack(alignment: .firstTextBaseline) {
                    Text(model.inUse + " " + L10n.t("To change these on this Mac, choose Custom."))
                        .font(.callout).foregroundStyle(.secondary)
                    Spacer()
                    Button(L10n.t("Use my own settings")) { model.useOwn() }
                    Button(L10n.t("Change…")) { model.pane = .general }
                }
            }
        }
    }
}
