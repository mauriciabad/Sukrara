// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import AppKit
import SwiftUI

/// The Alerts pane of the Settings window (SettingsUI.swift): the rules, the quiet times and the sounds.
/// `s` is what this Mac stores (and edits); `eff` what applies (Settings from: the mirrored rules may be the wearer's
/// or the defaults).
@MainActor final class AlertsModel: ObservableObject {
    private let alerts: Alerts
    @Published var s: AlertSettings { didSet { if s != oldValue { alerts.update(s); eff = alerts.settings; refreshState() } } }
    @Published private(set) var eff: AlertSettings
    @Published private(set) var state = ""
    /// Active now: the quiet alerts on this Mac with their ways out (round f29).
    @Published private(set) var quiet: [Alerts.Active] = []
    /// Mute all's end on this Mac (unix s, 0 = off; `AlertStatus.never` = until turned off): the line ticks down.
    @Published private(set) var allMuted: TimeInterval = 0
    @Published private(set) var following = false

    init(alerts: Alerts) {
        self.alerts = alerts
        s = alerts.own
        eff = alerts.settings
        refreshState()
        alerts.onChange = { [weak self] in self?.refreshState() }
    }

    func reload() { s = alerts.own; eff = alerts.settings; refreshState() }

    func perform(_ op: QuietOp) { alerts.perform(op); refreshState() }

    /// The Now row's line at `t` (its Mute all part counts down).
    func stateLine() -> String { alerts.stateLine }

    func refreshState() {
        state = alerts.stateLine
        quiet = alerts.active
        allMuted = alerts.allMuted
        following = alerts.followingV2()
    }

    /// The wearer decides when (their own rules); this Mac only picks sounds and checks its own link (no_data).
    var mirroring: Bool { eff.source == "wearer" && following }

    func preview(_ k: AlertKind, pack: String? = nil) { alerts.preview(k, pack: pack) }
    /// A real notification with its sound, titled "Test ·" (Android: Send a test alert).
    func test(_ k: AlertKind) { alerts.test(k.rawValue) }

    func rule(_ k: AlertKind) -> Binding<AlertRule> {
        Binding(get: { self.s[k] }, set: { self.s[k] = $0 })
    }
    func threshold(_ k: AlertKind) -> Binding<Double> {
        Binding(get: { self.s[k].threshold }, set: { self.s[k].threshold = $0 })
    }
    func repeatMin(_ k: AlertKind) -> Binding<Double> {
        Binding(get: { Double(self.s[k].repeatMin) }, set: { self.s[k].repeatMin = Int($0) })
    }
    var muteMin: Binding<Double> {
        Binding(get: { Double(self.s.notificationActions.muteMin) }, set: { self.s.notificationActions.muteMin = Int($0) })
    }
    var relaxMin: Binding<Double> {
        Binding(get: { Double(self.s.notificationActions.relaxMin) }, set: { self.s.notificationActions.relaxMin = Int($0) })
    }
    var backMgdl: Binding<Double> {
        Binding(get: { Double(self.s.notificationActions.backMgdl) }, set: { self.s.notificationActions.backMgdl = Int($0) })
    }
    var backMin: Binding<Double> {
        Binding(get: { Double(self.s.notificationActions.backMin) }, set: { self.s.notificationActions.backMin = Int($0) })
    }

    func sound(_ k: AlertKind) -> Binding<String> {
        Binding(get: { self.s.sounds.kinds[k.rawValue] ?? "" },
                set: { self.s.sounds.kinds[k.rawValue] = $0.isEmpty ? nil : $0 })
    }

    /// The quiet rules in use, for Default / Wearer (MirroredSection).
    var quietSummary: [(String, String)] {
        func q(_ m: AlertMute) -> String { m.on ? Durations.text(Int(m.min)) : "Off" }
        return [("Quiet highs after rapid insulin", q(eff.muteAfterInsulin)),
                ("Quiet highs falling back into range", q(eff.muteFallingHigh)),
                ("Quiet lows after carbs", q(eff.muteAfterCarbs))]
    }
}

