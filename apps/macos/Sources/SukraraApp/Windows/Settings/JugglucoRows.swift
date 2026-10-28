// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Observation
import SukraraCore
import SukraraShare
import SwiftUI

/// Settings › General › Source: Juggluco on this network (schema `source.jugglucoLan`, `source.jugglucoAddress`, the
/// secret in the Keychain `source.jugglucoSecret`), where it stands and its exits.
@Observable
public final class JugglucoModel {
    @ObservationIgnored let store: any SettingsStoring
    /// The Keychain (nil in a run without one: snapshots). The secret is never written to the defaults.
    @ObservationIgnored var secrets: (any SecretStoring)?
    /// The source's state (the app model's), `.off` when nothing feeds it.
    @ObservationIgnored var state: () -> LanState = { .off }
    /// The wearer's name while this Mac follows someone (Juggluco then pauses), else nil.
    @ObservationIgnored var pausedFor: () -> String? = { nil }
    /// This run may read the network (false in dev, fixture and snapshot runs: the switch is dimmed).
    @ObservationIgnored var available: () -> Bool = { false }
    @ObservationIgnored var retry: () -> Void = {}
    /// The secret changed (the Keychain posts nothing): the app re-reads the config.
    @ObservationIgnored var secretChanged: (String) -> Void = { _ in }
    private var revision = 0
    /// The fields as typed, saved on Return or when the pane closes.
    var addressDraft: String
    var secretDraft: String

    init(store: any SettingsStoring, secrets: (any SecretStoring)? = nil) {
        self.store = store
        self.secrets = secrets
        addressDraft = store.value(MacSettings.jugglucoAddress)
        secretDraft = ""
    }

    var on: Bool {
        get { _ = revision; return store.value(MacSettings.jugglucoLan) }
        set {
            store.set(newValue, for: MacSettings.jugglucoLan)
            revision += 1
        }
    }

    /// Loads the stored secret into its field (when the pane shows).
    func load() {
        addressDraft = store.value(MacSettings.jugglucoAddress)
        secretDraft = Self.secret(secrets)
    }

    func saveAddress() {
        let typed = addressDraft.trimmingCharacters(in: .whitespaces)
        guard typed != store.value(MacSettings.jugglucoAddress) else { return }
        store.set(typed, for: MacSettings.jugglucoAddress)
        revision += 1
    }

    func saveSecret() {
        guard let secrets, secretDraft != Self.secret(secrets) else { return }
        if secretDraft.isEmpty {
            try? secrets.delete(account: MacSettings.jugglucoSecretAccount)
        } else {
            try? secrets.write(Data(secretDraft.utf8), account: MacSettings.jugglucoSecretAccount)
        }
        secretChanged(secretDraft)
    }

    static func secret(_ secrets: (any SecretStoring)?) -> String {
        guard let data = try? secrets?.read(account: MacSettings.jugglucoSecretAccount) else { return "" }
        return String(decoding: data, as: UTF8.self)
    }

    /// The state line: words, a symbol and whether Try Again helps.
    static func line(_ state: LanState) -> (text: String, symbol: String, retry: Bool) {
        switch state {
        case .off: (L10n.t("Starting…"), "antenna.radiowaves.left.and.right", false)
        case .looking: (L10n.t("Looking for Juggluco on this network…"), "magnifyingglass", false)
        case .notFound:
            (
                L10n.t("Not found. Check that Juggluco's web server is on with Local only off, or type its address."),
                "exclamationmark.triangle", true
            )
        case .connected(let host): (L10n.t("Reading Juggluco at \(host)"), "checkmark.circle", false)
        case .unreachable(let host):
            (L10n.t("Can't reach Juggluco at \(host). Retrying…"), "exclamationmark.triangle", true)
        case .unauthorized: (L10n.t("Juggluco refused the API secret. Type the one set in Juggluco."), "lock", true)
        case .refused(let typed):
            (
                L10n.t("\(typed) isn't a home-network address (like 192.168.1.23). Fix it or leave it blank."),
                "exclamationmark.triangle", false
            )
        }
    }
}

/// The Source section's rows.
struct JugglucoRows: View {
    @Bindable var model: JugglucoModel

    var body: some View {
        let available = model.available()
        Toggle(L10n.t("Juggluco on this network"), isOn: available ? $model.on : .constant(false))
            .disabled(!available)
        if !available {
            Text(L10n.t("Not available in this run (development or test build).")).foregroundStyle(.secondary)
        } else if model.on {
            if let wearer = model.pausedFor() {
                Label(
                    L10n.t("Paused while following \(wearer): their readings come first."), systemImage: "pause.circle"
                )
                .foregroundStyle(.secondary)
            } else {
                let line = JugglucoModel.line(model.state())
                HStack {
                    Label(line.text, systemImage: line.symbol)
                        .fixedSize(horizontal: false, vertical: true)
                    Spacer()
                    if line.retry { Button(L10n.t("Try Again"), action: model.retry) }
                }
                .accessibilityElement(children: .combine)
            }
            TextField(
                L10n.t("Address"), text: $model.addressDraft, prompt: Text(L10n.t("Find it automatically"))
            )
            .onSubmit(model.saveAddress)
            SecureField(L10n.t("API secret"), text: $model.secretDraft, prompt: Text(L10n.t("None")))
                .onSubmit(model.saveSecret)
        }
    }
}
