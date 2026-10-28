// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation
import SukraraCore
import SukraraShare
import SukraraTesting
import Synchronization
import Testing

/// The follower's Bluetooth path without a radio: the test plays the phones in range.
final class FakeNearby: NearbyTransport {
    let events: AsyncStream<NearbyEvent>
    private let continuation: AsyncStream<NearbyEvent>.Continuation
    private let leaves = Mutex<[String]>([])

    init() { (events, continuation) = AsyncStream.makeStream() }

    var left: [String] { leaves.withLock { $0 } }
    func start() async {}
    func stop() async { continuation.finish() }
    func update(identity: NearbyLink.Identity) async {}
    func send(_ event: SignedEvent) async -> Bool { false }
    func leave(phone: UUID, for seconds: Double) async { leaves.withLock { $0.append("\(phone) \(Int(seconds))") } }
    func info(_ info: BleInfo, _ owner: BleInfo.Owner, phone: UUID) {
        continuation.yield(.info(info, owner, phone: phone))
    }
}

/// Which phones the follower avoids, and for how long (B§6 "Which owner").
struct NearbyRuntimeTests {
    let v = OgsVectors.shared

    private func runtime(_ h: RuntimeHarness, _ nearby: FakeNearby) -> FollowerRuntime {
        FollowerRuntime(
            secrets: h.secrets, stores: h.stores, crypto: NostrSDKCrypto(), clock: h.clock,
            me: Profile(name: "Mac", avatar: nil), makeRelays: { _, _ in h.hub.connect() }, makeNearby: { _ in nearby },
            sleep: { _ in try await Task.sleep(for: .seconds(3600)) })
    }

    private let stranger = BleInfo(nonce: "00", m: "00", identity: nil, relays: [], pairing: nil)

    @Test func anotherWearersPhoneIsAvoidedForTenMinutes() async throws {
        let h = RuntimeHarness(now: v.now, hub: FakeRelayHub(stored: [v.event("invite")]))
        let nearby = FakeNearby()
        let runtime = runtime(h, nearby)
        await runtime.start()
        try await runtime.join(link: (v.derivations["invite"] as! [String: Any])["link"] as! String)
        let phone = UUID()
        nearby.info(stranger, .other, phone: phone)
        #expect(await eventually { nearby.left == ["\(phone) 600"] })
        await runtime.stop()
    }

    @Test func aPhoneWithNothingToPairWithIsLeftForAMinute() async {
        let h = RuntimeHarness(now: v.now)
        let nearby = FakeNearby()
        let runtime = runtime(h, nearby)
        await runtime.start()
        await runtime.searchNearby(true)
        let phone = UUID()
        nearby.info(stranger, .unknown, phone: phone)
        #expect(await eventually { nearby.left == ["\(phone) 60"] })
        await runtime.stop()
    }
}
