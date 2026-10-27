// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation
import SukraraCore
import SukraraTesting
import Testing

/// `Durations` against `contracts/settings/durations.json` (the cases Android's formatter passes too; Mac is English).
@Suite struct DurationsTests {
    func cases(_ key: String) throws -> [[Any]] {
        let data = try Data(contentsOf: Contracts.url("settings/durations.json"))
        let root = try #require(try JSONSerialization.jsonObject(with: data) as? [String: Any])
        return try #require(root[key] as? [[Any]], "no \(key) cases")
    }

    @Test(arguments: ["text", "compact", "whole"])
    func format(_ key: String) throws {
        let f: (Int) -> String =
            switch key {
            case "text": Durations.text
            case "compact": Durations.compact
            default: Durations.whole
            }
        let list = try cases(key)
        #expect(!list.isEmpty)
        for c in list {
            let minutes = try #require(c[0] as? Int)
            #expect(f(minutes) == c[1] as? String, "\(key)(\(minutes))")
        }
    }

    @Test func parse() throws {
        let list = try cases("parse")
        #expect(!list.isEmpty)
        for c in list {
            let input = try #require(c[0] as? String)
            #expect(Durations.parse(input) == c[1] as? Int, "parse(\"\(input)\")")
        }
    }
}
