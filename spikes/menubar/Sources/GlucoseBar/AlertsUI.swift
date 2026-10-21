// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import AppKit
import SwiftUI

/// The Alerts pane of the Settings window (SettingsUI.swift): the rules, the quiet times and the sounds.
@MainActor final class AlertsModel: ObservableObject {
    private let alerts: Alerts
    @Published var s: AlertSettings { didSet { if s != oldValue { alerts.update(s); refreshState() } } }
    @Published private(set) var state = ""
    @Published private(set) var following = false

    init(alerts: Alerts) {
        self.alerts = alerts
        s = alerts.settings
        refreshState()
        alerts.onChange = { [weak self] in self?.refreshState() }
    }

    func reload() { s = alerts.settings; refreshState() }

    func refreshState() {
        state = alerts.stateLine
        following = alerts.followingV2()
    }

    /// The wearer decides when (its own rules); this Mac only picks sounds and checks its own link (no_data).
    var mirroring: Bool { s.source == "wearer" && following }

    func preview(_ k: AlertKind, pack: String? = nil) { alerts.preview(k, pack: pack) }

    func rule(_ k: AlertKind) -> Binding<AlertRule> {
        Binding(get: { self.s[k] }, set: { self.s[k] = $0 })
    }

    func sound(_ k: AlertKind) -> Binding<String> {
        Binding(get: { self.s.sounds.kinds[k.rawValue] ?? "" },
                set: { self.s.sounds.kinds[k.rawValue] = $0.isEmpty ? nil : $0 })
    }
}

/// Laid out like macOS System Settings (e.g. Notifications, Wi-Fi): one row per alert with its name, a one-line
/// summary and a switch; a "Details…" button opens a sheet with the threshold, the repeat, the sound and "Like an
/// alarm". The main pane stays short and scannable (HIG "Settings": minimize what people have to read; "Sheets":
/// a focused task that returns to the parent).
struct AlertsView: View {
    @ObservedObject var model: AlertsModel
    @State private var editing: AlertKind?

    var body: some View {
        Form {
            Section {
                Toggle("Glucose alerts", isOn: $model.s.on)
                LabeledContent("Now") { Text(model.state).multilineTextAlignment(.trailing) }
            }
            Section {
                Picker("Alert source", selection: $model.s.source) {
                    Text("Same as the wearer").tag("wearer")
                    Text("This Mac's settings").tag("own")
                }
            } footer: { note(sourceNote) }
            .disabled(!model.s.on)
            group("Lows", [.veryLow, .low])
            group("Highs", [.high, .veryHigh, .extremelyHigh])
            group("Fast changes", [.fallingFast, .risingFast])
            Section {
                KindRow(model: model, kind: .noData, locked: false) { editing = .noData }
            } header: { Text("Signal") } footer: {
                if model.mirroring { note("Checked on this Mac too: the wearer's phone can't tell when this Mac stops hearing from it.") }
            }
            .disabled(!model.s.on)
            Section {
                Toggle("After rapid insulin", isOn: $model.s.muteAfterInsulin.on)
                MinutesRow(title: "Quiet for", value: $model.s.muteAfterInsulin.min, range: 30...180, step: 15)
                    .disabled(!model.s.muteAfterInsulin.on)
                Toggle("When falling back into range", isOn: $model.s.muteFallingHigh.on)
                MinutesRow(title: "Back under \(Int(model.s[.high].threshold)) mg/dL within", value: $model.s.muteFallingHigh.min, range: 5...60, step: 5)
                    .disabled(!model.s.muteFallingHigh.on)
            } header: { Text("Quiet highs") } footer: {
                note(model.mirroring ? lockedNote : "High, very high and rising fast only. Extremely high always alerts.")
            }
            .disabled(model.mirroring || !model.s.on)
            Section {
                Toggle("After carbs", isOn: $model.s.muteAfterCarbs.on)
                MinutesRow(title: "Quiet for", value: $model.s.muteAfterCarbs.min, range: 5...45, step: 5)
                    .disabled(!model.s.muteAfterCarbs.on)
            } header: { Text("Quiet lows") } footer: {
                note(model.mirroring ? lockedNote : "Very low, low and falling fast only.")
            }
            .disabled(model.mirroring || !model.s.on)
            // the alert notification's buttons: local on every device (mirroring, they quiet this Mac's copy only)
            Section {
                MinutesRow(title: "Mute for", value: minutes(\.muteMin), range: 5...240, step: 5)
                MinutesRow(title: "Slow down to every", value: minutes(\.slowMin), range: 5...120, step: 5)
            } header: { Text("From the notification") } footer: {
                note("Mute quiets the alert for these minutes (Mute for… takes typed minutes); Slow down repeats it at this interval until it's back in range. Very low and extremely high are never muted, only slowed.")
            }
            .disabled(!model.s.on)
            Section {
                Picker("Sound pack", selection: $model.s.sounds.pack) {
                    ForEach(AlertSettings.packs, id: \.self) { Text($0.capitalized).tag($0) }
                    Divider()
                    Text("System").tag("system")
                    Text("None").tag("none")
                }
                Toggle("Different sound each time", isOn: $model.s.sounds.shuffle)
            } header: { Text("Sounds") } footer: {
                note("An alert can use another pack in its details.")
            }
            .disabled(!model.s.on)
        }
        .formStyle(.grouped)
        .sheet(item: $editing) { k in
            KindDetails(model: model, kind: k, locked: model.mirroring && k != .noData) { editing = nil }
        }
    }

