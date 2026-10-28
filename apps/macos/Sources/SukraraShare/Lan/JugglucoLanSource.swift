// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation
import SukraraCore
import os

/// Where the Juggluco source is, in the app's words (Settings › General › Source and the panel).
public enum LanState: Sendable, Equatable {
    /// The switch is off (or this Mac follows someone: following wins).
    case off
    /// Sweeping the Mac's networks for Juggluco's web server.
    case looking
    /// No Juggluco answered on this Mac's networks (swept again every minute).
    case notFound
    /// Reading Juggluco at this address (history, then the live stream).
    case connected(String)
    /// Juggluco at this address doesn't answer (any more); retried with backoff.
    case unreachable(String)
    /// Juggluco answered 401 / 403: the API secret is missing or wrong (retried slowly).
    case unauthorized(String)
    /// The typed address isn't one this Mac contacts in plain http (not a home-network IPv4 address).
    case refused(String)
}

/// What the app sees of the Juggluco source (one value, replaced on every change).
public struct LanSnapshot: Sendable, Equatable {
    public var state: LanState
    /// The last 24 hours of its readings, oldest first.
    public var readings: [Reading]
    /// Reached Juggluco at least once since it was switched on: from then on its readings are monitored and losing it
    /// rings the no-data alert (a source that never connected says so instead).
    public var monitoring: Bool

    public static let off = LanSnapshot(state: .off, readings: [], monitoring: false)
}

/// What the switch, the address field and the Keychain secret ask for.
public struct LanConfig: Sendable, Equatable {
    /// "192.168.1.23" or "192.168.1.23:17580"; blank = find it on this Mac's networks.
    public var address: String
    public var secret: String

    public init(address: String = "", secret: String = "") {
        self.address = address
        self.secret = secret
    }
}

/// The Juggluco source as the app sees it (`JugglucoLanSource`; tests use it over a fake `LanHTTP`).
public protocol LanSourcing: Sendable {
    /// Each call is one subscriber: the current snapshot first, then every change (only the newest is buffered).
    func snapshots() async -> AsyncStream<LanSnapshot>
    /// Switches it on with `config`, or off (nil). The same config again changes nothing.
    func set(_ config: LanConfig?) async
    /// Try Again: look / connect now instead of after the backoff.
    func retry() async
    /// Its stored readings since `since`, oldest first (31 days kept).
    func readings(since: Date) async -> [Reading]
}

/// Plain-http GETs on the home network (`URLSessionLanHTTP`; tests use a fake that never touches the network).
public protocol LanHTTP: Sendable {
    /// The status code and body.
    func get(_ url: URL, headers: [String: String], timeout: TimeInterval) async throws -> (status: Int, body: Data)
    /// A response held open: the status code, then its body line by line until the server closes it.
    func lines(_ url: URL, headers: [String: String]) async throws -> (
        status: Int, lines: AsyncThrowingStream<String, any Error>
    )
}

