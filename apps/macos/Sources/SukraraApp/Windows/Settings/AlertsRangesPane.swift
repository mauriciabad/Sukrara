// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import SukraraAlerts
import SukraraCore
import SwiftUI

/// Alerts: alerts on/off, per kind on/off + threshold + repeat, quiet times, sound pack. The mirrored rows (each
/// kind's on / threshold / repeat but no data's, the quiet times) show the rules in use and are read-only outside
/// Custom; what isn't mirrored (alerts on/off, no data, sounds) is always this Mac's and editable.
struct AlertsRangesPane: View {
    @Bindable var model: SettingsModel

    var body: some View {
        Form {
            if model.alerts.readError {
                Section {
                    Label(
                        L10n.t("Alert settings couldn't be read. Sukrara keeps using the last ones it read."),
                        systemImage: "exclamationmark.triangle.fill"
                    )
                    .foregroundStyle(.red)
                    Button(L10n.t("Reset alert settings")) { model.alerts.resetToDefaults() }
                }
            }
            SettingsFromSection(model: model)
            Section(L10n.t("Alerts")) {
                Toggle(L10n.t("Glucose alerts"), isOn: alertBinding(\.on))
                ForEach(AlertKind.allCases, id: \.self) { kind in
                    kindRows(kind)
                }
            }
            Section(L10n.t("Quiet times")) {
                quiet(L10n.t("Quiet after insulin"), \.muteAfterInsulin, "alerts.muteAfterInsulin.min")
                quiet(L10n.t("Quiet while a high falls"), \.muteFallingHigh, "alerts.muteFallingHigh.min")
                quiet(L10n.t("Quiet after carbs"), \.muteAfterCarbs, "alerts.muteAfterCarbs.min")
            }
            Section(L10n.t("Sounds")) {
                Picker(L10n.t("Sound pack"), selection: alertBinding(\.sounds.pack)) {
                    ForEach(SettingsSpec.options["soundPack"] ?? [], id: \.self) { Text(Self.packName($0)).tag($0) }
                }
            }
        }
        .formStyle(.grouped)
    }

    @ViewBuilder private func kindRows(_ kind: AlertKind) -> some View {
        let id = "alerts.threshold.\(kind.rawValue)"
        let readOnly = model.alertsMirrored && kind != .noData
        let shown = model.shownAlerts[kind]
        DisclosureGroup {
            if let name = SettingsSpec.settings.first(where: { $0.id == id })?.number,
                let format = SettingsSpec.numbers[name]
            {
                NumberSettingRow(
                    label: L10n.t("Threshold"), format: format,
                    value: Binding(
                        get: { shown.threshold }, set: { v in model.alerts.update { $0[kind].threshold = v } }),
                    readOnly: readOnly)
            }
            NumberSettingRow(
                label: L10n.t("Repeat"), format: SettingsSpec.alertRepeat,
                value: Binding(
                    get: { Double(shown.repeatMin) }, set: { v in model.alerts.update { $0[kind].repeatMin = Int(v) } }),
                readOnly: readOnly)
        } label: {
            Toggle(
                Self.kindName(kind),
                isOn: Binding(get: { shown.on }, set: { v in model.alerts.update { $0[kind].on = v } })
            )
            .disabled(readOnly)
        }
    }

    private func quiet(_ label: String, _ path: WritableKeyPath<AlertSettings, QuietRule>, _ id: String) -> some View {
        let name = SettingsSpec.settings.first { $0.id == id }?.number ?? ""
        let rule = model.shownAlerts[keyPath: path]
        let on = Binding(get: { rule.on }, set: { v in model.alerts.update { $0[keyPath: path].on = v } })
        let minutes = Binding(get: { rule.min }, set: { v in model.alerts.update { $0[keyPath: path].min = v } })
        return VStack(alignment: .leading) {
            Toggle(label, isOn: on).disabled(model.alertsMirrored)
            if let format = SettingsSpec.numbers[name], rule.on {
                NumberSettingRow(label: L10n.t("For"), format: format, value: minutes, readOnly: model.alertsMirrored)
            }
        }
    }

    private func alertBinding<V>(_ path: WritableKeyPath<AlertSettings, V>) -> Binding<V> {
        Binding(
            get: { model.alerts.settings[keyPath: path] }, set: { v in model.alerts.update { $0[keyPath: path] = v } })
    }

    static func kindName(_ k: AlertKind) -> String {
        switch k {
        case .veryLow: L10n.t("Very low")
        case .low: L10n.t("Low")
        case .high: L10n.t("High")
        case .veryHigh: L10n.t("Very high")
        case .extremelyHigh: L10n.t("Extremely high")
        case .fallingFast: L10n.t("Falling fast")
        case .risingFast: L10n.t("Rising fast")
        case .noData: L10n.t("No data")
        }
    }

    static func packName(_ p: String) -> String {
        switch p {
        case "system": L10n.t("System sound")
        case "none": L10n.t("No sound")
        default: p.capitalized
        }
    }
}
