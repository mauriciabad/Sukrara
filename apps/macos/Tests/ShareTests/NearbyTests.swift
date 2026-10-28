// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation
import SukraraShare
import Testing

/// The chunk backfill window (FEATURES: 1 h before the newest stored reading, at least 13 h, at most 7 days back).
@Suite struct ChunkWindowTests {
    let now = 1_800_000_000

    @Test func anEmptyStoreAsksForTheLast13Hours() {
        #expect(FollowerSession.chunkSince(newestStored: nil, now: now) == now - 13 * 3600)
    }

    @Test func aRecentStoreStillAsksForAtLeast13Hours() {
        #expect(FollowerSession.chunkSince(newestStored: now - 600, now: now) == now - 13 * 3600)
    }

    @Test func anOlderStoreStartsAnHourBeforeItsNewestReading() {
        let newest = now - 2 * 86_400
        #expect(FollowerSession.chunkSince(newestStored: newest, now: now) == newest - 3600)
    }

    @Test func neverBeyondTheChunksSevenDayExpiry() {
        #expect(FollowerSession.chunkSince(newestStored: now - 30 * 86_400, now: now) == now - 7 * 86_400)
    }
}

/// BLE framing and the per-connection protocol against the OGS `ble` vectors and a fake peripheral.
struct NearbyTests {
    let v = OgsVectors.shared
    let crypto = NostrSDKCrypto()
    var ble: [String: Any] { v.raw["ble"] as! [String: Any] }

    @Test(arguments: ["23", "185", "517"])
    func framesMatchTheVectors(_ mtu: String) throws {
        let want = (ble["frames_by_att_mtu"] as! [String: [String]])[mtu]!
        let event = try #require(SignedEvent.decode(json: JSONText.encode((ble["message"] as! [Any])[1])))
        // The wearer's exact bytes (its key order) = the vector frames' payloads joined.
        let payload = want.reduce(into: Data()) { $0.append(Data(Hex.decode($1)!.dropFirst(BleFraming.header))) }
        let frames = BleFraming.frames(msgId: UInt16(ble["msg_id"] as! Int), payload: payload, mtu: Int(mtu)!)
        #expect(frames.map { Hex.encode($0) } == want)

        var reassembler = BleReassembler()
        var whole: Data?
        for frame in frames { whole = reassembler.add(frame, now: Date()) ?? whole }
        #expect(whole.flatMap(BleMessage.parse) == .event(event))
    }

    @Test func infoProofAndOwner() throws {
        let m = ble["info_m"] as! [String: String]
        #expect(BleMessage.infoM(sid: m["sid"]!, nonce: m["nonce"]!) == m["m"])
        let info = try #require(
            BleMessage.parse(Data(JSONText.encode(ble["info"]!).utf8)).flatMap { msg -> BleInfo? in
                if case .info(let info) = msg { return info }
                return nil
            })
        #expect(info.owner(identity: nil, sid: m["sid"], nonces: [m["nonce"]!]) == .ours)
        #expect(info.owner(identity: nil, sid: m["sid"], nonces: ["00"]) == .other)
        #expect(info.owner(identity: nil, sid: nil, nonces: [m["nonce"]!]) == .unknown)

        guard case .info(let pairing)? = BleMessage.parse(Data(JSONText.encode(ble["info_pairing"]!).utf8)) else {
            Issue.record("info_pairing did not parse")
            return
        }
        #expect(pairing.owner(identity: v.pub("I"), sid: nil, nonces: []) == .ours)
        #expect(pairing.owner(identity: v.pub("F"), sid: nil, nonces: []) == .other)
        // B§6: once sid is known only `m` proves it (a copied pairing `i` never does)
        #expect(pairing.owner(identity: v.pub("I"), sid: m["sid"], nonces: [m["nonce"]!]) == .ours)
        #expect(pairing.owner(identity: v.pub("I"), sid: m["sid"], nonces: ["00"]) == .other)
        #expect(info.owner(identity: v.pub("I"), sid: nil, nonces: [m["nonce"]!]) == .unknown)
        let invite = try #require(pairing.invite)
        #expect(invite.identity == v.pub("I") && invite.token == "C_TX2IzrmRf7hg8cATyckQ")
        #expect(invite.profile?.name == "Alex")
    }

    @Test func helloProofVerifiesWithTheFollowerKey() throws {
        let h = ble["hello_id"] as! [String: Any]
        let sid = h["sid"] as! String, nonce = h["nonce"] as! String
        #expect(HelloProof.content(sid: sid, nonce: nonce) == h["content"] as? String)
        let sig = try HelloProof.sign(sid: sid, nonce: nonce, follower: v.secret("F"), crypto: crypto)
        let unsigned = try crypto.sign(
            kind: Wire.kindBleId, content: HelloProof.content(sid: sid, nonce: nonce), tags: [], createdAt: 0,
            with: v.secret("F"))
        var event = unsigned
        event.sig = sig
        #expect(crypto.verify(event))
        // The vector's own signature verifies too (same event id, BIP-340 over it).
        let vectorSig = ((h["hello"] as! [Any])[1] as! [String: Any])["id"] as! String
        event.sig = vectorSig
        #expect(crypto.verify(event))
    }

    @Test func linkSaysHelloWritesOneFrameAtATimeAndWaitsForTheProof() throws {
        let m = ble["info_m"] as! [String: String]
        var link = NearbyLink(
            identity: .init(identity: v.pub("I"), sid: m["sid"], follower: v.secret("F")), crypto: crypto)
        let event = try #require(SignedEvent.decode(json: JSONText.encode((ble["message"] as! [Any])[1])))
        #expect(link.send(event, token: 1) == [.sent(token: 1, ok: false)])  // not connected

        let hello = link.connected(mtu: 23, nonce: m["nonce"]!)
        guard case .write(let first)? = hello.first, hello.count == 1 else {
            Issue.record("expected one HELLO frame write")
            return
        }
        #expect(first.count == BleFraming.header + 14)
        #expect(link.send(event, token: 2) == [.sent(token: 2, ok: false)])  // phone not proved yet

        // Drain the HELLO, one frame per write response.
        var effects = link.wrote(ok: true)
        while case .write = effects.first { effects = link.wrote(ok: true) }
        #expect(effects.isEmpty)

        // The phone answers INFO proving our sid over our nonce → verified; EVENT goes out, completion reported.
        let info = BleMessage.info(BleInfo(nonce: m["nonce"], m: m["m"], identity: nil, relays: [], pairing: nil))
        var got: [NearbyLink.Effect] = []
        for frame in BleFraming.frames(msgId: 9, payload: info.encoded(), mtu: 185) {
            got += link.received(frame, now: Date())
        }
        guard case .info(_, .ours)? = got.last else {
            Issue.record("INFO not reported as ours: \(got)")
            return
        }
        #expect(link.verified)
        var out = link.send(event, token: 3)
        var writes = 0
        while case .write = out.first {
            writes += 1
            out = link.wrote(ok: true)
        }
        #expect(writes > 1 && out == [.sent(token: 3, ok: true)])

        // A queued message fails when the link drops.
        _ = link.send(event, token: 4)
        #expect(link.disconnected() == [.sent(token: 4, ok: false)])
    }

    @Test func reassemblerDropsOversizedAndIdleMessages() {
        var r = BleReassembler()
        let start = Date()
        // Idle: frame 0 of 2, then nothing for 31 s, then frame 1 → no message.
        let frames = BleFraming.frames(msgId: 1, payload: Data(repeating: 65, count: 40), mtu: 29)
        #expect(r.add(frames[0], now: start) == nil)
        #expect(r.add(frames[1], now: start.addingTimeInterval(31)) == nil)
        // Oversized: count would carry > 256 KiB.
        var big = BleReassembler()
        let huge = BleFraming.frames(msgId: 2, payload: Data(repeating: 66, count: 300 * 1024), mtu: 517)
        var result: Data?
        for frame in huge { result = big.add(frame, now: start) ?? result }
        #expect(result == nil && big.dropped == 1)
        #expect(r.add(Data([0, 1]), now: start) == nil)  // shorter than the header
    }
}

