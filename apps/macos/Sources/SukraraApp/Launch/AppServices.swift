// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation
import ServiceManagement
import SukraraAlerts
import SukraraCore
@preconcurrency import UserNotifications

/// The app's `ServiceStarting`: the only code that creates the notification center, copies alert sounds, runs the
/// alert engine, checks for updates or touches the login item. `ServicesPlan.start(using:)` calls it, so an offline
/// run (dev build, snapshot) creates none of them. BLE, relays and the widget feed are started by the follower
/// runtime's chunk (15) and the widget's (22).
final class AppServices: ServiceStarting {
    let plan: LaunchPlan
    private(set) var center: SystemNotificationCenter?
    private(set) var alerts: AlertController?
    private(set) var updates: UpdateCheck?
    private(set) var loginItem: LoginItem?
    private(set) var sounds: SoundLibrary?
    let responder = NotificationResponder()
    private let defaults: UserDefaults

    init(plan: LaunchPlan) {
        self.plan = plan
        defaults = plan.data.defaultsSuite.flatMap(UserDefaults.init(suiteName:)) ?? .standard
    }

    var alertSettings: AlertSettings {
        defaults.data(forKey: SettingsSpec.Keys.alerts).map(AlertSettings.decode) ?? AlertSettings()
    }

    func start(_ service: Service) {
        switch service {
        case .notifications:
            let center = SystemNotificationCenter()
            self.center = center
            let un = UNUserNotificationCenter.current()
            un.delegate = responder
            un.setNotificationCategories(Notifier.categories(.preferred()))
            un.requestAuthorization(options: [.alert, .sound, .badge]) { _, _ in }
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
            controller = AlertController(
                settings: settings, clock: SystemClock(), store: AlertStateFile(paths: plan.data.paths),
                notifier: notifier, wake: TaskWake())
            alerts = controller
            responder.handle = { [weak self] actionID, side, typed in
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
        case .ble, .relays, .widgetFeed:
            break  // started by the follower runtime / widget feed chunks
        }
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
