// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import SukraraCore
import SukraraShare
import SwiftUI

/// General: Settings from (with what's in use and Copy the wearer's settings…), units, open at login, updates,
/// version, Quit.
struct GeneralPane: View {
    @Bindable var model: SettingsModel
    /// Open at login (nil when this run may not touch login items: dev, fixture and snapshot runs).
    var loginItem: LoginItem?
    var updateResult: String?
    var checkForUpdates: () -> Void
    var openFollow: () -> Void

    var body: some View {
        Form {
            Section {
                SettingsFromPicker(model: model)
            } header: {
                Text(L10n.t("Settings"))
            } footer: {
                Text(
                    L10n.t(
                        "Wearer and Default decide the ranges, the time shown, the rings, the lines, the last treatment, the sensor countdown and when to alert; Custom lets you set them on this Mac. Sounds, the no-data alert, the notification's buttons and each place's own time shown and sensor time are always this Mac's."
                    )
                )
                .font(.callout).foregroundStyle(.secondary)
            }
            Section(L10n.t("Glucose")) {
                Picker(L10n.t("Unit"), selection: $model.unit) {
                    Text("mg/dL").tag(GlucoseUnit.mgdl)
                    Text("mmol/L").tag(GlucoseUnit.mmol)
                }
                .pickerStyle(.segmented)
            }
            Section(L10n.t("Startup")) {
                OpenAtLoginRows(loginItem: loginItem)
            }
            Section(L10n.t("Updates")) {
                HStack {
                    Text(L10n.t("Version \(model.version)"))
                    Spacer()
                    Button(L10n.t("Check now"), action: checkForUpdates)
                }
                if let r = updateResult { Text(r).foregroundStyle(.secondary) }
                HStack {
                    Spacer()
                    Button(L10n.t("Quit Sukrara")) { NSApp.terminate(nil) }
                }
            }
        }
        .formStyle(.grouped)
    }
}

/// Settings from: Wearer, Default or Custom, the sentence saying what's in use, and in Custom the copy of the wearer's
/// settings (asked first).
struct SettingsFromPicker: View {
    @Bindable var model: SettingsModel

    var body: some View {
        Picker(L10n.t("Settings from"), selection: $model.settingsFrom) {
            ForEach(SettingsFrom.allCases, id: \.self) { Text(Self.name($0, model)).tag($0) }
        }
        LabeledContent(L10n.t("In use")) {
            Text(model.inUse).multilineTextAlignment(.trailing).foregroundStyle(.secondary)
        }
        if model.editable {
            HStack {
                Spacer()
                Button(L10n.t("Copy the Wearer's Settings…")) { model.confirmingCopy = true }
                    .disabled(model.wearer == nil)
                    .confirmationDialog(
                        L10n.t("Copy \(Self.who(model))'s settings?"), isPresented: $model.confirmingCopy
                    ) {
                        Button(L10n.t("Copy Settings")) { model.copyWearer() }
                        Button(L10n.t("Cancel"), role: .cancel) {}
                    } message: {
                        Text(
                            L10n.t(
                                "This Mac's ranges, time shown, rings, lines, last treatment, sensor countdown and alert rules become theirs, as a starting point to change. Sounds and the other settings of this Mac stay."
                            ))
                    }
            }
        }
    }

    static func who(_ model: SettingsModel) -> String { model.wearer?.wearer ?? L10n.t("the wearer") }

    static func name(_ f: SettingsFrom, _ model: SettingsModel) -> String {
        switch f {
        case .wearer:
            model.wearer?.wearer.map { L10n.t("\($0) (wearer)") } ?? L10n.t("Wearer")
        case .default: L10n.t("Default")
        case .custom: L10n.t("Custom")
        }
    }
}

/// Following: the link with the wearer, its state in words and its exits.
struct FollowingPane: View {
    @Bindable var model: SettingsModel
    var openFollow: () -> Void
    /// Reconnect the relays and Bluetooth now (nil when this run has no follower runtime).
    var reconnect: (() -> Void)?

    var body: some View {
        Form {
            Section { FollowingRows(follow: model.follow, openFollow: openFollow) }
            if case .following = model.follow.status, let reconnect {
                Section {
                    HStack {
                        Spacer()
                        Button(L10n.t("Reconnect Now"), action: reconnect)
                            .help(L10n.t("Reconnect to the relays and to the phone over Bluetooth now"))
                    }
                }
            }
        }
        .formStyle(.grouped)
    }
}

/// Open at login with every `SMAppService` state in words and its way out; a run without a login item says so
/// (the switch is dimmed) instead of a switch that does nothing.
struct OpenAtLoginRows: View {
    let loginItem: LoginItem?

    var body: some View {
        if let loginItem {
            Toggle(
                L10n.t("Open at login"),
                isOn: Binding(get: { loginItem.status.isOn }, set: { loginItem.set($0) }))
            if let note = Self.note(loginItem.status, error: loginItem.lastError) {
                // the colour only on the symbol: the words keep full contrast
                Label {
                    Text(note)
                } icon: {
                    Image(systemName: "exclamationmark.triangle.fill").foregroundStyle(.orange)
                }
                .fixedSize(horizontal: false, vertical: true)
            }
            if loginItem.offersLoginItems {
                Button(L10n.t("Open Login Items…"), action: loginItem.openSettings)
            }
        } else {
            Toggle(L10n.t("Open at login"), isOn: .constant(false)).disabled(true)
            Text(L10n.t("Not available in this run (development or test build)."))
                .foregroundStyle(.secondary)
        }
    }

