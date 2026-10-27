// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation
import SukraraShare
import Synchronization

/// An in-memory relay: stores what is published and delivers it to matching subscriptions. `subscribe` registers
/// synchronously and replays the stored events first, so a test knows exactly which events are stored and which
/// are live. Tests use it instead of real relays.
public final class FakeRelay: RelayTransport {
    private typealias Continuation = AsyncThrowingStream<SignedEvent, Error>.Continuation

    private struct State {
        var connectedURLs: [URL] = []
        var published: [SignedEvent]
        var subscribers: [UUID: (filter: EventFilter, continuation: Continuation)] = [:]
    }

    private let state: Mutex<State>

    public init(stored: [SignedEvent] = []) {
        state = Mutex(State(published: stored))
    }

    public var connectedURLs: [URL] { state.withLock { $0.connectedURLs } }
    public var published: [SignedEvent] { state.withLock { $0.published } }

    public func connect(_ urls: [URL]) async {
        state.withLock { $0.connectedURLs = urls }
    }

    public func publish(_ event: SignedEvent) async {
        state.withLock { s in
            s.published.append(event)
            for subscriber in s.subscribers.values where subscriber.filter.matches(event) {
                subscriber.continuation.yield(event)
            }
        }
    }

    public func subscribe(_ filter: EventFilter) -> AsyncThrowingStream<SignedEvent, Error> {
        let (stream, continuation) = AsyncThrowingStream<SignedEvent, Error>.makeStream()
        let id = UUID()
        state.withLock { s in
            for event in s.published where filter.matches(event) { continuation.yield(event) }
            s.subscribers[id] = (filter, continuation)
        }
        continuation.onTermination = { [weak self] _ in
            self?.state.withLock { _ = $0.subscribers.removeValue(forKey: id) }
        }
        return stream
    }

    public func close() async {
        let continuations = state.withLock { s in
            defer { s.subscribers = [:] }
            return s.subscribers.values.map(\.continuation)
        }
        for continuation in continuations { continuation.finish() }
    }
}
