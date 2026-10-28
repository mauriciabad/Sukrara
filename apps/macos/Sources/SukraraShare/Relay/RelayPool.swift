// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation
import os

/// Reconnect delays: 2 s doubling to 5 min, each scaled by a random 50–100 % so many clients don't reconnect in the
/// same instant. Reset once a relay sends anything.
public struct Backoff: Sendable {
    public var base: Double = 2
    public var cap: Double = 300

    public init(base: Double = 2, cap: Double = 300) {
        self.base = base
        self.cap = cap
    }

    /// Seconds to wait before reconnect attempt `attempt` (0 = the first retry); `jitter` in 0.5…1.
    public func delay(attempt: Int, jitter: Double) -> Double {
        min(cap, base * pow(2, Double(min(attempt, 20)))) * jitter
    }
}

/// The relay connection: one WebSocket per relay, reconnect with backoff, subscriptions and an outbox re-sent after
/// every reconnect (gift wraps must reach relays that were down), NIP-42 AUTH as `authKey` when a relay closes a REQ
/// with `auth-required` (once per connection; REQs re-sent after the relay accepts it), a watchdog that pings a relay
/// silent for 150 s and reconnects after 270 s. Events are not deduped here: the follower session does that (an
/// event under a not-yet-known epoch must be seen again later). The owner must call `stop()` (terminal) to release it.
public actor RelayPool: RelayTransport {
    public nonisolated let inbound: AsyncStream<RelayInbound>
    private let continuation: AsyncStream<RelayInbound>.Continuation
    private let urls: [URL]
    private let authKey: SecretKeyHex?
    private let crypto: any NostrCrypto
    private let factory: any WebSocketFactory
    private let backoff: Backoff
    private let sleep: @Sendable (Double) async throws -> Void
    private let now: @Sendable () -> Int
    private static let log = Logger(subsystem: "app.mauri.sukrara", category: "relays")
    private static let outboxSeconds = 3600

    private struct Connection {
        var socket: (any WebSocket)?
        var task: Task<Void, Never>?
        var challenge: String?
        var authEventId: String?
        /// The relay asked for AUTH before sending its challenge: answer as soon as the challenge arrives.
        var authPending = false
        /// The relay has sent something on the current socket.
        var open = false
        var lastReceived = 0
    }

    private var connections: [URL: Connection] = [:]
    private var subscriptions: [String: RelayFilter] = [:]
    private var outbox: [(event: SignedEvent, at: Int)] = []
    private var watchdog: Task<Void, Never>?
    private var stopped = false

    /// `urls`: only `wss://` relays are used. `authKey`: the key relays' gift wraps are addressed to (follower `F`, or
    /// the invite key for an invite lookup).
    public init(
        relays: [String], authKey: SecretKeyHex?, crypto: any NostrCrypto,
        factory: any WebSocketFactory = URLSessionWebSocketFactory(), backoff: Backoff = Backoff(),
        sleep: @escaping @Sendable (Double) async throws -> Void = { try await Task.sleep(for: .seconds($0)) },
        now: @escaping @Sendable () -> Int = { Int(Date().timeIntervalSince1970) }
    ) {
        urls = relays.filter { $0.hasPrefix("wss://") }.compactMap(URL.init(string:))
        self.authKey = authKey
        self.crypto = crypto
        self.factory = factory
        self.backoff = backoff
        self.sleep = sleep
        self.now = now
        // Never drop: an alert is ephemeral and can't be fetched again.
        (inbound, continuation) = AsyncStream.makeStream(bufferingPolicy: .unbounded)
    }

    /// Connects every relay (again: a restart drops the current sockets). Does nothing after `stop()`.
    public func start() {
        guard !stopped else { return }
        for url in urls {
            connections[url]?.task?.cancel()
            connections[url]?.socket?.cancel()
            connections[url, default: Connection()].task = Task { await self.run(url) }
        }
        if watchdog == nil { watchdog = Task { await self.watch() } }
    }

    /// Closes every socket and ends `inbound`, for good.
    public func stop() {
        stopped = true
        watchdog?.cancel()
        watchdog = nil
        for (url, connection) in connections {
            connection.task?.cancel()
            connection.socket?.cancel()
            connections[url] = nil
        }
        continuation.finish()
    }

    public func subscribe(_ id: String, _ filter: RelayFilter) async {
        guard subscriptions[id] != filter else { return }
        subscriptions[id] = filter
        await broadcast(Self.req(id, filter))
    }

    public func close(_ id: String) async {
        guard subscriptions.removeValue(forKey: id) != nil else { return }
        await broadcast(JSONText.encode(["CLOSE", id]))
    }

    public func publish(_ event: SignedEvent) async {
        let t = now()
        outbox.removeAll { t - $0.at > Self.outboxSeconds || $0.event.id == event.id }
        outbox.append((event, t))
        await broadcast("[\"EVENT\",\(event.json())]")
    }

    /// Relays that have sent something on their current socket.
    public var connectedCount: Int { connections.values.filter(\.open).count }

    // MARK: - per relay

    private func run(_ url: URL) async {
        var attempt = 0
        while !Task.isCancelled {
            let socket = factory.connect(to: url)
            connections[url]?.socket = socket
            connections[url]?.challenge = nil
            connections[url]?.authEventId = nil
            connections[url]?.authPending = false
            connections[url]?.open = false
            connections[url]?.lastReceived = now()
            for (id, filter) in subscriptions { try? await socket.send(Self.req(id, filter)) }
            for item in outbox { try? await socket.send("[\"EVENT\",\(item.event.json())]") }
            var announced = false
            do {
                while true {
                    let text = try await socket.receive()
                    if !announced {
                        announced = true
                        connections[url]?.open = true
                        continuation.yield(.connection(.connected, relay: Self.host(url)))
                    }
                    attempt = 0
                    connections[url]?.lastReceived = now()
                    await handle(text, from: url, socket: socket)
                }
            } catch {
                socket.cancel()
                guard !Task.isCancelled else { return }
                connections[url]?.socket = nil
                connections[url]?.open = false
                continuation.yield(.connection(.disconnected, relay: Self.host(url)))
                Self.log.info("relay \(Self.host(url), privacy: .public) disconnected, attempt \(attempt)")
            }
            try? await sleep(backoff.delay(attempt: attempt, jitter: Double.random(in: 0.5...1)))
            attempt += 1
        }
    }

    private func handle(_ text: String, from url: URL, socket: any WebSocket) async {
        guard let message = try? JSONSerialization.jsonObject(with: Data(text.utf8)) as? [Any],
            let type = message.first as? String
        else { return }
        let relay = Self.host(url)
        func string(_ i: Int) -> String? { message.count > i ? message[i] as? String : nil }
        switch type {
        case "EVENT":
            guard message.count > 2, let sub = string(1), let object = message[2] as? [String: Any],
                let event = SignedEvent.decode(json: JSONText.encode(object))
            else { return }
            continuation.yield(.event(event, subscription: sub, relay: relay))
        case "EOSE":
            if let sub = string(1) { continuation.yield(.endOfStored(subscription: sub, relay: relay)) }
        case "AUTH":
            connections[url]?.challenge = string(1)
            if connections[url]?.authPending == true { await authenticate(url, socket: socket) }
        case "CLOSED":
            let sub = string(1) ?? "", reason = string(2) ?? ""
            if reason.hasPrefix("auth-required"), authKey != nil {
                connections[url]?.authPending = true
                await authenticate(url, socket: socket)
            } else {
                continuation.yield(.closed(subscription: sub, reason: reason, relay: relay))
            }
        case "OK":
            guard message.count > 2, let id = string(1) else { return }
            let accepted = message[2] as? Bool ?? false
            if id == connections[url]?.authEventId {
                Self.log.info("relay \(relay, privacy: .public) AUTH \(accepted ? "accepted" : "refused")")
                guard accepted else { return }
                for (sub, filter) in subscriptions { try? await socket.send(Self.req(sub, filter)) }
                for item in outbox { try? await socket.send("[\"EVENT\",\(item.event.json())]") }
            } else {
                continuation.yield(.ok(eventId: id, accepted: accepted, relay: relay))
            }
        default:
            break
        }
    }

    /// NIP-42: kind 22242 with the relay and the challenge, signed by the key the wraps are addressed to.
    private func authenticate(_ url: URL, socket: any WebSocket) async {
        guard let authKey, connections[url]?.authEventId == nil, let challenge = connections[url]?.challenge,
            let event = try? crypto.sign(
                kind: Wire.kindAuth, content: "", tags: [["relay", url.absoluteString], ["challenge", challenge]],
                createdAt: now(), with: authKey)
        else { return }
        connections[url]?.authEventId = event.id
        connections[url]?.authPending = false
        try? await socket.send("[\"AUTH\",\(event.json())]")
    }

    /// Every minute: first drop every socket silent for over 270 s (the run loop reconnects it), then ping the ones
    /// silent for over 150 s all at once, each given `pingTimeout`; a ping with no answer in time drops its socket too.
    /// A half-open connection after sleep or a Wi-Fi roam can leave a ping unanswered for minutes, and it must not hold
    /// up the other relays or the next round.
    private func watch() async {
        while !Task.isCancelled {
            try? await sleep(60)
            let t = now()
            var quiet: [(url: URL, socket: any WebSocket)] = []
            for (url, connection) in connections {
                guard let socket = connection.socket else { continue }
                let silent = t - connection.lastReceived
                if silent > 270 {
                    socket.cancel()
                } else if silent > 150 {
                    quiet.append((url, socket))
                }
            }
            guard !quiet.isEmpty else { continue }
            let sleep = sleep
            let answers = await withTaskGroup(of: (URL, Bool).self) { group in
                for (url, socket) in quiet {
                    group.addTask { (url, await Self.answers(socket, within: Self.pingTimeout, sleep: sleep)) }
                }
                var out: [URL: Bool] = [:]
                for await (url, answered) in group { out[url] = answered }
                return out
            }
            for (url, socket) in quiet {
                if answers[url] == true {
                    connections[url]?.lastReceived = now()
                } else {
                    socket.cancel()
                }
            }
        }
    }

    public static let pingTimeout = 10.0

    /// True when `socket` answers a ping within `seconds`. Returns at the deadline even if the ping never does (the
    /// unanswered ping ends when its socket is cancelled).
    private static func answers(
        _ socket: any WebSocket, within seconds: Double, sleep: @escaping @Sendable (Double) async throws -> Void
    ) async -> Bool {
        let first = FirstAnswer()
        return await withTaskCancellationHandler {
            await withCheckedContinuation { continuation in
                first.start(continuation)
                Task { first.resume((try? await socket.ping()) != nil) }
                Task {
                    try? await sleep(seconds)
                    first.resume(false)
                }
            }
        } onCancel: {
            first.resume(false)  // stop(): don't wait for the deadline
        }
    }

    private func broadcast(_ text: String) async {
        for connection in connections.values {
            try? await connection.socket?.send(text)
        }
    }

    private static func req(_ id: String, _ filter: RelayFilter) -> String {
        "[\"REQ\",\(JSONText.encode(id)),\(filter.json())]"
    }

    private static func host(_ url: URL) -> String { url.host() ?? url.absoluteString }
}

/// Resumes a continuation with the first answer only (a ping racing its timeout).
private final class FirstAnswer: @unchecked Sendable {
    // Guarded by `lock`.
    private let lock = NSLock()
    private var continuation: CheckedContinuation<Bool, Never>?
    /// The first answer, kept when it came before `start` (cancelled before the wait began).
    private var answer: Bool?

    func start(_ continuation: CheckedContinuation<Bool, Never>) {
        let early = lock.withLock { () -> Bool? in
            if answer == nil { self.continuation = continuation }
            return answer
        }
        if let early { continuation.resume(returning: early) }
    }

    func resume(_ answer: Bool) {
        let waiting = lock.withLock { () -> CheckedContinuation<Bool, Never>? in
            guard self.answer == nil else { return nil }
            self.answer = answer
            defer { continuation = nil }
            return continuation
        }
        waiting?.resume(returning: answer)
    }
}
