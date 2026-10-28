// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation
import SukraraCore

/// What the app hands the widget through the App Group (`SukraraPaths.appGroupSnapshot()`): the card's whole input,
/// so the widget draws exactly what the app's card shows. Versioned: a snapshot of another version reads as none
/// (the widget then shows its "open Sukrara" state, never a guessed value), and the app rewrites it on its next
/// reading. It lives with the renderer (not SukraraCore) because it carries the card's types.
public struct WidgetSnapshot: Sendable, Codable, Equatable {
    /// The format this build writes and reads.
    public static let version = 1

    public var v: Int
    /// When the app wrote it (epoch s): the widget's "updated" time, never the reading's.
    public var writtenAt: TimeInterval
    public var data: CardData
    public var config: GraphConfig
    public var unit: GlucoseUnit

    public init(writtenAt: TimeInterval, data: CardData, config: GraphConfig, unit: GlucoseUnit) {
        self.v = Self.version
        self.writtenAt = writtenAt
        self.data = data
        self.config = config
        self.unit = unit
    }

    public func encoded() throws -> Data {
        let encoder = JSONEncoder()
        encoder.outputFormatting = .sortedKeys
        return try encoder.encode(self)
    }

    /// The snapshot in `data`, or nil when it is unreadable or of another version.
    public static func decode(_ data: Data) -> WidgetSnapshot? {
        guard let s = try? JSONDecoder().decode(WidgetSnapshot.self, from: data), s.v == version else { return nil }
        return s
    }
}
