// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import AppKit
import Network
import SukraraCore
import SukraraGraph
import SukraraShare

/// The menu bar app shell. Services start only through `plan.services.start(using:)` (`AppServices`), before the
/// model and UI exist, so a notification answer that launched the app finds the alert engine already restored.
public final class AppDelegate: NSObject, NSApplicationDelegate {
    public let model: AppModel
    private let services: AppServices
    private let contextMenu = ContextMenu()
    private var statusItem: StatusItemController?
    private var observers: [NSObjectProtocol] = []
    private let network = NWPathMonitor()
    /// The last path status (nil before the monitor's first report).
    private var networkSatisfied: Bool?
    private let store: DefaultsSettingsStore
    private var windows: WindowCoordinator?
    /// The wearer's mirrored settings while following (nil otherwise).
    private var wearer: WearerSettings?

    public init(plan: LaunchPlan) {
        let services = AppServices(plan: plan)
        plan.services.start(using: services)
        self.services = services
        store = DefaultsSettingsStore(defaults: services.defaults)
        var follower: (any FollowerService)? = services.follower
        #if DEBUG
            if plan.mode == .fixture { follower = FixtureFollower() }
        #endif
        model = AppModel(
            plan: plan, alerts: services.alerts, updates: services.updates, loginItem: services.loginItem,
            follower: follower)
        model.unit = store.unit
        model.graph = GraphConfig.load(from: store, wearer: nil)
    }

    public func applicationDidFinishLaunching(_ notification: Notification) {
        NSApp.mainMenu = MainMenu.make()
        let windows = WindowCoordinator(model: model, store: store) { [services] in
            await services.requestNotifications()
        }
        self.windows = windows
        model.onSnapshot = { [weak self, weak windows] snapshot in
            windows?.apply(snapshot)
            self?.wearerChanged(WindowCoordinator.wearerSettings(snapshot))
        }
        contextMenu.openSettings = { [weak windows] in windows?.openSettings() }
        contextMenu.openTreatments = { [weak windows] in windows?.openTreatments() }
        let links = PanelLinks(
            setup: windows.setup, treatments: windows.treatments,
            openSetup: { [weak self] in self?.fromPanel { $0.openSetup() } },
            openFollow: { [weak self] in self?.fromPanel { $0.openFollow() } },
            openAddTreatment: { [weak self] in self?.fromPanel { $0.openAddTreatment() } },
            openTreatments: { [weak self] in self?.fromPanel { $0.openTreatments() } },
            openSettings: { [weak self] in self?.fromPanel { $0.openSettings() } })
        let statusItem = StatusItemController(model: model, menu: contextMenu, links: links)
        statusItem.onOpen = { [weak self] in self?.refreshNotificationPermission() }
        self.statusItem = statusItem
        if let updates = model.updates {
            // the result shows in the panel: open it once the check is done
            contextMenu.checkForUpdates = { [weak statusItem] in
                Task {
                    await updates.check(userAsked: true)
                    statusItem?.showPanel()
                }
            }
        }
        services.openPanel = { [weak statusItem] in statusItem?.showPanel() }
        windows.openPanel = { [weak statusItem] in statusItem?.showPanel() }
        services.permissionChanged = { [weak self] in self?.refreshNotificationPermission() }
        observeSystemTime()
        observeNetwork()
        refreshNotificationPermission()
        model.run()
        if case .snapshot = model.plan.mode {} else { windows.showSetupIfFirstRun() }
        AppLog.app.info("launched (offline: \(self.model.plan.isOffline, privacy: .public))")
    }

    /// A panel row: close the panel, then open its window.
    private func fromPanel(_ open: (WindowCoordinator) -> Void) {
        statusItem?.closePanel()
        if let windows { open(windows) }
    }

    /// Settings… ⌘, in the main menu (reached through the responder chain while a Sukrara window is key).
    @objc func showSettings(_ sender: Any?) {
        fromPanel { $0.openSettings() }
    }

    public func applicationDidBecomeActive(_ notification: Notification) {
        refreshNotificationPermission()
        model.loginItem?.refresh()
    }

    public func applicationWillTerminate(_ notification: Notification) {
        network.cancel()
        model.stop()
    }

    /// `sukrara:` links (Info.plist CFBundleURLTypes): invite links go to the follow step, `sukrara://treatment/new`
    /// to Add treatment, `sukrara://open` to the panel. A link that launched the app arrives after
    /// `applicationDidFinishLaunching`.
    public func application(_ application: NSApplication, open urls: [URL]) {
        for url in urls where url.scheme == SukraraIDs.urlScheme { windows?.open(url) }
    }

    /// Timers don't run while the Mac sleeps and a clock change moves every deadline: re-check alerts and freshness.
    private func observeSystemTime() {
        let workspace = NSWorkspace.shared.notificationCenter
        observers.append(
            workspace.addObserver(forName: NSWorkspace.didWakeNotification, object: nil, queue: .main) {
                [weak self] _ in
                Task { @MainActor in self?.model.didWake() }
            })
        observers.append(
            NotificationCenter.default.addObserver(
                forName: UserDefaults.didChangeNotification, object: nil, queue: .main
            ) { [weak self] _ in
                Task { @MainActor in self?.settingsChanged() }
            })
        observers.append(
            NotificationCenter.default.addObserver(forName: .NSSystemClockDidChange, object: nil, queue: .main) {
                [weak self] _ in
                Task { @MainActor in self?.model.systemTimeChanged() }
            })
    }

    /// The network came back: reconnect the relays now instead of waiting for the pool's watchdog (up to 270 s).
    private func observeNetwork() {
        guard model.follower != nil else { return }
        // only a transition to satisfied: the monitor's first callback (at start) reports the current path, and the
        // runtime is connecting then anyway
        network.pathUpdateHandler = { [weak self] path in
            let satisfied = path.status == .satisfied
            Task { @MainActor in self?.pathChanged(satisfied: satisfied) }
        }
        network.start(queue: .main)
    }

    private func pathChanged(satisfied: Bool) {
        defer { networkSatisfied = satisfied }
        if satisfied && networkSatisfied == false { model.networkCameBack() }
    }

    /// A defaults change (Settings window, another process): the alert rules, the unit and the card follow.
    private func settingsChanged() {
        services.settingsChanged()
        if model.unit != store.unit { model.unit = store.unit }
        let graph = GraphConfig.load(from: store, wearer: wearer)
        if model.graph != graph { model.graph = graph }
    }

    /// The wearer's mirrored settings arrived, changed or went away (stopped following).
    private func wearerChanged(_ settings: WearerSettings?) {
        guard settings != wearer else { return }
        wearer = settings
        services.wearerSettings(settings)
        settingsChanged()
    }

    private func refreshNotificationPermission() {
        guard let center = services.center else { return }
        Task {
            let permission = await center.refresh()
            model.notificationPermission = permission
            model.focusCanHoldAlerts = permission == .allowed && !center.timeSensitiveAllowed
        }
    }
}
