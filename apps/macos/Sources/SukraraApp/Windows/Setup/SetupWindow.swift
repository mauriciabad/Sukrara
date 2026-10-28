// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import SukraraAlerts
import SukraraCore
import SwiftUI

/// The setup walk's state for the view: the flow plus what the steps act on.
@Observable
public final class SetupModel {
    public var flow: SetupFlow
    public let follow: FollowModel
    public let alerts: AlertSettingsStore
    @ObservationIgnored let store: any SettingsStoring
    /// Asks for notification permission / registers the login item; true = granted.
    @ObservationIgnored var requestNotifications: () async -> Bool = { false }
    @ObservationIgnored var setOpenAtLogin: (Bool) -> Void = { _ in }

    public init(store: any SettingsStoring, follow: FollowModel) {
        self.store = store
        self.flow = SetupFlow(store: store)
        self.follow = follow
        self.alerts = AlertSettingsStore(store: store)
    }

    func complete(_ s: SetupFlow.Step) { flow.complete(s); flow.save(to: store) }
    func skip(_ s: SetupFlow.Step) { flow.skip(s); flow.save(to: store) }
}

/// First-run setup: Welcome → Follow → Alerts (thresholds confirmed) → Notifications → Open at login → Done.
/// Every step has Skip and Back; closing the window leaves "Finish setup" in the panel.
public struct SetupView: View {
    @Bindable var model: SetupModel
    var close: () -> Void
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    public init(model: SetupModel, close: @escaping () -> Void) {
        self.model = model
        self.close = close
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Group {
                switch model.flow.current {
                case .welcome: welcome
                case .follow: page(.follow, L10n.t("Follow someone")) { FollowStep(follow: model.follow) }
                case .alerts: page(.alerts, L10n.t("Check your alerts")) { thresholds }
                case .notifications: page(.notifications, L10n.t("Allow notifications")) {
                    Text(L10n.t("Alerts reach you as notifications, even when Sukrara is in the background."))
                    Text(L10n.t("A Focus (Do Not Disturb, Sleep…) can hold back Sukrara's alarms on this Mac. Allow Sukrara in each Focus, and keep the phone as the alarm."))
                        .foregroundStyle(.secondary)
                }
                case .openAtLogin: page(.openAtLogin, L10n.t("Open at login")) {
                    Text(L10n.t("Sukrara starts when you log in, so the menu bar always shows the glucose."))
                }
                case nil: done
                }
            }
            .transition(reduceMotion ? .opacity : .move(edge: .trailing).combined(with: .opacity))
        }
        .animation(reduceMotion ? nil : .spring(duration: 0.35), value: model.flow.current)
        .padding(24)
        .frame(width: 520, alignment: .topLeading)
    }

    private var welcome: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(L10n.t("Welcome to Sukrara")).font(.largeTitle.bold())
            Text(L10n.t("Sukrara shows the glucose of someone you follow in the menu bar and alerts you when it's out of range."))
            HStack {
                Spacer()
                Button(L10n.t("Set up later")) { model.skip(.welcome); close() }
                Button(L10n.t("Start")) { model.complete(.welcome) }.keyboardShortcut(.defaultAction)
            }
        }
    }

    private var thresholds: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(L10n.t("Sukrara alerts at these values. Use them, or change them in Settings › Alerts."))
            ForEach([AlertKind.veryLow, .low, .high, .veryHigh, .extremelyHigh], id: \.self) { k in
                LabeledContent(AlertsRangesPane.kindName(k)) {
                    Text(model.alerts.settings[k].on ? "\(Int(model.alerts.settings[k].threshold)) mg/dL" : L10n.t("Off"))
                }
                .frame(minHeight: 24)
            }
        }
    }

    private func page(_ step: SetupFlow.Step, _ title: String, @ViewBuilder content: () -> some View) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(title).font(.title.bold())
            content()
            HStack {
                Button(L10n.t("Back")) { model.flow.back() }
                Spacer()
                Button(L10n.t("Skip")) { model.skip(step) }
                Button(primaryTitle(step)) { Task { await primary(step) } }
                    .keyboardShortcut(.defaultAction)
                    .disabled(step == .follow && !followDone)
            }
        }
    }

    private var followDone: Bool {
        switch model.follow.status {
        case .following, .waiting: true
        default: false
        }
    }

    private func primaryTitle(_ s: SetupFlow.Step) -> String {
        switch s {
        case .alerts: L10n.t("Use these")
        case .notifications: L10n.t("Allow…")
        case .openAtLogin: L10n.t("Open at login")
        default: L10n.t("Continue")
        }
    }

    private func primary(_ s: SetupFlow.Step) async {
        switch s {
        case .notifications:
            if await model.requestNotifications() { model.complete(s) } else { model.skip(s) }
        case .openAtLogin:
            model.setOpenAtLogin(true)
            model.complete(s)
        default: model.complete(s)
        }
    }

    private var done: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(model.flow.isComplete ? L10n.t("All set") : L10n.t("Almost done")).font(.title.bold())
            if !model.flow.isComplete {
                Text(L10n.t("Some steps were skipped. \"Finish setup\" in the Sukrara menu takes you back to them."))
            }
            HStack {
                Spacer()
                if !model.flow.isComplete { Button(L10n.t("Finish now")) { model.flow.resume() } }
                Button(L10n.t("Done"), action: close).keyboardShortcut(.defaultAction)
            }
        }
    }
}
