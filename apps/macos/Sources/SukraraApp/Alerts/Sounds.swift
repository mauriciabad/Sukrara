// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation
import SukraraAlerts
import SukraraCore
import os

/// What an alert rings.
enum AlertSound: Equatable {
    case none
    /// The system's notification sound.
    case system
    /// A pack sound, `<pack>_<kind>_<variant>` (the bundled `contracts/alerts/sounds/mac/*.caf` without extension).
    case pack(String)
}

/// The alert sound packs and where Notification Center finds them.
///
/// Notification Center plays a custom sound only from `~/Library/Sounds` (the bundle lookup is unreliable
/// for a menu bar app with several registered copies), so the sounds that can ring are copied there as
/// `app.mauri.sukrara.<pack>_<kind>_<n>.caf` (`SukraraPaths.soundFileName`): at launch, when the sound settings change
/// and again right before a notification names one. Only those — every file in that folder shows in System Settings'
/// alert-sound list. Files without the prefix (other apps') are never touched.
struct SoundLibrary {
    static let variants = 3
    private static let log = Logger(subsystem: SukraraIDs.appBundleID, category: "sounds")

    /// The bundle's `Resources/Sounds` (bundle.sh copies the `.caf` files there).
    let source: URL
    let paths: SukraraPaths

    /// The sound for `kind`: with shuffle a random variant other than `last`, else variant 1.
    static func pick(
        _ kind: AlertKind, _ s: AlertSettings, last: Int? = nil, using rng: inout some RandomNumberGenerator
    ) -> AlertSound {
        let pack = s.soundPack(for: kind)
        switch pack {
        case "none": return .none
        case "system": return .system
        default:
            guard AlertSoundSettings.packs.contains(pack) else { return .system }
            let choices = (1...variants).filter { $0 != last }
            let n = s.sounds.shuffle ? choices.randomElement(using: &rng) ?? 1 : 1
            return .pack("\(pack)_\(kind.rawValue)_\(n)")
        }
    }

    /// Every pack sound the settings can ring (each kind's pack × its variants).
    static func ringable(_ s: AlertSettings) -> Set<String> {
        var names: Set<String> = []
        for kind in AlertKind.allCases {
            let pack = s.soundPack(for: kind)
            guard AlertSoundSettings.packs.contains(pack) else { continue }
            for n in 1...variants { names.insert("\(pack)_\(kind.rawValue)_\(n)") }
        }
        return names
    }

    /// Copies `name` into `~/Library/Sounds` unless an identical copy is there. Returns the file name to give
    /// `UNNotificationSound(named:)`, or nil when the copy can't be there (the caller rings the default sound).
    @discardableResult
    func install(_ name: String) -> String? {
        let src = source.appending(path: "\(name).caf", directoryHint: .notDirectory)
        let dst = paths.soundFile(named: name)
        let fm = FileManager.default
        guard let data = try? Data(contentsOf: src) else {
            Self.log.error("sound \(name, privacy: .public) missing from the bundle")
            return nil
        }
        if (try? Data(contentsOf: dst)) == data { return dst.lastPathComponent }
        do {
            try fm.createDirectory(at: paths.sounds, withIntermediateDirectories: true)
            try data.write(to: dst, options: .atomic)
            return dst.lastPathComponent
        } catch {
            Self.log.error(
                "sound \(name, privacy: .public) not copied: \(error.localizedDescription, privacy: .public)")
            return nil
        }
    }

    /// Makes `~/Library/Sounds` hold exactly the sounds `s` can ring (of ours): copies the missing, removes our others.
    func sync(_ s: AlertSettings) {
        let wanted = Self.ringable(s)
        for name in wanted { install(name) }
        let prefix = "\(SukraraIDs.appBundleID)."
        let wantedFiles = Set(wanted.map(SukraraPaths.soundFileName))
        let present = (try? FileManager.default.contentsOfDirectory(atPath: paths.sounds.path)) ?? []
        for file in present where file.hasPrefix(prefix) && file.hasSuffix(".caf") && !wantedFiles.contains(file) {
            try? FileManager.default.removeItem(at: paths.sounds.appending(path: file, directoryHint: .notDirectory))
        }
    }
}
