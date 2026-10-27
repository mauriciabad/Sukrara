// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation

/// A NIP-01 event as it travels (relays, BLE). Engines and stores pass these, never NostrSDK types. Nothing here is
/// verified: `NostrCrypto.verify` decides that.
public struct SignedEvent: Codable, Hashable, Sendable {
    public var id: String
    public var pubkey: String
    public var createdAt: Int
    public var kind: UInt16
    public var tags: [[String]]
    public var content: String
    public var sig: String

    public init(
        id: String, pubkey: String, createdAt: Int, kind: UInt16, tags: [[String]], content: String, sig: String
    ) {
        self.id = id
        self.pubkey = pubkey
        self.createdAt = createdAt
        self.kind = kind
        self.tags = tags
        self.content = content
        self.sig = sig
    }

    enum CodingKeys: String, CodingKey {
        case id, pubkey, kind, tags, content, sig
        case createdAt = "created_at"
    }

    /// The first value of the first tag named `name`.
    public func tag(_ name: String) -> String? { tags.first { $0.count > 1 && $0[0] == name }?[1] }

    /// NIP-40 expiration (unix s), nil = none or unreadable.
    public var expiration: Int? { tag("expiration").flatMap { Int($0) } }

    public func isExpired(now: Int) -> Bool { expiration.map { $0 <= now } ?? false }

    public static func decode(json: String) -> SignedEvent? {
        try? JSONDecoder().decode(SignedEvent.self, from: Data(json.utf8))
    }

    public func json() -> String {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.withoutEscapingSlashes, .sortedKeys]
        // Encoding a struct of strings and ints cannot fail.
        return String(decoding: (try? encoder.encode(self)) ?? Data(), as: UTF8.self)
    }
}

/// A secret key in hex. Never printed: `description` is redacted so it can't reach a log by accident.
public struct SecretKeyHex: Hashable, Sendable, Codable, CustomStringConvertible, CustomDebugStringConvertible,
    CustomReflectable
{
    public let hex: String

    /// nil unless `hex` is 64 lowercase hex digits.
    public init?(_ hex: String) {
        guard Hex.isKey(hex) else { return nil }
        self.hex = hex
    }

    init(unchecked hex: String) { self.hex = hex }

    public var description: String { "SecretKeyHex(***)" }
    public var debugDescription: String { description }
    /// `dump` and the debugger go through the mirror, not `description`.
    public var customMirror: Mirror { Mirror(self, children: ["hex": "***"]) }

    public init(from decoder: any Decoder) throws {
        let text = try decoder.singleValueContainer().decode(String.self)
        guard Hex.isKey(text) else {
            throw DecodingError.dataCorrupted(.init(codingPath: decoder.codingPath, debugDescription: "not a key"))
        }
        hex = text
    }

    public func encode(to encoder: any Encoder) throws {
        var container = encoder.singleValueContainer()
        try container.encode(hex)
    }
}