    /// The line under the switch (a refused change first: it explains why the switch didn't move), nil when the
    /// switch says it all (on / off).
    static func note(_ status: LoginItem.Status, error: String?) -> String? {
        if let error { return L10n.t("Couldn't change Open at login: \(error)") }
        return status == .needsApproval
            ? L10n.t("Waiting for your approval: allow Sukrara in System Settings › General › Login Items.") : nil
    }
}

/// The link with the wearer, in words, with its exits (Follow… / Stop following… / Cancel / Follow again).
struct FollowingRows: View {
    @Bindable var follow: FollowModel
    var openFollow: () -> Void

    var body: some View {
        switch follow.status {
        case .notFollowing:
            row(L10n.t("Not following anyone"), "person.crop.circle.badge.questionmark")
            Button(L10n.t("Follow…"), action: openFollow)
        case .joining:
            row(L10n.t("Looking up the invite…"), "arrow.triangle.2.circlepath")
            Button(L10n.t("Cancel")) { Task { await follow.cancel() } }
        case .waiting(let wearer, let code):
            row(L10n.t("Waiting for \(wearer ?? L10n.t("the wearer")) to approve"), "hourglass")
            if let code { Text(L10n.t("Code to type on the phone: \(code)")).font(.title3.monospaced()) }
            Button(L10n.t("Cancel")) { Task { await follow.cancel() } }
        case .following(let wearer, let last, let canWrite):
            Label {
                Text(L10n.t("Following \(wearer ?? L10n.t("the wearer"))"))
            } icon: {
                AvatarView(avatar: follow.wearer?.avatar, name: wearer ?? "", size: 24)
            }
            .frame(minHeight: 28)
            Text(
                last.map { L10n.t("Last reading \($0.formatted(.relative(presentation: .named)))") }
                    ?? L10n.t("No reading yet")
            )
            .foregroundStyle(.secondary)
            if let source = follow.source { Text(source).foregroundStyle(.secondary) }
            Text(canWrite ? L10n.t("Can add treatments") : L10n.t("Can't add treatments (the wearer decides)"))
                .foregroundStyle(.secondary)
            if let problem = follow.problem {
                // the colour only on the symbol: the words keep full contrast
                Label {
                    Text(problem)
                } icon: {
                    Image(systemName: "exclamationmark.triangle.fill").foregroundStyle(.orange)
                }
                .fixedSize(horizontal: false, vertical: true)
            }
            if let me = follow.me {
                IdentityRow(me: me, wearer: wearer) { name, avatar in
                    Task { await follow.saveProfile(name: name, avatar: avatar) }
                }
            }
            Button(L10n.t("Stop following…"), role: .destructive) { follow.confirmingUnfollow = true }
                .confirmationDialog(
                    L10n.t("Stop following \(wearer ?? L10n.t("the wearer"))?"), isPresented: $follow.confirmingUnfollow
                ) {
                    Button(L10n.t("Stop following"), role: .destructive) { Task { await follow.unfollow() } }
                    Button(L10n.t("Cancel"), role: .cancel) {}
                } message: {
                    Text(
                        L10n.t(
                            "Their readings are removed from this Mac. To follow again, they have to send a new invite."
                        ))
                }
        case .declined, .inviteUsed, .revoked, .broken:
            row(Self.endText(follow.status), "exclamationmark.triangle")
            Button(L10n.t("Follow again…"), action: openFollow)
        }
    }

    private func row(_ text: String, _ symbol: String) -> some View {
        Label(text, systemImage: symbol).frame(minHeight: 24)
    }

    static func endText(_ s: FollowStatus) -> String {
        switch s {
        case .declined: L10n.t("The wearer declined. Ask them for a new invite.")
        case .inviteUsed: L10n.t("Another device used this invite. Ask for a new one.")
        case .revoked: L10n.t("The wearer stopped sharing with this Mac.")
        case .broken(let why): L10n.t("Following stopped: \(why)")
        default: ""
        }
    }
}

/// The settings window's content: five panes; the window reopens on the last one.
struct SettingsView: View {
    @Bindable var model: SettingsModel
    /// Open at login (LoginItem over SMAppService.mainApp, schema `general.openAtLogin`).
    var loginItem: LoginItem?
    /// The last update check's result in words (UpdateCheck), nil before one ran.
    var updateResult: String?
    var checkForUpdates: () -> Void
    var openFollow: () -> Void
    var reconnect: (() -> Void)? = nil
    /// The running alert rules (Send Test Alert, Active now); nil in an offline run.
    var alerts: AlertController? = nil

    var body: some View {
        TabView(selection: $model.pane) {
            GeneralPane(
                model: model, loginItem: loginItem, updateResult: updateResult, checkForUpdates: checkForUpdates,
                openFollow: openFollow
            )
            .tabItem { Label(L10n.t("General"), systemImage: "gearshape") }
            .tag(SettingsModel.Pane.general)
            GraphPane(model: model)
                .tabItem { Label(L10n.t("Graph"), systemImage: "chart.xyaxis.line") }
                .tag(SettingsModel.Pane.graph)
            RingsPane(model: model)
                .tabItem { Label(L10n.t("Rings"), systemImage: "circle.circle") }
                .tag(SettingsModel.Pane.rings)
            AlertsRangesPane(model: model, controller: alerts)
                .tabItem { Label(L10n.t("Alerts"), systemImage: "bell") }
                .tag(SettingsModel.Pane.alerts)
            FollowingPane(model: model, openFollow: openFollow, reconnect: reconnect)
                .tabItem { Label(L10n.t("Following"), systemImage: "person.2") }
                .tag(SettingsModel.Pane.following)
        }
        .frame(minWidth: 560, minHeight: 560)
    }
}
