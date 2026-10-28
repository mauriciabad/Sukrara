// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation

/// One WebSocket connection carrying text frames (NIP-01 messages).
public protocol WebSocket: Sendable {
    func send(_ text: String) async throws
    /// The next text frame; throws when the connection ends.
    func receive() async throws -> String
    func ping() async throws
    func cancel()
}

/// Makes connections. `RelayPool` takes one so tests inject fakes and never open a socket to a real relay.
public protocol WebSocketFactory: Sendable {
    func connect(to url: URL) -> any WebSocket
}

/// URLSession WebSockets (one ephemeral session per factory: no cookies, no cache).
public final class URLSessionWebSocketFactory: WebSocketFactory {
    private let session = URLSession(configuration: .ephemeral)

    public init() {}

    deinit { session.invalidateAndCancel() }

    public func connect(to url: URL) -> any WebSocket {
        let task = session.webSocketTask(with: url)
        task.resume()  // sends queue until the socket is open
        return URLSessionWebSocket(task: task)
    }
}

struct URLSessionWebSocket: WebSocket {
    let task: URLSessionWebSocketTask

    func send(_ text: String) async throws { try await task.send(.string(text)) }

    func receive() async throws -> String {
        while true {
            if case .string(let text) = try await task.receive() { return text }
        }
    }

    func ping() async throws {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, any Error>) in
            task.sendPing { error in
                if let error { continuation.resume(throwing: error) } else { continuation.resume() }
            }
        }
    }

    func cancel() { task.cancel(with: .goingAway, reason: nil) }
}
