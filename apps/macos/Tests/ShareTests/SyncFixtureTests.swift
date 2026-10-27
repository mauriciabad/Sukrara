// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation
import SukraraShare
import SukraraTesting
import Testing

/// Every follower sequence in contracts/protocol/sync (written by the Android tests, cross-checked with the Python
/// reference): each step's verdict and the final follower state must match. Owner-side files (no `steps`, e.g.
/// owner-conflict.json) belong to the wearer's app.
struct SyncFixtureTests {
    static var followerSequences: [String] {
        let dir = Contracts.url("protocol/sync")
        let files = (try? FileManager.default.contentsOfDirectory(atPath: dir.path)) ?? []
        return files.filter { $0.hasSuffix(".json") }.sorted().filter { name in
            loadJSON("protocol/sync/\(name)")["steps"] != nil
        }
    }

    @Test(arguments: followerSequences)
    func sequence(_ name: String) throws {
        let fixture = loadJSON("protocol/sync/\(name)")
        let keys = fixture["keys"] as! [String: [String: String]]
        let followerKey = keys[fixture["follower"] as! String]!
        let now = fixture["now"] as! Int
        var state = FollowerState(
            follower: SecretKeyHex(followerKey["sec"]!)!, identity: fixture["identity"] as! String, token: "t",
            code: "0000", nearby: false, me: Profile(name: "Mac", avatar: nil), wearer: nil,
            relays: Wire.defaultRelays)
        state.status = .requested
        let crypto = NostrSDKCrypto()
        var session = try FollowerSession(state: state, crypto: crypto)
        let identitySecret = try #require(
            keys.values.first { $0["pub"] == fixture["identity"] as? String }.flatMap { SecretKeyHex($0["sec"]!) })
        let events = fixture["events"] as! [String: Any]
        var readings: [Int: WireReading] = [:]
        var treatments: [String: TreatmentPayload] = [:]
        var sensor: SensorLife?
        var settings: SettingsPayload?
        var alerts = 0
        for (i, step) in (fixture["steps"] as! [[String: Any]]).enumerated() {
            if step["restart"] as? Bool == true {
                // A relaunch: the state goes through the Keychain format; seen ids and rekey throttles are forgotten.
                session = try FollowerSession(state: FollowerState.decode(session.state.encoded()), crypto: crypto)
                continue
            }
            let label = "\(name) step \(i) \(step["event"] as! String)"
            let event = try #require(SignedEvent.decode(json: JSONText.encode(events[step["event"] as! String]!)))
            let outcome = session.receive(event, now: now)
            #expect(outcome.verdict.description == step["expect"] as? String, "\(label)")
            let have = try outcome.publish.map { wrap in
                let body = try JSONSerialization.jsonObject(
                    with: Data(try crypto.unwrap(wrap, with: identitySecret).content.utf8)) as? [String: Any]
                #expect(body?["type"] as? String == "rekey", "\(label)")
                return body?["have"] as? String
            }
            #expect(have == ((step["rekey"] as? String).map { [$0] } ?? []), "\(label) rekey")
            for delta in outcome.deltas {
                switch delta {
                case .readings(let rs): for r in rs where readings[r.t] == nil { readings[r.t] = r }
                case .treatment(let t, _, _, _): treatments[t.id] = t
                case .sensor(let s): sensor = s
                case .settings(let s): settings = s
                case .alert: alerts += 1
                case .status, .roster: break
                }
            }
        }
        let expected = fixture["expected"] as! [String: Any]
        #expect(session.state.status.rawValue == expected["status"] as? String, "\(name)")
        #expect(session.state.epochs.map(\.n).sorted() == expected["epochs"] as? [Int], "\(name)")
        #expect(session.state.devices == expected["devices"] as? [String], "\(name)")
        #expect(session.state.writers == expected["writers"] as? [String], "\(name)")
        let gotReadings = readings.values.sorted { $0.t < $1.t }.map { r -> [Double?] in
            [Double(r.t), Double(r.sgv), r.rate]
        }
        let wantReadings = (expected["readings"] as! [[Any]]).map { row in row.map { JSONNumber.double($0) } }
        #expect(gotReadings == wantReadings, "\(name)")
        if let s = expected["sensor"] as? [String: Int] {
            #expect(sensor == SensorLife(start: s["ss"]!, end: s["se"]!, warmupEnd: s["sw"]!), "\(name)")
        } else {
            #expect(sensor == nil, "\(name)")
        }
        let gotTreatments = treatments.values.sorted { $0.id < $1.id }.map {
            JSONText.encode(["id": $0.id, "k": $0.kind, "a": $0.amount, "u": $0.u, "o": $0.origin, "del": $0.deleted])
        }
        let wantTreatments = (expected["treatments"] as! [[String: Any]]).map {
            JSONText.encode($0.merging(["a": JSONNumber.double($0["a"]) ?? 0]) { $1 })
        }
        #expect(gotTreatments == wantTreatments, "\(name)")
        if let s = expected["settings"] as? [String: Any] {
            #expect(settings?.u == s["u"] as? Int, "\(name)")
            let body = try JSONSerialization.jsonObject(with: Data((settings?.json ?? "{}").utf8)) as? [String: Any]
            #expect(JSONText.encode(body?["graph"] ?? [:]) == JSONText.encode(s["graph"] ?? [:]), "\(name)")
            #expect(JSONText.encode(body?["alerts"] ?? [:]) == JSONText.encode(s["alerts"] ?? [:]), "\(name)")
        } else {
            #expect(settings == nil, "\(name)")
        }
        #expect(alerts == expected["alerts"] as? Int, "\(name)")
    }
}

enum JSONNumber {
    static func double(_ value: Any?) -> Double? { (value as? NSNumber).map(\.doubleValue) }
}
