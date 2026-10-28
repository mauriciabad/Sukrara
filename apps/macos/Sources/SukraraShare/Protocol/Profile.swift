// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation

/// A generated picture's numbers (PROTOCOL-v2 "Profiles"), kept as the received JSON so a newer renderer version is
/// stored and forwarded unchanged. Drawing is the UI's job; v1 normalisation and the editor ops live here.
public struct Avatar: Hashable, Sendable, Codable {
    public static let currentVersion = 1
    public static let maxWireBytes = 512
    public static let v1Shapes = ["cube", "drop", "dots", "rings", "letter"]
    public static let v1Palettes = 8

    /// Canonical JSON (sorted keys) of the avatar object.
    public let json: String

    /// From the wire: any object with a positive integer `v` whose JSON is at most 512 bytes; nil otherwise.
    public init?(wire: Any?) {
        guard let object = wire as? [String: Any], let v = object["v"] as? Int, v > 0,
            let data = try? JSONSerialization.data(
                withJSONObject: object, options: [.sortedKeys, .withoutEscapingSlashes]),
            data.count <= Self.maxWireBytes
        else { return nil }
        json = String(decoding: data, as: UTF8.self)
    }

    public var object: [String: Any] {
        (try? JSONSerialization.jsonObject(with: Data(json.utf8)) as? [String: Any]) ?? [:]
    }

    public var version: Int { object["v"] as? Int ?? 0 }
    /// A version this build can draw and edit.
    public var isKnown: Bool { (1...Self.currentVersion).contains(version) }

    /// A v1 picture, normalised: colours mod 8, unknown shape → cube, angle mod 360 down to a multiple of 15, the
    /// seed's low 31 bits (contracts/protocol/identity/profile-v1.json).
    public static func v1(c: Int, s: String, a: Int, n: Int64) -> Avatar {
        let object: [String: Any] = [
            "v": 1, "c": ((c % v1Palettes) + v1Palettes) % v1Palettes, "s": v1Shapes.contains(s) ? s : "cube",
            "a": (((a % 360) + 360) % 360) / 15 * 15, "n": Int(n & 0x7fff_ffff),
        ]
        return Avatar(wire: object)!  // a v1 object is always well under 512 bytes
    }

    /// A new random v1 picture (any shape but `letter`).
    public static func random() -> Avatar {
        v1(
            c: Int.random(in: 0..<v1Palettes), s: v1Shapes[Int.random(in: 0..<(v1Shapes.count - 1))],
            a: Int.random(in: 0..<24) * 15, n: Int64.random(in: 0...Int64(Int32.max)))
    }

    /// This picture with one v1 field changed (the editor's shape and colour buttons).
    public func with(colors: Int? = nil, shape: String? = nil, angle: Int? = nil, seed: Int64? = nil) -> Avatar {
        let o = object
        return Self.v1(
            c: colors ?? (o["c"] as? Int ?? 0), s: shape ?? (o["s"] as? String ?? "cube"),
            a: angle ?? (o["a"] as? Int ?? 0),
            n: seed ?? Int64(o["n"] as? Int ?? 0))
    }

    /// "Turn the colours": the gradient 45° further and the next dots seed.
    public func turned() -> Avatar {
        let o = object
        return with(angle: (o["a"] as? Int ?? 0) + 45, seed: Int64(o["n"] as? Int ?? 0) + 1)
    }
}

/// A person's self-asserted name and picture. Authenticates nothing.
public struct Profile: Hashable, Sendable, Codable {
    public static let maxName = 40

    public var name: String
    public var avatar: Avatar?

    public init(name: String, avatar: Avatar?) {
        self.name = name
        self.avatar = avatar
    }

    /// From a received `{"name","avatar"}` object; a missing name is "".
    public init?(wire: [String: Any]) {
        name = wire["name"] as? String ?? ""
        avatar = Avatar(wire: wire["avatar"])
    }

    public func wireObject() -> [String: Any] {
        ["name": name, "avatar": avatar?.object ?? NSNull()]
    }

    /// A typed name trimmed and cut to 40 UTF-16 units (as Android's `take`); a blank one keeps `previous`.
    public static func normalisedName(typed: String, previous: String) -> String {
        let trimmed = typed.trimmingCharacters(in: .whitespacesAndNewlines)
        var out = ""
        var units = 0
        for ch in trimmed.isEmpty ? previous : trimmed {
            units += ch.utf16.count
            if units > maxName { break }
            out.append(ch)
        }
        return out
    }
}
