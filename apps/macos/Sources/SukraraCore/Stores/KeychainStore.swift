// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation
import Security

/// Why a Keychain call failed. Shown as a broken link ("Can't read the Keychain"), never as "not following".
public enum KeychainError: Error, Equatable, Sendable {
    /// The data-protection keychain needs a `keychain-access-groups` entitlement authorised by a provisioning profile
    /// (-34018); the Apple Development signature has none.
    case missingEntitlement
    /// Locked keychain, denied access, … (the `OSStatus`).
    case status(Int32)
}

/// Generic-password items under one service (default `SukraraIDs.keychainService`; tests pass a temporary one).
///
/// Uses the **login (file-based) keychain** by default: the app is signed with an Apple Development identity and no
/// provisioning profile, so the data-protection keychain refuses every call with errSecMissingEntitlement (TN3137;
/// the same reason the PoC used the login keychain). `useDataProtectionKeychain` stays available for a profile-signed
/// build and is tested to fail visibly. Items are readable only after first unlock and never sync.
public struct KeychainStore: SecretStoring {
    /// The account holding the follower state (`FollowerState` JSON).
    public static let followerAccount = "follower"

    public let service: String
    public let useDataProtectionKeychain: Bool

    public init(service: String = SukraraIDs.keychainService, useDataProtectionKeychain: Bool = false) {
        self.service = service
        self.useDataProtectionKeychain = useDataProtectionKeychain
    }

    public func read(account: String) throws -> Data? {
        var query = base(account)
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: AnyObject?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        if status == errSecItemNotFound { return nil }
        try check(status)
        guard let data = result as? Data else { throw KeychainError.status(errSecDecode) }
        return data
    }

    public func write(_ data: Data, account: String) throws {
        let update: [String: Any] = [kSecValueData as String: data]
        let status = SecItemUpdate(base(account) as CFDictionary, update as CFDictionary)
        if status != errSecItemNotFound { return try check(status) }
        var add = base(account)
        add[kSecValueData as String] = data
        add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        try check(SecItemAdd(add as CFDictionary, nil))
    }

    public func delete(account: String) throws {
        let status = SecItemDelete(base(account) as CFDictionary)
        if status == errSecItemNotFound { return }
        try check(status)
    }

    private func base(_ account: String) -> [String: Any] {
        var query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
        if useDataProtectionKeychain { query[kSecUseDataProtectionKeychain as String] = true }
        return query
    }

    private func check(_ status: OSStatus) throws {
        switch status {
        case errSecSuccess: return
        case errSecMissingEntitlement: throw KeychainError.missingEntitlement
        default: throw KeychainError.status(status)
        }
    }
}
