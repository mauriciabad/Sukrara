// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation
import ServiceManagement
import SukraraAlerts
import SukraraCore
import SukraraShare
@preconcurrency import UserNotifications

/// The app's `ServiceStarting`: the only code that creates the notification center, copies alert sounds, runs the
/// alert engine, writes the widget's data, checks for updates or touches the login item. `ServicesPlan.start(using:)`
/// calls it, so an offline run (dev build, snapshot) creates none of them. Bluetooth runs inside the follower runtime.
final class AppServices: ServiceStarting {
    let plan: LaunchPlan
    private(set) var center: SystemNotificationCenter?
    private(set) var alerts: AlertController?
    private(set) var updates: UpdateCheck?
    private(set) var loginItem: LoginItem?
    private(set) var sounds: SoundLibrary?
    /// The follower runtime (relays, and Bluetooth when the plan allows it). Created here, started by `AppModel`.
    private(set) var follower: FollowerRuntime?
    /// Hands the card to the widgets (App Group file + timeline reloads). Fed by `AppModel` on every change.
    private(set) var widget: WidgetFeed?
    private let makeWidget: () -> WidgetFeed
    let responder = NotificationResponder()
    /// Re-read what notifications may do (after the authorization prompt).
    var permissionChanged: (() -> Void)?
    /// A tap on a notification's body: open the panel.
    var openPanel: (() -> Void)?
    let defaults: UserDefaults

    /// `widget`: creates the widget feed when the plan allows it (tests write to a scratch file).
    init(plan: LaunchPlan, widget: @escaping () -> WidgetFeed = { WidgetFeed() }) {
        self.plan = plan
        makeWidget = widget
        defaults = plan.data.defaultsSuite.flatMap(UserDefaults.init(suiteName:)) ?? .standard
    }

    /// The wearer's mirrored settings while following (from the follower snapshots), nil otherwise.
    private var wearer: WearerSettings?

    /// The rules this Mac runs: its own alert settings, with the wearer's mirrored values in "Settings from: Wearer".
    var alertSettings: AlertSettings {
        let own = defaults.data(forKey: SettingsSpec.Keys.alerts).map(AlertSettings.decode) ?? AlertSettings()
        guard own.source == .wearer, let wearer else { return own }
        return own.mirroring(wearer)
    }

    /// The alert settings changed (Settings window, a mirrored wearer setting): the rules and the copied sounds follow
    /// at once. Called on every defaults change and snapshot; does nothing when the alert settings are the same.
    func settingsChanged() {
        guard let alerts else { return }
        let s = alertSettings
        guard s != alerts.settings else { return }
        alerts.settings(s)
        sounds?.sync(s)
    }

    func wearerSettings(_ settings: WearerSettings?) {
        guard settings != wearer else { return }
        wearer = settings
        settingsChanged()
    }

