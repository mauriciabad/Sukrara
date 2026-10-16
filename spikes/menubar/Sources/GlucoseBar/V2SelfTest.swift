// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation
import NostrSDK

/// `Sukrara -v2selftest`: feeds hand-built genuine and forged v2 events through `FollowerV2.onEvent` (the path
/// every transport uses) and checks the acceptance rules fail closed. No network; uses a throwaway Keychain item.
enum V2SelfTest {
    @MainActor static func run() -> Bool {
        UserDefaults.standard.set("app.mauri.sukrara.poc.selftest", forKey: "keychainService")
        defer { FollowerV2State.clear() }
        let I = Keys.generate(), D = Keys.generate(), G = Keys.generate(), X = Keys.generate(), G2 = Keys.generate()
        let F = Keys.generate()
        let sid = V2.hex((0..<32).map { _ in UInt8.random(in: 0...255) })
        let gk = V2.gk(G.publicKey().toHex()), now = Int(Date().timeIntervalSince1970)
        let st = FollowerV2State(f: try! F.secretKey().toBech32(), i: I.publicKey().toHex(), relays: [], token: "tok",
                                 name: "selftest", code: V2.matchCode(F.publicKey().toHex(), "tok"))
        guard let node = FollowerV2(state: st) else { return false }
        var readings: [Double] = []
        node.onReading = { r, _ in readings.append(r.mgdl) }
        node.onHistory = { pts, _ in readings += pts.map(\.mgdl) }

        func event(_ k: Keys, kind: UInt16, encTo g: Keys, _ body: [String: Any], tags: [[String]]) -> String {
            let ct = try! nip44Encrypt(secretKey: k.secretKey(), publicKey: g.publicKey(), content: V2.json(body), version: .v2)
            return try! EventBuilder(kind: Kind(kind: kind), content: ct).tags(tags: tags.map { try! Tag.parse(data: $0) })
                .finalize(signer: k).asJson()
        }
        func key(sealedBy s: Keys, g: Keys) -> String {
            V2.gift(s, F.publicKey().toHex(), kind: V2.toFollower,
                    ["v": 2, "type": "key", "sid": sid, "epoch": 1, "g": try! g.secretKey().toBech32(),
                     "devices": [D.publicKey().toHex()], "relays": []])!
        }
        func live(_ by: Keys, _ sgv: Int, gk g: String = gk) -> String {
            event(by, kind: V2.live, encTo: G, ["v": 2, "t": now, "sgv": sgv, "r": 0.1, "dev": "test"], tags: [["gk", g]])
        }
        var ok = true
        func check(_ name: String, _ cond: Bool) { print("\(cond ? "PASS" : "FAIL")  \(name)"); ok = ok && cond }

        node.onEvent(key(sealedBy: X, g: G2), via: "test")
        check("forged key delivery (sealed by a non-identity key) rejected", node.phase == .waiting(code: st.code))
        node.onEvent(live(D, 100), via: "test")
        check("data before any key ignored", readings.isEmpty)
        node.onEvent(key(sealedBy: I, g: G), via: "test")
        check("key delivery sealed by I accepted", node.phase == .active(epoch: 1))
        node.onEvent(live(D, 111), via: "test")
        check("live from roster device accepted", readings == [111])
        node.onEvent(live(X, 222), via: "test")
        check("live from non-roster key (knows G) rejected", readings == [111])
        var o = try! JSONSerialization.jsonObject(with: Data(live(D, 123).utf8)) as! [String: Any]
        o["content"] = (try! nip44Encrypt(secretKey: D.secretKey(), publicKey: G.publicKey(),
                                          content: V2.json(["v": 2, "t": now, "sgv": 333]), version: .v2))
        node.onEvent(V2.json(o), via: "test")
        check("tampered content (bad id/signature) rejected", readings == [111])
        node.onEvent(live(D, 444, gk: "deadbeef"), via: "test")
        check("unknown gk not accepted (rekey requested)", readings == [111])
        let fakeRoster = event(X, kind: V2.chunk, encTo: G, ["v": 2, "epoch": 1, "devices": [X.publicKey().toHex()], "writers": []],
                               tags: [["d", V2.rosterD(sid)], ["gk", gk]])
        node.onEvent(fakeRoster, via: "test")
        node.onEvent(live(X, 555), via: "test")
        check("roster not signed by I ignored (attacker still not accepted)", readings == [111])
        let roster = event(I, kind: V2.chunk, encTo: G, ["v": 2, "epoch": 1, "devices": [D.publicKey().toHex(), X.publicKey().toHex()],
                                                         "writers": []], tags: [["d", V2.rosterD(sid)], ["gk", gk]])
        node.onEvent(roster, via: "test")
        node.onEvent(live(X, 666), via: "test")
        check("device added by an identity-signed roster accepted", readings == [111, 666])
        let chunk = event(D, kind: V2.chunk, encTo: G, ["v": 2, "h": now - now % 3600, "dev": "t", "r": [[0, 90, 12], [60, 91, nil as Any? ?? NSNull()]]],
                          tags: [["d", "x"], ["c", V2.chunkC(sid)], ["gk", gk]])
        node.onEvent(chunk, via: "test")
        check("hour chunk decoded (2 rows)", readings.suffix(2) == [90, 91])
        node.onEvent(V2.gift(X, F.publicKey().toHex(), kind: V2.toFollower, ["v": 2, "type": "revoked"])!, via: "test")
        check("forged revoked notice ignored", node.phase == .active(epoch: 1))
        node.onEvent(V2.gift(I, F.publicKey().toHex(), kind: V2.toFollower, ["v": 2, "type": "revoked"])!, via: "test")
        check("revoked notice from I → access removed", node.phase == .revoked)
        check("match code is 4 digits", node.code.count == 4 && Int(node.code) != nil)
        node.stop()
        print(ok ? "v2 self-test OK" : "v2 self-test FAILED")
        return ok
    }
}
