// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation
import UserNotifications

/// Where the alert sounds must be for Notification Center to play them.
///
/// `UNNotificationSound(named:)` looks in the app container's Library/Sounds, a group container's Library/Sounds, then
/// "the main bundle of the current executable" (developer.apple.com/documentation/usernotifications/unnotificationsound).
/// Notification Center plays the sound in its own process and finds that bundle through the system's record of the app,
/// not our running copy. On the user's Mac (2026-10-21) every Sukrara alert logged `Playing notification sound
/// { nam: quest_high_2.caf }` and then systemsoundserverd played built-in action 22 (the default AIFF), never the CAF,
/// while WhatsApp's bundled sound resolved (`Playing sound … for …`). Many copies with this bundle id are registered
/// (worktree builds, a mounted dmg, deleted test copies), so the bundle lookup isn't something we control.
///
/// For a non-sandboxed app the app container's Library/Sounds is `~/Library/Sounds`, looked up by name only. Each sound
/// is copied there as `Sukrara <pack> <kind> <n>.caf` right before the notification that uses it (only the sounds that
/// actually ring, so System Settings' alert-sound list gains a few, all named Sukrara), and the notification names that
/// copy. Copies that no longer match a bundled sound are removed at launch.
enum NotificationSounds {
    /// `~/Library/Sounds`; tests point it elsewhere with `-soundsDir <dir>`.
    nonisolated static var dir: URL {
        UserDefaults.standard.string(forKey: "soundsDir").map { URL(fileURLWithPath: $0) }
            ?? FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent("Library/Sounds")
    }

    /// "quest_high_2.caf" → "Sukrara quest high 2.caf".
    nonisolated static func installedName(_ file: String) -> String {
        "Sukrara " + file.dropLast(4).replacingOccurrences(of: "_", with: " ") + ".caf"
    }

    /// Copy `file` (a bundled pack sound) into `dir` unless an identical copy is there. Returns the name to give
    /// `UNNotificationSound(named:)`, or nil when it can't be there (the caller falls back to the default sound).
    @discardableResult
    nonisolated static func install(_ file: String, from src: URL?) -> String? {
        guard let src else { return nil }
        let fm = FileManager.default, name = installedName(file), dst = dir.appendingPathComponent(name)
        if let a = try? Data(contentsOf: dst), let b = try? Data(contentsOf: src), a == b { return name }
        do {
            try fm.createDirectory(at: dir, withIntermediateDirectories: true)
            if fm.fileExists(atPath: dst.path) { try fm.removeItem(at: dst) }
            try fm.copyItem(at: src, to: dst)
            return name
        } catch {
            log("alerts: couldn't copy \(file) to \(dir.path): \(error.localizedDescription)")
            return nil
        }
    }

    /// The notification sound for an alert's chosen sound: nil = silent; a named copy in `dir`; `.default` for the
    /// system sound or when the copy failed.
    nonisolated static func content(_ s: Alerts.Sound) -> UNNotificationSound? {
        switch s {
        case .none: return nil
        case .system: return .default
        case .file(let file, let url):
            guard let name = install(file, from: url) else { return .default }
            return UNNotificationSound(named: UNNotificationSoundName(name))
        }
    }

    /// Launch: remove our copies that match no bundled sound any more (a renamed kind or pack).
    nonisolated static func prune() {
        let fm = FileManager.default
        for n in (try? fm.contentsOfDirectory(atPath: dir.path)) ?? [] where n.hasPrefix("Sukrara ") && n.hasSuffix(".caf") {
            let file = n.dropFirst(8).dropLast(4).replacingOccurrences(of: " ", with: "_") + ".caf"
            if Alerts.soundURL(file) == nil { try? fm.removeItem(at: dir.appendingPathComponent(n)) }
        }
    }
}
