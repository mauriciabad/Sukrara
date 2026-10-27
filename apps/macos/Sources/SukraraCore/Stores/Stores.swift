// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation

/// The readings history under `SukraraPaths.appSupport`. Real implementation: chunk 14; fake: `SukraraTesting`.
public protocol ReadingsStoring: Sendable {
    /// Readings measured at or after `since`, oldest first.
    func load(since: Date) async throws -> [Reading]
    /// Adds readings; one per timestamp (a reading with an existing `t` replaces it).
    func merge(_ readings: [Reading]) async throws
}

/// The treatment log under `SukraraPaths.appSupport`.
public protocol TreatmentStoring: Sendable {
    func load() async throws -> TreatmentLog
    func save(_ log: TreatmentLog) async throws
}

/// Secrets (identity and follower keys) in the Keychain, service `SukraraIDs.keychainService`.
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
