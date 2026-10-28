// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation
import SukraraCore
import SukraraShare
import SukraraTesting
import Testing

/// A runtime over a fake relay hub, in-memory Keychain and real file stores in a temp directory.
struct RuntimeHarness {
    let hub: FakeRelayHub
    let secrets: any SecretStoring
    let clock: FakeClock
    let directory: URL
    let stores: FollowerStores
    let runtime: FollowerRuntime

    /// `directory`: an earlier harness's, to relaunch on the same stores.
    init(
        now: Int, hub: FakeRelayHub = FakeRelayHub(), secrets: any SecretStoring = InMemorySecretStore(),
        directory: URL? = nil
    ) {
        self.hub = hub
        self.secrets = secrets
        clock = FakeClock(Date(timeIntervalSince1970: Double(now)))
        self.directory =
            directory ?? FileManager.default.temporaryDirectory.appending(path: "sukrara-runtime-\(UUID().uuidString)")
        stores = .files(SukraraPaths(home: self.directory), clock: clock)
        runtime = FollowerRuntime(
            secrets: secrets, stores: stores, crypto: NostrSDKCrypto(), clock: clock,
            me: Profile(name: "Mac", avatar: nil), makeRelays: { _, _ in hub.connect() },
            sleep: { _ in try await Task.sleep(for: .seconds(3600)) })
    }

    var paths: SukraraPaths { SukraraPaths(home: directory) }
}

/// Polls `condition` for up to 3 s (the runtime processes relay messages on its own tasks).
func eventually(_ condition: @Sendable () async -> Bool) async -> Bool {
    for _ in 0..<300 {
        if await condition() { return true }
        try? await Task.sleep(for: .milliseconds(10))
    }
    return false
}

struct FollowerRuntimeTests {
    let v = OgsVectors.shared
    let crypto = NostrSDKCrypto()

    /// Re-wraps the OGS key delivery (made by the Python reference) to `follower`.
    func keyDelivery(to follower: String) throws -> SignedEvent {
        let rumor = (v.raw["plaintexts"] as! [String: Any])["key-1"] as! [String: Any]
        let content = (rumor["rumor"] as! [String: Any])["content"] as! String
        return try crypto.giftWrap(
            kind: Wire.kindToFollower, content: content, from: v.secret("I"), to: follower,
            expiration: v.now + 3600)
    }

    @Test func joinRequestThenFollowFillsTheStores() async throws {
        let hub = FakeRelayHub(stored: [v.event("invite")])
        let h = RuntimeHarness(now: v.now, hub: hub)
        await h.runtime.start()
        let snapshots = await h.runtime.snapshots()
        let invite = (v.derivations["invite"] as! [String: Any])["link"] as! String
        try await h.runtime.join(link: invite)

        // Requested: the access request (a gift wrap to the identity) went out, the key is in the Keychain.
        let stateData = try #require(try h.secrets.read(account: KeychainStore.followerAccount))
        let state = try FollowerState.decode(stateData)
        #expect(state.status == .requested)
        #expect(state.identity == v.pub("I"))
        let published = await hub.published()
        #expect(published.contains { $0.kind == Wire.kindWrap && $0.tag("p") == v.pub("I") })

        // The wearer allows: key, roster, an hour chunk, a live reading, a wearer treatment.
        let follower = try crypto.publicKey(of: state.follower)
        await hub.inject(try keyDelivery(to: follower))
        #expect(await eventually { await h.runtime.isFollowing })
        for name in ["roster-1", "chunk", "live", "treatment-owner"] { await hub.inject(v.event(name)) }
        #expect(await eventually { (try? await h.stores.readings.load(since: .distantPast).count) ?? 0 > 1 })
        #expect(await eventually { (try? await h.stores.treatments.load().entries.count) == 1 })
        let latest = try #require(try await h.stores.readings.load(since: .distantPast).last)
        #expect(Int(latest.t.timeIntervalSince1970) == 1_792_087_170 && latest.mgdl == 158)
        #expect(
            await h.stores.sensor.load()
                == WearerSensor(start: 1_791_828_000, end: 1_793_124_000, warmupEnd: 1_791_831_600))

