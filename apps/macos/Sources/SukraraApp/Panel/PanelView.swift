// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import AppKit
import SukraraAlerts
import SukraraGraph
import SwiftUI

/// The dropdown under the status item. Every state it shows has its way out right here: not monitoring → why (and
/// Finish setup); Mute all → countdown + Unmute; a side muted → its line + Unmute; Remind less → Remind as usual;
/// notifications off → Open Notification Settings; an update → Download / Later.
struct PanelView: View {
    let model: AppModel
    @Environment(\.colorScheme) private var scheme

    static let cardSize = CGSize(width: 340, height: 196)

    var body: some View {
        TimelineView(.periodic(from: .now, by: 30)) { context in
            VStack(alignment: .leading, spacing: 10) {
                if let reason = model.notMonitoringReason {
                    Label(reason, systemImage: "antenna.radiowaves.left.and.right.slash")
                        .font(.callout)
                        .accessibilityLabel(reason)
                } else {
                    card
                }
                if model.notificationPermission.needsAttention { notificationsWarning }
                if let alerts = model.alerts { AlertBlocks(alerts: alerts, now: context.date) }
                if let updates = model.updates { UpdateRow(updates: updates) }
            }
            .padding(14)
            .frame(width: Self.cardSize.width + 28, alignment: .leading)
        }
    }

    @ViewBuilder private var card: some View {
        let data = model.cardData
        if let image = GlucoseCard.image(
            surface: .menubarCard, size: Self.cardSize, config: GraphConfig(), data: data, unit: model.unit,
            dark: scheme == .dark)
        {
            Image(decorative: image, scale: 2)
                .accessibilityElement()
                .accessibilityLabel(GlucoseCard.accessibilityLabel(data, unit: model.unit, surface: .menubarCard, config: GraphConfig()))
        }
    }

    private var notificationsWarning: some View {
        VStack(alignment: .leading, spacing: 6) {
            Label(
                model.notificationPermission == .denied
                    ? "Notifications are off: alerts can't reach you."
                    : "Alert banners or sounds are off: alerts may go unnoticed.",
                systemImage: "exclamationmark.triangle.fill"
            )
            .foregroundStyle(.orange)
            Button("Open Notification Settings") {
                if let url = URL(string: "x-apple.systempreferences:com.apple.Notifications-Settings.extension") {
                    NSWorkspace.shared.open(url)
                }
            }
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
                Button("Unmute") { alerts.perform(.unmuteAll) }
            }
        }
        ForEach(ui.quiets, id: \.kind) { q in
            if let reason = q.quiet, let line = MuteCopy.line(reason, side: q.side, now: now) {
                HStack {
                    Text(line)
                    Spacer()
                    if case .sideMuted = reason { Button("Unmute") { alerts.perform(.unmute(q.side)) } }
                }
            }
            if q.relaxMin > 0 {
                HStack {
                    Text(MuteCopy.sideLess(q.side, intervalMin: q.relaxMin))
                    Spacer()
                    Button("Remind as Usual") { alerts.perform(.remindAsUsual(q.side)) }
                }
            }
        }
        if ui.muteAllUntil == nil {
            Menu("Mute All Alerts") {
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
                Label("Sukrara \(version.description) is available", systemImage: "arrow.down.circle")
                Spacer()
                Button("Later") { updates.later() }
                Button("Download") {
                    // only the project's own release page
                    if release.htmlURL.host() == "github.com" { NSWorkspace.shared.open(release.htmlURL) }
                }
                .keyboardShortcut(.defaultAction)
            }
        case .failed:
            HStack {
                Text("Couldn't check for updates.")
                Spacer()
                Button("OK") { updates.dismiss() }
            }
        case .upToDate:
            HStack {
                Text("Sukrara is up to date.")
                Spacer()
                Button("OK") { updates.dismiss() }
            }
        case .idle:
            EmptyView()
        }
    }
}
