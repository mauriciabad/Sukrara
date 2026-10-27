// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation
import SukraraShare

/// An in-memory relay: keeps what is published, delivers stored then live events to matching subscriptions (with
/// `endOfStored` after the stored ones), answers publishes with `ok`. Tests use it instead of real relays.
public actor FakeRelay: RelayTransport {
    public nonisolated let inbound: AsyncStream<RelayInbound>
    private let continuation: AsyncStream<RelayInbound>.Continuation
    public let host: String
    public private(set) var published: [SignedEvent] = []
    public private(set) var subscriptions: [String: RelayFilter] = [:]
    /// Every REQ sent, in order (a repeated identical subscribe is not sent).
    public private(set) var requests: [String] = []

    public init(stored: [SignedEvent] = [], host: String = "fake.relay") {
        published = stored
        self.host = host
        (inbound, continuation) = AsyncStream.makeStream()
    }

    public func subscribe(_ id: String, _ filter: RelayFilter) {
        guard subscriptions[id] != filter else { return }
        subscriptions[id] = filter
        requests.append(id)
        for event in published where filter.matches(event) {
            continuation.yield(.event(event, subscription: id, relay: host))
        }
        continuation.yield(.endOfStored(subscription: id, relay: host))
    }

    public func close(_ id: String) { subscriptions[id] = nil }

    public func publish(_ event: SignedEvent) {
        published.append(event)
        continuation.yield(.ok(eventId: event.id, accepted: true, relay: host))
        deliver(event)
    }

    /// An event arriving from elsewhere (the wearer's phone publishing).
    public func inject(_ event: SignedEvent) {
        published.append(event)
        deliver(event)
    }

    private func deliver(_ event: SignedEvent) {
        for (id, filter) in subscriptions where filter.matches(event) {
            continuation.yield(.event(event, subscription: id, relay: host))
        }
    }
}

/// A scripted WebSocket for `RelayPool` tests: records what the pool sends; the test pushes frames or fails it.
public final class FakeWebSocket: WebSocket, @unchecked Sendable {
    // Guarded by `lock`.
    private let lock = NSLock()
    private var sentFrames: [String] = []
    private var pings = 0
    private let frames: AsyncThrowingStream<String, any Error>
    private let frameContinuation: AsyncThrowingStream<String, any Error>.Continuation

    public init() {
        (frames, frameContinuation) = AsyncThrowingStream.makeStream()
    }

    public var sent: [String] { lock.withLock { sentFrames } }

    /// A frame from the relay.
    public func push(_ text: String) { frameContinuation.yield(text) }

    /// The connection drops.
    public func fail() { frameContinuation.finish(throwing: URLError(.networkConnectionLost)) }

    public func send(_ text: String) async throws { lock.withLock { sentFrames.append(text) } }

    public func receive() async throws -> String {
        var iterator = frames.makeAsyncIterator()
        guard let next = try await iterator.next() else { throw URLError(.cancelled) }
        return next
    }

    public func ping() async throws { lock.withLock { pings += 1 } }

    public func cancel() { frameContinuation.finish(throwing: URLError(.cancelled)) }

    /// Waits (≤ 2 s) until `predicate` holds for the sent frames.
    public func waitUntilSent(_ predicate: @Sendable ([String]) -> Bool) async -> Bool {
        for _ in 0..<400 {
            if predicate(sent) { return true }
            try? await Task.sleep(for: .milliseconds(5))
        }
        return false
    }
}

/// Hands out a new `FakeWebSocket` per connection and keeps them in order.
public final class FakeWebSocketFactory: WebSocketFactory, @unchecked Sendable {
    // Guarded by `lock`.
    private let lock = NSLock()
    private var made: [(url: URL, socket: FakeWebSocket)] = []

    public init() {}

    public var sockets: [FakeWebSocket] { lock.withLock { made.map(\.socket) } }
    public var urls: [URL] { lock.withLock { made.map(\.url) } }

    public func connect(to url: URL) -> any WebSocket {
        let socket = FakeWebSocket()
        lock.withLock { made.append((url, socket)) }
        return socket
    }

    /// Waits (≤ 2 s) until `count` connections were made.
    public func waitForConnections(_ count: Int) async -> Bool {
        for _ in 0..<400 {
            if sockets.count >= count { return true }
            try? await Task.sleep(for: .milliseconds(5))
        }
        return false
    }
}
