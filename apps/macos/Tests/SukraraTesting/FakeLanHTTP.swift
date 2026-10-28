// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation
import SukraraShare
import Synchronization

/// A home network in memory for the Juggluco source: `answer` gives each GET's status and body (nil = nothing
/// answers there), `live` the lines of a live stream (held open until the test ends it). Never touches the network.
public final class FakeLanHTTP: LanHTTP {
    public struct Request: Sendable, Equatable {
        public var url: URL
        public var headers: [String: String]
    }

    private let answer: @Sendable (URL, [String: String]) -> (Int, Data)?
    private let liveLines: @Sendable (URL) -> (Int, [String])?
    private let log = Mutex<[Request]>([])
    private let streams = Mutex<[AsyncThrowingStream<String, any Error>.Continuation]>([])

    public init(
        answer: @escaping @Sendable (URL, [String: String]) -> (Int, Data)?,
        live: @escaping @Sendable (URL) -> (Int, [String])? = { _ in nil }
    ) {
        self.answer = answer
        self.liveLines = live
    }

    /// Every request so far, in order.
    public var requests: [Request] { log.withLock { $0 } }

    public func get(_ url: URL, headers: [String: String], timeout: TimeInterval) async throws -> (
        status: Int, body: Data
    ) {
        log.withLock { $0.append(Request(url: url, headers: headers)) }
        guard let (status, body) = answer(url, headers) else { throw URLError(.cannotConnectToHost) }
        return (status, body)
    }

    public func lines(_ url: URL, headers: [String: String]) async throws -> (
        status: Int, lines: AsyncThrowingStream<String, any Error>
    ) {
        log.withLock { $0.append(Request(url: url, headers: headers)) }
        guard let (status, lines) = liveLines(url) else { throw URLError(.cannotConnectToHost) }
        let (stream, continuation) = AsyncThrowingStream.makeStream(of: String.self, throwing: (any Error).self)
        for line in lines { continuation.yield(line) }
        streams.withLock { $0.append(continuation) }
        return (status, stream)
    }

    /// Juggluco closes every live stream (the phone left the network).
    public func dropLive() {
        streams.withLock {
            for c in $0 { c.finish(throwing: URLError(.networkConnectionLost)) }
            $0 = []
        }
    }
}
