// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
//
// The few Trio symbols BloodGlucose.swift needs, so the unmodified model file compiles on its own.
import Foundation

protocol JSON: Codable {}

func rounded(_ value: Decimal, scale: Int, roundingMode: NSDecimalNumber.RoundingMode) -> Decimal {
    var result = Decimal()
    var v = value
    NSDecimalRound(&result, &v, scale, roundingMode)
    return result
}

// Trio/Sources/Helpers/Formatters.swift:19-29 and :117-124, verbatim in behaviour
extension Formatter {
    static let iso8601withFractionalSeconds: ISO8601DateFormatter = {
        let f = ISO8601DateFormatter(); f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]; return f
    }()
    static let iso8601: ISO8601DateFormatter = {
        let f = ISO8601DateFormatter(); f.formatOptions = [.withInternetDateTime]; return f
    }()
}

extension JSONDecoder.DateDecodingStrategy {
    static let customISO8601 = custom {
        let container = try $0.singleValueContainer()
        let string = try container.decode(String.self)
        if let date = Formatter.iso8601withFractionalSeconds.date(from: string) ?? Formatter.iso8601.date(from: string) {
            return date
        }
        throw DecodingError.dataCorruptedError(in: container, debugDescription: "Invalid date: \(string)")
    }
}

// Trio/Sources/Helpers/JSON.swift:91-95
enum JSONCoding {
    static var decoder: JSONDecoder { let d = JSONDecoder(); d.dateDecodingStrategy = .customISO8601; return d }
}

// Trio/Sources/APS/OpenAPSSwift/Extensions/Decimal+rounding.swift:16
extension Decimal {
    func rounded() -> Decimal { Trio.rounded(self, scale: 0, roundingMode: .plain) }
}
