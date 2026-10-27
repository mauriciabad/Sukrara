// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation
import SukraraShare
import Synchronization

/// An in-memory relay: keeps what is published, delivers stored then live events to matching subscriptions (with
/// `endOfStored` after the stored ones), answers publishes with `ok`. Tests use it instead of real relays.
public actor FakeRelay: RelayLink {
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

    public private(set) var isStopped = false

    public func start() {}

    /// Ends `inbound` (the runtime stops a relay connection when it reconnects or unfollows).
    public func stop() {
        isStopped = true
        subscriptions = [:]
        continuation.finish()
    }

    public func publish(_ event: SignedEvent) {
        published.append(event)
        continuation.yield(.ok(eventId: event.id, accepted: true, relay: host))
        deliver(event)
    }

    /// An event delivered whatever the subscriptions (like the Bluetooth path, which sends unasked); not stored.
    public func push(_ event: SignedEvent) {
        continuation.yield(.event(event, subscription: "push", relay: host))
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

/// The relays as a whole for runtime tests: every connection the runtime makes is a new `FakeRelay` that starts with
/// everything stored so far; `inject` reaches the stored list and every open connection. `connect` is synchronous
/// (the runtime's relay factory is).
public final class FakeRelayHub: Sendable {
    private let state: Mutex<(stored: [SignedEvent], relays: [FakeRelay])>

    public init(stored: [SignedEvent] = []) {
        state = Mutex((stored, []))
    }

    /// A new connection (the runtime's `makeRelays`).
    public func connect() -> FakeRelay {
        state.withLock { state in
            let relay = FakeRelay(stored: state.stored)
            state.relays.append(relay)
            return relay
        }
    }

    public var relays: [FakeRelay] { state.withLock { $0.relays } }

    public func inject(_ event: SignedEvent) async {
        let relays = state.withLock { state in
            state.stored.append(event)
            return state.relays
        }
        for relay in relays where await !relay.isStopped { await relay.inject(event) }
    }

    /// Delivers `event` on every open connection whatever the subscriptions (fixture replay in step order).
    public func push(_ event: SignedEvent) async {
        for relay in relays where await !relay.isStopped { await relay.push(event) }
    }

    /// Everything the runtime published on any connection (events it sent, not the stored / injected ones).
    public func published() async -> [SignedEvent] {
        let (stored, relays) = state.withLock { ($0.stored, $0.relays) }
        var out: [SignedEvent] = []
        for relay in relays { out += await relay.published }
        return out.filter { !stored.contains($0) }
    }
}

/// A scripted WebSocket for `RelayPool` tests: records what the pool sends; the test pushes frames or fails it.
public final class FakeWebSocket: WebSocket, @unchecked Sendable {
    // Guarded by `lock`.
    private let lock = NSLock()
    private var sentFrames: [String] = []
    private var pings = 0
    private var cancelled = false
    private var hangs = false
    private let frames: AsyncThrowingStream<String, any Error>
    private let frameContinuation: AsyncThrowingStream<String, any Error>.Continuation

    public init() {
        (frames, frameContinuation) = AsyncThrowingStream.makeStream()
    }

    public var sent: [String] { lock.withLock { sentFrames } }
    public var pingCount: Int { lock.withLock { pings } }
    public var isCancelled: Bool { lock.withLock { cancelled } }

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

    /// A half-open connection: pings are never answered (the ping returns only when the test process ends).
    public var pingHangs: Bool {
        get { lock.withLock { hangs } }
        set { lock.withLock { hangs = newValue } }
    }

    public func ping() async throws {
        let hang = lock.withLock { () -> Bool in
            pings += 1
            return hangs
        }
        if hang { try? await Task.sleep(for: .seconds(86_400)) }
    }

    public func cancel() {
        lock.withLock { cancelled = true }
        frameContinuation.finish(throwing: URLError(.cancelled))
    }

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
