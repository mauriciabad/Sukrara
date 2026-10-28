// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation
import SukraraTesting
import Testing

/// The shared graph fixtures (`contracts/graph`, the files Android's GraphFixtureTest / CardLayoutTest run too).
enum GraphFixtures {
    /// "Now" of every fixture: times in the files are seconds relative to it.
    static let now: TimeInterval = 1_800_000_000

    /// `contracts/graph`.
    static let dir: URL = Contracts.url("graph")

    /// A JSON file under the fixtures folder as a dictionary (a missing or broken file fails the test that asks).
    static func object(_ path: String) -> JSON {
        guard let data = FileManager.default.contents(atPath: dir.appending(path: path).path),
            let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
        else {
            Issue.record("graph fixture \(path) unreadable under \(dir.path)")
            return JSON([:])
        }
        return JSON(root)
    }

    /// The `cases` of every `*.json` in `folder` (or of one file when `folder` ends in `.json`), named "file: case".
    static func cases(_ folder: String) -> [JSON] {
        let files: [String]
        if folder.hasSuffix(".json") {
            files = [folder]
        } else {
            let names = (try? FileManager.default.contentsOfDirectory(atPath: dir.appending(path: folder).path)) ?? []
            files = names.filter { $0.hasSuffix(".json") }.sorted().map { "\(folder)/\($0)" }
        }
        return files.flatMap { file in
            (object(file)["cases"] as? [[String: Any]] ?? []).map { c in
                var named = c
                named["name"] = "\(file): \(c["name"] as? String ?? "?")"
                return JSON(named)
            }
        }
    }
}

/// One immutable JSON object from a fixture, passable as a Swift Testing argument.
struct JSON: @unchecked Sendable, CustomTestStringConvertible {
    // Immutable after init (a copied Foundation dictionary), so sharing it between test tasks is safe.
    private let object: [String: Any]

    init(_ object: [String: Any]) { self.object = object }

    subscript(key: String) -> Any? { object[key] }

    var testDescription: String { object["name"] as? String ?? "?" }

    func number(_ key: String) -> Double? { (object[key] as? NSNumber)?.doubleValue }
    func string(_ key: String) -> String? { object[key] as? String }
    func bool(_ key: String) -> Bool? { object[key] as? Bool }
    func child(_ key: String) -> JSON { JSON(object[key] as? [String: Any] ?? [:]) }
    var expect: JSON { child("expect") }
}
