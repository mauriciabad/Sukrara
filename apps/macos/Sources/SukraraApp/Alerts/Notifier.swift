// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation
import SukraraAlerts
import SukraraCore
@preconcurrency import UserNotifications

/// What the alert controller asks of the notification layer.
protocol AlertNotifying {
    /// Posts `firing`, replacing its group's notification. `sound` false = a silent re-show after a relaunch.
    func post(_ firing: AlertFiring, sound: Bool, category: String)
    func clear(_ group: AlertGroup)
    /// The silent "Low alerts muted / Until 10:42" note with Unmute and Change Time…
    func postMuted(_ side: AlertSide, until: Date)
    func removeMuted(_ side: AlertSide)
}

/// The part of `UNUserNotificationCenter` the notifier uses (tests record requests instead of posting).
protocol NotificationPosting {
    func add(_ request: UNNotificationRequest)
    func removeDelivered(ids: [String])
    /// Whether Time Sensitive delivery is on for the app (System Settings > Notifications).
    var timeSensitiveAllowed: Bool { get }
}

/// Builds the alert notifications: self-contained text in the user's language, the pack sound (copied where
/// Notification Center finds it), the actions' category, Time Sensitive for alarms when allowed.
final class Notifier: AlertNotifying {
    nonisolated static let sideKey = "side"
    nonisolated static let firingKey = "firing"

    private let center: any NotificationPosting
    private let sounds: SoundLibrary?
    private let text: AlertText
    private let clock: any TimeSource
    private let settings: () -> AlertSettings
    private var lastVariant: [AlertKind: Int] = [:]
    /// The bug-report log: which alert was posted or cleared, never a value.
    var eventLog: RotatingFileLog?

    init(
        center: any NotificationPosting, sounds: SoundLibrary?, text: AlertText = .preferred(), clock: any TimeSource,
        settings: @escaping () -> AlertSettings
    ) {
        self.center = center
        self.sounds = sounds
        self.text = text
        self.clock = clock
        self.settings = settings
    }

    func post(_ f: AlertFiring, sound: Bool, category: String) {
        let s = settings()
        let content = UNMutableNotificationContent()
        content.title = text.title(f)
        content.body = text.body(f, now: clock.now)
        content.categoryIdentifier = category
        content.threadIdentifier = AlertNotificationIDs.alertCategory
        content.userInfo = [Self.sideKey: f.kind.side.rawValue, Self.firingKey: f.id]
        content.interruptionLevel = s[f.kind].alarm && center.timeSensitiveAllowed ? .timeSensitive : .active
        content.sound = sound ? notificationSound(f.kind, s) : nil
        center.add(
            UNNotificationRequest(
                identifier: AlertNotificationIDs.notificationID(f.kind.group), content: content, trigger: nil))
        eventLog?.write("alert \(f.kind.rawValue) n=\(f.n) \(sound ? "posted" : "resumed silently")")
    }

    func clear(_ group: AlertGroup) {
        eventLog?.write("alert \(group.rawValue) cleared")
        center.removeDelivered(ids: [AlertNotificationIDs.notificationID(group)])
    }

    func postMuted(_ side: AlertSide, until: Date) {
        let content = UNMutableNotificationContent()
        content.title = text.mutedTitle(side)
        content.body = text.mutedBody(until: SukraraCore.Clock.time(until))
        content.categoryIdentifier = AlertNotificationIDs.mutedCategory
        content.userInfo = [Self.sideKey: side.rawValue]
        content.interruptionLevel = .passive
        content.sound = nil
        center.add(
            UNNotificationRequest(
                identifier: AlertNotificationIDs.mutedNotificationID(side), content: content, trigger: nil))
    }

    func removeMuted(_ side: AlertSide) {
        center.removeDelivered(ids: [AlertNotificationIDs.mutedNotificationID(side)])
    }

    private func notificationSound(_ kind: AlertKind, _ s: AlertSettings) -> UNNotificationSound? {
        var rng = SystemRandomNumberGenerator()
        switch SoundLibrary.pick(kind, s, last: lastVariant[kind], using: &rng) {
        case .none: return nil
        case .system: return .default
        case .pack(let name):
            lastVariant[kind] = name.split(separator: "_").last.flatMap { Int($0) }
            // a sound that can't be copied rings the default one, never silence
            guard let file = sounds?.install(name) else { return .default }
            return UNNotificationSound(named: UNNotificationSoundName(file))
        }
    }

