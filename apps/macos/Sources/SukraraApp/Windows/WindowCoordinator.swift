// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import AppKit
import SukraraCore
import SukraraGraph
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
    let report: ReportModel
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
        report = ReportModel(store: store, app: model)
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

    func openReport() {
        windows.show(.report, title: L10n.t("Glucose Report")) { ReportView(model: report) }
    }

    /// Add treatment, placed under the menu bar: the Carbs field has the focus while the latest reading is below
    /// the range (or a low alert is up), else Rapid; the card's pill names the field itself (`focus`).
    func openAddTreatment(focus: Treatment.Kind? = nil) {
        let low =
            model.isLow
            || (model.look.state == .value && GlucoseCard.rangeSide(model.cardData, config: model.graph) == .below)
        let field = focus ?? (low ? .carbs : .rapid)
        windows.show(.addTreatment, title: L10n.t("Add Treatment")) {
            AddTreatmentView(
                focus: field,
                quiet: { [weak model] rapid, carbs, at in
                    TreatmentQuietNote.of(
                        rapid: rapid, carbs: carbs, at: at, now: Int64(Date().timeIntervalSince1970),
                        settings: model?.alerts?.settings, mirroring: model?.alerts?.mirroring ?? false)
                },
                save: { [weak self] entries in
                    guard let self else { return }
                    Task { await self.treatments.add(entries) }
                    self.windows.close(.addTreatment)
                },
                cancel: { [weak self] in self?.windows.close(.addTreatment) })
        }
        windows.placeNearMenuBar(.addTreatment)
    }

    /// Opens the menu bar panel (set by `AppDelegate`; `sukrara://open`).
    var openPanel: () -> Void = {}

    /// `sukrara:` links: `sukrara://treatment/new` opens Add treatment, `sukrara://open` (a widget click with no
    /// data) the panel; anything else is treated as an invite link: it opens Follow and starts following (nothing
    /// reaches the relays unless it parses as an invite).
    func open(_ url: URL) {
        switch AppLink(url) {
        case .newTreatment:
            if treatments.canWrite { openAddTreatment() } else { openTreatments() }
        case .panel:
            openPanel()
        case .invite(let text):
            follow.linkText = text
            openFollow()
            Task { await follow.follow() }
        }
    }

}

/// Where a `sukrara:` link goes (`WindowCoordinator.open`).
enum AppLink: Equatable {
    /// `sukrara://treatment/new`: the widget's quick Add treatment.
    case newTreatment
    /// `sukrara://open`: the widget with no data asks for the app.
    case panel
    /// Anything else: an invite link (`sukrara:join#…`), checked by the follow step before it reaches the relays.
    case invite(String)

    init(_ url: URL) {
        switch (url.host(), url.path()) {
        case ("treatment", "/new"): self = .newTreatment
        case ("open", ""), ("open", "/"): self = .panel
        default: self = .invite(url.absoluteString)
        }
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
            loginItem: app.loginItem,
            updateResult: app.updates.flatMap { Self.text($0.state) },
            checkForUpdates: { [updates = app.updates] in Task { await updates?.check(userAsked: true) } },
            openFollow: openFollow,
            reconnect: app.follower == nil ? nil : { [weak app] in app?.networkCameBack() },
            alerts: app.alerts)
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