/// contracts/protocol/ble/default-mtu.json: this Mac and an Android wearer at ATT MTU 23 (no MTU exchange). Every
/// write fits one ATT Write Request (never a long write), and the wearer's INFO frames prove our wearer. Android
/// `NearbyDefaultMtuTest` replays the wearer side of the same file.
struct NearbyDefaultMtuTests {
    let fx = loadJSON("protocol/ble/default-mtu.json")
    let crypto = NostrSDKCrypto()
    var mtu: Int { fx["att_mtu"] as! Int }
    func frames(_ key: String) -> [Data] { (fx[key] as! [String]).map { Data(Hex.decode($0)!) } }

    @Test func coreBluetoothsWriteLengthGivesTheAttMtu() {
        #expect(NearbyCentral.attMtu(writeWithoutResponse: 20) == 23)
        #expect(NearbyCentral.attMtu(writeWithoutResponse: 182) == 185)
        #expect(NearbyCentral.attMtu(writeWithoutResponse: 1000) == 515)
    }

    @Test func theFixtureFramesAreTheVectorHelloAndFitOneWrite() {
        var r = BleReassembler()
        var whole: Data?
        for frame in frames("hello_frames") {
            #expect(frame.count <= mtu - 3)
            whole = r.add(frame, now: Date()) ?? whole
        }
        let body = (fx["hello"] as! [Any])[1] as! [String: Any]
        #expect(
            whole.flatMap(BleMessage.parse) == .hello(nonce: body["nonce"] as! String, id: body["id"] as? String))
    }

    @Test func thisMacWritesOnlyWholeFramesAndTrustsTheWearersInfo() throws {
        let v = OgsVectors.shared
        let sid = fx["sid"] as! String
        let nonce = fx["nonce"] as! String
        #expect(try crypto.publicKey(of: v.secret("F")) == fx["follower"] as? String)
        var link = NearbyLink(identity: .init(identity: v.pub("I"), sid: sid, follower: v.secret("F")), crypto: crypto)
        var writes: [Data] = []
        var effects = link.connected(mtu: mtu, nonce: nonce)
        while case .write(let frame)? = effects.first {
            writes.append(frame)
            effects = link.wrote(ok: true)
        }
        #expect(writes.count == frames("hello_frames").count)
        #expect(writes.allSatisfy { $0.count <= mtu - 3 })
        var r = BleReassembler()
        var whole: Data?
        for frame in writes { whole = r.add(frame, now: Date()) ?? whole }
        guard case .hello(let sent, let id?)? = whole.flatMap(BleMessage.parse) else {
            Issue.record("no HELLO with proof")
            return
        }
        #expect(sent == nonce)
        var proof = try crypto.sign(
            kind: Wire.kindBleId, content: HelloProof.content(sid: sid, nonce: nonce), tags: [], createdAt: 0,
            with: v.secret("F"))
        proof.sig = id
        #expect(crypto.verify(proof))

        var got: [NearbyLink.Effect] = []
        for frame in frames("info_frames") { got += link.received(frame, now: Date()) }
        guard case .info(_, .ours)? = got.last else {
            Issue.record("INFO not ours: \(got)")
            return
        }
        #expect(link.verified)
    }
}
