// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import AppKit
import SukraraAlerts
import SukraraCore
import SwiftUI

/// Alerts: alerts on/off; each kind as a row with its summary and Details… (threshold, repeat, its sound with ▶, Like
/// an alarm, Send Test Alert); the quiet times; what the notification's buttons offer; the sound pack with ▶ and
/// Different sound each time; Notification Settings…; and what is quiet right now with its ways out. The mirrored
/// rules (each kind's on / threshold / repeat but no data's, the quiet times) are read-only outside Custom; what
/// isn't mirrored (alerts on/off, no data, sounds, Like an alarm, the notification's buttons) is this Mac's.
struct AlertsRangesPane: View {
    @Bindable var model: SettingsModel
    /// The running alert rules (Send Test Alert, Active now); nil in an offline run.
    var controller: AlertController? = nil
    @State private var editing: AlertKind?

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
                    kindRow(kind)
                }
            }
            if let controller { ActiveNowSection(alerts: controller) }
            Section(L10n.t("Quiet times")) {
                quiet(L10n.t("Quiet after insulin"), \.muteAfterInsulin, "alerts.muteAfterInsulin.min")
                quiet(L10n.t("Quiet while a high falls"), \.muteFallingHigh, "alerts.muteFallingHigh.min")
                quiet(L10n.t("Quiet after carbs"), \.muteAfterCarbs, "alerts.muteAfterCarbs.min")
            }
            Section {
                NumberSettingRow(
                    label: L10n.t("Mute first offers"), format: SettingsSpec.actionMute,
                    value: Binding(
                        get: { Double(model.alerts.settings.notificationActions.muteMin) },
                        set: { v in model.alerts.update { $0.notificationActions.muteMin = Int(v) } }))
                Picker(
                    L10n.t("Remind less: every"),
                    selection: Binding(
                        get: { model.alerts.settings.notificationActions.relaxMin },
                        set: { v in model.alerts.update { $0.notificationActions.relaxMin = v } })
                ) {
                    let choices = model.alerts.settings.relaxChoices
                    let current = model.alerts.settings.notificationActions.relaxMin
                    ForEach(choices.contains(current) ? choices : [current] + choices, id: \.self) {
                        Text(Durations.text($0)).tag($0)
                    }
                }
                NumberSettingRow(
                    label: L10n.t("Back in range by"), format: SettingsSpec.backMgdl,
                    value: Binding(
                        get: { Double(model.alerts.settings.notificationActions.backMgdl) },
                        set: { v in model.alerts.update { $0.notificationActions.backMgdl = Int(v) } }))
                NumberSettingRow(
                    label: L10n.t("For at least"), format: SettingsSpec.backMin,
                    value: Binding(
                        get: { Double(model.alerts.settings.notificationActions.backMin) },
                        set: { v in model.alerts.update { $0.notificationActions.backMin = Int(v) } }))
            } header: {
                Text(L10n.t("From the notification"))
            } footer: {
                Text(
                    L10n.t(
                        "Mute and Remind less end once the value is back inside the threshold by this margin for this long."
                    )
                )
                .font(.callout).foregroundStyle(.secondary)
            }
            Section(L10n.t("Sounds")) {
                HStack {
                    Picker(L10n.t("Sound pack"), selection: alertBinding(\.sounds.pack)) {
                        ForEach(SettingsSpec.options["soundPack"] ?? [], id: \.self) { Text(Self.packName($0)).tag($0) }
                    }
                    PreviewButton(pack: model.alerts.settings.sounds.pack, kind: .low)
                }
                Toggle(L10n.t("Different sound each time"), isOn: alertBinding(\.sounds.shuffle))
                HStack {
                    Text(L10n.t("Banners, Focus and the lock screen are in System Settings."))
                        .font(.callout).foregroundStyle(.secondary)
                    Spacer()
                    Button(L10n.t("Notification Settings…")) { Self.openNotificationSettings() }
                }
            }
        }
        .formStyle(.grouped)
        .sheet(item: $editing) { kind in
            AlertDetails(
                model: model, kind: kind, controller: controller,
                readOnly: model.alertsMirrored && kind != .noData
            ) { editing = nil }
        }
    }

    /// One alert: its switch, a summary of what applies, and Details….
    private func kindRow(_ kind: AlertKind) -> some View {
        let readOnly = model.alertsMirrored && kind != .noData
        let shown = model.shownAlerts[kind]
        return HStack(spacing: 10) {
            VStack(alignment: .leading, spacing: 2) {
                Text(Self.kindName(kind))
                Text(Self.summary(kind, model.shownAlerts, unit: model.unit)).font(.callout).foregroundStyle(.secondary)
            }
            Spacer()
            Button(L10n.t("Details…")) { editing = kind }
                .controlSize(.small)
                .accessibilityLabel(L10n.t("\(Self.kindName(kind)) alert details"))
            Toggle(
                Self.kindName(kind),
                isOn: Binding(get: { shown.on }, set: { v in model.alerts.update { $0[kind].on = v } })
            )
            .labelsHidden()
            .toggleStyle(.switch)
            .disabled(readOnly)
        }
        .accessibilityElement(children: .contain)
    }

    /// "Below 70 mg/dL · every 5 min · Quest · like an alarm" (what applies).
    static func summary(_ kind: AlertKind, _ s: AlertSettings, unit: GlucoseUnit) -> String {
        let r = s[kind]
        let when: String =
            switch kind.group {
            case .level:
                kind.isBelowThreshold
                    ? L10n.t("Below \(unit.format(Int(r.threshold))) \(unit.symbol)")
                    : L10n.t("From \(unit.format(Int(r.threshold))) \(unit.symbol)")
            case .rate: L10n.t("Faster than \(String(format: "%.1f", r.threshold)) mg/dL/min")
            case .noData: L10n.t("After \(Durations.text(Int(r.threshold))) without data")
            }
        let repeats = r.repeatMin == 0 ? L10n.t("once") : L10n.t("every \(Durations.text(r.repeatMin))")
        let pack = s.soundPack(for: kind)
        return [
            r.on ? nil : L10n.t("Off"), when, repeats, pack == "none" ? L10n.t("silent") : packName(pack),
            r.alarm ? L10n.t("like an alarm") : nil,
        ]
        .compactMap { $0 }
        .joined(separator: " · ")
    }

    static func openNotificationSettings() {
        let id = Bundle.main.bundleIdentifier ?? SukraraIDs.appBundleID
        if let url = URL(string: "x-apple.systempreferences:com.apple.Notifications-Settings.extension?id=\(id)") {
            NSWorkspace.shared.open(url)
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

/// The sheet behind Details…: when it alerts (threshold, repeat; read-only while the wearer's rules apply), its sound
/// with ▶, Like an alarm, and Send Test Alert.
private struct AlertDetails: View {
    @Bindable var model: SettingsModel
    let kind: AlertKind
    let controller: AlertController?
    let readOnly: Bool
    let done: () -> Void
    @State private var sent = false

    var body: some View {
        let shown = model.shownAlerts[kind]
        let name = AlertsRangesPane.kindName(kind)
        VStack(spacing: 0) {
            Form {
                Section {
                    Toggle(
                        L10n.t("\(name) alert"),
                        isOn: Binding(get: { shown.on }, set: { v in model.alerts.update { $0[kind].on = v } })
                    )
                    .disabled(readOnly)
                    let id = "alerts.threshold.\(kind.rawValue)"
                    if let number = SettingsSpec.settings.first(where: { $0.id == id })?.number,
                        let format = SettingsSpec.numbers[number]
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
                            get: { Double(shown.repeatMin) },
                            set: { v in model.alerts.update { $0[kind].repeatMin = Int(v) } }),
                        readOnly: readOnly)
                } footer: {
                    if readOnly {
                        Text(
                            L10n.t("When it alerts follows the wearer's rules. Choose Custom to change it on this Mac.")
                        )
                        .font(.callout).foregroundStyle(.secondary)
                    }
                }
                Section {
                    HStack {
                        Picker(
                            L10n.t("Sound"),
                            selection: Binding(
                                get: { model.alerts.settings.sounds.kinds[kind.rawValue] ?? "" },
                                set: { v in model.alerts.update { $0.sounds.kinds[kind.rawValue] = v.isEmpty ? nil : v }
                                })
                        ) {
                            Text(L10n.t("Same as the pack")).tag("")
                            ForEach(SettingsSpec.options["soundPack"] ?? [], id: \.self) {
                                Text(AlertsRangesPane.packName($0)).tag($0)
                            }
                        }
                        PreviewButton(pack: model.alerts.settings.soundPack(for: kind), kind: kind)
                    }
                    Toggle(
                        L10n.t("Like an alarm"),
                        isOn: Binding(
                            get: { model.alerts.settings[kind].alarm },
                            set: { v in model.alerts.update { $0[kind].alarm = v } }))
                    if let controller {
                        HStack {
                            if sent {
                                Label(L10n.t("Sent"), systemImage: "checkmark.circle").foregroundStyle(.secondary)
                            }
                            Spacer()
                            Button(L10n.t("Send Test Alert")) {
                                controller.sendTest(kind)
                                sent = true
                            }
                        }
                    }
                } header: {
                    Text(L10n.t("Sound"))
                } footer: {
                    Text(
                        L10n.t(
                            "Like an alarm: Time Sensitive, so it shows and plays during a Focus when Sukrara is allowed to. The test alert is a real notification titled “Test”; nothing is muted or logged."
                        )
                    )
                    .font(.callout).foregroundStyle(.secondary)
                }
            }
            .formStyle(.grouped)
            HStack {
                Spacer()
                Button(L10n.t("Done"), action: done).keyboardShortcut(.defaultAction)
            }
            .padding([.horizontal, .bottom], 20)
        }
        .frame(width: 480, height: 520)
    }
}

