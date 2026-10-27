// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation

/// A type a setting can hold: one of the property-list scalars UserDefaults stores natively, so a store never
/// needs an untyped cast beyond `as? Value`.
public protocol SettingValue: Sendable, Equatable {}
extension Bool: SettingValue {}
extension Int: SettingValue {}
extension Double: SettingValue {}
extension String: SettingValue {}
extension Data: SettingValue {}

/// One setting as the schema (`contracts/settings/settings.json`) defines it on the Mac: its defaults key and the
/// value used when nothing is stored. `tools/settings/gen.py` emits one `static let` per setting in
/// `SettingsSpec.swift`; code never spells a defaults key by hand.
public struct SettingSpec<Value: SettingValue>: Sendable {
    public let key: String
    public let defaultValue: Value

    public init(key: String, defaultValue: Value) {
        self.key = key
        self.defaultValue = defaultValue
    }
}