        var sawFollowing = false
        for await snapshot in snapshots {
            if case .following = snapshot.link, snapshot.readings.last?.mgdl == 158 {
                sawFollowing = true
                break
            }
        }
        #expect(sawFollowing)
        await h.runtime.stop()
    }

    /// Wake, the network coming back and a relay change can reconnect at once: the changes run one after another, so
    /// exactly one relay connection stays open (overlapping ones leaked pools that kept their sockets).
    @Test func overlappingReconnectsLeaveOneConnection() async throws {
        let h = RuntimeHarness(now: v.now, hub: FakeRelayHub(stored: [v.event("invite")]))
        await h.runtime.start()
        try await h.runtime.join(link: (v.derivations["invite"] as! [String: Any])["link"] as! String)
        await withTaskGroup(of: Void.self) { group in
            for _ in 0..<8 { group.addTask { await h.runtime.reconnect() } }
        }
        var open = 0
        for relay in h.hub.relays where await !relay.isStopped { open += 1 }
        #expect(open == 1)
        // the triggers that arrived while one was queued joined it
        #expect(h.hub.relays.count < 2 + 8)
        await h.runtime.stop()
        for relay in h.hub.relays { #expect(await relay.isStopped) }
    }

    @Test func unfollowWipesTheWearersDataAndTheKey() async throws {
        let h = RuntimeHarness(now: v.now, hub: FakeRelayHub(stored: [v.event("invite")]))
        await h.runtime.start()
        try await h.runtime.join(link: (v.derivations["invite"] as! [String: Any])["link"] as! String)
        try await h.stores.readings.merge([Reading(t: Date(timeIntervalSince1970: Double(v.now)), mgdl: 120)])
        await h.runtime.unfollow()
        #expect(try h.secrets.read(account: KeychainStore.followerAccount) == nil)
        #expect(try await h.stores.readings.load(since: .distantPast).isEmpty)
        #expect(await h.runtime.currentLink == .notFollowing)
        await h.runtime.stop()
    }

    @Test func aLinkWithoutAnInviteOnTheRelaysIsNotFound() async throws {
        let h = RuntimeHarness(now: v.now)
        let runtime = FollowerRuntime(
            secrets: h.secrets, stores: h.stores, crypto: crypto, clock: h.clock, me: Profile(name: "Mac", avatar: nil),
            makeRelays: { _, _ in h.hub.connect() }, sleep: { _ in })
        await runtime.start()
        await #expect(throws: FollowerJoinError.notFound) {
            try await runtime.join(link: (v.derivations["invite"] as! [String: Any])["link"] as! String)
        }
        await #expect(throws: FollowerJoinError.invalidLink) { try await runtime.join(link: "https://example.org") }
        #expect(await runtime.currentLink == .notFollowing)
        await runtime.stop()
    }

    @Test func anUnreadableKeychainKeepsTheHistoryAndSaysWhy() async throws {
        let h = RuntimeHarness(now: v.now, secrets: FailingSecretStore())
        try await h.stores.readings.merge([Reading(t: Date(timeIntervalSince1970: Double(v.now)), mgdl: 120)])
        let runtime = FollowerRuntime(
            secrets: FailingSecretStore(), stores: h.stores, crypto: crypto, clock: h.clock,
            me: Profile(name: "Mac", avatar: nil), makeRelays: { _, _ in h.hub.connect() })
        await runtime.start()
        guard case .broken = await runtime.currentLink else {
            Issue.record("expected broken")
            return
        }
        #expect(try await h.stores.readings.load(since: .distantPast).count == 1)
        await runtime.stop()
    }

    @Test func noKeychainItemAtStartWipesLeftovers() async throws {
        let h = RuntimeHarness(now: v.now)
        try await h.stores.readings.merge([Reading(t: Date(timeIntervalSince1970: Double(v.now)), mgdl: 120)])
        await h.runtime.start()
        #expect(try await h.stores.readings.load(since: .distantPast).isEmpty)
        #expect(await h.runtime.currentLink == .notFollowing)
        await h.runtime.stop()
    }

    @Test func treatmentOutboxGoesSendingSentAccepted() async throws {
        // The OGS writer W (roster-1-writer lists it), waiting for its key.
        var state = FollowerState(
            follower: v.secret("W"), identity: v.pub("I"), token: "t", code: "0000", nearby: false,
            me: Profile(name: "Mac", avatar: nil), wearer: nil, relays: Wire.defaultRelays)
        state.status = .requested
        let secrets = InMemorySecretStore()
        secrets.write(try state.encoded(), account: KeychainStore.followerAccount)
        let hub = FakeRelayHub()
        let h = RuntimeHarness(now: v.now, hub: hub)
        let runtime = FollowerRuntime(
            secrets: secrets, stores: h.stores, crypto: crypto, clock: h.clock, me: state.me,
            makeRelays: { _, _ in hub.connect() }, sleep: { _ in try await Task.sleep(for: .seconds(3600)) })
        await runtime.start()
        await hub.inject(try keyDelivery(to: v.pub("W")))
        await hub.inject(v.event("roster-1-writer"))
        #expect(await eventually { await runtime.currentLinkIsWriter() })
        let entry = Treatment(
            id: "0b9e3f52-7c1d-4e8a-9f60-123456789abd", k: .rapid, a: 2, t: Int64(v.now - 60),
            u: Int64(v.now) * 1000, o: .follower)
        #expect(await runtime.send(entry) == .sending)
        // FakeRelay answers OK true at once → sent.
        #expect(await eventually { (try? await h.stores.treatments.load().entries[entry.id]?.delivery) == .sent })
        await runtime.stop()
    }
}

