// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation
import SukraraCore
import Testing

/// The Keychain under a throwaway service (never `app.mauri.sukrara`); every test removes its item.
struct KeychainStoreTests {
    /// Whether this machine has a usable login keychain (CI runners and sandboxes may not).
    static let loginKeychainUsable: Bool = {
        let probe = KeychainStore(service: "app.mauri.sukrara.test.probe-\(UUID().uuidString)")
        defer { try? probe.delete(account: "probe") }
        return (try? probe.write(Data([1]), account: "probe")) != nil
    }()

    let store = KeychainStore(service: "app.mauri.sukrara.test-\(UUID().uuidString)")

    @Test(.enabled(if: loginKeychainUsable, "no usable login keychain on this machine: Keychain round-trip NOT tested"))
    func roundTripUpdateAndDelete() throws {
        defer { try? store.delete(account: "follower") }
        #expect(try store.read(account: "follower") == nil)  // missing item = not following
        try store.write(Data("one".utf8), account: "follower")
        try store.write(Data("two".utf8), account: "follower")  // update in place
        #expect(try store.read(account: "follower") == Data("two".utf8))
        try store.delete(account: "follower")
        #expect(try store.read(account: "follower") == nil)
        try store.delete(account: "follower")  // deleting a missing item is fine
    }

    @Test func defaultServiceIsTheV1One() {
        #expect(KeychainStore().service == "app.mauri.sukrara")
        #expect(!KeychainStore().useDataProtectionKeychain)
    }

    /// Without a provisioning profile (swift test, Apple Development signature) the data-protection keychain refuses:
    /// that must surface as an error, never as "no item" (which would read as "not following").
    @Test func dataProtectionKeychainWithoutEntitlementFailsVisibly() {
        let dp = KeychainStore(service: "app.mauri.sukrara.test-\(UUID().uuidString)", useDataProtectionKeychain: true)
        defer { try? dp.delete(account: "x") }
        do {
            try dp.write(Data([1]), account: "x")
            // Entitled build: the item round-trips.
            #expect((try? dp.read(account: "x")) == Data([1]))
        } catch let error as KeychainError {
            #expect(error == .missingEntitlement)
        } catch {
            Issue.record("unexpected error \(error)")
        }
    }
}