    /// The categories (SPEC §2): every alert has *Mute…* (typed length, empty = the default) and *Remind Less* /
    /// *Remind as Usual*; one whose repeats Remind Less wouldn't slow has Mute… alone. The muted note: *Unmute* +
    /// *Change Time…*.
    static func categories(_ text: AlertText) -> Set<UNNotificationCategory> {
        let mute = UNTextInputNotificationAction(
            identifier: AlertNotificationIDs.muteAction, title: text.mute, options: [],
            textInputButtonTitle: text.muteButton, textInputPlaceholder: text.mutePlaceholder)
        let less = UNNotificationAction(identifier: AlertNotificationIDs.remindLessAction, title: text.remindLess)
        let usual = UNNotificationAction(
            identifier: AlertNotificationIDs.remindAsUsualAction, title: text.remindAsUsual)
        let unmute = UNNotificationAction(identifier: AlertNotificationIDs.unmuteAction, title: text.unmute)
        let change = UNTextInputNotificationAction(
            identifier: AlertNotificationIDs.changeMuteAction, title: text.changeTime, options: [],
            textInputButtonTitle: text.muteButton, textInputPlaceholder: text.mutePlaceholder)
        func category(_ id: String, _ actions: [UNNotificationAction]) -> UNNotificationCategory {
            UNNotificationCategory(identifier: id, actions: actions, intentIdentifiers: [])
        }
        return [
            category(AlertNotificationIDs.alertCategory, [mute, less]),
            category(AlertNotificationIDs.alertRelaxedCategory, [mute, usual]),
            category(AlertNotificationIDs.alertMuteOnlyCategory, [mute]),
            category(AlertNotificationIDs.mutedCategory, [unmute, change]),
        ]
    }

    /// The user's answer to a notification as an alert action, or nil (a tap on the body, an unknown side, a typed
    /// length that isn't one). Mute… with nothing typed mutes for the default length; a typed length is clamped to the
    /// longest allowed.
    static func action(actionID: String, side rawSide: String?, typed: String?, settings: AlertSettings)
        -> AlertAction?
    {
        guard let rawSide, let side = AlertSide(rawValue: rawSide) else { return nil }
        switch actionID {
        case AlertNotificationIDs.muteAction, AlertNotificationIDs.changeMuteAction:
            let input = typed?.trimmingCharacters(in: .whitespaces) ?? ""
            let minutes = input.isEmpty ? settings.notificationActions.muteMin : Durations.parse(input)
            guard let minutes, minutes > 0 else { return nil }
            return .mute(side, minutes: min(minutes, Int(AlertLimits.longestMuteMin)))
        case AlertNotificationIDs.remindLessAction: return .remindLess(side)
        case AlertNotificationIDs.remindAsUsualAction: return .remindAsUsual(side)
        case AlertNotificationIDs.unmuteAction: return .unmute(side)
        default: return nil
        }
    }
}

/// `UNUserNotificationCenter` behind `NotificationPosting`. Created only by `AppServices` (a launch whose plan allows
/// notifications), never by tests or offline runs.
final class SystemNotificationCenter: NotificationPosting {
    private let center = UNUserNotificationCenter.current()
    private(set) var timeSensitiveAllowed = false

    func add(_ request: UNNotificationRequest) {
        center.add(request) { error in
            if let error {
                AppLog.alerts.error("notification not posted: \(error.localizedDescription, privacy: .public)")
            }
        }
    }

    func removeDelivered(ids: [String]) { center.removeDeliveredNotifications(withIdentifiers: ids) }

    /// Re-reads what the user allows (launch, app activation, panel open).
    func refresh() async -> NotificationPermission {
        let s = await center.notificationSettings()
        timeSensitiveAllowed = s.timeSensitiveSetting == .enabled
        return NotificationPermission(s)
    }
}

/// What the user allows, for the panel's warning row.
enum NotificationPermission: Equatable {
    case notAsked, allowed, denied
    /// Allowed, but without banners or sound: alerts may go unnoticed.
    case quiet

    init(_ s: UNNotificationSettings) {
        switch s.authorizationStatus {
        case .notDetermined: self = .notAsked
        case .denied: self = .denied
        default: self = s.alertSetting == .enabled && s.soundSetting == .enabled ? .allowed : .quiet
        }
    }

    var needsAttention: Bool { self == .denied || self == .quiet }
}
