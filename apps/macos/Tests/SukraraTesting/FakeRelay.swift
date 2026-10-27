// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation
import SukraraShare

/// An in-memory relay: stores what is published and delivers it to matching subscriptions (stored events first,
/// then live ones). Tests use it instead of real relays.
public actor FakeRelay: RelayTransport {
    public private(set) var connectedURLs: [URL] = []
    public private(set) var published: [SignedEvent] = []
    private var subscribers:
        [UUID: (filter: EventFilter, continuation: AsyncThrowingStream<SignedEvent, Error>.Continuation)] = [:]

    public init(stored: [SignedEvent] = []) {
        published = stored
    }

    public func connect(_ urls: [URL]) {
        connectedURLs = urls
    }

    public func publish(_ event: SignedEvent) {
        published.append(event)
        for subscriber in subscribers.values where subscriber.filter.matches(event) {
            subscriber.continuation.yield(event)
        }
    }

    public nonisolated func subscribe(_ filter: EventFilter) -> AsyncThrowingStream<SignedEvent, Error> {
        let (stream, continuation) = AsyncThrowingStream<SignedEvent, Error>.makeStream()
        let id = UUID()
        continuation.onTermination = { _ in Task { await self.remove(id) } }
        Task { await self.add(id, filter: filter, continuation: continuation) }
        return stream
    }

    public func close() {
        for subscriber in subscribers.values { subscriber.continuation.finish() }
        subscribers = [:]
    }

    private func add(
        _ id: UUID, filter: EventFilter, continuation: AsyncThrowingStream<SignedEvent, Error>.Continuation
    ) {
        for event in published where filter.matches(event) { continuation.yield(event) }
        subscribers[id] = (filter, continuation)
    }

    private func remove(_ id: UUID) {
        subscribers[id] = nil
    }
}
