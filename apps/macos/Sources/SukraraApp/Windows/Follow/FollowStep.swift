// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import AppKit
import SwiftUI
import UniformTypeIdentifiers

/// Follow someone: paste or open an invite link, import a QR image, or pair with a phone nearby. Used by the setup
/// walk and by Settings › General › Follow….
struct FollowStep: View {
    @Bindable var follow: FollowModel
    @State private var importing = false

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            if let phone = follow.nearby { NearbyPairingDialog(follow: follow, phone: phone) }
            Text(L10n.t("Paste the invite link from the wearer's phone (Sukrara › Share › Invite)."))
            HStack {
                TextField(L10n.t("Invite link"), text: $follow.linkText)
                    .textFieldStyle(.roundedBorder)
                    .onSubmit { Task { await follow.follow() } }
                    .accessibilityLabel(L10n.t("Invite link"))
                Button(L10n.t("Follow")) { Task { await follow.follow() } }
                    .keyboardShortcut(.defaultAction)
                    .disabled(follow.linkText.isEmpty || follow.status == .joining)
            }
            Button(L10n.t("Import a QR code image…")) { importing = true }
            if follow.status == .joining {
                HStack {
                    ProgressView().controlSize(.small)
                    Text(L10n.t("Looking up the invite…"))
                    Button(L10n.t("Cancel")) { Task { await follow.cancel() } }
                }
            }
            if let failure = follow.failure {
                Label(failure.message, systemImage: "exclamationmark.triangle.fill")
                    .foregroundStyle(.red)
                    .accessibilityAddTraits(.isStaticText)
            }
        }
        .onAppear { follow.searchNearby(follow.status == .notFollowing) }
        .onDisappear { follow.searchNearby(false) }
        .fileImporter(isPresented: $importing, allowedContentTypes: [.image]) { result in
            let text = (try? result.get()).flatMap { url -> String? in
                let ok = url.startAccessingSecurityScopedResource()
                defer { if ok { url.stopAccessingSecurityScopedResource() } }
                return QRImport.inviteText(atURL: url)
            }
            Task { await follow.follow(qrText: text) }
        }
    }
}

/// A phone nearby offers pairing: its avatar, this Mac's name (editable), "Ask to follow" / "Not now"; while it
/// waits, "Waiting for <wearer> to approve" with Cancel.
struct NearbyPairingDialog: View {
    @Bindable var follow: FollowModel
    let phone: NearbyPhone

    var body: some View {
        GroupBox {
            HStack(alignment: .top, spacing: 12) {
                Text(phone.avatarInitial)
                    .font(.title.bold())
                    .frame(width: 44, height: 44)
                    .background(Circle().fill(.tint.opacity(0.25)))
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 8) {
                    if case .waiting = follow.status {
                        Text(L10n.t("Waiting for \(phone.wearer) to approve"))
                        Button(L10n.t("Cancel")) { Task { await follow.cancel() } }
                    } else {
                        Text(L10n.t("\(phone.wearer)'s phone is nearby")).font(.headline)
                        TextField(L10n.t("Name the wearer sees"), text: $follow.macName)
                            .textFieldStyle(.roundedBorder)
                        HStack {
                            Button(L10n.t("Ask to follow")) { Task { await follow.askNearby() } }
                            Button(L10n.t("Not now")) { follow.nearby = nil }
                        }
                    }
                }
            }
            .padding(4)
        }
        .accessibilityElement(children: .contain)
    }
}
