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
        treatments(node, sid: sid, I: I, D: D, G: G, X: X, F: F, check: check)
        node.onEvent(V2.gift(X, F.publicKey().toHex(), kind: V2.toFollower, ["v": 2, "type": "revoked"])!, via: "test")
        check("forged revoked notice ignored", node.phase == .active(epoch: 1))
        node.onEvent(V2.gift(I, F.publicKey().toHex(), kind: V2.toFollower, ["v": 2, "type": "revoked"])!, via: "test")
        check("revoked notice from I → access removed", node.phase == .revoked)
        check("match code is 4 digits", node.code.count == 4 && Int(node.code) != nil)
        node.stop()
        print(ok ? "v2 self-test OK" : "v2 self-test FAILED")
        return ok
    }

    /// Treatments over v2 (SPEC §5, PROTOCOL-v2 "Treatments"): writer gating, the follower's acceptance rules, the §1
    /// merge into the one store, and sending → sent → accepted by the wearer's echo. Store in a temp directory.
    @MainActor static func treatments(_ node: FollowerV2, sid: String, I: Keys, D: Keys, G: Keys, X: Keys, F: Keys,
                                      check: (String, Bool) -> Void) {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("sukrara-selftest-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: dir) }
        let store = TreatmentStore(directory: dir)
        var got: [(Treatment, Bool)] = []
        node.onTreatment = { t, fromWearer, via in got.append((t, fromWearer)); store.receiveV2(t, fromWearer: fromWearer, via: via) }
        let gk = V2.gk(G.publicKey().toHex()), W = Keys.generate()
        func roster(writers: [Keys]) -> String {
            let ct = try! nip44Encrypt(secretKey: I.secretKey(), publicKey: G.publicKey(), content: V2.json(
                ["v": 2, "epoch": 1, "devices": [D.publicKey().toHex()], "writers": writers.map { $0.publicKey().toHex() }]), version: .v2)
            return try! EventBuilder(kind: Kind(kind: V2.chunk), content: ct)
                .tags(tags: [["d", V2.rosterD(sid)], ["gk", gk]].map { try! Tag.parse(data: $0) })
                .customCreatedAt(createdAt: Timestamp.fromSecs(secs: UInt64(Date().timeIntervalSince1970) + UInt64(got.count + 10)))
                .finalize(signer: I).asJson()
        }
        func tr(_ by: Keys, _ t: Treatment, dFor: String? = nil, gk g: String? = nil) -> String {
            var body = t.dict
            body.removeValue(forKey: "lv"); body["v"] = 1
            let ct = try! nip44Encrypt(secretKey: by.secretKey(), publicKey: G.publicKey(), content: V2.json(body), version: .v2)
            return try! EventBuilder(kind: Kind(kind: V2.chunk), content: ct)
                .tags(tags: [["d", V2.treatmentD(sid, dFor ?? t.id)], ["c", V2.treatmentsC(sid)], ["gk", g ?? gk]].map { try! Tag.parse(data: $0) })
                .finalize(signer: by).asJson()
        }
        let now = Int64(Date().timeIntervalSince1970)

        // Writer gating
        node.onEvent(roster(writers: [W]), via: "test")
        let mine = Treatment(k: "rapid", a: 2.5, t: now, o: "mac")
        check("not a writer: canWrite false, nothing published", !node.canWrite && !node.publishTreatment(mine))
        node.onEvent(roster(writers: [W, F]), via: "test")
        check("roster lists this Mac in writers: canWrite true", node.canWrite)
        store.upsertLocal(mine)
        let sent = node.publishTreatment(mine)
        if sent { store.setV2(mine.id, u: mine.u, .sending) }
        check("writer: entry published (sending)", sent && store.v2Status(mine.id)?.state == .sending)

        // Follower acceptance rules
        let wearerCarbs = Treatment(k: "carbs", a: 30, t: now - 600, o: "phone")
        node.onEvent(tr(D, wearerCarbs), via: "test")
        check("treatment from the wearer's device key accepted (fromWearer)", got.last.map { $0.0.id == wearerCarbs.id && $0.1 } ?? false)
        check("…merged into the store", store.row(wearerCarbs.id)?.a == 30)
        let writerNote = Treatment(k: "note", a: 0, t: now - 300, n: "walk", o: "follower")
        node.onEvent(tr(W, writerNote), via: "test")
        check("treatment from a roster writer accepted (not fromWearer)", got.last.map { $0.0.id == writerNote.id && !$0.1 } ?? false)
        let n0 = got.count
        node.onEvent(tr(X, Treatment(k: "rapid", a: 9, t: now, o: "follower")), via: "test")
        check("treatment from a key outside writers ∪ devices rejected", got.count == n0)
        node.onEvent(tr(D, Treatment(k: "rapid", a: 9, t: now, o: "phone"), dFor: wearerCarbs.id), via: "test")
        check("treatment whose d tag names another id rejected", got.count == n0 && store.row(wearerCarbs.id)?.k == "carbs")
        node.onEvent(tr(D, Treatment(k: "rapid", a: 9, t: now, o: "phone"), gk: "0badc0de"), via: "test")
        check("treatment under an unknown gk not accepted", got.count == n0)
        var tampered = try! JSONSerialization.jsonObject(with: Data(tr(D, Treatment(k: "carbs", a: 5, t: now, o: "phone")).utf8)) as! [String: Any]
        tampered["content"] = try! nip44Encrypt(secretKey: D.secretKey(), publicKey: G.publicKey(),
                                                content: V2.json(["id": "x", "k": "carbs", "a": 500, "t": now, "u": 1, "o": "phone"]), version: .v2)
        node.onEvent(V2.json(tampered), via: "test")
        check("tampered treatment (bad id/signature) rejected", got.count == n0)

        // LWW (§1): an older version from a writer doesn't overwrite the wearer's newer edit
        var edited = wearerCarbs; edited.a = 45; edited.u += 5000
        node.onEvent(tr(D, edited), via: "test")
        var stale = wearerCarbs; stale.a = 20; stale.u += 1000
        node.onEvent(tr(W, stale), via: "test")
        check("§1 merge: newest u wins across authors (45 g kept)", store.row(wearerCarbs.id)?.a == 45)

        // Echo = acceptance: our own copy (author F, a writer) doesn't count, the wearer's device key does — only with u ≥ ours.
        node.onEvent(tr(F, mine), via: "test")
        check("own event echoed by a relay is not acceptance", store.v2Status(mine.id)?.state == .sending)
        var older = mine; older.u -= 1
        node.onEvent(tr(D, older), via: "test")
        check("wearer copy with u < ours is not acceptance", store.v2Status(mine.id)?.state != .accepted)
        store.setV2(mine.id, u: mine.u, .sent, via: "relay:test")
        check("relay OK → sent; shown 'v2 sent'", TreatmentPaths.v2Text(store.v2Status(mine.id)!) == "v2 sent")
        check("…after 2 min without echo: 'v2 not accepted yet'",
              TreatmentPaths.v2Text(store.v2Status(mine.id)!, now: Date().addingTimeInterval(121)) == "v2 not accepted yet")
        node.onEvent(tr(D, mine), via: "test")
        check("wearer's device key echoes the same id with u ≥ ours → accepted", store.v2Status(mine.id)?.state == .accepted)
        store.setV2(mine.id, u: mine.u, .sent)
        check("status never moves back (accepted stays)", store.v2Status(mine.id)?.state == .accepted)

        // Deletes travel as tombstones
        var gone = writerNote; gone.del = true; gone.u += 10
        node.onEvent(tr(D, gone), via: "test")
        check("tombstone from the wearer deletes the row", store.row(writerNote.id)?.del == true)

        // Writer removed from the roster → may not write any more
        node.onEvent(roster(writers: [W]), via: "test")
        check("roster without this Mac: canWrite false again", !node.canWrite)
        node.onTreatment = nil
    }
}

