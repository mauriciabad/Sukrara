// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import AppKit
import SukraraCore
import SukraraShare
import SwiftUI

/// Connects the windows (setup, settings, treatments) to the running app: one model per window kind over the one
/// settings store, fed by the follower runtime's snapshots, acting through the `FollowerAdapter` and the app's
/// services (notification permission, login item, update check). Owned by `AppDelegate`.
final class WindowCoordinator {
    let windows = AppWindows()
    let follow: FollowModel
    let setup: SetupModel
    let settings: SettingsModel
    let treatments: TreatmentsModel
    private let model: AppModel
    private let adapter: FollowerAdapter?

    init(model: AppModel, store: any SettingsStoring, requestNotifications: @escaping () async -> Bool) {
        self.model = model
        let adapter = model.follower.map(FollowerAdapter.init(follower:))
        self.adapter = adapter
        follow = FollowModel(status: .notFollowing, actions: adapter)
        setup = SetupModel(store: store, follow: follow)
        settings = SettingsModel(store: store, follow: follow, version: Self.version)
        treatments = TreatmentsModel(rows: [], canWrite: false, actions: adapter)
        setup.requestNotifications = requestNotifications
        setup.setOpenAtLogin = { [weak model] on in model?.loginItem?.set(on) }
        follow.searchNearby = { [weak adapter] on in adapter?.searchNearby(on) }
    }

    static var version: String {
        Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "dev"
    }

    // MARK: runtime → windows

    /// A follower snapshot: the link, the nearby offer, the wearer's mirrored settings and the treatments.
    func apply(_ snapshot: FollowerSnapshot) {
        adapter?.update(snapshot)
        follow.status = FollowerAdapter.status(snapshot)
        follow.nearby = snapshot.nearbyOffer.map { NearbyPhone(wearer: $0.wearerName ?? L10n.t("A phone")) }
        settings.wearer = Self.wearerSettings(snapshot)
        let canWrite = if case .following(_, true) = snapshot.link { true } else { false }
        treatments.isFollowing = snapshot.link.isFollowing
        treatments.sync(
            snapshot.treatments, deliveries: snapshot.deliveries.compactMapValues(FollowerAdapter.delivery),
            canWrite: canWrite)
    }

    /// The wearer's mirrored settings while following (nil otherwise: this Mac's own values apply).
    static func wearerSettings(_ snapshot: FollowerSnapshot) -> WearerSettings? {
        guard snapshot.link.isFollowing, let mirrored = snapshot.wearerSettings else { return nil }
        return WearerSettings(json: mirrored.json, wearer: FollowerAdapter.wearerName(snapshot.link))
    }

    // MARK: opening windows

    /// First launch (the walk never started): open the setup window.
    func showSetupIfFirstRun() {
        if setup.flow.isFirstRun { openSetup() }
    }

    /// "Finish setup" (nil = the first unfinished step) or one step (Follow… → `.follow`).
    func openSetup(at step: SetupFlow.Step? = nil) {
        if let step { setup.flow.open(step) } else if !setup.flow.isFirstRun { setup.flow.resume() }
        windows.show(.setup, title: L10n.t("Set Up Sukrara")) {
            SetupView(model: setup) { [weak self] in self?.windows.close(.setup) }
        }
    }

    func openFollow() { openSetup(at: .follow) }

    func openSettings() {
        windows.show(.settings, title: L10n.t("Sukrara Settings")) {
            SettingsWindowContent(model: settings, app: model) { [weak self] in self?.openFollow() }
        }
    }

    func openTreatments() {
        windows.show(.treatments, title: L10n.t("Treatments")) {
            TreatmentsView(model: treatments) { [weak self] in self?.openAddTreatment() }
        }
    }

    /// Add treatment; while low the Carbs field has the focus.
    func openAddTreatment() {
        windows.show(.addTreatment, title: L10n.t("Add Treatment")) {
            AddTreatmentView(
                focusCarbs: model.isLow,
                save: { [weak self] entries in
                    guard let self else { return }
                    Task { await self.treatments.add(entries) }
                    self.windows.close(.addTreatment)
                },
                cancel: { [weak self] in self?.windows.close(.addTreatment) })
        }
    }

    /// `sukrara:` links: `sukrara://treatment/new` opens Add treatment; an invite link opens Follow and starts
    /// following (nothing reaches the relays unless it parses as an invite).
    func open(_ url: URL) {
        if url.host() == "treatment" && url.path() == "/new" {
            if treatments.canWrite { openAddTreatment() } else { openTreatments() }
            return
        }
        follow.linkText = url.absoluteString
        openFollow()
        Task { await follow.follow() }
    }

}

/// The settings window with the app's live parts: open at login (the login item), the update check's result.
private struct SettingsWindowContent: View {
    let model: SettingsModel
    let app: AppModel
    let openFollow: () -> Void

    var body: some View {
        SettingsView(
            model: model,
            openAtLogin: Binding(
                get: { app.loginItem?.status == .on }, set: { app.loginItem?.set($0) }),
            updateResult: app.updates.flatMap { Self.text($0.state) },
            checkForUpdates: { [updates = app.updates] in Task { await updates?.check(userAsked: true) } },
            openFollow: openFollow)
    }

    static func text(_ state: UpdateCheck.State) -> String? {
        switch state {
        case .idle: nil
        case .available(_, let version): L10n.t("Sukrara \(version.description) is available (see the menu bar panel).")
        case .failed: L10n.t("Couldn't check for updates.")
        case .upToDate: L10n.t("Sukrara is up to date.")
        }
    }
}
