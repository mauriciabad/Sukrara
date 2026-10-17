// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import AppKit
import SwiftUI

/// "Alerts…" (⌘,): the settings window for glucose alerts, per the HIG's macOS settings guidance
/// (developer.apple.com/design/human-interface-guidelines/settings): a non-customizable preference-style toolbar whose
/// selected pane names the window ("Alerts" — the one pane for now), minimize/zoom dimmed, fixed size, and every
/// change applies at once (no Save button).
@MainActor final class AlertsWindow: NSObject, NSToolbarDelegate {
    private let alerts: Alerts
    private let model: AlertsModel
    private var window: NSWindow?
    private static let pane = NSToolbarItem.Identifier("alerts")

    init(alerts: Alerts) {
        self.alerts = alerts
        model = AlertsModel(alerts: alerts)
        super.init()
        alerts.onChange = { [weak model] in model?.refreshState() }
    }

    func show() {
        let w = window ?? make()
        window = w
        model.reload()
        if !w.isVisible { w.center() }
        NSApp.activate(ignoringOtherApps: true)
        w.makeKeyAndOrderFront(nil)
    }

    private func make() -> NSWindow {
        let host = NSHostingController(rootView: AlertsView(model: model))
        host.sizingOptions = []                   // fixed size; the grouped form scrolls
        let w = NSWindow(contentViewController: host)
        w.styleMask = [.titled, .closable]        // HIG: dim minimize and zoom
        w.setContentSize(NSSize(width: 560, height: 720))
        w.title = "Alerts"
        w.isReleasedWhenClosed = false
        let tb = NSToolbar(identifier: "sukrara.settings")
        tb.delegate = self
        tb.allowsUserCustomization = false
        tb.displayMode = .iconAndLabel
        w.toolbar = tb
        w.toolbarStyle = .preference
        tb.selectedItemIdentifier = Self.pane
        return w
    }

    /// Evidence aid: `-alertsSnapshot out.png` renders the whole window (title bar + toolbar + form) to a PNG
    /// without screen-recording permission.
    func snapshot(to path: String) {
        guard let frame = window?.contentView?.superview,
              let rep = frame.bitmapImageRepForCachingDisplay(in: frame.bounds) else { return log("alerts snapshot: no window") }
        frame.cacheDisplay(in: frame.bounds, to: rep)
        try? rep.representation(using: .png, properties: [:])?.write(to: URL(fileURLWithPath: path))
        log("alerts snapshot → \(path) (window \(window?.windowNumber ?? 0))")
    }
    var windowNumber: Int { window?.windowNumber ?? 0 }

    // MARK: NSToolbarDelegate — one pane for now; more panes (General, Following) would join it here.

    func toolbar(_ toolbar: NSToolbar, itemForItemIdentifier id: NSToolbarItem.Identifier,
                 willBeInsertedIntoToolbar flag: Bool) -> NSToolbarItem? {
        let item = NSToolbarItem(itemIdentifier: id)
        item.label = "Alerts"
        item.image = NSImage(systemSymbolName: "bell.badge", accessibilityDescription: "Alerts")
        item.target = self
        item.action = #selector(selectPane)
        return item
    }
    @objc private func selectPane() { window?.toolbar?.selectedItemIdentifier = Self.pane }
    func toolbarDefaultItemIdentifiers(_ toolbar: NSToolbar) -> [NSToolbarItem.Identifier] { [Self.pane] }
    func toolbarAllowedItemIdentifiers(_ toolbar: NSToolbar) -> [NSToolbarItem.Identifier] { [Self.pane] }
    func toolbarSelectableItemIdentifiers(_ toolbar: NSToolbar) -> [NSToolbarItem.Identifier] { [Self.pane] }
}

@MainActor final class AlertsModel: ObservableObject {
    private let alerts: Alerts
    @Published var s: AlertSettings { didSet { if s != oldValue { alerts.update(s); refreshState() } } }
    @Published private(set) var state = ""
    @Published private(set) var following = false