/// `Sukrara -v2relaytest`: treatments over **real public relays** with a throwaway wearer (fresh I, D, G, sid — never
/// the user's identity) and a throwaway follower key in a throwaway Keychain item: the follower's `#c` subscription
/// receives the wearer's treatment, its own writer entry gets a relay OK (→ sent), and the wearer's echo marks it
/// accepted. Events are encrypted to the throwaway G and expire after 1 h (ours: +30 d, unreadable to anyone).
enum V2RelayTest {
    @MainActor static func run() -> Bool {
        UserDefaults.standard.set("app.mauri.sukrara.poc.relaytest", forKey: "keychainService")
        defer { FollowerV2State.clear() }
        let relays = ["wss://nos.lol", "wss://relay.primal.net", "wss://nostr.mom"]
        let I = Keys.generate(), D = Keys.generate(), G = Keys.generate(), F = Keys.generate()
        let sid = V2.hex((0..<32).map { _ in UInt8.random(in: 0...255) })
        var st = FollowerV2State(f: try! F.secretKey().toBech32(), i: I.publicKey().toHex(), relays: relays, token: "t",
                                 name: "relaytest", code: "0000")
        st.sid = sid; st.status = "active"; st.epochs = [.init(n: 1, g: try! G.secretKey().toBech32())]
        st.devices = [D.publicKey().toHex()]; st.writers = [F.publicKey().toHex()]
        guard let node = FollowerV2(state: st) else { return false }
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("sukrara-relaytest-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: dir) }
        let store = TreatmentStore(directory: dir)
        var fromWearer: [String] = [], sentVia = ""
        node.onTreatment = { t, w, via in
            if w { fromWearer.append(t.id) }
            if store.receiveV2(t, fromWearer: w, via: via) { print("  accepted via \(via)") }
        }
        node.onTreatmentSent = { id, u, via in sentVia = via; store.setV2(id, u: u, .sent, via: via) }
        let wearer = RelayPool(urls: relays, authKeys: D)
        let gk = V2.gk(G.publicKey().toHex())
        func tr(_ t: Treatment) -> String {
            var body = t.dict
            body.removeValue(forKey: "lv"); body["v"] = 1
            let ct = try! nip44Encrypt(secretKey: D.secretKey(), publicKey: G.publicKey(), content: V2.json(body), version: .v2)
            return try! EventBuilder(kind: Kind(kind: V2.chunk), content: ct)
                .tags(tags: [["d", V2.treatmentD(sid, t.id)], ["c", V2.treatmentsC(sid)], ["gk", gk],
                             ["expiration", String(Int(Date().timeIntervalSince1970) + 3600)]].map { try! Tag.parse(data: $0) })
                .finalize(signer: D).asJson()
        }
        func wait(_ s: Double, until cond: () -> Bool) -> Bool {
            let end = Date().addingTimeInterval(s)
            while Date() < end { if cond() { return true }; RunLoop.main.run(until: Date().addingTimeInterval(0.2)) }
            return cond()
        }
        var ok = true
        func check(_ name: String, _ c: Bool) { print("\(c ? "PASS" : "FAIL")  \(name)"); ok = ok && c }

        node.start()
        wearer.restart("relaytest")
        _ = wait(4) { node.connectedRelays > 0 }
        let carbs = Treatment(k: "carbs", a: 30, t: Int64(Date().timeIntervalSince1970), o: "phone")
        wearer.publish(tr(carbs))
        check("wearer's treatment received over relays (#c + authors filter) and merged",
              wait(20) { fromWearer.contains(carbs.id) } && store.row(carbs.id)?.a == 30)
        let mine = Treatment(k: "rapid", a: 1.5, t: Int64(Date().timeIntervalSince1970), o: "mac")
        store.upsertLocal(mine)
        if node.publishTreatment(mine) { store.setV2(mine.id, u: mine.u, .sending) }
        let sent = wait(20) { store.v2Status(mine.id)?.state == .sent }
        check("own entry: relay OK → sent (\(sentVia))", sent)
        wearer.publish(tr(mine))                         // what Sukrara for Android does after merging it
        check("wearer's echo over relays → accepted", wait(20) { store.v2Status(mine.id)?.state == .accepted })
        node.stop(); wearer.stop()
        print(ok ? "v2 relay test OK" : "v2 relay test FAILED")
        return ok
    }
}
