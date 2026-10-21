// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation
import NostrSDK

/// `Sukrara -v2selftest`: feeds hand-built genuine and forged v2 events through `FollowerV2.onEvent` (the path
/// every transport uses) and checks the acceptance rules fail closed. No network; uses a throwaway Keychain item.
enum V2SelfTest {
    @MainActor static func run() -> Bool {
        // in memory only: persisting it would point the user's daily Sukrara.app (same defaults domain) at this test item
        UserDefaults.standard.setVolatileDomain(["keychainService": "app.mauri.sukrara.poc.selftest"], forName: UserDefaults.argumentDomain)
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
                     "devices": [D.publicKey().toHex()], "relays": []], ttl: 30 * 86400)!
        }
        func live(_ by: Keys, _ sgv: Int, gk g: String = gk) -> String {
            event(by, kind: V2.live, encTo: G, ["v": 2, "t": now, "sgv": sgv, "r": 0.1, "dev": "test"], tags: [["gk", g]])
        }
        var ok = true
        func check(_ name: String, _ cond: Bool) { print("\(cond ? "PASS" : "FAIL")  \(name)"); ok = ok && cond }

        node.onEvent(key(sealedBy: X, g: G2), via: "test")
        // a link request waits without a code (0.11.6: only nearby pairing shows one); still waiting = not accepted
        check("forged key delivery (sealed by a non-identity key) rejected", node.phase == .waiting(code: nil))
        node.onEvent(live(D, 100), via: "test")
        check("data before any key ignored", readings.isEmpty)
        node.onEvent(key(sealedBy: I, g: G), via: "test")
        check("key delivery sealed by I accepted", node.phase == .active(epoch: 1))
        node.onEvent(live(D, 111), via: "test")
        check("live from roster device accepted", readings == [111])
        var sensor: SensorInfo?
        node.onSensor = { sensor = $0 }
        node.onEvent(event(D, kind: V2.live, encTo: G, ["v": 2, "ss": now - 600, "se": now + 1_295_400, "sw": now + 3000], tags: [["gk", gk]]), via: "test")
        check("reading-less live event: the sensor only, no reading (PROTOCOL-v2 Sensor life)",
              readings == [111] && sensor == SensorInfo(start: TimeInterval(now - 600), end: TimeInterval(now + 1_295_400), warmupEnd: TimeInterval(now + 3000)))
        node.onEvent(live(X, 222), via: "test")
        check("live from non-roster key (knows G) rejected", readings == [111])
        var o = try! JSONSerialization.jsonObject(with: Data(live(D, 123).utf8)) as! [String: Any]
        o["content"] = (try! nip44Encrypt(secretKey: D.secretKey(), publicKey: G.publicKey(),
                                          content: V2.json(["v": 2, "t": now, "sgv": 333]), version: .v2))
        node.onEvent(V2.json(o), via: "test")
        check("tampered content (bad id/signature) rejected", readings == [111])
        var sentOut: [String] = []
        node.extraPublish = { ev, _ in sentOut.append(ev) }
        node.onEvent(live(X, 444, gk: "0badbeef"), via: "test")
        check("unknown gk from a stranger: no rekey request (P10)", sentOut.isEmpty)
        node.onEvent(live(D, 444, gk: "deadbeef"), via: "test")
        check("unknown gk not accepted (rekey requested)", readings == [111] && sentOut.count == 1)
        if let w = sentOut.first, let o = try? JSONSerialization.jsonObject(with: Data(w.utf8)) as? [String: Any],
           let exp = ((o["tags"] as? [[String]]) ?? []).first(where: { $0.first == "expiration" }).flatMap({ Int($0[1]) }) {
            check("rekey wrap expires in an absolute now + 7 d (P5)", abs(exp - (now + V2.ttlRequest)) < 60)
        } else { check("rekey wrap carries an expiration tag (P5)", false) }
        node.extraPublish = nil
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
        let hour = now - now % 3600
        func chunk(d: String, c: String, sgv: Int) -> String {
            event(D, kind: V2.chunk, encTo: G, ["v": 2, "h": hour, "dev": "t", "r": [[0, sgv, 12], [60, sgv + 1, NSNull()]]],
                  tags: [["d", d], ["c", c], ["gk", gk]])
        }
        node.onEvent(chunk(d: V2.chunkD(sid, hour - 3600), c: V2.chunkC(sid), sgv: 70), via: "test")
        check("chunk whose d isn't its hour's rejected (P10)", readings.count == 2)
        node.onEvent(chunk(d: V2.chunkD(sid, hour), c: "x", sgv: 75), via: "test")
        check("30078 from a device without the chunk c tag rejected (P10)", readings.count == 2)
        node.onEvent(chunk(d: V2.chunkD(sid, hour), c: V2.chunkC(sid), sgv: 90), via: "test")
        check("hour chunk decoded (2 rows)", readings.suffix(2) == [90, 91])
        node.onEvent(V2.gift(I, F.publicKey().toHex(), kind: V2.toFollower,
                             ["v": 2, "type": "key", "sid": String(repeating: "ab", count: 32), "epoch": 2,
                              "g": try! G2.secretKey().toBech32(), "devices": [X.publicKey().toHex()], "relays": []], ttl: 30 * 86400)!, via: "test")
        check("key delivery naming another sid rejected, devices kept (P6)",
              node.phase == .active(epoch: 1) && node.st.sid == sid && node.st.devices.contains(D.publicKey().toHex()))
        treatments(node, sid: sid, I: I, D: D, G: G, X: X, F: F, check: check)
        // Alert events (alerts SPEC §4): kind 21079, same checks as live, deduped by id, ≤ 10 min old.
        var alerts: [String] = []
        node.onAlert = { body, _ in alerts.append("\(body["k"] ?? "?"):\(body["state"] ?? "?")") }
        func alert(_ by: Keys, _ k: String, at: Int = now, type: String = "alert") -> String {
            event(by, kind: V2.alert, encTo: G, ["v": 2, "type": type, "id": "0123456789abcdef", "k": k, "state": "fire", "at": at,
                                                 "t": at, "sgv": 192, "r": 0.4, "th": 185, "n": 1], tags: [["gk", gk]])
        }
        let genuine = alert(D, "high")
        node.onEvent(genuine, via: "relay:test")
        node.onEvent(genuine, via: "ble:test")
        check("alert from a roster device accepted once (relay + BLE copy deduped)", alerts == ["high:fire"])
        node.onEvent(alert(X, "low"), via: "test")
        check("alert from a non-roster key rejected", alerts == ["high:fire"])
        node.onEvent(alert(D, "very_high", at: now - 11 * 60), via: "test")
        check("alert older than 10 min ignored", alerts == ["high:fire"])
        node.onEvent(alert(D, "low", type: "live"), via: "test")
        check("kind 21079 whose type isn't \"alert\" rejected", alerts == ["high:fire"])
        node.onEvent(V2.gift(X, F.publicKey().toHex(), kind: V2.toFollower, ["v": 2, "type": "revoked"], ttl: 7 * 86400)!, via: "test")
        check("forged revoked notice ignored", node.phase == .active(epoch: 1))
        node.onEvent(V2.gift(I, F.publicKey().toHex(), kind: V2.toFollower, ["v": 2, "type": "revoked"], ttl: 7 * 86400)!, via: "test")
        check("revoked notice from I → access removed", node.phase == .revoked)
        check("match code is 4 digits", node.code.count == 4 && Int(node.code) != nil)
        node.stop()

        // 0.11.6 single-use invites: a fresh request; only the identity may end it ("invite-used" / "declined")
        let F2 = Keys.generate()
        let st2 = FollowerV2State(f: try! F2.secretKey().toBech32(), i: I.publicKey().toHex(), relays: [], token: "tok2",
                                  name: "selftest2", code: V2.matchCode(F2.publicKey().toHex(), "tok2"))
        if let n2 = FollowerV2(state: st2) {
            n2.onEvent(V2.gift(X, F2.publicKey().toHex(), kind: V2.toFollower, ["v": 2, "type": "invite-used"], ttl: 7 * 86400)!, via: "test")
            check("forged invite-used ignored", n2.phase == .waiting(code: nil))
            n2.onEvent(V2.gift(I, F2.publicKey().toHex(), kind: V2.toFollower, ["v": 2, "type": "invite-used"], ttl: 7 * 86400)!, via: "test")
            check("invite-used from I → request ended (already used)", n2.phase == .ended(used: true))
            n2.onEvent(V2.gift(I, F2.publicKey().toHex(), kind: V2.toFollower, ["v": 2, "type": "declined"], ttl: 7 * 86400)!, via: "test")
            check("a later notice doesn't change an ended request", n2.phase == .ended(used: true))
            n2.stop()
        } else { check("second follower node", false) }
        // PROTOCOL-v2 "Missing history": whole hours of the last 12 without a reading, only while the stream is live
        let h0 = now - now % 3600
        let full = (0..<(14 * 60)).map { TimeInterval(now - $0 * 60) }
        check("no missing hours when every hour has readings", FollowerV2.missingHours(stored: full, now: now) == [])
        let holed = full.filter { !(h0 - 5 * 3600 ..< h0 - 3 * 3600).contains(Int($0)) }
        check("two empty hours found",
              FollowerV2.missingHours(stored: holed, now: now).map { Set($0) } == [h0 - 5 * 3600, h0 - 4 * 3600])
        check("stale stream (newest > 15 min) → no request", FollowerV2.missingHours(stored: full.map { $0 - 1200 }, now: now) == nil)
        check("chunk since: ≥ 13 h back", FollowerV2.chunkSince(newestStored: now - 60, now: now) == now - 13 * 3600)
        check("chunk since: from an hour before the newest stored", FollowerV2.chunkSince(newestStored: now - 86400, now: now) == now - 90000)
        check("chunk since: capped at the 7-day expiry", FollowerV2.chunkSince(newestStored: now - 30 * 86400, now: now) == now - 7 * 86400)
        check("chunk since: nothing stored → 13 h", FollowerV2.chunkSince(newestStored: nil, now: now) == now - 13 * 3600)
        // Settings › Following › Edit…: a profile update wrapped to I (sealed by F), stored for the next request
        var sentWraps: [String] = []
        node.extraPublish = { ev, _ in sentWraps.append(ev) }
        let pic: [String: Any] = ["v": 1, "c": 3, "s": "rings", "a": 90, "n": 5]
        node.setProfile(name: "  Ana's Mac  ", avatar: pic)
        let opened = sentWraps.first.flatMap { try? Event.fromJson(json: $0) }.flatMap { try? UnwrappedGift.fromGiftWrap(signer: I, giftWrap: $0) }
        let body = opened.flatMap { try? JSONSerialization.jsonObject(with: Data($0.rumor().content().utf8)) as? [String: Any] }
        check("profile update: one wrap to I, sealed by F, rumor 30500 {v 2, type profile, trimmed name, picture}",
              sentWraps.count == 1 && opened?.sender().toHex() == F.publicKey().toHex() && opened?.rumor().kind().asU16() == V2.toOwner
              && V2.json(body ?? [:]) == V2.json(V2.profileUpdate(name: "Ana's Mac", avatar: pic)))
        check("profile update: kept for this Mac (name, picture)", node.st.name == "Ana's Mac" && V2.json(node.myAvatar ?? [:]) == V2.json(pic))
        node.setProfile(name: " ", avatar: nil)
        check("profile update: a blank name keeps the old one, no picture = the initial", node.st.name == "Ana's Mac" && node.myAvatar == nil)
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
        node.treatmentCreator = { store.creator($0) }
        node.treatmentStoredO = { store.row($0)?.o }
        node.onTreatment = { t, fromWearer, via, creator, force in
            got.append((t, fromWearer)); store.receiveV2(t, fromWearer: fromWearer, via: via, creator: creator, force: force)
        }
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
        store.upsertLocal(mine, creator: node.followerPub)
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
        var echo = mine; echo.o = "follower"            // the owner stores a writer's entry as o "follower" (P4)
        var older = echo; older.u -= 1
        node.onEvent(tr(D, older), via: "test")
        check("wearer copy with u < ours is not acceptance", store.v2Status(mine.id)?.state != .accepted)
        store.setV2(mine.id, u: mine.u, .sent, via: "relay:test")
        check("relay OK → sent; shown 'v2 sent'", TreatmentPaths.v2Text(store.v2Status(mine.id)!) == "v2 sent")
        check("…after 2 min without echo: 'v2 not accepted yet'",
              TreatmentPaths.v2Text(store.v2Status(mine.id)!, now: Date().addingTimeInterval(121)) == "v2 not accepted yet")
        node.onEvent(tr(D, echo), via: "test")
        check("wearer's device key echoes the same id with u ≥ ours → accepted", store.v2Status(mine.id)?.state == .accepted)
        store.setV2(mine.id, u: mine.u, .sent)
        check("status never moves back (accepted stays)", store.v2Status(mine.id)?.state == .accepted)

        // P4: a writer changes only the entries it created
        let gotBefore = got.count
        var hijack = wearerCarbs; hijack.del = true; hijack.u += 60_000; hijack.o = "follower"
        node.onEvent(tr(W, hijack), via: "test")
        check("writer tombstoning the wearer's entry rejected (P4)", got.count == gotBefore && store.row(wearerCarbs.id)?.del == false)
        var noteEdit = writerNote; noteEdit.n = "long walk"; noteEdit.u += 5
        node.onEvent(tr(W, noteEdit), via: "test")
        check("writer editing its own entry accepted (P4)", store.row(writerNote.id)?.n == "long walk")
        let W2 = Keys.generate()
        node.onEvent(roster(writers: [W, W2, F]), via: "test")
        var steal = writerNote; steal.n = "stolen"; steal.u += 50_000
        node.onEvent(tr(W2, steal), via: "test")
        check("writer editing another writer's entry rejected (P4)", store.row(writerNote.id)?.n == "long walk")
        check("only this Mac's own entries are deletable here (P4)",
              TreatmentPaths.mayChange(mine, store: store, node: node) && !TreatmentPaths.mayChange(wearerCarbs, store: store, node: node))
        let echoFirst = Treatment(k: "carbs", a: 12, t: now - 200, o: "follower")
        node.onEvent(tr(D, echoFirst), via: "test")                 // the wearer's copy of W's entry arrives first
        let rejectedBefore = node.rejected
        node.onEvent(tr(W, echoFirst), via: "test")                 // …then W's own copy: redundant, not a rejection
        check("a writer's own copy after the wearer's echo is ignored, not rejected (P4)", node.rejected == rejectedBefore && store.row(echoFirst.id)?.a == 12)
        // a writer that saw the wearer's id first forges it (huge u) before the real entry reaches this Mac
        let late = Treatment(k: "rapid", a: 4, t: now - 120, o: "phone")
        var forged = late; forged.a = 40; forged.u += 9_000_000; forged.o = "follower"
        node.onEvent(tr(W2, forged), via: "test")
        check("…the forged copy is taken first (unknown id)", store.row(late.id)?.a == 40)
        node.onEvent(tr(D, late), via: "test")
        check("the wearer's own entry replaces the forged copy whatever its u (P4)", store.row(late.id)?.a == 4 && store.creator(late.id) == D.publicKey().toHex())
        var forged2 = forged; forged2.u += 1
        node.onEvent(tr(W2, forged2), via: "test")
        check("…and the forger can't change it again", store.row(late.id)?.a == 4)

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
        // in memory only: persisting it would point the user's daily Sukrara.app (same defaults domain) at this test item
        UserDefaults.standard.setVolatileDomain(["keychainService": "app.mauri.sukrara.poc.relaytest"], forName: UserDefaults.argumentDomain)
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
        node.treatmentCreator = { store.creator($0) }
        node.treatmentStoredO = { store.row($0)?.o }
        node.onTreatment = { t, w, via, creator, force in
            if w { fromWearer.append(t.id) }
            if store.receiveV2(t, fromWearer: w, via: via, creator: creator, force: force) { print("  accepted via \(via)") }
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
        store.upsertLocal(mine, creator: node.followerPub)
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

/// `Sukrara -bleselftest [-ogsVectors <file>]`: the Bluetooth limits (P13) and the blinded INFO / owner check (P12),
/// against spec/test-vectors/ogs-draft1.json (the same cases as Android `BleTest`). No radio, no Keychain.
enum BLESelfTest {
    static func run() -> Bool {
        var ok = true
        func check(_ name: String, _ c: Bool) { print("\(c ? "PASS" : "FAIL")  \(name)"); ok = ok && c }
        let path = UserDefaults.standard.string(forKey: "ogsVectors") ?? "../../spec/test-vectors/ogs-draft1.json"
        guard let data = FileManager.default.contents(atPath: path),
              let vec = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let ble = vec["ble"] as? [String: Any], let im = ble["info_m"] as? [String: String],
              let byMtu = ble["frames_by_att_mtu"] as? [String: [String]],
              let iPub = ((vec["keys"] as? [String: Any])?["I"] as? [String: String])?["pub"] else {
            print("ble self-test FAILED: vectors not found at \(path) (run from spikes/menubar or pass -ogsVectors)")
            return false
        }
        func unhex(_ s: String) -> Data {
            Data(stride(from: 0, to: s.count, by: 2).map { i in UInt8(s.dropFirst(i).prefix(2), radix: 16)! })
        }
        func frame(_ id: Int, _ idx: Int, _ count: Int, _ payload: Data = Data()) -> Data {
            Data([UInt8(id >> 8), UInt8(id & 0xff), UInt8(idx >> 8), UInt8(idx & 0xff), UInt8(count >> 8), UInt8(count & 0xff)]) + payload
        }
        // framing: the vector frames reassemble at every MTU
        for (mtu, frames) in byMtu.sorted(by: { $0.key < $1.key }) {
            var r = BLEV2Central.Reassembler()
            let out = frames.map { r.add(unhex($0)) }
            check("vector frames reassemble at MTU \(mtu)", out.dropLast().allSatisfy { $0 == nil } && (out.last??.first as? String) == "EVENT")
        }
        // P13 limits
        var r = BLEV2Central.Reassembler()
        let t0 = Date()
        let p = Data(#"["HELLO",{"v":2}]"#.utf8)
        _ = r.add(frame(1, 0, 2, p.prefix(5)), now: t0)
        check("a buffer idle > 30 s is dropped", r.add(frame(1, 1, 2, p.dropFirst(5)), now: t0 + 31) == nil && r.dropped == 1)
        r = BLEV2Central.Reassembler()
        let chunk = Data(repeating: UInt8(ascii: "x"), count: 500), n = BLEV2Central.Reassembler.maxMessage / 500 + 2
        let big = (0..<n).map { r.add(frame(9, $0, n, chunk)) }
        check("a message over 256 KiB is dropped", big.allSatisfy { $0 == nil } && r.dropped == 1)
        r = BLEV2Central.Reassembler()
        for id in 0..<(BLEV2Central.Reassembler.maxBuffers + 4) { _ = r.add(frame(id, 0, 2, Data("[".utf8))) }
        check("at most 16 open buffers (the oldest goes)",
              r.add(frame(0, 1, 2, Data(#""HELLO",{}]"#.utf8))) == nil
              && (r.add(frame(BLEV2Central.Reassembler.maxBuffers + 3, 1, 2, Data(#""HELLO",{}]"#.utf8)))?.first as? String) == "HELLO")
        check("malformed frames ignored", r.add(Data([0, 1, 0])) == nil && r.add(frame(1, 2, 2)) == nil && r.add(frame(1, 0, 0)) == nil)
        // P12 blinded INFO
        let sid = im["sid"]!, nonce = im["nonce"]!
        check("m = H(cgm2:ble:sid:nonce) matches the vectors", BLEV2Central.infoM(sid: sid, nonce: nonce) == im["m"])
        let info = BLEV2Central.Info(i: nil, relays: [], token: nil, expiry: nil, profile: nil, nonce: nonce, m: im["m"])
        check("owner proves our sid over our nonce → ours", BLEV2Central.owner(info, i: iPub, sid: sid, nonces: [nonce]) == .ours)
        check("a replayed INFO (not our nonce) → other", BLEV2Central.owner(info, i: iPub, sid: sid, nonces: [String(repeating: "0", count: 32)]) == .other)
        check("another stream's phone → other", BLEV2Central.owner(info, i: iPub, sid: String(repeating: "e", count: 64), nonces: [nonce]) == .other)
        check("request pending, phone not pairing → unknown (receive only)", BLEV2Central.owner(info, i: iPub, sid: nil, nonces: []) == .unknown)
        let pairing = BLEV2Central.Info(i: iPub, relays: [], token: "t", expiry: 1, profile: nil, nonce: nonce, m: im["m"])
        check("pairing INFO naming our I → ours", BLEV2Central.owner(pairing, i: iPub, sid: nil, nonces: []) == .ours)
        check("pairing INFO naming another I → other", BLEV2Central.owner(pairing, i: String(repeating: "c", count: 64), sid: sid, nonces: [nonce]) == .other)
        print(ok ? "ble self-test OK" : "ble self-test FAILED")
        return ok
    }
}
