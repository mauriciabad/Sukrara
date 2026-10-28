// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation

/// One glucose reading as the sensor (or the wearer's phone) reported it.
///
/// Encoded as `{"t": <Unix epoch milliseconds>, "mgdl": <Int>, "rate": <mg/dL per minute>?}` whatever the
/// encoder's date strategy, so files and the App Group snapshot never depend on how an encoder was configured.
public struct Reading: Sendable, Hashable {
    /// When the sensor measured it.
    public var t: Date
    /// Glucose in mg/dL (the stored unit; mmol/L is a display choice).
    public var mgdl: Int
    /// Rate of change in **mg/dL per minute**, when the source reports one.
    public var rate: Double?

    public init(t: Date, mgdl: Int, rate: Double? = nil) {
        self.t = t
        self.mgdl = mgdl
        self.rate = rate
    }
}

extension Reading: Codable {
    private enum CodingKeys: String, CodingKey {
        case t, mgdl, rate
    }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        let millis = try c.decode(Int64.self, forKey: .t)
        t = Date(timeIntervalSince1970: Double(millis) / 1000)
        mgdl = try c.decode(Int.self, forKey: .mgdl)
        rate = try c.decodeIfPresent(Double.self, forKey: .rate)
    }

    public func encode(to encoder: any Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(Int64((t.timeIntervalSince1970 * 1000).rounded()), forKey: .t)
        try c.encode(mgdl, forKey: .mgdl)
        try c.encodeIfPresent(rate, forKey: .rate)
    }
}

/// The range a glucose number is shown in. Outside it a reading is a flag, LO or HI, never a number: CGMs measure
/// about 40–400 mg/dL and say "LO" / "HI" beyond. LO / HI travel as 39 / 401 mg/dL (the values Nightscout, xDrip+ and
/// the Dexcom share use). The card shows the flag and the alert rules (contracts/alerts/SPEC.md §1) treat LO as below
/// every low threshold and HI as above every high one. Android `:core:model` `GlucoseRange` has the same numbers.
public enum GlucoseRange {
    public static let floor = 40
    public static let ceiling = 400

    /// How a LO reading is stored and sent.
    public static let lo = floor - 1

    /// How a HI reading is stored and sent.
    public static let hi = ceiling + 1

    public static func isLo(_ mgdl: Int) -> Bool { mgdl < floor }

    public static func isHi(_ mgdl: Int) -> Bool { mgdl > ceiling }

    public static func isLo(_ mgdl: Double) -> Bool { mgdl < Double(floor) }

    public static func isHi(_ mgdl: Double) -> Bool { mgdl > Double(ceiling) }

    /// `reading` as it is stored (Android `GlucoseRange.normalise`, at the hub): any number below the floor becomes
    /// `lo` and any above the ceiling `hi`, without a rate, so a wearer's raw 35 or 450 reaches the card, graph and
    /// export as the flag.
    public static func normalised(_ reading: Reading) -> Reading {
        if isLo(reading.mgdl) { return Reading(t: reading.t, mgdl: lo, rate: nil) }
        if isHi(reading.mgdl) { return Reading(t: reading.t, mgdl: hi, rate: nil) }
        return reading
    }
}