/// Laid out like macOS System Settings (e.g. Notifications, Wi-Fi): one row per alert with its name, a one-line
/// summary and (in Custom) a switch; a "Details…" button opens a sheet with the threshold, the repeat, the sound and
/// "Like an alarm". The main pane stays short and scannable (HIG "Settings": "Minimize the number of settings you
/// offer"; "Sheets": a focused task that returns to the parent). In Default / Wearer the rules (on, threshold, repeat,
/// quiet times) aren't editable here: a read-only summary says what applies; sounds, like an alarm, no data and the
/// notification's buttons stay this Mac's in every mode.
struct AlertsView: View {
    @ObservedObject var model: AlertsModel
    @ObservedObject var from: SettingsFromModel
    @State private var editing: AlertKind?

    var body: some View {
        Form {
            if !model.quiet.isEmpty || model.allMuted > Date().timeIntervalSince1970 {
                // Active now (round f29, f30): Mute all and every muted / quiet / relaxed alert on this Mac with its
                // ways out, reachable without the notification
                Section("Active now") {
                    if model.allMuted > Date().timeIntervalSince1970 {
                        LabeledContent {
                            HStack {
                                Button("Unmute") { model.perform(.unmuteAll) }.accessibilityLabel("Alerts: Unmute")
                                Menu("Change Time") {
                                    ForEach(muteAllChoices, id: \.1) { c in Button(c.0) { model.perform(.muteAll(c.1)) } }
                                }
                                .fixedSize()
                                .accessibilityLabel("Alerts: Change time")
                            }
                        } label: {
                            Countdown(until: Date(timeIntervalSince1970: model.allMuted)) { t in
                                Text(MuteCopy.allLine(until: model.allMuted, now: t.timeIntervalSince1970) ?? "")
                            }
                        }
                    }
                    ForEach(model.quiet, id: \.kind) { q in
                        LabeledContent {
                            HStack {
                                if let l = q.lift { Button(l) { model.perform(.unmute(q.kind)) } }
                                if q.relaxMin > 0 { Button("Remind as Usual") { model.perform(.unrelax(q.kind)) } }
                                else if q.canRelax { Button("Remind Less") { model.perform(.relax(q.kind)) } }
                                Menu(quietMenuTitle(q)) {
                                    ForEach(quietChoices, id: \.1) { c in Button(c.0) { model.perform(.mute(q.kind, c.1)) } }
                                }
                                .fixedSize()
                                .accessibilityLabel(quietMenuLabel(q))
                            }
                        } label: {
                            Countdown(until: q.ticks ? q.until : nil) { t in Text(q.title(now: t)) }
                            if q.quiet != nil, q.relaxMin > 0 { Text(q.relaxedLine) }
                        }
                    }
                }
            }
            Section {
                Toggle("Glucose alerts", isOn: $model.s.on)
                LabeledContent("Now") {
                    // its Mute all part counts down
                    Countdown(until: model.allMuted > 0 ? Date(timeIntervalSince1970: model.allMuted) : nil) { _ in
                        Text(model.stateLine()).multilineTextAlignment(.trailing)
                    }
                }
            } footer: {
                if model.eff.source == "wearer" { note(model.following ? lockedNote : "Not following anyone over Sukrara's sharing, so the rules run on this Mac.") }
            }
            if !from.custom {
                MirroredSection(from: from, rows: model.quietSummary).disabled(!model.s.on)
            }
            group("Lows", [.veryLow, .low])
            group("Highs", [.high, .veryHigh, .extremelyHigh])
            group("Fast changes", [.fallingFast, .risingFast])
            Section {
                KindRow(model: model, kind: .noData, custom: true) { editing = .noData }
            } header: { Text("Signal") } footer: {
                note("Always this Mac's: the wearer's phone can't tell when this Mac stops hearing from it.")
            }
            .disabled(!model.s.on)
            if from.custom {
                Section {
                    Toggle("After rapid insulin", isOn: $model.s.muteAfterInsulin.on)
                    NumberSettingRow(title: "Quiet for", spec: SettingsSpec.muteInsulin, value: $model.s.muteAfterInsulin.min)
                        .padding(.leading, 16).disabled(!model.s.muteAfterInsulin.on)
                    Toggle("When falling back into range", isOn: $model.s.muteFallingHigh.on)
                    NumberSettingRow(title: "Back under \(Int(model.s[.high].threshold)) mg/dL within", spec: SettingsSpec.muteFalling,
                                     value: $model.s.muteFallingHigh.min)
                        .padding(.leading, 16).disabled(!model.s.muteFallingHigh.on)
                } header: { Text("Quiet highs") } footer: {
                    note("High, very high and rising fast only. Extremely high always alerts.")
                }
                .disabled(!model.s.on)
                Section {
                    Toggle("After carbs", isOn: $model.s.muteAfterCarbs.on)
                    NumberSettingRow(title: "Quiet for", spec: SettingsSpec.muteCarbs, value: $model.s.muteAfterCarbs.min)
                        .padding(.leading, 16).disabled(!model.s.muteAfterCarbs.on)
                } header: { Text("Quiet lows") } footer: {
                    note("Very low, low and falling fast only.")
                }
                .disabled(!model.s.on)
            }
            // the alert notification's buttons: local on every device (mirroring, they quiet this Mac's copy only)
            Section {
                NumberSettingRow(title: "Mute: suggested time", spec: SettingsSpec.actionMute, value: model.muteMin)
                NumberSettingRow(title: "Remind Less: every", spec: SettingsSpec.actionRelax, value: model.relaxMin,
                                 presets: model.eff.relaxChoices.map(Double.init), customMin: Double(model.eff.relaxFloor + 1))
                NumberSettingRow(title: "Mute and Remind Less end back in range by", spec: SettingsSpec.backMgdl, value: model.backMgdl)
                NumberSettingRow(title: "… for at least", spec: SettingsSpec.backMin, value: model.backMin)
            } header: { Text("From the notification") } footer: {
                note("Mute and Remind Less act on one side, lows or highs, on this Mac only.")
            }
            .disabled(!model.s.on)
            Section {
                // Android plays the pack's high-glucose sound in its pack dialog
                SoundPickerButton(title: "Sound pack", kind: .high, selection: $model.s.sounds.pack, packDefault: nil,
                                  onPlay: { model.preview(.high, pack: $0) })
                Toggle("Different sound each time", isOn: $model.s.sounds.shuffle)
                if let id = Bundle.main.bundleIdentifier,
                   let url = URL(string: "x-apple.systempreferences:com.apple.Notifications-Settings.extension?id=\(id)") {
                    HStack { Spacer(); Button("Notification Settings…") { NSWorkspace.shared.open(url) } }
                }
            } header: { Text("Sounds") } footer: {
                note("An alert can use another pack in its details. Banners, Focus and the lock screen: Notification Settings.")
            }
            .disabled(!model.s.on)
        }
        .formStyle(.grouped)
        .sheet(item: $editing) { k in
            KindDetails(model: model, from: from, kind: k, custom: from.custom || k == .noData) { editing = nil }
        }
    }

