// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation

/// A glucose reading as the rules see it: when it was taken, mg/dL, and its trend in mg/dL/min (nil = unknown).
public struct GlucoseSample: Codable, Equatable, Sendable {
    public var at: Date
    public var mgdl: Double
    public var rate: Double?

    public init(at: Date, mgdl: Double, rate: Double? = nil) {
        self.at = at
        self.mgdl = mgdl
        self.rate = rate
    }
}

/// A logged treatment that can quiet alerts (deleted entries are never passed in).
public struct AlertTreatment: Equatable, Sendable {
    public var kind: TreatmentKind
    public var at: Date

    public init(kind: TreatmentKind, at: Date) {
        self.kind = kind
        self.at = at
    }
}

/// The current sensor's life (the live event's ss / se / sw): no `no_data` alert while it has ended or warms up.
public struct SensorWindow: Codable, Equatable, Sendable {
    /// The warm-up state lasts until the first reading, at most this long past the warm-up's end.
    public static let warmupGrace: TimeInterval = 15 * 60

    public var start: Date
    public var end: Date
    public var warmupEnd: Date

    public init(start: Date, end: Date, warmupEnd: Date) {
        self.start = start
        self.end = end
        self.warmupEnd = warmupEnd
    }

    public func hasEnded(at t: Date) -> Bool { t >= end }

    /// Warming up: started, no reading of this sensor yet, and not more than the grace past the warm-up's end.
    public func isWarmingUp(at t: Date, latestReading: Date?) -> Bool {
        t >= start && t < warmupEnd.addingTimeInterval(Self.warmupGrace) && (latestReading.map { $0 < start } ?? true)
    }

    /// No reading is expected: the sensor has ended or is warming up.
    public func expectsNoReading(at t: Date, latestReading: Date?) -> Bool {
        hasEnded(at: t) || isWarmingUp(at: t, latestReading: latestReading)
    }
}

/// One firing of an alert (SPEC §4 fields): `id` random per firing; a clear repeats the id of the firing it ends.
public struct AlertFiring: Codable, Equatable, Sendable {
    public var id: String
    public var kind: AlertKind
    public var at: Date
    /// The reading that triggered it (`no_data`: the last reading, may be nil).
    public var reading: GlucoseSample?
    /// The threshold that matched (mg/dL, mg/dL/min, or minutes for `no_data`).
    public var threshold: Double
    /// 1 for the first firing of the episode, 2… for the next ones.
    public var n: Int
    /// Answering treatments acknowledged in the headline: 0, 1, or 2 for two or more.
    public var treated: Int

    public init(id: String, kind: AlertKind, at: Date, reading: GlucoseSample?, threshold: Double, n: Int, treated: Int)
    {
        self.id = id
        self.kind = kind
        self.at = at
        self.reading = reading
        self.threshold = threshold
        self.n = n
        self.treated = treated
    }

    public var headline: String { kind.headline(treated: treated) }

    /// 16 lowercase hex characters (SPEC §4).
    static func newID(using rng: inout some RandomNumberGenerator) -> String {
        let v = UInt64.random(in: .min ... .max, using: &rng)
        let hex = String(v, radix: 16)
        return String(repeating: "0", count: 16 - hex.count) + hex
    }
}

/// Why an episode is quiet now (the UI words it with `MuteCopy`).
public enum QuietReason: Codable, Equatable, Sendable {
    /// A treatment logged at `at` quiets it until `until`.
    case treatment(TreatmentKind, at: Date, until: Date)
    /// Falling back below the high threshold within the falling rule's window; no end of its own.
    case fallingBack(inMinutes: Int)
    /// This device's Mute of the side.
    case sideMuted(until: Date)
    /// This device's Mute all (`muteAllForever` = until turned off).
    case allMuted(until: Date)
}