/// Reads Juggluco's web server on the home network: finds it (the typed address, else a sweep of the Mac's private
/// IPv4 networks on :17580 for `status.json` saying "juggluco"), loads the history, then holds the live stream open;
/// every reading goes into its own 31-day store (kept apart from a followed wearer's, which an unfollow wipes).
/// Reconnects with backoff (5 s → 60 s). Plain http, only ever to RFC 1918 addresses: the person turns it on.
public actor JugglucoLanSource: LanSourcing {
    private let http: any LanHTTP
    private let store: any ReadingsStoring
    private let clock: any TimeSource
    private let interfaces: @Sendable () -> [LanInterface]
    private let sleep: @Sendable (Double) async throws -> Void
    private let backoff: Backoff
    private static let log = Logger(subsystem: SukraraIDs.appBundleID, category: "juggluco")
    /// Seconds between sweeps while nothing was found, and between tries with a refused secret.
    static let sweepAgain: Double = 60
    /// Probes running at once in a sweep.
    static let sweepWidth = 64

    private var config: LanConfig?
    private var task: Task<Void, Never>?
    private var current = LanSnapshot.off
    private var subscribers: [UUID: AsyncStream<LanSnapshot>.Continuation] = [:]
    /// The address the last sweep found (tried first again; forgotten after repeated failures).
    private var found: URL?
    /// Bumped by every switch, config change and Try Again: a run of an older generation publishes nothing (it may
    /// still be finishing an await after it was cancelled).
    private var generation = 0

    public init(
        http: any LanHTTP, store: any ReadingsStoring, clock: any TimeSource = SystemClock(),
        interfaces: @escaping @Sendable () -> [LanInterface] = LanInterface.current,
        sleep: @escaping @Sendable (Double) async throws -> Void = { try await Task.sleep(for: .seconds($0)) },
        backoff: Backoff = Backoff(base: 5, cap: 60)
    ) {
        self.http = http
        self.store = store
        self.clock = clock
        self.interfaces = interfaces
        self.sleep = sleep
        self.backoff = backoff
    }

    public func snapshots() -> AsyncStream<LanSnapshot> {
        let (stream, continuation) = AsyncStream.makeStream(of: LanSnapshot.self, bufferingPolicy: .bufferingNewest(1))
        let id = UUID()
        subscribers[id] = continuation
        continuation.onTermination = { [weak self] _ in Task { await self?.unsubscribe(id) } }
        continuation.yield(current)
        return stream
    }

    private func unsubscribe(_ id: UUID) { subscribers[id] = nil }

    public func set(_ config: LanConfig?) async {
        guard config != self.config else { return }
        let addressChanged = config?.address != self.config?.address
        self.config = config
        if addressChanged { found = nil }
        task?.cancel()
        task = nil
        generation += 1
        guard let config else {
            publish(LanSnapshot.off)
            return
        }
        start(config)
    }

    public func retry() async {
        guard let config else { return }
        task?.cancel()
        generation += 1
        start(config)
    }

    public func readings(since: Date) async -> [Reading] {
        (try? await store.load(since: since)) ?? []
    }

    private func start(_ config: LanConfig) {
        let gen = generation
        task = Task { [weak self] in await self?.run(config, gen: gen) }
    }

    private func publish(_ snapshot: LanSnapshot) {
        current = snapshot
        for c in subscribers.values { c.yield(snapshot) }
    }

    /// Publishes for the run of generation `gen` only while it is the current one.
    private func publish(_ snapshot: LanSnapshot, gen: Int) {
        guard gen == generation else { return }
        publish(snapshot)
    }

    private func publish(state: LanState, gen: Int) {
        var s = current
        s.state = state
        publish(s, gen: gen)
    }

    // MARK: - the loop

    private func run(_ config: LanConfig, gen: Int) async {
        var attempt = 0
        var failures = 0
        let headers = JugglucoApi.headers(secret: config.secret)
        await refreshReadings(gen: gen)
        while !Task.isCancelled && gen == generation {
            let base: URL
            let typed = config.address.trimmingCharacters(in: .whitespaces)
            if !typed.isEmpty {
                guard let b = Self.base(typed) else {
                    publish(state: .refused(typed), gen: gen)
                    return
                }
                base = b
            } else if let found {
                base = found
            } else {
                if current.state != .notFound { publish(state: .looking, gen: gen) }
                let sweep = await find(secret: config.secret)
                guard let b = sweep.hit else {
                    guard !Task.isCancelled else { return }
                    // Juggluco answered but wants a (different) API secret: say so, not "not found"
                    publish(state: sweep.locked.map { .unauthorized(Self.hostText($0)) } ?? .notFound, gen: gen)
                    try? await sleep(Self.sweepAgain)
                    continue
                }
                found = b
                base = b
            }
            let host = Self.hostText(base)
            do {
                let (status, body) = try await http.get(
                    JugglucoApi.entriesURL(base, count: await historyCount()), headers: headers, timeout: 15)
                try Self.check(status)
                try await merge(JugglucoApi.entries(body, now: clock.now), gen: gen)
                failures = 0
                var s = current
                s.state = .connected(host)
                s.monitoring = true
                publish(s, gen: gen)
                let (liveStatus, lines) = try await http.lines(JugglucoApi.liveURL(base), headers: headers)
                try Self.check(liveStatus)
                var parser = SseParser()
                for try await line in lines {
                    attempt = 0
                    if let data = parser.feed(line), let r = JugglucoApi.live(data, now: clock.now) {
                        try await merge([r], gen: gen)
                    }
                }
                Self.log.info("live stream ended")
            } catch JugglucoError.unauthorized {
                guard !Task.isCancelled else { return }
                publish(state: .unauthorized(host), gen: gen)
                try? await sleep(Self.sweepAgain)
                continue
            } catch {
                guard !Task.isCancelled else { return }
                Self.log.info("juggluco: \(String(describing: error), privacy: .public)")
                failures += 1
                // the phone may have a new address: sweep again after a few misses
                if typed.isEmpty && (failures >= 3 || error as? JugglucoError == .notJuggluco) { found = nil }
            }
            guard !Task.isCancelled else { return }
            publish(state: .unreachable(host), gen: gen)
            try? await sleep(backoff.delay(attempt: attempt, jitter: Double.random(in: 0.5...1)))
            attempt += 1
        }
    }

    private func merge(_ readings: [Reading], gen: Int) async throws {
        guard !readings.isEmpty else { return }
        try await store.merge(readings)
        await refreshReadings(gen: gen)
    }

    private func refreshReadings(gen: Int) async {
        let day = (try? await store.load(since: clock.now.addingTimeInterval(-86_400))) ?? []
        guard day != current.readings else { return }
        var s = current
        s.readings = day
        publish(s, gen: gen)
    }

    /// How many minutes of history to ask for: everything since the newest stored reading (a night asleep included),
    /// at least `againCount`, at most `firstCount`.
    private func historyCount() async -> Int {
        let since = clock.now.addingTimeInterval(-Double(JugglucoApi.firstCount) * 60)
        guard let newest = (try? await store.load(since: since))?.last else { return JugglucoApi.firstCount }
        let minutes = Int(clock.now.timeIntervalSince(newest.t) / 60) + 10
        return min(JugglucoApi.firstCount, max(JugglucoApi.againCount, minutes))
    }

    /// The first host on this Mac's networks whose status says Juggluco, or else one that refused for want of the
    /// right secret. Probed without the secret first; a secret set in Sukrara goes only to the hosts that refused.
    private func find(secret: String) async -> (hit: URL?, locked: URL?) {
        let hosts = LanInterface.hosts(interfaces()).compactMap { JugglucoApi.base($0) }
        Self.log.info("sweeping \(hosts.count) hosts")
        let (hit, locked) = await probe(hosts, headers: [:])
        if let hit { return (hit, nil) }
        guard !secret.isEmpty, !locked.isEmpty else { return (nil, locked.first) }
        let retried = await probe(locked, headers: JugglucoApi.headers(secret: secret))
        return (retried.hit, retried.locked.first)
    }

    private func probe(_ bases: [URL], headers: [String: String]) async -> (hit: URL?, locked: [URL]) {
        let http = self.http
        return await withTaskGroup(of: (URL, Int?).self) { group in
            var next = 0
            var locked: [URL] = []
            func add() {
                guard next < bases.count else { return }
                let base = bases[next]
                next += 1
                group.addTask {
                    guard
                        let (status, body) = try? await http.get(
                            JugglucoApi.statusURL(base), headers: headers, timeout: 1.5)
                    else { return (base, nil) }
                    return (base, status == 200 && !JugglucoApi.isJuggluco(status: body) ? nil : status)
                }
            }
            for _ in 0..<Self.sweepWidth { add() }
            while let (base, status) = await group.next() {
                if status == 200 {
                    group.cancelAll()
                    return (base, [])
                }
                if status == 401 || status == 403 { locked.append(base) }
                if Task.isCancelled { break }
                add()
            }
            return (nil, locked)
        }
    }

    /// "192.168.1.23" or "192.168.1.23:17580" → its base URL, or nil (not a private IPv4 address, bad port).
    static func base(_ typed: String) -> URL? {
        let parts = typed.split(separator: ":", omittingEmptySubsequences: false)
        guard parts.count <= 2, let address = LanAddress(String(parts[0])) else { return nil }
        let port = parts.count == 2 ? Int(parts[1]) : JugglucoApi.port
        guard let port, (1...65535).contains(port) else { return nil }
        return JugglucoApi.base(address, port: port)
    }

    static func hostText(_ base: URL) -> String {
        let host = base.host() ?? ""
        return base.port == JugglucoApi.port ? host : "\(host):\(base.port ?? JugglucoApi.port)"
    }

    static func check(_ status: Int) throws {
        if status == 401 || status == 403 { throw JugglucoError.unauthorized }
        if status != 200 { throw JugglucoError.status(status) }
    }
}

