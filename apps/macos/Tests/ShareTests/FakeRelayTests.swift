// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation
import SukraraShare
import SukraraTesting
import Testing

/// The follower session over the fake relay, end to end, without a network.
struct FakeRelayTests {
    let v = OgsVectors.shared
    let crypto = NostrSDKCrypto()

    @Test func filterMatching() {
        let e = SignedEvent(
            id: "1", pubkey: "p1", createdAt: 1000, kind: 1059, tags: [["p", "me"]], content: "", sig: "")
        #expect(RelayFilter(kinds: [1059], authors: ["p1"], p: ["me"]).matches(e))
        #expect(!RelayFilter(authors: ["p2"]).matches(e))
        #expect(!RelayFilter(since: 2000).matches(e))
        #expect(!RelayFilter(p: ["other"]).matches(e))
        #expect(!RelayFilter(d: ["x"]).matches(e))
    }

    @Test func followerJoinsAndFollowsOverTheFakeRelay() async throws {
        let stored = ["key-1", "roster-1", "chunk", "live", "alert", "treatment-owner"].map(v.event)
        let relay = FakeRelay(stored: stored)
        var state = FollowerState(
            follower: v.secret("F"), identity: v.pub("I"), token: "t", code: "0000", nearby: false,
            me: Profile(name: "Mac", avatar: nil), wearer: nil, relays: Wire.defaultRelays)
        state.status = .requested
        var session = try FollowerSession(state: state, crypto: crypto)
        var verdicts: [String] = []
        var readings = 0
        for (id, filter) in session.filters(now: v.now, newestStored: nil) { await relay.subscribe(id, filter) }
        var pendingEnds = Set(await relay.subscriptions.keys)
        for await message in relay.inbound {
            switch message {
            case .event(let event, _, _):
                let outcome = session.receive(event, now: v.now)
                verdicts.append(outcome.verdict.description)
                for case .readings(let rs) in outcome.deltas { readings += rs.count }
                if outcome.resubscribe {
                    for (id, filter) in session.filters(now: v.now, newestStored: nil) {
                        if await relay.subscriptions[id] != filter { pendingEnds.insert(id) }
                        await relay.subscribe(id, filter)
                    }
                }
            case .endOfStored(let id, _):
                pendingEnds.remove(id)
            default: break
            }
            if pendingEnds.isEmpty { break }
        }
        #expect(session.state.status == .active)
        #expect(verdicts.contains("accepted:roster"))
        #expect(verdicts.contains("accepted:chunk"))
        #expect(verdicts.contains("accepted:alert"))
        #expect(verdicts.contains("accepted:treatment"))
        #expect(readings > 1)
    }

    @Test func accessRequestReachesTheWearer() async throws {
        let relay = FakeRelay()
        let invite = Invite(
            identity: v.pub("I"), token: "tok", expiry: v.now + 3600, relays: [], profile: nil)
        var session = try FollowerSession.join(
            invite: invite, me: Profile(name: "Mac", avatar: Avatar.random()), nearby: false, crypto: crypto,
            now: v.now)
        #expect(session.state.status == .new)
        #expect(session.state.relays == Wire.defaultRelays)
        await relay.publish(try session.requestAccess(now: v.now))
        #expect(session.state.status == .requested)
        let wrap = try #require(await relay.published.first)
        let rumor = try crypto.unwrap(wrap, with: v.secret("I"))
        #expect(rumor.sender == session.followerPublicKey)
        #expect(rumor.content.contains("\"token\":\"tok\""))
        #expect(rumor.content.contains("\"name\":\"Mac\""))
    }

    @Test func expiredInviteCannotBeJoined() {
        let invite = Invite(identity: v.pub("I"), token: "t", expiry: v.now - 1, relays: [], profile: nil)
        #expect(throws: FollowerSession.JoinError.expired) {
            try FollowerSession.join(
                invite: invite, me: Profile(name: "Mac", avatar: nil), nearby: false, crypto: crypto, now: v.now)
        }
    }

    @Test func writerTreatmentIsAcceptedBySessionsAndEchoTracked() throws {
        var state = FollowerState(
            follower: v.secret("W"), identity: v.pub("I"), token: "t", code: "0000", nearby: false,
            me: Profile(name: "Mac", avatar: nil), wearer: nil, relays: Wire.defaultRelays)
        // W already holds epoch 1 (its key delivery isn't in the vectors)
        state.status = .active
        state.sid = v.sid
        state.epochs = [.init(n: 1, g: v.secret("G1"))]
        state.devices = [v.pub("D")]
        var writer = try FollowerSession(state: state, crypto: crypto)
        let entry = TreatmentPayload(
            id: "0b9e3f52-7c1d-4e8a-9f60-123456789abd", kind: "rapid", amount: 2, t: v.now - 60, note: "",
            u: v.now * 1000,
            deleted: false, origin: "mac")
        #expect(try writer.treatmentEvent(entry, now: v.now) == nil)  // not a writer yet
        _ = writer.receive(v.event("roster-1-writer"), now: v.now)
        #expect(writer.canWrite)
        let first = try #require(try writer.treatmentEvent(entry, now: v.now))
        let edit = try #require(try writer.treatmentEvent(entry, now: v.now))
        #expect(edit.createdAt == first.createdAt + 1)  // strictly increasing per d
        #expect(first.tag("expiration") == String(first.createdAt + Wire.ttlTreatment))
        // another follower accepts it as the writer's own entry, with o forced to follower
        var other = try AcceptanceRulesTests().session()
        _ = other.receive(v.event("key-1"), now: v.now)
        _ = other.receive(v.event("roster-1-writer"), now: v.now)
        let outcome = other.receive(first, now: v.now)
        #expect(outcome.verdict == .accepted("treatment"))
        guard case .treatment(let got, let fromWearer, let creator, _) = outcome.deltas.first else {
            Issue.record("no treatment delta")
            return
        }
        #expect(got.origin == "follower")
        #expect(!fromWearer)
        #expect(creator == v.pub("W"))
    }
}
