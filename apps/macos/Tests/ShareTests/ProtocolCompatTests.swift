// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation
import SukraraCore
import SukraraShare
import SukraraTesting
import Testing

/// Following works end to end with the wearer's wire format: every follower sequence in contracts/protocol/sync is
/// replayed in step order through the `FollowerRuntime` (relay path, Keychain state, real file stores) and the
/// stores must end as the fixture says. `rotation.json` is also the identity-restore case: a new device key and
/// epoch delivered to every follower, a roster with only the new device, late rosters from the old one ignored —
/// the link stays "following" with no action. `revoke-reapprove.json` relaunches the runtime mid-sequence: a removed
/// and re-approved Mac keeps following when the relays replay the old revoked notice. The wire format itself is pinned by the
/// OGS vectors (Python reference, `FollowerRuntimeTests.joinRequestThenFollowFillsTheStores`).
struct ProtocolCompatTests {
    @Test(arguments: SyncFixtureTests.followerSequences)
    func replayThroughTheRuntime(_ name: String) async throws {
        let fixture = loadJSON("protocol/sync/\(name)")
        let keys = fixture["keys"] as! [String: [String: String]]
        let now = fixture["now"] as! Int
        var state = FollowerState(
            follower: SecretKeyHex(keys[fixture["follower"] as! String]!["sec"]!)!,
            identity: fixture["identity"] as! String, token: "t", code: "0000", nearby: false,
            me: Profile(name: "Mac", avatar: nil), wearer: nil, relays: Wire.defaultRelays)
        state.status = .requested
        let secrets = InMemorySecretStore()
        secrets.write(try state.encoded(), account: KeychainStore.followerAccount)
        var harness = RuntimeHarness(now: now, secrets: secrets)
        await harness.runtime.start()

        let events = fixture["events"] as! [String: Any]
        for step in fixture["steps"] as! [[String: Any]] {
            if step["restart"] as? Bool == true {
                // A relaunch: a new runtime on the same Keychain item and stores.
                await harness.runtime.stop()
                harness = RuntimeHarness(now: now, hub: harness.hub, secrets: secrets, directory: harness.directory)
                await harness.runtime.start()
                continue
            }
            let event = try #require(SignedEvent.decode(json: JSONText.encode(events[step["event"] as! String]!)))
            await harness.hub.push(event)
            // One step at a time, in order: wait until the runtime has judged it (state or stores settle).
            try await Task.sleep(for: .milliseconds(30))
        }
        let h = harness
        let expected = fixture["expected"] as! [String: Any]
        let wantReadings = (expected["readings"] as! [[Any]]).count
        #expect(
            await eventually { (try? await h.stores.readings.load(since: .distantPast).count) == wantReadings },
            "\(name) readings")

        let wantDevices = expected["devices"] as? [String]
        let wantStatus = expected["status"] as? String
        #expect(
            await eventually {
                let state = try? secrets.read(account: KeychainStore.followerAccount).map(FollowerState.decode)
                return state?.devices == wantDevices && state?.status.rawValue == wantStatus
            }, "\(name) saved state")
        let saved = try FollowerState.decode(try #require(try secrets.read(account: KeychainStore.followerAccount)))
        #expect(saved.status.rawValue == expected["status"] as? String, "\(name)")
        #expect(saved.epochs.map(\.n).sorted() == expected["epochs"] as? [Int], "\(name)")
        #expect(saved.devices == expected["devices"] as? [String], "\(name)")
        #expect(saved.writers == expected["writers"] as? [String], "\(name)")

        let gotReadings = try await h.stores.readings.load(since: .distantPast).map { r -> [Double?] in
            [r.t.timeIntervalSince1970, Double(r.mgdl), r.rate]
        }
        let want = (expected["readings"] as! [[Any]]).map { row in row.map { JSONNumber.double($0) } }
        #expect(gotReadings == want, "\(name)")

        if let s = expected["sensor"] as? [String: Int] {
            #expect(await h.stores.sensor.load() == WearerSensor(start: s["ss"]!, end: s["se"]!, warmupEnd: s["sw"]!))
        } else {
            #expect(await h.stores.sensor.load() == nil, "\(name)")
        }

        let log = try await h.stores.treatments.load()
        let gotTreatments = log.entries.values.map(\.treatment).sorted { $0.id < $1.id }.map {
            JSONText.encode(["id": $0.id, "k": $0.k.rawValue, "a": $0.a, "u": $0.u, "o": $0.o.rawValue, "del": $0.del])
        }
        let wantTreatments = (expected["treatments"] as! [[String: Any]]).map {
            JSONText.encode($0.merging(["a": JSONNumber.double($0["a"]) ?? 0]) { $1 })
        }
        #expect(gotTreatments == wantTreatments, "\(name)")

        let settings = await h.stores.settings.load()
        #expect(settings?.u == (expected["settings"] as? [String: Any])?["u"] as? Int, "\(name)")
        if saved.status == .active {
            #expect(await h.runtime.isFollowing, "\(name): still following")
        }
        await h.runtime.stop()
    }
}
