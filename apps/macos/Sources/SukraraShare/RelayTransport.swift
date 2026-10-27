// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation

/// A signed Nostr event as it travels between relays and the app (NIP-01 fields). Chunk 07 builds and verifies
/// these with NostrSDK; this type keeps the transport independent of the SDK.
public struct SignedEvent: Sendable, Codable, Hashable {
    public var id: String
    public var pubkey: String
    public var createdAt: Date
    public var kind: Int
    public var tags: [[String]]
    public var content: String
    public var sig: String

    public init(
        id: String,
        pubkey: String,
        createdAt: Date,
        kind: Int,
        tags: [[String]],
        content: String,
        sig: String
    ) {
        self.id = id
        self.pubkey = pubkey
        self.createdAt = createdAt
        self.kind = kind
        self.tags = tags
        self.content = content
        self.sig = sig
    }
}

/// A relay subscription filter (NIP-01 subset). `nil` = no constraint on that field.
public struct EventFilter: Sendable, Hashable {
    public var kinds: Set<Int>?
    public var authors: Set<String>?
    public var since: Date?
    /// Single-letter tag filters, e.g. `["p": [pubkey]]`.
    public var tags: [String: Set<String>]

    public init(
        kinds: Set<Int>? = nil, authors: Set<String>? = nil, since: Date? = nil, tags: [String: Set<String>] = [:]
    ) {
        self.kinds = kinds
        self.authors = authors
        self.since = since
        self.tags = tags
    }

    /// Whether `event` passes this filter.
    public func matches(_ event: SignedEvent) -> Bool {
        if let kinds, !kinds.contains(event.kind) { return false }
        if let authors, !authors.contains(event.pubkey) { return false }
        if let since, event.createdAt < since { return false }
        return tags.allSatisfy { name, values in
            event.tags.contains { $0.count >= 2 && $0[0] == name && values.contains($0[1]) }
        }
    }
}

/// The relay connection. The real one (chunk 07, an actor over URLSession WebSockets) is the only code that opens
/// relay sockets; tests use `FakeRelay` from `SukraraTesting`, never real relays.
public protocol RelayTransport: Sendable {
    func connect(_ urls: [URL]) async
    func publish(_ event: SignedEvent) async throws
    /// Events matching `filter`, stored ones first, then live ones until the stream is cancelled or `close()`.
    func subscribe(_ filter: EventFilter) -> AsyncThrowingStream<SignedEvent, Error>
    func close() async
}
