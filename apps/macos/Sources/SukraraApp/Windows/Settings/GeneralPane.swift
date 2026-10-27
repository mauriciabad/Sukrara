// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import SukraraCore
import SwiftUI

/// General: Following (wearer, data age, link state, Unfollow confirmed), units, open at login, updates, version.
struct GeneralPane: View {
    @Bindable var model: SettingsModel
    @Binding var openAtLogin: Bool
    var updateResult: String?
    var checkForUpdates: () -> Void
    var openFollow: () -> Void

    var body: some View {
        Form {
            Section(L10n.t("Following")) { FollowingRows(follow: model.follow, openFollow: openFollow) }
            Section(L10n.t("Glucose")) {
                Picker(L10n.t("Unit"), selection: $model.unit) {
                    Text("mg/dL").tag(GlucoseUnit.mgdl)
                    Text("mmol/L").tag(GlucoseUnit.mmol)
                }
                .pickerStyle(.segmented)
            }
            Section(L10n.t("Startup")) {
                Toggle(L10n.t("Open at login"), isOn: $openAtLogin)
            }
            Section(L10n.t("Updates")) {
                HStack {
                    Text(L10n.t("Version \(model.version)"))
                    Spacer()
                    Button(L10n.t("Check now"), action: checkForUpdates)
                }
                if let r = updateResult { Text(r).foregroundStyle(.secondary) }
            }
        }
        .formStyle(.grouped)
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
            row(L10n.t("Following \(wearer ?? L10n.t("the wearer"))"), "person.crop.circle.badge.checkmark")
            Text(last.map { L10n.t("Last reading \($0.formatted(.relative(presentation: .named)))") }
                ?? L10n.t("No reading yet"))
                .foregroundStyle(.secondary)
            Text(canWrite ? L10n.t("Can add treatments") : L10n.t("Can't add treatments (the wearer decides)"))
                .foregroundStyle(.secondary)
            Button(L10n.t("Stop following…"), role: .destructive) { follow.confirmingUnfollow = true }
                .confirmationDialog(
                    L10n.t("Stop following \(wearer ?? L10n.t("the wearer"))?"), isPresented: $follow.confirmingUnfollow
                ) {
                    Button(L10n.t("Stop following"), role: .destructive) { Task { await follow.unfollow() } }
                    Button(L10n.t("Cancel"), role: .cancel) {}
                } message: {
                    Text(L10n.t("Their readings are removed from this Mac. To follow again, they have to send a new invite."))
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

/// The settings window's content: two panes, the last one chosen is remembered.
public struct SettingsView: View {
    @Bindable var model: SettingsModel
    /// Open at login (chunk 21's LoginItem over SMAppService.mainApp, schema `general.openAtLogin`).
    @Binding var openAtLogin: Bool
    /// The last update check's result in words (chunk 21's UpdateCheck), nil before one ran.
    var updateResult: String?
    var checkForUpdates: () -> Void
    var openFollow: () -> Void

    public init(
        model: SettingsModel, openAtLogin: Binding<Bool>, updateResult: String?,
        checkForUpdates: @escaping () -> Void, openFollow: @escaping () -> Void
    ) {
        self.model = model
        self._openAtLogin = openAtLogin
        self.updateResult = updateResult
        self.checkForUpdates = checkForUpdates
        self.openFollow = openFollow
    }

    public var body: some View {
        TabView(selection: $model.pane) {
            GeneralPane(
                model: model, openAtLogin: $openAtLogin, updateResult: updateResult, checkForUpdates: checkForUpdates,
                openFollow: openFollow)
                .tabItem { Label(L10n.t("General"), systemImage: "gearshape") }
                .tag(SettingsModel.Pane.general)
            AlertsRangesPane(model: model)
                .tabItem { Label(L10n.t("Alerts"), systemImage: "bell") }
                .tag(SettingsModel.Pane.alerts)
        }
        .frame(minWidth: 520, minHeight: 520)
    }
}
