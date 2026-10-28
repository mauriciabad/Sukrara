// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation
import Security

/// GlucoseBar (`dev.spike.GlucoseBar`) became **Sukrara for Mac** (`app.mauri.sukrara.poc`). The bundle id names the
/// defaults domain, so on the first launch of the renamed app copy the old state over once:
/// - defaults (`lvEmail`, `lvUpload`, `url`, `unit`, `ble`, …): keys the new domain doesn't have yet,
/// - the v2 follower Keychain item (`dev.spike.GlucoseBar.follower` → `app.mauri.sukrara.poc.follower`), so the Mac
///   keeps following without re-pairing. The old item's ACL names the old (ad-hoc signed) binary, so macOS asks once
///   whether Sukrara may read it,
/// - `~/Library/Application Support/GlucoseBar` (treatments, LibreView ledger) → `…/Sukrara`.
/// The old items are left in place (delete them by hand once Sukrara works). Runs before anything reads defaults.
enum Migration {
    static let oldDomain = "dev.spike.GlucoseBar"
    static let oldKeychainService = "dev.spike.GlucoseBar.follower"
    private static let doneKey = "migratedFromGlucoseBar"

    static func run() {
        guard Bundle.main.bundleIdentifier == "app.mauri.sukrara.poc" else { return }   // unbundled runs / tests
        let d = UserDefaults.standard
        guard !d.bool(forKey: doneKey) else { return }
        var notes: [String] = []

        if let old = d.persistentDomain(forName: oldDomain), !old.isEmpty {
            var cur = d.persistentDomain(forName: Bundle.main.bundleIdentifier!) ?? [:]
            var copied: [String] = []
            for (k, v) in old where cur[k] == nil && !k.hasPrefix("NSStatusItem") { cur[k] = v; copied.append(k) }
            d.setPersistentDomain(cur, forName: Bundle.main.bundleIdentifier!)
            notes.append("defaults: copied \(copied.sorted().joined(separator: ", "))")
        }

        let support = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        let oldDir = support.appendingPathComponent("GlucoseBar"), newDir = support.appendingPathComponent("Sukrara")
        if FileManager.default.fileExists(atPath: oldDir.path), !FileManager.default.fileExists(atPath: newDir.path) {
            notes.append((try? FileManager.default.copyItem(at: oldDir, to: newDir)) != nil
                ? "files: Application Support/GlucoseBar copied to Sukrara" : "files: copy FAILED")
        }

        d.set(true, forKey: doneKey)
        log("migration from GlucoseBar: " + (notes.isEmpty ? "nothing to migrate" : notes.joined(separator: "; ")))
    }

    /// The follower Keychain item, off the main thread: reading the old item makes macOS ask once ("Sukrara wants to
    /// use … dev.spike.GlucoseBar.follower"), and that prompt blocks the caller until answered. Run in the background
    /// so the menubar (and the Bluetooth sources) work meanwhile; [done] runs on the main queue when the state was
    /// copied. Retried on each launch until the new item exists or the old one is gone.
    static func keychain(done: @escaping @MainActor () -> Void) {
        guard Bundle.main.bundleIdentifier == "app.mauri.sukrara.poc",
              UserDefaults.standard.object(forKey: "keychainService") == nil else { return }
        DispatchQueue.global(qos: .utility).async {
            var notes: [String] = []
            if Keychain.read(FollowerV2State.account) == nil {
                let q: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: oldKeychainService,
                                        kSecAttrAccount as String: FollowerV2State.account, kSecReturnData as String: true,
                                        kSecMatchLimit as String: kSecMatchLimitOne]
                var out: CFTypeRef?
                let st = SecItemCopyMatching(q as CFDictionary, &out)
                if st == errSecSuccess, let data = out as? Data, Keychain.read(FollowerV2State.account) == nil {   // not re-followed meanwhile
                    notes.append(Keychain.write(FollowerV2State.account, data) ? "keychain: follower state copied" : "keychain: copy FAILED")
                } else if st != errSecItemNotFound {
                    notes.append("keychain: old item not readable (\(st)), follow again from the menu")
                }
            }

            guard !notes.isEmpty else { return }
            log("migration from GlucoseBar: " + notes.joined(separator: "; "))
            if notes.contains("keychain: follower state copied") { DispatchQueue.main.async { MainActor.assumeIsolated { done() } } }
        }
    }
}