    init(alerts: Alerts) {
        self.alerts = alerts
        s = alerts.settings
        refreshState()
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

struct AlertsView: View {
    @ObservedObject var model: AlertsModel

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
                Text(sourceNote).font(.callout).foregroundStyle(.secondary)
            }
            .disabled(!model.s.on)
            Section {
                KindRow(model: model, kind: .urgentLow, rulesLocked: model.mirroring)
                KindRow(model: model, kind: .low, rulesLocked: model.mirroring)
            } header: { Text("Lows") }
            Section {
                KindRow(model: model, kind: .high, rulesLocked: model.mirroring)
                KindRow(model: model, kind: .veryHigh, rulesLocked: model.mirroring)
            } header: { Text("Highs") }
            Section {
                KindRow(model: model, kind: .fallingFast, rulesLocked: model.mirroring)
                KindRow(model: model, kind: .risingFast, rulesLocked: model.mirroring)
            } header: { Text("Fast changes") }
            Section {
                KindRow(model: model, kind: .noData, rulesLocked: false)
            } header: { Text("Signal") } footer: {
                if model.mirroring { note("Checked on this Mac too: the wearer's phone can't tell when this Mac stops hearing from it.") }
            }
            Section {
                Toggle("After rapid insulin", isOn: $model.s.muteAfterInsulin.on)
                Stepper(value: $model.s.muteAfterInsulin.min, in: 30...180, step: 15) {
                    Text("Stay quiet for \(Int(model.s.muteAfterInsulin.min)) min")
                }.disabled(!model.s.muteAfterInsulin.on)
                Toggle("When falling back into range", isOn: $model.s.muteFallingHigh.on)
                Stepper(value: $model.s.muteFallingHigh.min, in: 5...60, step: 5) {
                    Text("Back under \(Int(model.s[.high].threshold)) mg/dL within \(Int(model.s.muteFallingHigh.min)) min")
                }.disabled(!model.s.muteFallingHigh.on)
            } header: { Text("Quiet highs") } footer: {
                note(model.mirroring ? lockedNote : "High, very high and rising fast only.")
            }
            .disabled(model.mirroring || !model.s.on)
            Section {
                Toggle("After carbs", isOn: $model.s.muteAfterCarbs.on)
                Stepper(value: $model.s.muteAfterCarbs.min, in: 5...45, step: 5) {
                    Text("Stay quiet for \(Int(model.s.muteAfterCarbs.min)) min")
                }.disabled(!model.s.muteAfterCarbs.on)
            } header: { Text("Quiet lows") } footer: {
                note(model.mirroring ? lockedNote : "Low and falling fast only. Urgent low always alerts.")
            }
            .disabled(model.mirroring || !model.s.on)
            Section {
                Picker("Sound pack", selection: $model.s.sounds.pack) {
                    ForEach(AlertSettings.packs, id: \.self) { Text($0.capitalized).tag($0) }
                    Divider()
                    Text("System").tag("system")
                    Text("None").tag("none")
                }
                Toggle("Different sound each time", isOn: $model.s.sounds.shuffle)
            } header: { Text("Sounds") } footer: {
                note("Each kind can use another pack above. ▶︎ plays a random variant.")
            }
            .disabled(!model.s.on)
        }
        .formStyle(.grouped)
    }

    private var lockedNote: String { "The wearer's phone decides when to alert; this Mac only picks the sounds." }

    private var sourceNote: String {
        if model.s.source == "own" { return "Alerts come from the rules below, run on this Mac with the readings it receives." }
        if !model.following { return "Not following over Sukrara sharing — using this Mac's settings." }
        return "Alerts when the wearer's phone does, with this Mac's sounds. " + lockedNote
    }

    private func note(_ s: String) -> some View { Text(s).font(.callout).foregroundStyle(.secondary) }
}

/// One alert kind: on/off, threshold, repeat, sound + preview.
private struct KindRow: View {
    @ObservedObject var model: AlertsModel
    let kind: AlertKind
    let rulesLocked: Bool

    var body: some View {
        let rule = model.rule(kind)
        VStack(alignment: .leading, spacing: 8) {
            Toggle(kind.title, isOn: rule.on).disabled(rulesLocked)
            HStack(spacing: 14) {
                HStack(spacing: 4) {
                    Text(kind == .noData ? "After" : kind.lowSide ? "≤" : kind.group == .rate ? "Faster than" : "≥")
                    TextField("", value: rule.threshold, format: .number.precision(.fractionLength(kind.group == .rate ? 1 : 0)))
                        .labelsHidden().frame(width: 48).multilineTextAlignment(.trailing)
                    Stepper("", value: rule.threshold, in: range, step: step).labelsHidden()
                    Text(unitLabel).foregroundStyle(.secondary)
                }
                .disabled(rulesLocked && kind != .noData)
                Spacer(minLength: 0)
                HStack(spacing: 4) {
                    Text("Repeat every")
                    TextField("", value: rule.repeatMin, format: .number).labelsHidden().frame(width: 36).multilineTextAlignment(.trailing)
                    Stepper("", value: rule.repeatMin, in: 0...240, step: 5).labelsHidden()
                    Text("min").foregroundStyle(.secondary)
                }
                .help("0 = alert once")
                .disabled(rulesLocked && kind != .noData)
            }
            .disabled(!rule.wrappedValue.on)
            HStack(spacing: 6) {
                Picker("Sound", selection: model.sound(kind)) {
                    Text("Pack (\(packName(model.s.sounds.pack)))").tag("")
                    ForEach(AlertSettings.packs, id: \.self) { Text($0.capitalized).tag($0) }
                    Divider()
                    Text("System").tag("system")
                    Text("None").tag("none")
                }
                Button { model.preview(kind) } label: { Image(systemName: "play.fill") }
                    .help("Play a random variant")
                    .disabled(model.s.pack(for: kind) == "none")
                Spacer(minLength: 12)
                // the phone's "Alarm volume"; macOS has no alarm volume, its nearest is Time Sensitive
                Toggle("Like an alarm", isOn: rule.alarm)
                    .help(Alerts.timeSensitive
                          ? "Time Sensitive: shows and plays even during a Focus"
                          : "Time Sensitive needs a build signed with that permission; this one shows it as a normal notification")
            }
        }
        .padding(.vertical, 2)
        .disabled(!model.s.on)
    }

    private func packName(_ p: String) -> String { p.capitalized }

    private var unitLabel: String {
        switch kind.group { case .level: "mg/dL"; case .rate: "mg/dL/min"; case .noData: "min without data" }
    }
    private var range: ClosedRange<Double> {
        switch kind {
        case .urgentLow: 40...80
        case .low: 50...100
        case .high: 120...300
        case .veryHigh: 150...400
        case .fallingFast, .risingFast: 0.5...5
        case .noData: 5...120
        }
    }
    private var step: Double { kind.group == .rate ? 0.5 : 5 }
}
