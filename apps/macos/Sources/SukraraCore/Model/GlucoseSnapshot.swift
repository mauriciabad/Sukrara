// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation

/// What the app shows right now and hands to the widget through the App Group (`SukraraPaths.appGroupSnapshotName`).
/// Placeholder shape: chunk 14 (core + graph) defines the full card payload and its versioned codec.
public struct GlucoseSnapshot: Sendable, Codable, Hashable {
    /// Format version of the encoded snapshot.
    public var v: Int
    /// The readings the card draws, oldest first; the last one is the current value.
    public var readings: [Reading]

    public init(v: Int = 1, readings: [Reading]) {
        self.v = v
        self.readings = readings
    }

    /// The newest reading, or nil when there is none.
    public var latest: Reading? { readings.last }
}