    private func group(_ title: String, _ kinds: [AlertKind]) -> some View {
        Section(title) {
            ForEach(kinds, id: \.self) { k in
                KindRow(model: model, kind: k, locked: model.mirroring) { editing = k }
            }
        }
        .disabled(!model.s.on)
    }

    private var lockedNote: String { "The wearer's phone decides when to alert; this Mac only picks the sounds." }

    private var sourceNote: String {
        if model.s.source == "own" { return "Alerts come from the rules below, run on this Mac with the readings it receives." }
        if !model.following { return "Not following anyone, so this Mac's settings are used." }
        return "Alerts when the wearer's phone does, with this Mac's sounds. " + lockedNote
    }

    private func note(_ s: String) -> some View { Text(s).font(.callout).foregroundStyle(.secondary) }
}

extension AlertKind: Identifiable { public var id: String { rawValue } }

/// "Below 55 mg/dL · repeats every 5 min · Quest · like an alarm".
@MainActor private func summary(_ model: AlertsModel, _ kind: AlertKind) -> String {
    let r = model.s[kind]
    let t = r.threshold
    let when: String = switch kind.group {
    case .level: (kind.lowSide ? "Below " : "From ") + "\(Int(t)) mg/dL"
    case .rate: "Faster than " + String(format: "%.1f", t) + " mg/dL/min"
    case .noData: "After \(Int(t)) min without data"
    }
    let rep = r.repeatMin == 0 ? "once" : "repeats every \(r.repeatMin) min"
    let pack = model.s.pack(for: kind)
    return [when, rep, pack == "none" ? "silent" : pack.capitalized, r.alarm ? "like an alarm" : nil]
        .compactMap { $0 }.joined(separator: " · ")
}

/// One alert: name, summary, Details…, switch.
private struct KindRow: View {
    @ObservedObject var model: AlertsModel
    let kind: AlertKind
    let locked: Bool
    let details: () -> Void

    var body: some View {
        HStack(spacing: 10) {
            VStack(alignment: .leading, spacing: 2) {
                Text(kind.title)
                Text(summary(model, kind)).font(.callout).foregroundStyle(.secondary)
            }
            Spacer()
            Button("Details…", action: details)
                .controlSize(.small)
                .accessibilityLabel("\(kind.title) alert details")
            Toggle(kind.title, isOn: model.rule(kind).on).labelsHidden().toggleStyle(.switch).disabled(locked)
        }
        .accessibilityElement(children: .contain)
    }
}

extension AlertsView {
    /// A notification-action minutes field as the Double MinutesRow edits.
    fileprivate func minutes(_ k: WritableKeyPath<AlertActions, Int>) -> Binding<Double> {
        Binding(get: { Double(model.s.notificationActions[keyPath: k]) }, set: { model.s.notificationActions[keyPath: k] = Int($0) })
    }
}

/// "Quiet for   [60] ⌃⌄ min".
private struct MinutesRow: View {
    let title: String
    @Binding var value: Double
    let range: ClosedRange<Double>
    let step: Double

