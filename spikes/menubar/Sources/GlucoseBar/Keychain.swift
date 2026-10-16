// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation
import Security

/// One generic-password item in the login Keychain holding the v2 follower state (follower key F, epoch read keys,
/// stream id…) as JSON. Never in UserDefaults, never logged.
///
/// Default ACL: only the app that created the item reads it without a prompt. The spike is ad-hoc signed, so after a
/// rebuild macOS asks once ("Always Allow"). A shipped app would be Developer-ID signed and use the data-protection
/// keychain with its own access group. `-keychainService <name>` lets tests use a throwaway item.
enum Keychain {
    static var service: String { UserDefaults.standard.string(forKey: "keychainService") ?? "app.mauri.sukrara.poc.follower" }

    static func read(_ account: String) -> Data? {
        let q: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service,
                                kSecAttrAccount as String: account, kSecReturnData as String: true,
                                kSecMatchLimit as String: kSecMatchLimitOne]
        var out: CFTypeRef?
        return SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess ? out as? Data : nil
    }

    @discardableResult
    static func write(_ account: String, _ data: Data) -> Bool {
        let base: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service,
                                   kSecAttrAccount as String: account]
        if SecItemUpdate(base as CFDictionary, [kSecValueData as String: data] as CFDictionary) == errSecSuccess { return true }
        var add = base
        add[kSecValueData as String] = data
        add[kSecAttrLabel as String] = "Sukrara follower keys"
        let st = SecItemAdd(add as CFDictionary, nil)
        if st != errSecSuccess { log("keychain: add failed (\(st))") }
        return st == errSecSuccess
    }

    static func delete(_ account: String) {
        let q: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service,
                                kSecAttrAccount as String: account]
        SecItemDelete(q as CFDictionary)
    }

}