/// ▶: plays `pack`'s sound for `kind` once (the bundled file; the system sound for "system"; nothing for "none").
struct PreviewButton: View {
    let pack: String
    let kind: AlertKind

    var body: some View {
        Button {
            Self.play(pack, kind)
        } label: {
            Image(systemName: "play.fill")
        }
        .buttonStyle(.borderless)
        .disabled(pack == "none")
        .help(L10n.t("Play"))
        .accessibilityLabel(L10n.t("Play \(AlertsRangesPane.packName(pack))"))
    }

    static func play(_ pack: String, _ kind: AlertKind) {
        if pack == "system" {
            NSSound.beep()
            return
        }
        guard AlertSoundSettings.packs.contains(pack),
            let url = Bundle.main.resourceURL?.appending(path: "Sounds/\(pack)_\(kind.rawValue)_1.caf"),
            let sound = NSSound(contentsOf: url, byReference: true)
        else {
            NSSound.beep()
            return
        }
        sound.play()
    }
}

/// What is quiet right now, each with its ways out (the panel's blocks): Mute all, each side's mute, Remind less.
private struct ActiveNowSection: View {
    let alerts: AlertController

    var body: some View {
        let ui = alerts.ui
        let now = Date()
        if ui.muteAllUntil != nil || !ui.quiets.isEmpty {
            Section(L10n.t("Active now")) {
                if let until = ui.muteAllUntil, let line = MuteCopy.allLine(until: until, now: now) {
                    HStack {
                        Label(line, systemImage: "bell.slash.fill")
                        Spacer()
                        Button(L10n.t("Unmute")) { alerts.perform(.unmuteAll) }
                    }
                }
                ForEach(ui.quiets, id: \.kind) { q in
                    VStack(alignment: .leading, spacing: 4) {
                        if let reason = q.quiet, let line = MuteCopy.line(reason, side: q.side, now: now) {
                            Text(line)
                        }
                        if q.relaxMin > 0 { Text(MuteCopy.sideLess(q.side, intervalMin: q.relaxMin)) }
                        HStack { QuietMenu(alerts: alerts, side: q.side, now: now) }
                            .controlSize(.small)
                    }
                }
            }
        }
    }
}

extension AlertKind: Identifiable {
    public var id: String { rawValue }
}
