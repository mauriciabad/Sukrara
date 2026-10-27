// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation
import SukraraCore
import os

/// Where the follower keeps the wearer's data. `files(in:)` = the real files under App Support.
public struct FollowerStores: Sendable {
    public var readings: any ReadingsStoring
    public var treatments: any TreatmentStoring
    public var sensor: SensorStateStore
    public var settings: WearerSettingsStore

    public init(
        readings: any ReadingsStoring, treatments: any TreatmentStoring, sensor: SensorStateStore,
        settings: WearerSettingsStore
    ) {
        self.readings = readings
        self.treatments = treatments
        self.sensor = sensor
        self.settings = settings
    }

    public static func files(_ paths: SukraraPaths, clock: any TimeSource) -> FollowerStores {
        FollowerStores(
            readings: ReadingsFileStore(directory: paths.appSupport, clock: clock),
            treatments: TreatmentFileStore(directory: paths.appSupport, clock: clock),
            sensor: SensorStateStore(url: paths.sensorFile, clock: clock),
            settings: WearerSettingsStore(url: paths.wearerSettingsFile, clock: clock))
    }
}

/// The live follower: the protocol session (`FollowerSession`) fed by the relays and the wearer's phone over
/// Bluetooth, the stores it fills, the follower state in the Keychain, the treatment outbox and the history check.
/// Constructed by the app (21's AppModel) only when the launch plan allows relays; tests pass a `FakeRelay`.
public actor FollowerRuntime: FollowerService {
    public typealias RelayFactory = @Sendable (_ relays: [String], _ authKey: SecretKeyHex) -> any RelayLink
    public typealias NearbyFactory = @Sendable (NearbyLink.Identity) -> any NearbyTransport

    static let joinTimeout: Double = 25
    static let historyDelay: Double = 90
    static let historyInterval = 30 * 60
    static let persistInterval: TimeInterval = 300
    static let snapshotWindow: TimeInterval = 24 * 3600
    static let nearbyLeave: Double = 60
    private static let log = Logger(subsystem: SukraraIDs.appBundleID, category: "follower")

    private let secrets: any SecretStoring
    private let account: String
    private let stores: FollowerStores
    private let crypto: any NostrCrypto
    private let clock: any TimeSource
    private let me: Profile
    private let makeRelays: RelayFactory
    private let makeNearby: NearbyFactory?
    private let sleep: @Sendable (Double) async throws -> Void

    private var session: FollowerSession?
    /// Bumped when a follow begins or ends: work that awaited across that change drops its writes, so a previous
    /// wearer's late readings or treatments never land in the next wearer's stores.
    private var generation = 0
    /// A join is between its lookup and its Keychain write (a second join is refused).
    private var beginning = false
    private var link: LinkState = .notFollowing
    private var relays: (any RelayLink)?
    private var relayURLs: [String] = []
    private var relayTask: Task<Void, Never>?
    private var subscribed: [String: RelayFilter] = [:]
    private var nearby: (any NearbyTransport)?
    private var nearbyTask: Task<Void, Never>?
    private var nearbyState: NearbyState?
    private var nearbyOffer: NearbyOffer?
    private var searchingNearby = false
    private var joinTask: Task<Result<Invite, FollowerJoinError>, Never>?
    private var historyTask: Task<Void, Never>?
    private var lastHistoryRequest: Int?
    private var persisted: FollowerState?
    private var persistedAt = Date.distantPast
    private var started = false
    private var stopped = false
    private var snapshotSubscribers: [UUID: AsyncStream<FollowerSnapshot>.Continuation] = [:]
    private var alertSubscribers: [UUID: AsyncStream<AlertPayload>.Continuation] = [:]
    private var current = FollowerSnapshot.empty

    public init(
        secrets: any SecretStoring, account: String = KeychainStore.followerAccount, stores: FollowerStores,
        crypto: any NostrCrypto, clock: any TimeSource, me: Profile, makeRelays: @escaping RelayFactory,
        makeNearby: NearbyFactory? = nil,
        sleep: @escaping @Sendable (Double) async throws -> Void = { try await Task.sleep(for: .seconds($0)) }
    ) {
        self.secrets = secrets
        self.account = account
        self.stores = stores
        self.crypto = crypto
        self.clock = clock
        self.me = me
        self.makeRelays = makeRelays
        self.makeNearby = makeNearby
        self.sleep = sleep
    }

    private var now: Int { Int(clock.now.timeIntervalSince1970) }

    // MARK: - subscribers

    public func snapshots() -> AsyncStream<FollowerSnapshot> {
        let (stream, continuation) = AsyncStream.makeStream(
            of: FollowerSnapshot.self, bufferingPolicy: .bufferingNewest(1))
        let id = UUID()
        snapshotSubscribers[id] = continuation
        continuation.onTermination = { [weak self] _ in Task { await self?.removeSubscriber(id) } }
        continuation.yield(current)
        return stream
    }

    public func alerts() -> AsyncStream<AlertPayload> {
        let (stream, continuation) = AsyncStream.makeStream(of: AlertPayload.self, bufferingPolicy: .unbounded)
        let id = UUID()
        alertSubscribers[id] = continuation
        continuation.onTermination = { [weak self] _ in Task { await self?.removeSubscriber(id) } }
        return stream
    }

    private func removeSubscriber(_ id: UUID) {
        snapshotSubscribers[id] = nil
        alertSubscribers[id] = nil
    }

    // MARK: - lifecycle

    public func start() async {
        guard !started, !stopped else { return }
        started = true
        let data: Data?
        do {
            data = try secrets.read(account: account)
        } catch {
            // Never wipe on a read error (locked or unreadable Keychain): the history stays, the state says why.
            Self.log.error("follower state unreadable: \(String(describing: error), privacy: .public)")
            link = .broken("Can't read the follower key from the Keychain")
            return await publishSnapshot()
        }
        guard let data else {
            await wipeStores()  // a crash between the wipe and the key removal in `unfollow` left data behind
            return await publishSnapshot()
        }
        do {
            let state = try FollowerState.decode(data)
            session = try FollowerSession(state: state, crypto: crypto)
            persisted = state
            persistedAt = clock.now
        } catch {
            link = .broken("The follower state is damaged; stop following and join again")
            return await publishSnapshot()
        }
        await connect()
    }

    public func stop() async {
        guard !stopped else { return }
        stopped = true
        joinTask?.cancel()
        await disconnect()
        persist(force: true)
        for continuation in snapshotSubscribers.values { continuation.finish() }
        for continuation in alertSubscribers.values { continuation.finish() }
        snapshotSubscribers = [:]
        alertSubscribers = [:]
    }

    public func reconnect() async {
        guard !stopped, session != nil else { return }
        await disconnect()
        await connect()
    }

    // MARK: - joining

    public func join(link text: String) async throws(FollowerJoinError) {
        guard session == nil else { throw .alreadyFollowing }
        guard let parsed = InviteLink.parse(text) else { throw .invalidLink }
        link = .joining
        await publishSnapshot()
        let lookup = makeRelays(Wire.defaultRelays, parsed.inviteKey)
        let task = Task { [crypto, clock, sleep] in
            await Self.lookUpInvite(parsed, on: lookup, crypto: crypto, clock: clock, sleep: sleep)
        }
        joinTask = task
        let result = await task.value
        joinTask = nil
        switch result {
        case .success(let invite): try await begin(invite: invite, nearby: false)
        case .failure(let why):
            if session == nil { link = .notFollowing }
            await publishSnapshot()
            throw why
        }
    }

    public func cancelJoin() { joinTask?.cancel() }

    public func joinNearby(_ offer: NearbyOffer) async throws(FollowerJoinError) {
        guard session == nil else { throw .alreadyFollowing }
        try await begin(invite: offer.invite, nearby: true)
    }

    public func searchNearby(_ on: Bool) async {
        searchingNearby = on
        if on, nearby == nil, session == nil {
            startNearby()
        } else if !on, session == nil {
            await stopNearby()
        }
    }

    /// Fetches the invite wrap for `link` (`#p` = the invite key) and opens it; waits up to 25 s on a live subscription
    /// (the phone shows the link before its wrap reaches the relays).
    private static func lookUpInvite(
        _ link: InviteLink, on relays: any RelayLink, crypto: any NostrCrypto, clock: any TimeSource,
        sleep: @escaping @Sendable (Double) async throws -> Void
    ) async -> Result<Invite, FollowerJoinError> {
        guard let invitePub = try? crypto.publicKey(of: link.inviteKey) else { return .failure(.invalidLink) }
        await relays.start()
        await relays.subscribe("invite", RelayFilter(kinds: [Wire.kindWrap], p: [invitePub]))
        let opener = InviteOpener(crypto: crypto)
        let result = await withTaskGroup(of: Result<Invite, FollowerJoinError>?.self) { group in
            group.addTask {
                var failure: FollowerJoinError = .notFound
                for await message in relays.inbound {
                    guard case .event(let wrap, _, _) = message else { continue }
                    switch opener.open(wrap, link: link, now: Int(clock.now.timeIntervalSince1970)) {
                    case .success(let invite): return .success(invite)
                    case .failure(.expired): failure = .expired
                    case .failure(.fingerprintMismatch): failure = .fingerprintMismatch
                    case .failure(.notAnInvite): break
                    }
                }
                return Task.isCancelled ? .failure(.cancelled) : .failure(failure)
            }
            group.addTask {
                do { try await sleep(joinTimeout) } catch { return .failure(.cancelled) }
                return nil
            }
            let first = await group.next() ?? nil
            group.cancelAll()
            return first ?? .failure(.notFound)
        }
        await relays.stop()
        return Task.isCancelled ? .failure(.cancelled) : result
    }

    private func begin(invite: Invite, nearby: Bool) async throws(FollowerJoinError) {
        guard session == nil, !beginning else { throw .alreadyFollowing }
        beginning = true
        defer { beginning = false }
        let joined: FollowerSession
        do {
            joined = try FollowerSession.join(invite: invite, me: me, nearby: nearby, crypto: crypto, now: now)
        } catch .expired {
            link = .notFollowing
            await publishSnapshot()
            throw .expired
        } catch {
            link = .notFollowing
            await publishSnapshot()
            throw .keyFailed
        }
        generation += 1
        await wipeStores()  // never mix a previous person's history in
        do {
            try secrets.write(try joined.state.encoded(), account: account)
        } catch {
            link = .notFollowing
            await publishSnapshot()
            throw .keychain
        }
        session = joined
        persisted = joined.state
        persistedAt = clock.now
        nearbyOffer = nil
        Self.log.info("joined a wearer (nearby: \(nearby, privacy: .public))")
        await disconnect()
        await connect()
    }

    // MARK: - unfollowing

    public func unfollow() async {
        generation += 1
        joinTask?.cancel()
        await disconnect()
        session = nil
        persisted = nil
        lastHistoryRequest = nil
        // Stores first: a crash before the key removal is healed at the next start (no key → wipe).
        await wipeStores()
        do {
            try secrets.delete(account: account)
            link = .notFollowing
        } catch {
            link = .broken("Can't remove the follower key from the Keychain")
        }
        if searchingNearby { startNearby() }
        await publishSnapshot()
    }

    private func wipeStores() async {
        try? await stores.readings.removeAll()
        try? await stores.treatments.removeAll()
        try? await stores.sensor.save(nil)
        try? await stores.settings.save(nil)
    }

    // MARK: - connections

    private func connect() async {
        guard let session, !stopped else { return }
        let state = session.state
        let pool = makeRelays(state.relays, state.follower)
        relays = pool
        relayURLs = state.relays
        subscribed = [:]
        await pool.start()
        relayTask = Task { [weak self] in
            for await message in pool.inbound { await self?.handle(message) }
        }
        startNearby()
        await resubscribe()
        if state.status == .new || state.status == .requested {
            if var current = self.session, let request = try? current.requestAccess(now: now) {
                self.session = current
                persist(force: true)
                await publish(request)
            }
        }
        await resendUnsentTreatments()
        await publishSnapshot()
    }

    private func disconnect() async {
        relayTask?.cancel()
        relayTask = nil
        historyTask?.cancel()
        historyTask = nil
        await relays?.stop()
        relays = nil
        subscribed = [:]
        await stopNearby()
    }

    private func startNearby() {
        guard nearby == nil, let makeNearby, !stopped else { return }
        let transport = makeNearby(nearbyIdentity)
        nearby = transport
        nearbyTask = Task { [weak self] in
            await transport.start()
            for await event in transport.events { await self?.handle(event) }
        }
    }

    private func stopNearby() async {
        nearbyTask?.cancel()
        nearbyTask = nil
        await nearby?.stop()
        nearby = nil
        nearbyState = nil
    }

    private var nearbyIdentity: NearbyLink.Identity {
        guard let state = session?.state else { return NearbyLink.Identity() }
        return NearbyLink.Identity(identity: state.identity, sid: state.sid, follower: state.follower)
    }

    private func resubscribe() async {
        guard let session, let relays else { return }
        let newest = try? await stores.readings.load(since: clock.now.addingTimeInterval(-7 * 86_400)).last
        let filters = session.filters(now: now, newestStored: newest.map { Int($0.t.timeIntervalSince1970) })
        for (id, filter) in filters.sorted(by: { $0.key < $1.key }) where subscribed[id] != filter {
            await relays.subscribe(id, filter)
        }
        for id in subscribed.keys where filters[id] == nil { await relays.close(id) }
        subscribed = filters
    }

    private func publish(_ event: SignedEvent) async {
        await relays?.publish(event)
        if let nearby { Task { _ = await nearby.send(event) } }
    }

    // MARK: - incoming

    private func handle(_ message: RelayInbound) async {
        switch message {
        case .event(let event, _, _): await receive(event)
        case .endOfStored(let subscription, _):
            if subscription == "chunks", historyTask == nil { scheduleHistoryCheck() }  // again after a reconnect
        case .ok(let eventId, let accepted, _):
            if accepted { await markSent(eventId: eventId) }
        case .closed, .connection: break
        }
    }

    private func handle(_ event: NearbyEvent) async {
        switch event {
        case .event(let signed): await receive(signed)
        case .state(let state):
            nearbyState = state
            await publishSnapshot()
        case .info(let info, let owner):
            if session == nil {
                if let invite = info.invite, invite.expiry > now {
                    nearbyOffer = NearbyOffer(invite: invite)
                    await publishSnapshot()
                } else {
                    await nearby?.leave(for: Self.nearbyLeave)  // nothing to pair with: keep both radios idle
                }
            } else if owner == .other {
                await nearby?.leave(for: Self.nearbyLeave)  // another wearer's phone
            }
        }
    }

    private func receive(_ event: SignedEvent) async {
        guard var session else { return }
        let started = generation
        let before = session.state
        let outcome = session.receive(event, now: now)
        self.session = session
        for event in outcome.publish { await publish(event) }
        await apply(outcome.deltas, generation: started)
        guard generation == started else { return }  // unfollowed or joined someone else meanwhile
        if session.state.relays != relayURLs {
            persist(force: true)
            return await reconnect()
        }
        if outcome.resubscribe { await resubscribe() }
        if before.sid != session.state.sid || before.epochs != session.state.epochs {
            await nearby?.update(identity: nearbyIdentity)
        }
        persist(force: false)
        if !outcome.deltas.isEmpty { await publishSnapshot() }
    }

    /// Store writes are skipped once `generation` moved on (each check runs right before its write is enqueued on the
    /// store actor, ahead of any later wipe).
    private func apply(_ deltas: [FollowerSession.Delta], generation started: Int) async {
        for delta in deltas {
            guard generation == started else { return }
            switch delta {
            case .readings(let readings):
                try? await stores.readings.merge(readings.map(Self.reading))
            case .sensor(let sensor):
                try? await stores.sensor.save(
                    sensor.map { WearerSensor(start: $0.start, end: $0.end, warmupEnd: $0.warmupEnd) })
            case .alert(let alert):
                for continuation in alertSubscribers.values { continuation.yield(alert) }
            case .treatment(let payload, let fromWearer, let creator, _):
                await store(treatment: payload, fromWearer: fromWearer, creator: creator, generation: started)
            case .settings(let settings):
                try? await stores.settings.save(MirroredSettings(u: settings.u, json: settings.json))
            case .status, .roster: break
            }
        }
    }

    private func store(treatment payload: TreatmentPayload, fromWearer: Bool, creator: String, generation started: Int)
        async
    {
        guard var log = try? await stores.treatments.load(), generation == started,
            let treatment = Self.treatment(payload)
        else { return }
        let held = log.entries[payload.id]
        var delivery = held?.delivery
        if fromWearer, let held, held.delivery != nil, payload.u >= held.treatment.u { delivery = .accepted }
        log.entries[payload.id] = FollowedTreatments.Entry(
            treatment: treatment, creator: creator, delivery: delivery, sentEventId: held?.sentEventId)
        try? await stores.treatments.save(log)
    }

    // MARK: - treatments out

    public func send(_ treatment: Treatment) async -> TreatmentSendResult {
        guard var session, session.canWrite else { return .notAllowed }
        var entry = treatment
        entry.o = .follower
        guard let event = try? session.treatmentEvent(Self.payload(entry), now: now) else { return .notAllowed }
        self.session = session
        guard var log = try? await stores.treatments.load() else { return .notAllowed }
        log.entries[entry.id] = FollowedTreatments.Entry(
            treatment: entry, creator: session.followerPublicKey, delivery: .sending, sentEventId: event.id)
        try? await stores.treatments.save(log)
        await publish(event)
        await publishSnapshot()
        return .sending
    }

    private func markSent(eventId: String) async {
        guard var log = try? await stores.treatments.load(),
            let (id, entry) = log.entries.first(where: { $0.value.sentEventId == eventId }), entry.delivery == .sending
        else { return }
        log.entries[id]?.delivery = .sent
        try? await stores.treatments.save(log)
        await publishSnapshot()
    }

    /// Entries still `sending` after a restart are signed again (a new `created_at`) and re-sent.
    private func resendUnsentTreatments() async {
        let started = generation
        guard var log = try? await stores.treatments.load(), generation == started,
            var session = self.session, session.canWrite
        else { return }
        // Sign everything before the next await, so the session written back is the current one.
        var events: [SignedEvent] = []
        for (id, entry) in log.entries where entry.delivery == .sending {
            guard let event = try? session.treatmentEvent(Self.payload(entry.treatment), now: now) else { continue }
            log.entries[id]?.sentEventId = event.id
            events.append(event)
        }
        guard !events.isEmpty else { return }
        self.session = session
        try? await stores.treatments.save(log)
        for event in events { await publish(event) }
    }

    // MARK: - history

    /// PROTOCOL-v2 "Missing history": 90 s after the backfill, ask for the hours still missing; check once more 90 s
    /// later; at most one request per 30 minutes.
    private func scheduleHistoryCheck() {
        historyTask = Task { [weak self, sleep] in
            for _ in 0..<2 {
                do { try await sleep(Self.historyDelay) } catch { return }
                await self?.checkHistory()
            }
            await self?.historyCheckDone()
        }
    }

    private func historyCheckDone() { historyTask = nil }

    private func checkHistory() async {
        guard let session, session.state.status == .active else { return }
        let stored = (try? await stores.readings.load(since: clock.now.addingTimeInterval(-13 * 3600))) ?? []
        guard
            let missing = FollowerSession.missingHours(
                stored: stored.map { Int($0.t.timeIntervalSince1970) }, now: now),
            let since = missing.min(), now - (lastHistoryRequest ?? Int.min / 2) >= Self.historyInterval,
            let request = try? session.historyRequest(since: since, now: now)
        else { return }
        lastHistoryRequest = now
        await publish(request)
    }

    // MARK: - state and snapshots

    /// Trust decisions (status, keys, roster, alert dedupe) are written to the Keychain at once; the rest (sensor
    /// event time, treatment versions) at most every 5 minutes and on stop.
    private func persist(force: Bool) {
        guard let state = session?.state, state != persisted else { return }
        let urgent = force || persisted.map { Self.trustPart($0) != Self.trustPart(state) } ?? true
        guard urgent || clock.now.timeIntervalSince(persistedAt) >= Self.persistInterval else { return }
        do {
            try secrets.write(try state.encoded(), account: account)
            persisted = state
            persistedAt = clock.now
        } catch {
            Self.log.error("follower state not saved: \(String(describing: error), privacy: .public)")
        }
    }

    private static func trustPart(_ state: FollowerState) -> FollowerState {
        var part = state
        part.sensorCreatedAt = 0
        part.treatments = [:]
        return part
    }

    private func publishSnapshot() async {
        if let state = session?.state {
            switch state.status {
            case .new, .requested:
                link = .requested(code: state.code, wearer: state.wearer?.name, nearby: state.nearby)
            case .active: link = .following(wearer: state.wearer?.name, canWrite: session?.canWrite ?? false)
            case .declined: link = .declined
            case .inviteUsed: link = .inviteUsed
            case .revoked: link = .revoked
            }
        }
        let since = clock.now.addingTimeInterval(-Self.snapshotWindow)
        let log = (try? await stores.treatments.load()) ?? FollowedTreatments()
        let issues = [
            await stores.readings.issue, await stores.treatments.issue, await stores.sensor.issue,
            await stores.settings.issue,
        ].compactMap { $0 }
        let snapshot = FollowerSnapshot(
            link: link, readings: (try? await stores.readings.load(since: since)) ?? [],
            sensor: await stores.sensor.load(), treatments: log.log.live.reversed(), unsentTreatments: log.unsentCount,
            deliveries: log.entries.compactMapValues(\.delivery), wearerSettings: await stores.settings.load(),
            storeIssues: issues, nearby: nearbyState,
            nearbyOffer: session == nil ? nearbyOffer : nil)
        guard snapshot != current else { return }
        current = snapshot
        for continuation in snapshotSubscribers.values { continuation.yield(snapshot) }
    }

    // MARK: - conversions

    static func reading(_ wire: WireReading) -> Reading {
        Reading(t: Date(timeIntervalSince1970: Double(wire.t)), mgdl: wire.sgv, rate: wire.rate)
    }

    /// nil for an entry Core's model refuses (unknown kind or origin): it is not stored.
    static func treatment(_ p: TreatmentPayload) -> Treatment? {
        guard let kind = Treatment.Kind(rawValue: p.kind), let origin = Treatment.Origin(rawValue: p.origin) else {
            return nil
        }
        return Treatment(
            id: p.id, k: kind, a: p.amount, t: Int64(p.t), n: p.note, u: Int64(p.u), del: p.deleted, o: origin,
            pr: p.primed, pen: p.pen,
            pd: p.doses?.map { Treatment.PenDose(t: Int64($0.t), a: $0.a, p: $0.primed ?? false) })
    }

    static func payload(_ t: Treatment) -> TreatmentPayload {
        TreatmentPayload(
            id: t.id, kind: t.k.rawValue, amount: t.a, t: Int(t.t), note: t.n, u: Int(t.u), deleted: t.del,
            origin: t.o.rawValue, primed: t.pr, pen: t.pen,
            doses: t.pd?.map { TreatmentPayload.Dose(t: Int($0.t), a: $0.a, primed: $0.p) })
    }
}
