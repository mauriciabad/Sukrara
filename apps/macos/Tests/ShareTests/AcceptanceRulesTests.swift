// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation
import SukraraShare
import Testing

/// The follower acceptance rules: the OGS 27-step scenario (every verdict + the final state) and the cases it lacks.
struct AcceptanceRulesTests {
    let v = OgsVectors.shared
    let crypto = NostrSDKCrypto()

    func session(status: LinkStatus = .requested) throws -> FollowerSession {
        var state = FollowerState(
            follower: v.secret("F"), identity: v.pub("I"), token: "t", code: "0000", nearby: false,
            me: Profile(name: "Mac", avatar: nil), wearer: nil, relays: Wire.defaultRelays)
        state.status = status
        return try FollowerSession(state: state, crypto: crypto)
    }

    @Test func ogsScenario() throws {
        let scenario = v.raw["scenario"] as! [String: Any]
        let now = scenario["now"] as! Int
        var follower = try session()
        var readings = Set<Int>()
        var treatments: [String: TreatmentPayload] = [:]
        var sensor: SensorLife?
        var alerts = 0
        for (i, step) in (scenario["steps"] as! [[String: String]]).enumerated() {
            let outcome = follower.receive(v.event(step["event"]!), now: now)
            #expect(outcome.verdict.description == step["expect"], "step \(i) \(step["event"]!)")
            for delta in outcome.deltas {
                switch delta {
                case .readings(let rs): rs.forEach { readings.insert($0.t) }
                case .treatment(let t, _, _, _): treatments[t.id] = t
                case .sensor(let s): sensor = s
                case .alert: alerts += 1
                default: break
                }
            }
        }
        let final = scenario["final"] as! [String: Any]
        #expect(follower.state.status.rawValue == final["status"] as? String)
        #expect(follower.state.epochs.map(\.n).sorted() == final["epochs"] as? [Int])
        #expect(readings.count == final["readings"] as? Int)
        #expect(treatments.count == final["treatments"] as? Int)
        #expect(treatments.values.filter(\.deleted).count == final["deleted"] as? Int)
        #expect(follower.state.writers == final["writers"] as? [String])
        let s = final["sensor"] as! [String: Int]
        #expect(sensor == SensorLife(start: s["ss"]!, end: s["se"]!, warmupEnd: s["sw"]!))
        #expect(alerts == final["alerts"] as? Int)
    }

