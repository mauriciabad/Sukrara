// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import SukraraAlerts
import SukraraCore
import SwiftUI

/// Alerts + ranges: alerts on/off, per kind on/off + threshold + repeat, quiet times, sound pack, range bands.
/// Mirrored rows are read-only and labelled "From <wearer>" with the exits "Use my own settings" / "Defaults".
struct AlertsRangesPane: View {
    @Bindable var model: SettingsModel

    var body: some View {
        Form {
            if model.alerts.readError {
                Section {
                    Label(L10n.t("Alert settings couldn't be read. Sukrara keeps using the last ones it read."),
                        systemImage: "exclamationmark.triangle.fill")
                        .foregroundStyle(.red)
                    Button(L10n.t("Reset alert settings")) { model.alerts.resetToDefaults() }
                }
            }
            MirroredSection(model: model)
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
            Section(L10n.t("Ranges (mg/dL)")) {
                ForEach(MacSettings.bands, id: \.id) { band in
                    NumberSettingRow(
                        label: Self.bandName(band.id), format: band.format,
                        value: Binding(get: { model.number(band) }, set: { model.setNumber($0, band) }),
                        readOnly: model.mirrors)
                }
            }
        }
        .formStyle(.grouped)
    }

    @ViewBuilder private func kindRows(_ kind: AlertKind) -> some View {
        let id = "alerts.threshold.\(kind.rawValue)"
        let mirrored = model.mirrors && kind != .noData
        DisclosureGroup {
            if let name = SettingsSpec.settings.first(where: { $0.id == id })?.number,
                let format = SettingsSpec.numbers[name]
            {
                NumberSettingRow(
                    label: L10n.t("Threshold"), format: format,
                    value: Binding(
                        get: { (mirrored ? model.wearer?.number(id) : nil) ?? model.alerts.settings[kind].threshold },
                        set: { v in model.alerts.update { $0[kind].threshold = v } }),
                    readOnly: mirrored)
            }
            NumberSettingRow(
                label: L10n.t("Repeat"), format: SettingsSpec.alertRepeat,
                value: Binding(
                    get: {
                        (mirrored ? model.wearerNumber("alerts.kind.repeat", kind: kind) : nil)
                            ?? Double(model.alerts.settings[kind].repeatMin)
                    },
                    set: { v in model.alerts.update { $0[kind].repeatMin = Int(v) } }),
                readOnly: mirrored)
        } label: {
            Toggle(Self.kindName(kind), isOn: Binding(
                get: {
                    (mirrored ? model.wearer?.bool("alerts.kind.on", kind: kind.rawValue) : nil)
                        ?? model.alerts.settings[kind].on
                },
                set: { v in model.alerts.update { $0[kind].on = v } }))
            .disabled(mirrored)
        }
    }

    private func quiet(_ label: String, _ path: WritableKeyPath<AlertSettings, QuietRule>, _ id: String) -> some View {
        let name = SettingsSpec.settings.first { $0.id == id }?.number ?? ""
        let onId = id.replacingOccurrences(of: ".min", with: ".on")
        let isOn = model.wearerBool(onId) ?? model.alerts.settings[keyPath: path].on
        let on = Binding(get: { isOn }, set: { v in model.alerts.update { $0[keyPath: path].on = v } })
        let minutes = Binding(
            get: { model.wearerNumber(id) ?? model.alerts.settings[keyPath: path].min },
            set: { v in model.alerts.update { $0[keyPath: path].min = v } })
        return VStack(alignment: .leading) {
            Toggle(label, isOn: on).disabled(model.mirrors)
            if let format = SettingsSpec.numbers[name], isOn {
                NumberSettingRow(label: L10n.t("For"), format: format, value: minutes, readOnly: model.mirrors)
            }
        }
    }

    private func alertBinding<V>(_ path: WritableKeyPath<AlertSettings, V>) -> Binding<V> {
        Binding(get: { model.alerts.settings[keyPath: path] }, set: { v in model.alerts.update { $0[keyPath: path] = v } })
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

    static func packName(_ p: String) -> String {
        switch p {
        case "system": L10n.t("System sound")
        case "none": L10n.t("No sound")
        default: p.capitalized
        }
    }
}

/// "Settings from": Wearer (read-only rows, "From <wearer>"), Default or Custom; the exits from mirroring.
struct MirroredSection: View {
    @Bindable var model: SettingsModel

    var body: some View {
        if model.wearer != nil {
            Section {
                if model.mirrors {
                    Label(L10n.t("From \(model.wearer?.wearer ?? L10n.t("the wearer")): these follow their phone"),
                        systemImage: "iphone")
                    HStack {
                        Button(L10n.t("Use my own settings")) { model.useOwn() }
                        Button(L10n.t("Defaults…")) { model.confirmingDefaults = true }
                            .confirmationDialog(
                                L10n.t("Use the default alerts and ranges?"), isPresented: $model.confirmingDefaults
                            ) {
                                Button(L10n.t("Use defaults")) { model.useDefaults() }
                                Button(L10n.t("Cancel"), role: .cancel) {}
                            } message: {
                                Text(L10n.t("This Mac's own thresholds and ranges are replaced by the defaults."))
                            }
                    }
                } else {
                    Button(L10n.t("Use \(model.wearer?.wearer ?? L10n.t("the wearer"))'s settings")) {
                        model.settingsFrom = .wearer
                    }
                }
            }
        }
    }
}
