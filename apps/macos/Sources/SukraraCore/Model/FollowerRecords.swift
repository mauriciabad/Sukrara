// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation

/// How far a treatment this Mac wrote has got (PROTOCOL-v2 "Treatments": sending → sent → accepted).
public enum TreatmentDelivery: String, Sendable, Codable, Hashable {
    /// Signed and handed to the relays / the phone; no relay confirmed it yet.
    case sending
    /// A relay stored it (`OK true`).
    case sent
    /// The wearer's phone merged it (its echo arrived with the same or a newer version).
    case accepted
}

/// The followed wearer's treatments as this Mac stores them (`treatments.json`, format `v` 1): each entry with
/// the key that created it (writer scope) and, for entries written here, their delivery.
public struct FollowedTreatments: Sendable, Codable, Hashable {
    public static let formatVersion = 1

    public struct Entry: Sendable, Codable, Hashable {
        public var treatment: Treatment
        /// The public key that created this id (only the creator may change it, the wearer always).
        public var creator: String
        /// Only for entries this Mac wrote.
        public var delivery: TreatmentDelivery?
        /// The event id of the last version this Mac sent (matches a relay's `OK`).
        public var sentEventId: String?

        public init(
            treatment: Treatment, creator: String, delivery: TreatmentDelivery? = nil, sentEventId: String? = nil
        ) {
            self.treatment = treatment
            self.creator = creator
            self.delivery = delivery
            self.sentEventId = sentEventId
        }
    }

    public var v: Int
    /// By treatment id (tombstones included).
    public var entries: [String: Entry]

    public init(v: Int = formatVersion, entries: [String: Entry] = [:]) {
        self.v = v
        self.entries = entries
    }

    /// The entries as the shared treatment log (merge rules, live entries) for the card and the windows.
    public var log: TreatmentLog { TreatmentLog(entries: entries.values.map(\.treatment)) }

    /// Entries this Mac wrote that no relay has confirmed yet (shown before "Stop following").
    public var unsentCount: Int { entries.values.filter { $0.delivery == .sending }.count }
}

/// The wearer's current sensor as their phone last reported it (Unix seconds).
public struct WearerSensor: Sendable, Codable, Hashable {
    public var start: Int
    public var end: Int
    public var warmupEnd: Int

    public init(start: Int, end: Int, warmupEnd: Int) {
        self.start = start
        self.end = end
        self.warmupEnd = warmupEnd
    }
}

/// The newest settings the wearer's phone mirrored to followers (PROTOCOL-v2 "Settings"): `u` and the raw payload.
public struct MirroredSettings: Sendable, Codable, Hashable {
    public var u: Int
    public var json: String

    public init(u: Int, json: String) {
        self.u = u
        self.json = json
    }
}