    @Test func keyDeliveryActivatesAndAsksToResubscribe() throws {
        var follower = try session()
        let outcome = follower.receive(v.event("key-1"), now: v.now)
        #expect(outcome.verdict == .accepted("key"))
        #expect(outcome.deltas == [.status(.active)])
        #expect(outcome.resubscribe)
        #expect(follower.state.sid == v.sid)
        #expect(
            follower.filters(now: v.now, newestStored: nil).keys.sorted() == [
                "chunks", "inbox", "live", "roster", "settings", "treatments",
            ])
    }

    @Test func unknownEpochIsReadableAfterTheKeyArrives() throws {
        var follower = try session()
        _ = follower.receive(v.event("key-1"), now: v.now)
        _ = follower.receive(v.event("roster-1"), now: v.now)
        let early = v.event("live-epoch-2-early")
        #expect(follower.receive(early, now: v.now).verdict == .ignored("unknown-epoch"))
        _ = follower.receive(v.event("key-2"), now: v.now)
        _ = follower.receive(v.event("roster-2"), now: v.now)
        #expect(follower.receive(early, now: v.now).verdict == .accepted("live"))
    }

    @Test func rekeyRequestOnlyFromTheStreamAndThrottled() throws {
        var follower = try session()
        _ = follower.receive(v.event("key-1"), now: v.now)
        _ = follower.receive(v.event("roster-1"), now: v.now)
        let early = v.event("live-epoch-2-early")  // signed by roster device D under unknown epoch 2
        let first = follower.receive(early, now: v.now)
        #expect(first.publish.count == 1)
        let wrap = try #require(first.publish.first)
        let rumor = try crypto.unwrap(wrap, with: v.secret("I"))
        #expect(rumor.sender == v.pub("F"))
        #expect(rumor.kind == Wire.kindToOwner)
        #expect(rumor.content.contains("\"type\":\"rekey\""))
        #expect(wrap.expiration == v.now + Wire.ttlRequest)
        #expect(follower.receive(early, now: v.now + 60).publish.isEmpty)
        #expect(follower.receive(early, now: v.now + Wire.rekeyInterval).publish.count == 1)
    }

    @Test func strangersUnknownEpochSendsNothing() throws {
        var follower = try session()
        _ = follower.receive(v.event("key-1"), now: v.now)
        let event = try crypto.sign(
            kind: Wire.kindLive, content: "x", tags: [["gk", "deadbeef"]], createdAt: v.now, with: v.secret("X"))
        let outcome = follower.receive(event, now: v.now)
        #expect(outcome.verdict == .ignored("unknown-epoch"))
        #expect(outcome.publish.isEmpty)
    }

    @Test func settingsNewestUWinsFromDevicesOnly() throws {
        var follower = try session()
        _ = follower.receive(v.event("key-1"), now: v.now)
        _ = follower.receive(v.event("roster-1"), now: v.now)
        func settings(u: Int, by signer: String, d: String? = nil, createdAt: Int) throws -> SignedEvent {
            let body = "{\"v\":2,\"type\":\"settings\",\"u\":\(u),\"graph\":{},\"alerts\":{}}"
            let content = try crypto.nip44Encrypt(body, from: v.secret(signer), to: v.pub("G1"))
            return try crypto.sign(
                kind: Wire.kindData, content: content,
                tags: [["d", d ?? Wire.settingsD(sid: v.sid)], ["gk", Wire.gk(groupPublicKey: v.pub("G1"))]],
                createdAt: createdAt, with: v.secret(signer))
        }
        let first = follower.receive(try settings(u: 100, by: "D", createdAt: v.now - 10), now: v.now)
        #expect(first.verdict == .accepted("settings"))
        guard case .settings(let s) = first.deltas.first else { Issue.record("no settings delta"); return }
        #expect(s.u == 100)
        #expect(
            follower.receive(try settings(u: 100, by: "D", createdAt: v.now - 9), now: v.now).verdict
                == .ignored("settings-older"))
        #expect(
            follower.receive(try settings(u: 99, by: "D", createdAt: v.now - 8), now: v.now).verdict
                == .ignored("settings-older"))
        #expect(
            follower.receive(try settings(u: 200, by: "X", createdAt: v.now - 7), now: v.now).verdict
                == .rejected("author-not-device"))
        #expect(
            follower.receive(try settings(u: 201, by: "D", createdAt: v.now - 6), now: v.now).verdict
                == .accepted("settings"))
        #expect(follower.state.settingsU == 201)
    }

    @Test func readingInTheFutureIsRefusedBeforeTheSensorChanges() throws {
        var follower = try session()
        _ = follower.receive(v.event("key-1"), now: v.now)
        _ = follower.receive(v.event("roster-1"), now: v.now)
        let body = "{\"v\":2,\"t\":\(v.now + 3600),\"sgv\":120,\"r\":null,\"dev\":\"x\"}"
        let content = try crypto.nip44Encrypt(body, from: v.secret("D"), to: v.pub("G1"))
        let event = try crypto.sign(
            kind: Wire.kindLive, content: content, tags: [["gk", Wire.gk(groupPublicKey: v.pub("G1"))]],
            createdAt: v.now, with: v.secret("D"))
        let outcome = follower.receive(event, now: v.now)
        #expect(outcome.verdict == .rejected("reading-in-future"))
        #expect(outcome.deltas.isEmpty)
        #expect(follower.state.sensorCreatedAt == -1)
    }

    @Test func declinedOnlyWhileWaiting() throws {
        var follower = try session()
        let declined = try crypto.giftWrap(
            kind: Wire.kindToFollower, content: "{\"v\":2,\"type\":\"declined\"}", from: v.secret("I"),
            to: v.pub("F"), expiration: v.now + 86_400)
        let outcome = follower.receive(declined, now: v.now)
        #expect(outcome.verdict == .accepted("declined"))
        #expect(follower.state.status == .declined)
        var active = try session(status: .active)
        let used = try crypto.giftWrap(
            kind: Wire.kindToFollower, content: "{\"v\":2,\"type\":\"invite-used\"}", from: v.secret("I"),
            to: v.pub("F"), expiration: v.now + 86_400)
        #expect(active.receive(used, now: v.now).verdict == .ignored("invite-used"))
        #expect(active.state.status == .active)
    }

    @Test func olderRosterIgnored() throws {
        var follower = try session()
        _ = follower.receive(v.event("key-1"), now: v.now)
        _ = follower.receive(v.event("roster-1-writer"), now: v.now)
        #expect(follower.receive(v.event("roster-1"), now: v.now).verdict == .ignored("roster-older"))
    }

    @Test func stateSurvivesARestart() throws {
        var follower = try session()
        _ = follower.receive(v.event("key-1"), now: v.now)
        _ = follower.receive(v.event("treatment-owner"), now: v.now)
        _ = follower.receive(v.event("roster-1-writer"), now: v.now)
        let restored = try FollowerState.decode(follower.state.encoded())
        #expect(restored == follower.state)
        var again = try FollowerSession(state: restored, crypto: crypto)
        // trust survives: the hijack is still refused after a restart
        #expect(
            again.receive(v.event("treatment-writer-hijack"), now: v.now).verdict == .rejected("writer-not-creator"))
        #expect(again.receive(v.event("live"), now: v.now).verdict == .accepted("live"))
    }

    @Test func secretsNeverPrint() throws {
        let secret = v.secret("F")
        #expect(!"\(secret)".contains(secret.hex))
        #expect(!String(reflecting: secret).contains(secret.hex))
        var dumped = ""
        dump(secret, to: &dumped)
        #expect(!dumped.contains(secret.hex))
    }
}