    /// The setup walk's "Allow…": asks (or re-reads) the notification permission. False when this run posts none.
    func requestNotifications() async -> Bool {
        guard center != nil else { return false }
        let granted =
            (try? await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge]))
            ?? false
        permissionChanged?()
        return granted
    }

    func start(_ service: Service) {
        switch service {
        case .notifications:
            let center = SystemNotificationCenter()
            self.center = center
            let un = UNUserNotificationCenter.current()
            un.delegate = responder
            un.setNotificationCategories(Notifier.categories(.preferred()))
            un.requestAuthorization(options: [.alert, .sound, .badge]) { [weak self] _, _ in
                Task { @MainActor in self?.permissionChanged?() }
            }
        case .alerts:
            let sounds = Bundle.main.resourceURL.map {
                SoundLibrary(source: $0.appending(path: "Sounds"), paths: plan.data.paths)
            }
            sounds?.sync(alertSettings)
            self.sounds = sounds
            let posting: any NotificationPosting = center ?? SilentCenter()
            let settings = alertSettings
            var controller: AlertController?
            let notifier = Notifier(center: posting, sounds: sounds, clock: SystemClock()) {
                controller?.settings ?? settings
            }
            notifier.eventLog = RotatingFileLog(directory: plan.data.paths.logs)
            controller = AlertController(
                settings: settings, clock: SystemClock(), store: AlertStateFile(paths: plan.data.paths),
                notifier: notifier, wake: TaskWake())
            alerts = controller
            responder.handle = { [weak self] actionID, side, typed in
                if actionID == UNNotificationDefaultActionIdentifier {
                    self?.openPanel?()
                    return
                }
                guard let alerts = self?.alerts,
                    let action = Notifier.action(
                        actionID: actionID, side: side, typed: typed, settings: alerts.settings)
                else { return }
                alerts.perform(action)
            }
        case .updateCheck:
            let installed =
                (Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String)
                .flatMap(AppVersion.init) ?? AppVersion("0.0.0")!
            let check = UpdateCheck(installed: installed, fetcher: URLSessionFetcher(), defaults: defaults)
            updates = check
            Task {
                while !Task.isCancelled {
                    await check.check(userAsked: false)
                    try? await Task.sleep(for: .seconds(24 * 3600))
                }
            }
        case .loginItem:
            loginItem = LoginItem(service: SMAppService.mainApp)
        case .relays:
            let crypto = NostrSDKCrypto()
            let nearby: FollowerRuntime.NearbyFactory? =
                plan.services.ble ? { @Sendable identity in NearbyCentral(identity: identity, crypto: crypto) } : nil
            follower = FollowerRuntime(
                secrets: KeychainStore(service: plan.data.keychainService),
                stores: .files(plan.data.paths, clock: SystemClock()), crypto: crypto, clock: SystemClock(),
                me: Profile(name: Host.current().localizedName ?? "Mac", avatar: nil),
                makeRelays: { relays, key in RelayPool(relays: relays, authKey: key, crypto: crypto) },
                makeNearby: nearby)
        case .widgetFeed:
            widget = makeWidget()
        case .ble:
            break  // Bluetooth runs inside the follower runtime (`relays`)
        }
    }

    /// The app's model over what this run started: the follower runtime (the fixture follower in a fixture run), the
    /// alert engine, the update check, the login item and the widget feed.
    func model(clock: any TimeSource = SystemClock()) -> AppModel {
        var follower: (any FollowerService)? = follower
        #if DEBUG
            if plan.mode == .fixture { follower = FixtureFollower() }
        #endif
        return AppModel(
            plan: plan, clock: clock, alerts: alerts, updates: updates, loginItem: loginItem, follower: follower,
            widget: widget)
    }
}

/// Posts nothing: alerts allowed but notifications not (never in a real plan; keeps the engine's state right).
private struct SilentCenter: NotificationPosting {
    var timeSensitiveAllowed: Bool { false }
    func add(_ request: UNNotificationRequest) {}
    func removeDelivered(ids: [String]) {}
}

/// The notification center's delegate. UserNotifications calls it off the main thread, so it is nonisolated, copies
/// out the Sendable action id, side and typed text, and hands them to the main actor.
final class NotificationResponder: NSObject, UNUserNotificationCenterDelegate {
    /// Set once at launch on the main actor, before the delegate is installed; read on the main actor only.
    var handle: ((String, String?, String?) -> Void)?

    nonisolated func userNotificationCenter(
        _ center: UNUserNotificationCenter, willPresent notification: UNNotification
    ) async -> UNNotificationPresentationOptions {
        [.banner, .sound, .list]
    }

    nonisolated func userNotificationCenter(
        _ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse
    ) async {
        let actionID = response.actionIdentifier
        let side = response.notification.request.content.userInfo[Notifier.sideKey] as? String
        let typed = (response as? UNTextInputNotificationResponse)?.userText
        await MainActor.run { handle?(actionID, side, typed) }
    }
}