    private func group(_ title: String, _ kinds: [AlertKind]) -> some View {
        Section(title) {
            ForEach(kinds, id: \.self) { k in
                KindRow(model: model, kind: k, custom: from.custom) { editing = k }
            }
        }
        .disabled(!model.s.on)
    }

    private var lockedNote: String { "Alerts when the wearer's phone does; this Mac picks the sounds and checks its own signal." }

    private func note(_ s: String) -> some View { Text(s).font(.callout).foregroundStyle(.secondary) }
}

extension AlertKind: Identifiable { public var id: String { rawValue } }

/// "Below 55 mg/dL · repeats every 5 min · Quest · like an alarm" (what applies: `eff`).
@MainActor private func summary(_ model: AlertsModel, _ kind: AlertKind) -> String {
    let r = model.eff[kind]
    let t = r.threshold
    let when: String = switch kind.group {
    case .level: (kind.lowSide ? "Below " : "From ") + "\(Int(t)) mg/dL"
    case .rate: "Faster than " + String(format: "%.1f", t) + " mg/dL/min"
    case .noData: "After \(Durations.text(Int(t))) without data"
    }
    let rep = r.repeatMin == 0 ? "once" : "repeats every \(Durations.text(r.repeatMin))"
    let pack = model.eff.pack(for: kind)
    return [r.on ? nil : "Off", when, rep, pack == "none" ? "silent" : pack.capitalized, r.alarm ? "like an alarm" : nil]
        .compactMap { $0 }.joined(separator: " · ")
}