    var body: some View {
        LabeledContent(title) {
            HStack(spacing: 4) {
                Text("\(Int(value))").monospacedDigit()
                Stepper(title, value: $value, in: range, step: step).labelsHidden()
                Text("min").foregroundStyle(.secondary)
            }
        }
        .padding(.leading, 16)
    }
}

/// The sheet behind "Details…".
private struct KindDetails: View {
    @ObservedObject var model: AlertsModel
    let kind: AlertKind
    let locked: Bool
    let done: () -> Void

    var body: some View {
        let rule = model.rule(kind)
        VStack(spacing: 0) {
            Form {
                Section {
                    Toggle("\(kind.title) alert", isOn: rule.on).disabled(locked)
                } footer: {
                    if locked { note("Set by the wearer's phone.") }
                }
                Section("Alert when") {
                    LabeledContent(thresholdLabel) {
                        HStack(spacing: 4) {
                            TextField(thresholdLabel, value: rule.threshold, format: .number.precision(.fractionLength(kind.group == .rate ? 1 : 0)))
                                .labelsHidden().textFieldStyle(.roundedBorder).frame(width: 56).multilineTextAlignment(.trailing)
                            Stepper(thresholdLabel, value: rule.threshold, in: range, step: step).labelsHidden()
                            Text(unitLabel).foregroundStyle(.secondary)
                        }
                    }
                    LabeledContent("Repeat every") {
                        HStack(spacing: 4) {
                            TextField("Repeat every", value: rule.repeatMin, format: .number)
                                .labelsHidden().textFieldStyle(.roundedBorder).frame(width: 56).multilineTextAlignment(.trailing)
                            Stepper("Repeat every", value: rule.repeatMin, in: 0...240, step: 5).labelsHidden()
                            Text("min").foregroundStyle(.secondary)
                        }
                    }
                }
                .disabled(locked || !rule.wrappedValue.on)
                Section {
                    LabeledContent("Sound") {
                        HStack(spacing: 6) {
                            Picker("Sound", selection: model.sound(kind)) {
                                Text("Pack (\(model.s.sounds.pack.capitalized))").tag("")
                                ForEach(AlertSettings.packs, id: \.self) { Text($0.capitalized).tag($0) }
                                Divider()
                                Text("System").tag("system")
                                Text("None").tag("none")
                            }
                            .labelsHidden()
                            .fixedSize()
                            Button { model.preview(kind) } label: { Image(systemName: "play.fill") }
                                .help("Play a random variant")
                                .accessibilityLabel("Play")
                                .disabled(model.s.pack(for: kind) == "none")
                        }
                    }
                    // the phone's "Alarm volume"; macOS has no alarm volume, its nearest is Time Sensitive
                    Toggle("Like an alarm", isOn: rule.alarm)
                } header: { Text("Sound") } footer: {
                    note(Alerts.timeSensitive ? "Time Sensitive: shows and plays even during a Focus."
                         : "Shows and plays during a Focus only in a build signed with the Time Sensitive permission; this one sends a normal notification.")
                }
                .disabled(!rule.wrappedValue.on)
            }
            .formStyle(.grouped)
            HStack {
                Spacer()
                Button("Done", action: done).keyboardShortcut(.defaultAction)
            }
            .padding([.horizontal, .bottom], 20)
        }
        .frame(width: 460, height: 430)
    }

    private func note(_ s: String) -> some View { Text(s).font(.callout).foregroundStyle(.secondary) }
    private var thresholdLabel: String {
        switch kind.group { case .level: kind.lowSide ? "Below" : "At or above"; case .rate: "Faster than"; case .noData: "After" }
    }
    private var unitLabel: String {
        switch kind.group { case .level: "mg/dL"; case .rate: "mg/dL/min"; case .noData: "min without data" }
    }
    private var range: ClosedRange<Double> {
        switch kind {
        case .veryLow: 40...80
        case .low: 50...100
        case .high: 120...300
        case .veryHigh: 150...400
        case .extremelyHigh: 200...450
        case .fallingFast, .risingFast: 0.5...5
        case .noData: 5...120
        }
    }
    private var step: Double { kind.group == .rate ? 0.5 : 5 }
}

/// Evidence aid (`-settingsSnapshot`): the Details sheet for one kind, outside its sheet.
@MainActor func alertDetailsPreview(_ model: AlertsModel, _ kind: AlertKind) -> some View {
    KindDetails(model: model, kind: kind, locked: false) {}
}
