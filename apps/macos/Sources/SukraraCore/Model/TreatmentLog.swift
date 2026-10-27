// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation

/// The wearer's treatments (insulin, carbs, notes). Placeholder shape: the treatments chunk defines the entries,
/// the merge and the versioned file format from `contracts/treatments`.
public struct TreatmentLog: Sendable, Codable, Hashable {
    /// Format version of the stored log.
    public var v: Int

    public init(v: Int = 1) {
        self.v = v
    }
}
