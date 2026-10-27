// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import AppKit
import Network
import SukraraCore

/// The menu bar app shell. Services start only through `plan.services.start(using:)` (`AppServices`), before the
/// model and UI exist, so a notification answer that launched the app finds the alert engine already restored.
public final class AppDelegate: NSObject, NSApplicationDelegate {
    public let model: AppModel
    private let services: AppServices
    private let contextMenu = ContextMenu()
    private var statusItem: StatusItemController?
    private var observers: [NSObjectProtocol] = []
    private let network = NWPathMonitor()
    /// Join links (`sukrara:` / web) handed to the follow window (chunk 23 / follower runtime 15 set this).
    var openJoinLink: ((URL) -> Void)?

    public init(plan: LaunchPlan) {
        let services = AppServices(plan: plan)
        plan.services.start(using: services)
        self.services = services
        model = AppModel(
            plan: plan, alerts: services.alerts, updates: services.updates, loginItem: services.loginItem,
            follower: services.follower)
    }

    public func applicationDidFinishLaunching(_ notification: Notification) {
        NSApp.mainMenu = MainMenu.make()
        if let updates = model.updates {
            contextMenu.checkForUpdates = { Task { await updates.check(userAsked: true) } }
        }
        statusItem = StatusItemController(model: model, menu: contextMenu)
        observeSystemTime()
        observeNetwork()
        refreshNotificationPermission()
        model.run()
        AppLog.app.info("launched (offline: \(self.model.plan.isOffline, privacy: .public))")
    }

    public func applicationDidBecomeActive(_ notification: Notification) {
        refreshNotificationPermission()
    }

    public func applicationWillTerminate(_ notification: Notification) {
        model.stop()
    }

    /// `sukrara:` links (Info.plist CFBundleURLTypes): join links go to the follow flow.
    public func application(_ application: NSApplication, open urls: [URL]) {
        for url in urls where url.scheme == SukraraIDs.urlScheme {
            if let open = openJoinLink {
                open(url)
            } else {
                AppLog.app.info("join link received before the follow window exists")
            }
        }
    }

    /// Timers don't run while the Mac sleeps and a clock change moves every deadline: re-check alerts and freshness.
    private func observeSystemTime() {
        let workspace = NSWorkspace.shared.notificationCenter
        observers.append(
            workspace.addObserver(forName: NSWorkspace.didWakeNotification, object: nil, queue: .main) {
                [weak self] _ in
                Task { @MainActor in self?.model.systemTimeChanged() }
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
        network.pathUpdateHandler = { [weak self] path in
            guard path.status == .satisfied else { return }
            Task { @MainActor in self?.model.networkCameBack() }
        }
        network.start(queue: .main)
    }

    private func refreshNotificationPermission() {
        guard let center = services.center else { return }
        Task {
            let permission = await center.refresh()
            model.notificationPermission = permission
        }
    }
}
