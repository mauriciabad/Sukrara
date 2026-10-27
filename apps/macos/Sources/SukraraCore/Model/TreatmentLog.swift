// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation

/// The wearer's treatments (insulin, carbs, notes), one version per id, deleted entries kept as tombstones so a
/// deletion wins over an older copy arriving later.
public struct TreatmentLog: Sendable, Codable, Hashable {
    /// Format version of the stored log.
    public var v: Int
    /// Every entry by id (tombstones included).
    public private(set) var entries: [String: Treatment]

    public init(v: Int = 1, entries: [Treatment] = []) {
        self.v = v
        self.entries = [:]
        merge(entries)
    }

    /// Merges `incoming` with the SPEC §1 rule (`TreatmentMerge.winner`); returns the entries that changed.
    @discardableResult
    public mutating func merge(_ incoming: [Treatment]) -> [Treatment] {
        var changed: [Treatment] = []
        for inc in incoming {
            let won = TreatmentMerge.winner(entries[inc.id], inc)
            if won != entries[inc.id] {
                entries[inc.id] = won
                changed.append(won)
            }
        }
        return changed
    }

    /// The live (not deleted) entries, oldest first (ties by id, so the order is stable).
    public var live: [Treatment] {
        entries.values.filter { !$0.del }.sorted { ($0.t, $0.id) < ($1.t, $1.id) }
    }
}
