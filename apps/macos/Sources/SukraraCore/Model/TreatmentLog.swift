// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation

/// One treatment entry (insulin, carbs, note) as the protocol carries it (`contracts/treatments`, PROTOCOL-v2
/// "Treatments"): `id` is stable, `(u, origin)` orders versions, a delete is a tombstone (`deleted`).
public struct Treatment: Sendable, Codable, Hashable {
    public struct Dose: Sendable, Codable, Hashable {
        public var t: Int
        public var a: Double
        public var primed: Bool?

        public init(t: Int, a: Double, primed: Bool? = nil) {
            self.t = t
            self.a = a
            self.primed = primed
        }
    }

    public var id: String
    /// `rapid`, `long`, `carbs`, `note`, … (the wire's `k`).
    public var kind: String
    public var amount: Double
    /// When it was taken (Unix seconds).
    public var t: Int
    public var note: String
    /// Version (Unix seconds of the last change).
    public var u: Int
    public var deleted: Bool
    /// Who made this version: `wearer`, `follower`, `pen`, …
    public var origin: String
    public var primed: Double?
    public var pen: String?
    public var doses: [Dose]?

    public init(
        id: String, kind: String, amount: Double, t: Int, note: String, u: Int, deleted: Bool, origin: String,
        primed: Double? = nil, pen: String? = nil, doses: [Dose]? = nil
    ) {
        self.id = id
        self.kind = kind
        self.amount = amount
        self.t = t
        self.note = note
        self.u = u
        self.deleted = deleted
        self.origin = origin
        self.primed = primed
        self.pen = pen
        self.doses = doses
    }
}

/// How far a treatment this Mac wrote has got (PROTOCOL-v2 "Treatments": sending → sent → accepted).
public enum TreatmentDelivery: String, Sendable, Codable, Hashable {
    /// Signed and handed to the relays / the phone; no relay confirmed it yet.
    case sending
    /// A relay stored it (`OK true`).
    case sent
    /// The wearer's phone merged it (its echo arrived with the same or a newer version).
    case accepted
}

/// The wearer's treatments as this follower holds them, V1 file format (`v` 1).
public struct TreatmentLog: Sendable, Codable, Hashable {
    public static let formatVersion = 1

    public struct Entry: Sendable, Codable, Hashable {
        public var treatment: Treatment
        /// The public key that created this id (writer scope: only the creator may change it, the wearer always).
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

    /// Format version of the stored log.
    public var v: Int
    /// By treatment id.
    public var entries: [String: Entry]

    public init(v: Int = formatVersion, entries: [String: Entry] = [:]) {
        self.v = v
        self.entries = entries
    }

    /// Visible entries (tombstones left out), newest first.
    public var visible: [Treatment] {
        entries.values.map(\.treatment).filter { !$0.deleted }.sorted { ($0.t, $0.id) > ($1.t, $1.id) }
    }

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
