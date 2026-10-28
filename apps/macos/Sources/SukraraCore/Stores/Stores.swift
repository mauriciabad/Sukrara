// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation

/// The readings history under `SukraraPaths.appSupport` (`ReadingsFileStore`; fake: `SukraraTesting`).
public protocol ReadingsStoring: Sendable {
    /// Readings measured at or after `since`, oldest first.
    func load(since: Date) async throws -> [Reading]
    /// Adds readings; one per timestamp: the first reading stored for a `t` is kept (the sync fixtures' rule).
    func merge(_ readings: [Reading]) async throws
    /// Forgets every reading (unfollow: two people's history never mix).
    func removeAll() async throws
    /// The file was unreadable and moved aside (shown to the person).
    var issue: StoreIssue? { get async }
}

/// The treatment log under `SukraraPaths.appSupport` (`TreatmentFileStore`).
public protocol TreatmentStoring: Sendable {
    func load() async throws -> FollowedTreatments
    func save(_ log: FollowedTreatments) async throws
    /// Forgets every entry (unfollow).
    func removeAll() async throws
    /// The file was unreadable and moved aside (shown to the person).
    var issue: StoreIssue? { get async }
}

/// Secrets (identity and follower keys) in the Keychain, service `SukraraIDs.keychainService` (`KeychainStore`).
public protocol SecretStoring: Sendable {
    /// The stored bytes, or nil when the account has no item.
    func read(account: String) throws -> Data?
    func write(_ data: Data, account: String) throws
    /// Removes the item; removing a missing item is not an error.
    func delete(account: String) throws
}

/// User settings in the standard defaults domain, keyed by the schema (`SettingSpec`).
public protocol SettingsStoring: Sendable {
    /// The stored value, or the spec's default when nothing (or a value of another type) is stored.
    func value<Value>(_ spec: SettingSpec<Value>) -> Value
    func set<Value>(_ value: Value, for spec: SettingSpec<Value>)
}

/// A file that couldn't be read and was moved aside (`<name>.corrupt-<unix s>`): shown to the person, never
/// silently treated as "no data".
public struct StoreIssue: Sendable, Hashable {
    public var file: String
    public var quarantined: URL?

    public init(file: String, quarantined: URL?) {
        self.file = file
        self.quarantined = quarantined
    }
}
