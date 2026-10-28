// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation

/// The Mac's settings store: the app's UserDefaults (or an injected suite in tests and snapshots). Keys come only
/// from the generated `SettingsSpec.Keys` through `MacSettings`; a stored value of the wrong type reads as the
/// default.
public final class DefaultsSettingsStore: SettingsStoring, @unchecked Sendable {
    // UserDefaults is documented thread-safe; the class holds no other state.
    private let defaults: UserDefaults

    public init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    public func value<Value>(_ spec: SettingSpec<Value>) -> Value {
        defaults.object(forKey: spec.key) as? Value ?? spec.defaultValue
    }

    public func set<Value>(_ value: Value, for spec: SettingSpec<Value>) {
        defaults.set(value, forKey: spec.key)
    }
}