extension FollowerRuntime {
    /// Test helper: the link state of the newest snapshot.
    var currentLink: LinkState {
        get async {
            var iterator = snapshots().makeAsyncIterator()
            return await iterator.next()?.link ?? .notFollowing
        }
    }

    var isFollowing: Bool { get async { await currentLink.isFollowing } }

    func currentLinkIsWriter() async -> Bool {
        if case .following(_, let canWrite) = await currentLink { return canWrite }
        return false
    }
}

/// A Keychain that can't be read (locked / access denied).
final class FailingSecretStore: SecretStoring {
    func read(account: String) throws -> Data? { throw KeychainError.status(-25308) }
    func write(_ data: Data, account: String) throws { throw KeychainError.status(-25308) }
    func delete(account: String) throws { throw KeychainError.status(-25308) }
}

/// A Keychain that refuses reads until `recover()` (the login keychain not unlocked yet at a login-item launch).
final class FlakySecretStore: SecretStoring, @unchecked Sendable {
    // Guarded by `lock`.
    private let lock = NSLock()
    private var failing = true
    private let inner = InMemorySecretStore()

    func recover() { lock.withLock { failing = false } }

    func read(account: String) throws -> Data? {
        if lock.withLock({ failing }) { throw KeychainError.status(-25308) }
        return inner.read(account: account)
    }

    func write(_ data: Data, account: String) throws { inner.write(data, account: account) }
    func delete(account: String) throws { inner.delete(account: account) }
}

/// r-code-mac H2: an unreadable Keychain at start is retried (backoff and every reconnect) and the snapshot says this
/// Mac still follows, so the app keeps alerting on the stored readings.
struct KeychainRetryTests {
    let v = OgsVectors.shared
    let requested = LinkState.requested(code: "0000", wearer: nil, nearby: false)

    func secrets() throws -> FlakySecretStore {
        var state = FollowerState(
            follower: v.secret("F"), identity: v.pub("I"), token: "t", code: "0000", nearby: false,
            me: Profile(name: "Mac", avatar: nil), wearer: nil, relays: Wire.defaultRelays)
        state.status = .requested
        let secrets = FlakySecretStore()
        try secrets.write(try state.encoded(), account: KeychainStore.followerAccount)
        return secrets
    }

    func runtime(
        _ secrets: FlakySecretStore, _ h: RuntimeHarness, sleep: @escaping @Sendable (Double) async throws -> Void
    )
        -> FollowerRuntime
    {
        FollowerRuntime(
            secrets: secrets, stores: h.stores, crypto: NostrSDKCrypto(), clock: h.clock,
            me: Profile(name: "Mac", avatar: nil), makeRelays: { _, _ in h.hub.connect() }, sleep: sleep)
    }

    @Test func reconnectReadsTheKeychainAgain() async throws {
        let secrets = try secrets()
        let h = RuntimeHarness(now: v.now, secrets: secrets)
        try await h.stores.readings.merge([Reading(t: Date(timeIntervalSince1970: Double(v.now)), mgdl: 120)])
        let runtime = runtime(secrets, h) { _ in try await Task.sleep(for: .seconds(3600)) }
        await runtime.start()
        var snapshots = await runtime.snapshots().makeAsyncIterator()
        let broken = try #require(await snapshots.next())
        guard case .broken = broken.link else {
            Issue.record("expected broken, got \(broken.link)")
            return
        }
        #expect(broken.keychainUnreadable)
        #expect(broken.readings.count == 1)  // still the wearer's
        secrets.recover()
        await runtime.reconnect()
        let requested = requested
        #expect(await eventually { await runtime.currentLink == requested })
        await runtime.stop()
    }

    @Test func retriesOnItsOwnWithBackoff() async throws {
        let secrets = try secrets()
        let h = RuntimeHarness(now: v.now, secrets: secrets)
        let delays = DelayLog()
        let runtime = runtime(secrets, h) { seconds in
            delays.add(seconds)
            try await Task.sleep(for: .milliseconds(20))
        }
        await runtime.start()
        #expect(await eventually { delays.values.count >= 3 })
        #expect(Array(delays.values.prefix(3)) == [5, 10, 20])
        secrets.recover()
        let requested = requested
        #expect(await eventually { await runtime.currentLink == requested })
        await runtime.stop()
    }
}

/// The delays a runtime asked to sleep, in order.
final class DelayLog: @unchecked Sendable {
    // Guarded by `lock`.
    private let lock = NSLock()
    private var log: [Double] = []

    func add(_ seconds: Double) { lock.withLock { log.append(seconds) } }
    var values: [Double] { lock.withLock { log } }
}
