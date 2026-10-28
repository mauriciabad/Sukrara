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
