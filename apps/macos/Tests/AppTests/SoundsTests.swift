// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation
import SukraraAlerts
import SukraraCore
import SukraraTesting
import Testing

@testable import SukraraApp

/// Sounds are copied into a temporary home, never the real `~/Library/Sounds`.
@Suite struct SoundsTests {
    let root = FileManager.default.temporaryDirectory.appending(path: "sukrara-sounds-\(UUID().uuidString)")
    var source: URL { root.appending(path: "bundle") }
    var paths: SukraraPaths { SukraraPaths(home: root.appending(path: "home")) }
    var library: SoundLibrary { SoundLibrary(source: source, paths: paths) }

    func makeBundle() throws {
        try FileManager.default.createDirectory(at: source, withIntermediateDirectories: true)
        for pack in AlertSoundSettings.packs {
            for kind in AlertKind.allCases {
                for n in 1...SoundLibrary.variants {
                    try Data("\(pack)\(kind)\(n)".utf8).write(
                        to: source.appending(path: "\(pack)_\(kind.rawValue)_\(n).caf"))
                }
            }
        }
    }

    func installed() -> Set<String> {
        Set((try? FileManager.default.contentsOfDirectory(atPath: paths.sounds.path)) ?? [])
    }

    @Test func installAppliesThePrefixAndIsIdempotent() throws {
        try makeBundle()
        let name = library.install("quest_low_2")
        #expect(name == "app.mauri.sukrara.quest_low_2.caf")
        let first = try FileManager.default.attributesOfItem(atPath: paths.soundFile(named: "quest_low_2").path)
        #expect(library.install("quest_low_2") == name)
        let second = try FileManager.default.attributesOfItem(atPath: paths.soundFile(named: "quest_low_2").path)
        #expect(first[.modificationDate] as? Date == second[.modificationDate] as? Date)
    }

    @Test func aMissingSoundReturnsNil() throws {
        try makeBundle()
        #expect(library.install("quest_nothing_1") == nil)
    }

    @Test func syncInstallsOnlyRingableSoundsAndLeavesOthersFilesAlone() throws {
        try makeBundle()
        try FileManager.default.createDirectory(at: paths.sounds, withIntermediateDirectories: true)
        // the Showcase's copy, another app's, and a stale one of ours
        for f in ["Sukrara quest low 1.caf", "Other.caf", "app.mauri.sukrara.kart_low_1.caf"] {
            try Data("x".utf8).write(to: paths.sounds.appending(path: f))
        }
        var s = AlertSettings()
        s.sounds.pack = "arcade"
        s.sounds.kinds = ["low": "none", "high": "system"]
        library.sync(s)
        let files = installed()
        #expect(files.contains("Sukrara quest low 1.caf") && files.contains("Other.caf"))
        #expect(!files.contains("app.mauri.sukrara.kart_low_1.caf"))
        #expect(files.contains("app.mauri.sukrara.arcade_very_low_3.caf"))
        #expect(!files.contains { $0.contains("_low_") && !$0.contains("very_low") })
        #expect(files.filter { $0.hasPrefix("app.mauri.sukrara.") }.count == (AlertKind.allCases.count - 2) * 3)
    }

    @Test func pickHonoursPackOverridesAndShuffle() {
        var rng = SystemRandomNumberGenerator()
        var s = AlertSettings()
        s.sounds.kinds = ["low": "none", "high": "system"]
        #expect(SoundLibrary.pick(.low, s, using: &rng) == AlertSound.none)
        #expect(SoundLibrary.pick(.high, s, using: &rng) == .system)
        s.sounds.shuffle = false
        #expect(SoundLibrary.pick(.veryLow, s, using: &rng) == .pack("quest_very_low_1"))
        s.sounds.shuffle = true
        for _ in 0..<20 { #expect(SoundLibrary.pick(.veryLow, s, last: 2, using: &rng) != .pack("quest_very_low_2")) }
    }
}
