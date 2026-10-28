// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import AppKit
import SukraraAlerts
import SukraraGraph
import SwiftUI

/// The dropdown under the status item. Every state it shows has its way out right here: not monitoring → why (and
/// Finish setup); Mute all → countdown + Unmute; a side muted → its line + Unmute; Remind less → Remind as usual;
/// notifications off → Open Notification Settings; an update → Download / Later. Under them, like a menu: Add
/// Treatment… (Return; dimmed with the reason when this Mac can't log), Treatments…, Settings… (⌘,), so a left click
/// reaches every window without the right-click menu.
struct PanelView: View {
    let model: AppModel
    /// The window layer's entry points (nil in a render without windows).
    var links: PanelLinks?
    @Environment(\.colorScheme) private var scheme

    static let cardSize = CGSize(width: 340, height: 196)

    var body: some View {
        TimelineView(.periodic(from: .now, by: 30)) { context in
            VStack(alignment: .leading, spacing: 10) {
                #if DEBUG
                    if model.plan.mode == .fixture {
                        Label(L10n.t("Synthetic data (dev build)"), systemImage: "flask")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                #endif
                if let reason = model.notMonitoringReason {
                    Label(reason, systemImage: "antenna.radiowaves.left.and.right.slash")
                        .font(.callout)
                        .accessibilityLabel(reason)
                } else {
                    card
                }
                if let links { SetupRows(links: links, needsFollow: model.needsFollow) }
                if model.notificationPermission.needsAttention { notificationsWarning }
                if model.focusCanHoldAlerts { focusNote }
                if let alerts = model.alerts { AlertBlocks(alerts: alerts, now: context.date) }
                if let updates = model.updates { UpdateRow(updates: updates) }
                if let links { ActionRows(links: links) }
            }
            .padding(14)
            .frame(width: Self.cardSize.width + 28, alignment: .leading)
        }
    }

    @ViewBuilder private var card: some View {
        let data = model.cardData
        if let image = GlucoseCard.image(
            surface: .menubarCard, size: Self.cardSize, config: model.graph, data: data, unit: model.unit,
            dark: scheme == .dark)
        {
            Image(decorative: image, scale: 2)
                .accessibilityElement()
                .accessibilityLabel(
                    GlucoseCard.accessibilityLabel(data, unit: model.unit, surface: .menubarCard, config: model.graph))
        }
    }

    /// Without Time Sensitive notifications a Focus holds Sukrara's alarms back: say so, and where to allow it.
    private var focusNote: some View {
        VStack(alignment: .leading, spacing: 6) {
            Label(
                L10n.t(
                    "A Focus (Do Not Disturb, Sleep…) can hold back Sukrara's alarms on this Mac. Allow Sukrara in each Focus, and keep the phone as the alarm."
                ),
                systemImage: "moon.fill"
            )
            .font(.callout)
            .fixedSize(horizontal: false, vertical: true)
            Button(L10n.t("Open Focus Settings")) {
                if let url = URL(string: "x-apple.systempreferences:com.apple.Focus-Settings.extension") {
                    NSWorkspace.shared.open(url)
                }
            }
        }
    }

    private var notificationsWarning: some View {
        VStack(alignment: .leading, spacing: 6) {
            Label(
                model.notificationPermission == .denied
                    ? L10n.t("Notifications are off: alerts can't reach you.")
                    : L10n.t("Alert banners or sounds are off: alerts may go unnoticed."),
                systemImage: "exclamationmark.triangle.fill"
            )
            .foregroundStyle(.orange)
            Button(L10n.t("Open Notification Settings")) {
                if let url = URL(string: "x-apple.systempreferences:com.apple.Notifications-Settings.extension") {
                    NSWorkspace.shared.open(url)
                }
            }
        }
    }
}

/// What the window layer opens from the panel (each closes the panel first).
struct PanelLinks {
    let setup: SetupModel
    /// Whether this Mac may log (`AddTreatmentAvailability`).
    let treatments: TreatmentsModel
    let openSetup: () -> Void
    let openFollow: () -> Void
    let openAddTreatment: () -> Void
    let openTreatments: () -> Void
    let openSettings: () -> Void
}

/// Whether the panel's Add Treatment… can open, and why not in words (on the row, its tooltip and VoiceOver hint).
enum AddTreatmentAvailability: Equatable {
    case available
    /// Following nobody: there's no wearer to log for.
    case notFollowing
    /// Following, but the wearer hasn't allowed this Mac to add entries.
    case notAllowed

    init(isFollowing: Bool, canWrite: Bool) {
        self = !isFollowing ? .notFollowing : canWrite ? .available : .notAllowed
    }

    var reason: String? {
        switch self {
        case .available: nil
        case .notFollowing: L10n.t("Follow someone to add treatments.")
        case .notAllowed: L10n.t("Can't add treatments (the wearer decides)")
        }
    }
}

/// The panel's menu-like rows: Add Treatment… (the default button: Return), Treatments…, Settings… ⌘,.
private struct ActionRows: View {
    let links: PanelLinks