/// `LanHTTP` over URLSession (ephemeral: no cookies, cache or credentials kept). Plain http to home-network addresses
/// only (callers build every URL with `JugglucoApi.base`).
public final class URLSessionLanHTTP: LanHTTP {
    private let session: URLSession
    private let stream: URLSession

    public init() {
        let cfg = URLSessionConfiguration.ephemeral
        cfg.waitsForConnectivity = false
        session = URLSession(configuration: cfg)
        let live = URLSessionConfiguration.ephemeral
        live.timeoutIntervalForRequest = 60  // Juggluco sends a heartbeat every 20 s
        live.timeoutIntervalForResource = .infinity
        stream = URLSession(configuration: live)
    }

    deinit {
        session.invalidateAndCancel()
        stream.invalidateAndCancel()
    }

    public func get(_ url: URL, headers: [String: String], timeout: TimeInterval) async throws -> (
        status: Int, body: Data
    ) {
        var request = URLRequest(url: url, timeoutInterval: timeout)
        for (k, v) in headers { request.setValue(v, forHTTPHeaderField: k) }
        let (data, response) = try await session.data(for: request)
        return ((response as? HTTPURLResponse)?.statusCode ?? 0, data)
    }

    public func lines(_ url: URL, headers: [String: String]) async throws -> (
        status: Int, lines: AsyncThrowingStream<String, any Error>
    ) {
        var request = URLRequest(url: url)
        request.setValue("text/event-stream", forHTTPHeaderField: "Accept")
        for (k, v) in headers { request.setValue(v, forHTTPHeaderField: k) }
        let (bytes, response) = try await stream.bytes(for: request)
        let lines = AsyncThrowingStream<String, any Error> { continuation in
            let task = Task {
                do {
                    for try await line in bytes.lines { continuation.yield(line) }
                    continuation.finish()
                } catch {
                    continuation.finish(throwing: error)
                }
            }
            continuation.onTermination = { _ in task.cancel() }
        }
        return ((response as? HTTPURLResponse)?.statusCode ?? 0, lines)
    }
}
