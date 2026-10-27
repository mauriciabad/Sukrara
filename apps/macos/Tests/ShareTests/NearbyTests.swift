// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation
import SukraraShare
import Testing

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

        let hello = link.connected(perFrame: 14, nonce: m["nonce"]!)
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
        let frames = BleFraming.frames(msgId: 1, payload: Data(repeating: 65, count: 40), perFrame: 20)
        #expect(r.add(frames[0], now: start) == nil)
        #expect(r.add(frames[1], now: start.addingTimeInterval(31)) == nil)
        // Oversized: count would carry > 256 KiB.
        var big = BleReassembler()
        let huge = BleFraming.frames(msgId: 2, payload: Data(repeating: 66, count: 300 * 1024), perFrame: 506)
        var result: Data?
        for frame in huge { result = big.add(frame, now: start) ?? result }
        #expect(result == nil && big.dropped == 1)
        #expect(r.add(Data([0, 1]), now: start) == nil)  // shorter than the header
    }
}