    var body: some View {
        let add = AddTreatmentAvailability(
            isFollowing: links.treatments.isFollowing, canWrite: links.treatments.canWrite)
        VStack(alignment: .leading, spacing: 2) {
            Divider().padding(.bottom, 4)
            Button(action: links.openAddTreatment) {
                PanelRowLabel(
                    title: L10n.t("Add Treatment…"), symbol: "plus.circle", shortcut: add == .available ? "↩" : nil)
            }
            .keyboardShortcut(add == .available ? .defaultAction : nil)
            .disabled(add != .available)
            .help(add.reason ?? "")
            .accessibilityHint(add.reason ?? "")
            if let reason = add.reason {
                Text(reason)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .padding(.leading, 32)
                    .accessibilityHidden(true)  // already the row's hint
            }
            Button(action: links.openTreatments) {
                PanelRowLabel(title: L10n.t("Treatments…"), symbol: "list.bullet", shortcut: nil)
            }
            Divider().padding(.vertical, 4)
            Button(action: links.openSettings) {
                PanelRowLabel(title: L10n.t("Settings…"), symbol: "gearshape", shortcut: "⌘,")
            }
            .keyboardShortcut(",", modifiers: .command)
        }
        .buttonStyle(PanelRowStyle())
    }
}

/// A menu item's look: symbol, title, the shortcut on the trailing edge (hidden from VoiceOver: it reads the
/// shortcut from the button itself).
private struct PanelRowLabel: View {
    let title: String
    let symbol: String
    let shortcut: String?

    var body: some View {
        HStack(spacing: 8) {
            Image(systemName: symbol).frame(width: 18).accessibilityHidden(true)
            Text(title)
            Spacer(minLength: 8)
            if let shortcut { Text(shortcut).foregroundStyle(.secondary).accessibilityHidden(true) }
        }
        .contentShape(Rectangle())
    }
}

/// Full-width rows highlighted on hover and press like menu items, dimmed when disabled.
private struct PanelRowStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View { Row(configuration: configuration) }

    private struct Row: View {
        let configuration: ButtonStyleConfiguration
        @Environment(\.isEnabled) private var isEnabled
        @Environment(\.isFocused) private var isFocused
        @State private var hovering = false

        var body: some View {
            // hover or Full Keyboard Access focus, in the system's menu selection colours
            let lit = (hovering || isFocused) && isEnabled
            configuration.label
                .padding(.horizontal, 6)
                .padding(.vertical, 4)
                .frame(maxWidth: .infinity, alignment: .leading)
                .foregroundStyle(lit ? Color(nsColor: .selectedMenuItemTextColor) : .primary)
                .background(
                    RoundedRectangle(cornerRadius: 5)
                        .fill(lit ? Color(nsColor: .selectedContentBackgroundColor) : .clear)
                        .opacity(configuration.isPressed ? 0.8 : 1)
                )
                .opacity(isEnabled ? 1 : 0.45)
                .onHover { hovering = $0 }
        }
    }
}

/// "Finish setup…" while a setup step is skipped or not reached; "Follow Someone…" when the walk is done but this
/// Mac follows nobody (after Stop following, a decline, a revoke).
private struct SetupRows: View {
    let links: PanelLinks
    let needsFollow: Bool

    var body: some View {
        if !links.setup.flow.isComplete {
            Button(L10n.t("Finish Setup…"), systemImage: "checklist", action: links.openSetup)
        } else if needsFollow {
            Button(L10n.t("Follow Someone…"), systemImage: "person.badge.plus", action: links.openFollow)
        }
    }
}

/// Mute all and each side's quiet, with countdowns (`MuteCopy`) and their exits.
private struct AlertBlocks: View {
    let alerts: AlertController
    let now: Date

    var body: some View {
        let ui = alerts.ui
        if let until = ui.muteAllUntil, let line = MuteCopy.allLine(until: until, now: now) {
            HStack {
                Label(line, systemImage: "bell.slash.fill")
                Spacer()
                Button(L10n.t("Unmute")) { alerts.perform(.unmuteAll) }
            }
        }
        ForEach(ui.quiets, id: \.kind) { q in
            if let reason = q.quiet, let line = MuteCopy.line(reason, side: q.side, now: now) {
                HStack {
                    Text(line)
                    Spacer()
                    if case .sideMuted = reason { Button(L10n.t("Unmute")) { alerts.perform(.unmute(q.side)) } }
                }
            }
            if q.relaxMin > 0 {
                HStack {
                    Text(MuteCopy.sideLess(q.side, intervalMin: q.relaxMin))
                    Spacer()
                    Button(L10n.t("Remind as Usual")) { alerts.perform(.remindAsUsual(q.side)) }
                }
            }
        }
        if ui.muteAllUntil == nil {
            Menu(L10n.t("Mute All Alerts")) {
                ForEach(AlertLimits.muteAllPresets, id: \.self) { minutes in
                    Button(MuteCopy.choice(minutes)) {
                        alerts.perform(.muteAll(until: now.addingTimeInterval(TimeInterval(minutes * 60))))
                    }
                }
                Button(MuteCopy.menuForever) { alerts.perform(.muteAll(until: muteAllForever)) }
            }
            .fixedSize()
        }
    }
}

private struct UpdateRow: View {
    let updates: UpdateCheck

    var body: some View {
        switch updates.state {
        case .available(let release, let version):
            HStack {
                Label(L10n.t("Sukrara \(version.description) is available"), systemImage: "arrow.down.circle")
                Spacer()
                Button(L10n.t("Later")) { updates.later() }
                Button(L10n.t("Download")) {
                    // only the project's own release page
                    if release.htmlURL.host() == "github.com" { NSWorkspace.shared.open(release.htmlURL) }
                }
            }
        case .failed:
            HStack {
                Text(L10n.t("Couldn't check for updates."))
                Spacer()
                Button(L10n.t("OK")) { updates.dismiss() }
            }
        case .upToDate:
            HStack {
                Text(L10n.t("Sukrara is up to date."))
                Spacer()
                Button(L10n.t("OK")) { updates.dismiss() }
            }
        case .idle:
            EmptyView()
        }
    }
}