/// One alert: name, summary, Details…, and its switch where this Mac decides (Custom, or no data).
private struct KindRow: View {
    @ObservedObject var model: AlertsModel
    let kind: AlertKind
    let custom: Bool
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
            if custom { Toggle(kind.title, isOn: model.rule(kind).on).labelsHidden().toggleStyle(.switch) }
        }
        .accessibilityElement(children: .contain)
    }
}

/// The sheet behind "Details…".
private struct KindDetails: View {
    @ObservedObject var model: AlertsModel
    @ObservedObject var from: SettingsFromModel
    let kind: AlertKind
    /// This Mac decides when it alerts (Custom, or no data); otherwise only the sound and like an alarm.
    let custom: Bool
    let done: () -> Void

    var body: some View {
        let rule = model.rule(kind)
        VStack(spacing: 0) {
            Form {
                if custom {
                    Section {
                        Toggle("\(kind.title) alert", isOn: rule.on)
                    }
                    Section("Alert when") {
                        NumberSettingRow(title: thresholdLabel, spec: kind.thresholdSpec, value: model.threshold(kind))
                        NumberSettingRow(title: "Repeat", spec: SettingsSpec.alertRepeat, value: model.repeatMin(kind),
                                         label: { $0 == 0 ? NumberFormat.word("once") : "Every \(Durations.text(Int($0)))" })
                    }
                    .disabled(!rule.wrappedValue.on)
                } else {
                    Section {
                        LabeledContent("\(kind.title) alert", value: summary(model, kind))
                    } footer: {
                        note("When it alerts: \(from.modeName). To change it on this Mac, choose Custom in General.")
                    }
                }
                Section {
                    SoundPickerButton(title: "Sound", kind: kind, selection: model.sound(kind), packDefault: model.s.sounds.pack,
                                      onPlay: { model.preview(kind, pack: $0) })
                    // the phone's "Alarm volume"; macOS has no alarm volume, its nearest is Time Sensitive
                    Toggle("Like an alarm", isOn: rule.alarm)
                    HStack { Spacer(); Button("Send Test Alert") { model.test(kind) } }
                } header: { Text("Sound") } footer: {
                    note((Alerts.timeSensitive ? "Time Sensitive: shows and plays even during a Focus."
                          : "Shows and plays during a Focus only in a build signed with the Time Sensitive permission; this one sends a normal notification.")
                         + " The test alert is a real notification titled \"Test\"; the wearer doesn't get it.")
                }
                .disabled(!model.eff[kind].on && custom)
            }
            .formStyle(.grouped)
            HStack {
                Spacer()
                Button("Done", action: done).keyboardShortcut(.defaultAction)
            }
            .padding([.horizontal, .bottom], 20)
        }
        .frame(width: 480, height: custom ? 540 : 420)
    }

    private func note(_ s: String) -> some View { Text(s).font(.callout).foregroundStyle(.secondary) }
    private var thresholdLabel: String {
        switch kind.group { case .level: kind.lowSide ? "Below" : "At or above"; case .rate: "Faster than"; case .noData: "After no data for" }
    }
}

/// Evidence aid (`-settingsSnapshot`): the Details sheet for one kind, outside its sheet.
@MainActor func alertDetailsPreview(_ model: AlertsModel, _ from: SettingsFromModel, _ kind: AlertKind) -> some View {
    KindDetails(model: model, from: from, kind: kind, custom: from.custom || kind == .noData) {}
}
