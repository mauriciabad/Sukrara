// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation
import SukraraCore
import Synchronization

/// A clock tests set and advance.
public final class FakeClock: TimeSource {
    private let current: Mutex<Date>

    public init(_ start: Date = Date(timeIntervalSince1970: 1_800_000_000)) {
        current = Mutex(start)
    }

    public var now: Date { current.withLock { $0 } }

    public func advance(by seconds: TimeInterval) {
        current.withLock { $0 += seconds }
    }
}

/// Readings in memory, one per timestamp.
public actor InMemoryReadingsStore: ReadingsStoring {
    private var byTime: [Date: Reading] = [:]

    public init(_ readings: [Reading] = []) {
        for reading in readings { byTime[reading.t] = reading }
    }

    public func load(since: Date) -> [Reading] {
        byTime.values.filter { $0.t >= since }.sorted { $0.t < $1.t }
    }

    public func merge(_ readings: [Reading]) {
        for reading in readings { byTime[reading.t] = reading }
    }
}

public actor InMemoryTreatmentStore: TreatmentStoring {
    private var log: TreatmentLog

    public init(_ log: TreatmentLog = TreatmentLog()) {
        self.log = log
    }

    public func load() -> TreatmentLog { log }
    public func save(_ log: TreatmentLog) { self.log = log }
}

public final class InMemorySecretStore: SecretStoring {
    private let items = Mutex<[String: Data]>([:])

    public init() {}

    public func read(account: String) -> Data? { items.withLock { $0[account] } }
    public func write(_ data: Data, account: String) { items.withLock { $0[account] = data } }
    public func delete(account: String) { _ = items.withLock { $0.removeValue(forKey: account) } }
}

public final class InMemorySettingsStore: SettingsStoring {
    private let values = Mutex<[String: any SettingValue]>([:])

    public init() {}

    public func value<Value>(_ spec: SettingSpec<Value>) -> Value {
        values.withLock { $0[spec.key] as? Value } ?? spec.defaultValue
    }

    public func set<Value>(_ value: Value, for spec: SettingSpec<Value>) {
        values.withLock { $0[spec.key] = value }
    }
}
