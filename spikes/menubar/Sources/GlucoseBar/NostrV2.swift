// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import CryptoKit
import Foundation
import NostrSDK

/// CGM-over-Nostr protocol v2 follower (spikes/nostr/PROTOCOL-v2.md), same rules as the Android "CGM Share"
/// follower and the Python reference (spikes/nostr/v2/nodes.py FollowerNode):
///   invite link (no secrets) → own follower key F → access request (NIP-59 gift wrap, seal by F) → match code →
///   key delivery (accepted ONLY if the seal is signed by the identity key I) → roster (signed by I) →
///   backfill chunks + live from roster devices only.
/// Every transport hands events to `onEvent(_:via:)`; all acceptance rules live behind it.
enum V2 {
    static let live: UInt16 = 21078, chunk: UInt16 = 30078, wrap: UInt16 = 1059
    static let toOwner: UInt16 = 30500, toFollower: UInt16 = 30501, auth: UInt16 = 22242

    static func hex(_ d: some Sequence<UInt8>) -> String { d.map { String(format: "%02x", $0) }.joined() }
    static func sha(_ s: String) -> [UInt8] { Array(SHA256.hash(data: Data(s.utf8))) }
    /// H(x) = hex(sha256(x))[:32]
    static func H(_ s: String) -> String { String(hex(sha(s)).prefix(32)) }
    /// gk = hex(sha256(G.pub bytes))[:8]
    static func gk(_ gPubHex: String) -> String {
        var bytes = [UInt8](), chars = Array(gPubHex)
        for i in stride(from: 0, to: chars.count - 1, by: 2) { bytes.append(UInt8(String(chars[i...i + 1]), radix: 16) ?? 0) }
        return String(hex(SHA256.hash(data: Data(bytes))).prefix(8))
    }
    /// int(sha256(F.pub_hex + token)) mod 10000, zero-padded (as Python and Android do).
    static func matchCode(_ fPub: String, _ token: String) -> String {
        let r = sha(fPub + token).reduce(0) { ($0 * 256 + Int($1)) % 10000 }
        return String(format: "%04d", r)
    }
    static func rosterD(_ sid: String) -> String { H("cgm2:roster:\(sid)") }
    static func chunkC(_ sid: String) -> String { H("cgm2:glucose:\(sid)") }
    /// Treatments (libreview SPEC §5): `#c` index shared by every treatment, `d` per treatment id.
    static func treatmentsC(_ sid: String) -> String { H("cgm2:treatments:\(sid)") }
    static func treatmentD(_ sid: String, _ id: String) -> String { H("cgm2:tr:\(sid):\(id)") }

    static func json(_ o: Any) -> String {
        String(decoding: (try? JSONSerialization.data(withJSONObject: o, options: [.sortedKeys, .withoutEscapingSlashes, .fragmentsAllowed])) ?? Data(), as: UTF8.self)
    }

    /// What a follower needs to ask for access (PROTOCOL-identity "Short invites"): identity, token, expiry, relays and
    /// the wearer's profile (`{"name","avatar"}`), from a v3 short link or a phone's BLE INFO.
    struct Invite { let i: String; let t: String; let x: Int; let r: [String]; let profile: [String: Any]? }

    /// Same set as Android `Proto.RELAYS`: take events from new keys and serve gift wraps (measured 2026-10-17).
    static let relays = ["wss://nos.lol", "wss://relay.primal.net", "wss://nostr.mom", "wss://relay.snort.social"]
    static let inviteBase = "https://sukrara.mauri.app/join#", appScheme = "sukrara:join#"
    static let rumorInvite: UInt16 = 30502

    /// The v3 short link: `https://sukrara.mauri.app/join#<base64url(I.pub[0:8] ‖ secret[16])>` (63 chars). The app's
    /// own `sukrara:join#<32>` (the web page's "Open in Sukrara" button) is the same link. Nothing else is an invite.
    struct Link { let fp: String; let secret: [UInt8]; var token: String { b64u(secret) } }

    static func parseLink(_ link: String) -> Link? {
        let s = link.trimmingCharacters(in: .whitespacesAndNewlines)
        let code: Substring
        if s.hasPrefix(inviteBase) { code = s.dropFirst(inviteBase.count) }
        else if s.hasPrefix(appScheme) { code = s.dropFirst(appScheme.count) }
        else { return nil }
        guard code.count == 32, code.allSatisfy({ $0.isASCII && ($0.isLetter || $0.isNumber || $0 == "-" || $0 == "_") }),
              let raw = unb64u(String(code)), raw.count == 24 else { return nil }
        return Link(fp: hex(raw[0..<8]), secret: Array(raw[8..<24]))
    }

    /// Is this text an invite link? Used for clipboard prefill and QR decoding.
    static func isInvite(_ link: String) -> Bool { parseLink(link) != nil }

    static func b64u(_ b: [UInt8]) -> String {
        Data(b).base64EncodedString().replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }
    static func unb64u(_ s: String) -> [UInt8]? {
        var t = s.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        while t.count % 4 != 0 { t += "=" }
        return Data(base64Encoded: t).map { Array($0) }
    }

    /// secp256k1 order n, big endian: an HKDF output must be in 1 ..< n to be a key.
    private static let n: [UInt8] = (0..<32).map { i in
        UInt8("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141".dropFirst(i * 2).prefix(2), radix: 16)! }

    /// `scalar(ikm, salt, info)` of PROTOCOL-identity: RFC 5869 HKDF-SHA256, counter suffix if out of range.
    static func scalar(_ ikm: [UInt8], salt: String, info: String) -> [UInt8] {
        for ctr in 0..<256 {
            let inf = Array(info.utf8) + (ctr == 0 ? [] : [UInt8(ctr)])
            let k = HKDF<SHA256>.deriveKey(inputKeyMaterial: SymmetricKey(data: ikm), salt: Array(salt.utf8), info: inf,
                                           outputByteCount: 32).withUnsafeBytes { Array($0) }
            if k.contains(where: { $0 != 0 }), k.lexicographicallyPrecedes(n) { return k }
        }
        fatalError("unreachable")
    }

    /// The key the invite's gift wrap is addressed to; anyone holding the link can derive it.
    static func inviteKeys(_ l: Link) -> Keys? { try? Keys.parse(secretKey: hex(scalar(l.secret, salt: "sukrara/invite/v3", info: "wrap-recipient"))) }

    /// Open the invite: fetch the gift wraps addressed to the invite key from the default relays (AUTH as that key
    /// where a relay asks), accept the one sealed by a key starting with the fingerprint and not expired.
    @MainActor static func resolveInvite(_ link: String) async -> Result<Invite, FollowerV2.JoinError> {
        guard let l = parseLink(link), let k = inviteKeys(l) else {
            return .failure(.init(text: "That isn't a Sukrara invite link (https://sukrara.mauri.app/join#…)."))
        }
        let wraps = await RelayPool.fetch(urls: relays, authKeys: k,
                                          filter: ["kinds": [Int(wrap)], "#p": [k.publicKey().toHex()]], timeout: 10)
        var reasons: [String] = []
        for json in wraps {
            guard let ev = try? Event.fromJson(json: json), ev.verify(),
                  let u = try? UnwrappedGift.fromGiftWrap(signer: k, giftWrap: ev) else { reasons.append("doesn't open with this link"); continue }
            let sender = u.sender().toHex(), rumor = u.rumor()
            guard rumor.kind().asU16() == rumorInvite,
                  let body = try? JSONSerialization.jsonObject(with: Data(rumor.content().utf8)) as? [String: Any],
                  body["type"] as? String == "invite", let x = body["x"] as? Int else { reasons.append("not an invite"); continue }
            guard sender.hasPrefix(l.fp) else { reasons.append("sealed by \(sender.prefix(16))…, the link expects \(l.fp)…"); continue }
            guard x > Int(Date().timeIntervalSince1970) else { reasons.append("expired"); continue }
            let r = (body["r"] as? [String] ?? []).filter { $0.hasPrefix("wss://") }
            log("v2: invite from \(sender.prefix(12)) '\((body["profile"] as? [String: Any])?["name"] as? String ?? "")' (\(wraps.count) wrap(s))")
            return .success(Invite(i: sender, t: l.token, x: x, r: r, profile: body["profile"] as? [String: Any]))
        }
        let why = Array(Set(reasons)).sorted().joined(separator: "; ")
        return .failure(.init(text: "Can't open this invite: \(why.isEmpty ? "nothing found on the relays (expired?)" : why)."))
    }

    /// Gift wrap a JSON body as rumor `kind`, sealed by `sender`, to `recipientHex`.
    static func gift(_ sender: Keys, _ recipientHex: String, kind: UInt16, _ body: [String: Any]) -> String? {
        do {
            let to = try PublicKey.parse(publicKey: recipientHex)
            let rumor = EventBuilder(kind: Kind(kind: kind), content: json(body)).finalizeUnsigned(publicKey: sender.publicKey())
            return try nip59MakeGiftWrap(signer: sender, receiverPubkey: to, rumor: rumor).asJson()
        } catch {
            log("v2: gift wrap failed: \(error)")
            return nil
        }
    }
}

// MARK: - relay pool

/// Minimal multi-relay WebSocket client: subscriptions re-sent after reconnects, an outbox re-sent on reconnect
/// (gift wraps must reach relays that were down), NIP-42 AUTH as `authKeys` when a relay answers
/// `CLOSED auth-required` (once per connection; REQs re-sent after the relay OKs it). No polling: a 60 s watchdog
/// only pings relays silent for > 150 s.
@MainActor final class RelayPool {
    @MainActor final class Relay {
        let url: URL
        var host: String { url.host() ?? url.absoluteString }
        var ws: URLSessionWebSocketTask?
        var task: Task<Void, Never>?
        var lastRx = Date()
        var challenge: String?, authSent = false, authId: String?
        init(url: URL) { self.url = url }
    }

    var onEvent: ((String, String) -> Void)?       // (event json, via "relay:<host>")
    var onOk: ((String, Bool, String) -> Void)?    // (event id, accepted, relay host) for events we published
    private(set) var relays: [Relay] = []
    private let authKeys: Keys
    private var subs: [String: [String: Any]] = [:]
    private var outbox: [(id: String, json: String, at: Date)] = []
    private let session = URLSession(configuration: .ephemeral)
    private var tick: Task<Void, Never>?

    init(urls: [String], authKeys: Keys) {
        self.authKeys = authKeys
        relays = urls.compactMap { URL(string: $0) }.map(Relay.init)
    }

    var connected: Int { relays.filter { $0.ws != nil }.count }

    /// One-shot query (invite lookup): REQ on every relay in parallel, NIP-42 AUTH as `authKeys` when a relay
    /// closes the REQ with auth-required, events until EOSE or `timeout`. Union of the events' JSON, by id.
    static func fetch(urls: [String], authKeys: Keys, filter: [String: Any], timeout: TimeInterval) async -> [String] {
        let req = "[\"REQ\",\"q\",\(V2.json(filter))]"
        return await withTaskGroup(of: [String].self) { group in
            for u in urls.compactMap(URL.init(string:)) {
                group.addTask {
                    let ws = URLSession(configuration: .ephemeral).webSocketTask(with: u)
                    ws.resume()
                    try? await ws.send(.string(req))
                    var got: [String] = [], challenge: String?, authId: String?
                    let deadline = Date().addingTimeInterval(timeout)
                    let killer = Task { try? await Task.sleep(for: .seconds(timeout)); ws.cancel(with: .goingAway, reason: nil) }
                    defer { killer.cancel(); ws.cancel(with: .goingAway, reason: nil) }
                    while Date() < deadline, let msg = try? await ws.receive() {
                        guard case .string(let text) = msg,
                              let arr = try? JSONSerialization.jsonObject(with: Data(text.utf8)) as? [Any],
                              let type = arr.first as? String else { continue }
                        switch type {
                        case "EVENT":
                            if arr.count > 2, let ev = arr[2] as? [String: Any],
                               let d = try? JSONSerialization.data(withJSONObject: ev) { got.append(String(decoding: d, as: UTF8.self)) }
                        case "EOSE": return got
                        case "AUTH": challenge = arr.count > 1 ? arr[1] as? String : nil
                        case "CLOSED":
                            guard (arr.count > 2 ? arr[2] as? String ?? "" : "").contains("auth-required"), authId == nil, let ch = challenge,
                                  let tags = try? [Tag.parse(data: ["relay", u.absoluteString]), Tag.parse(data: ["challenge", ch])],
                                  let ev = try? EventBuilder(kind: Kind(kind: V2.auth), content: "").tags(tags: tags).finalize(signer: authKeys),
                                  let j = try? ev.asJson() else { return got }
                            authId = ev.id().toHex()
                            try? await ws.send(.string("[\"AUTH\",\(j)]"))
                        case "OK":
                            if arr.count > 2, arr[1] as? String == authId {
                                guard arr[2] as? Bool == true else { return got }
                                try? await ws.send(.string(req))
                            }
                        default: break
                        }
                    }
                    return got
                }
            }
            var all: [String: String] = [:]
            for await evs in group {
                for e in evs {
                    if let id = (try? JSONSerialization.jsonObject(with: Data(e.utf8)) as? [String: Any])?["id"] as? String { all[id] = e }
                }
            }
            return Array(all.values)
        }
    }

    func restart(_ why: String) {
        log("v2 relays restart: \(why) (\(relays.count) relays)")
        for r in relays {
            r.task?.cancel()
            r.ws?.cancel(with: .goingAway, reason: nil)
            r.task = Task { await run(r) }
        }
        if tick == nil { tick = Task { await watchdog() } }
    }

    func stop() {
        tick?.cancel(); tick = nil
        for r in relays { r.task?.cancel(); r.ws?.cancel(with: .goingAway, reason: nil); r.ws = nil }
    }

    func subscribe(_ id: String, _ filter: [String: Any]) {
        subs[id] = filter
        for r in relays { send(r, "[\"REQ\",\(V2.json(id)),\(V2.json(filter))]") }
    }

    func close(_ id: String) {
        guard subs.removeValue(forKey: id) != nil else { return }
        for r in relays { send(r, "[\"CLOSE\",\(V2.json(id))]") }
    }

    func publish(_ eventJson: String) {
        let id = (try? JSONSerialization.jsonObject(with: Data(eventJson.utf8)) as? [String: Any])?["id"] as? String ?? ""
        outbox.removeAll { Date().timeIntervalSince($0.at) > 3600 }
        outbox.append((id, eventJson, Date()))
        for r in relays { send(r, "[\"EVENT\",\(eventJson)]") }
    }

    private func run(_ r: Relay) async {
        var backoff = 2.0
        while !Task.isCancelled {
            let ws = session.webSocketTask(with: r.url)
            r.ws = ws
            r.lastRx = Date()
            r.challenge = nil; r.authSent = false; r.authId = nil
            ws.resume()                                   // sends are queued until the socket is open
            for (id, f) in subs { send(r, "[\"REQ\",\(V2.json(id)),\(V2.json(f))]") }
            for o in outbox { send(r, "[\"EVENT\",\(o.json)]") }
            do {
                while true {
                    handle(try await ws.receive(), from: r)
                    backoff = 2
                }
            } catch {
                if Task.isCancelled { return }
                log("v2 \(r.host): \(error.localizedDescription) — retry in \(Int(backoff)) s")
            }
            ws.cancel()
            r.ws = nil
            try? await Task.sleep(for: .seconds(backoff))
            backoff = min(backoff * 2, 300)
        }
    }

    private func send(_ r: Relay, _ text: String) {
        r.ws?.send(.string(text)) { _ in }
    }

    private func watchdog() async {
        while !Task.isCancelled {
            try? await Task.sleep(for: .seconds(60), tolerance: .seconds(10))
            let now = Date()
            for r in relays {
                guard let ws = r.ws else { continue }
                let silent = now.timeIntervalSince(r.lastRx)
                if silent > 270 {
                    log("v2 \(r.host): silent \(Int(silent)) s, reconnecting")
                    ws.cancel(with: .goingAway, reason: nil)
                } else if silent > 150 {
                    ws.sendPing { error in if error == nil { Task { @MainActor in r.lastRx = Date() } } }
                }
            }
        }
    }

    private func handle(_ msg: URLSessionWebSocketTask.Message, from r: Relay) {
        guard case .string(let text) = msg else { return }
        r.lastRx = Date()
        guard let arr = try? JSONSerialization.jsonObject(with: Data(text.utf8)) as? [Any],
              let type = arr.first as? String else { return }
        switch type {
        case "EVENT":
            guard arr.count > 2, let ev = arr[2] as? [String: Any],
                  let data = try? JSONSerialization.data(withJSONObject: ev) else { return }
            onEvent?(String(decoding: data, as: UTF8.self), "relay:\(r.host)")
        case "AUTH":
            r.challenge = arr.count > 1 ? arr[1] as? String : nil
        case "CLOSED":
            let reason = arr.count > 2 ? arr[2] as? String ?? "" : ""
            log("v2 \(r.host): CLOSED \(arr.count > 1 ? arr[1] as? String ?? "" : ""): \(reason.prefix(80))")
            if reason.contains("auth-required") { auth(r) }
        case "OK":
            guard arr.count > 2 else { return }
            let id = arr[1] as? String, ok = arr[2] as? Bool ?? false
            if id != nil, id == r.authId {
                log("v2 \(r.host): NIP-42 AUTH \(ok ? "accepted" : "rejected: \((arr.count > 3 ? arr[3] as? String ?? "" : "").prefix(60))")")
                if ok { for (sid, f) in subs { send(r, "[\"REQ\",\(V2.json(sid)),\(V2.json(f))]") } }
            } else {
                if !ok { log("v2 \(r.host): event \(id?.prefix(8) ?? "?") refused: \((arr.count > 3 ? arr[3] as? String ?? "" : "").prefix(80))") }
                if let id { onOk?(id, ok, r.host) }
            }
        case "NOTICE":
            log("v2 \(r.host): \(text.prefix(120))")
        default: break
        }
    }

    /// NIP-42: kind 22242 signed by the key the gift wraps are addressed to.
    private func auth(_ r: Relay) {
        guard !r.authSent, let ch = r.challenge,
              let tags = try? [Tag.parse(data: ["relay", r.url.absoluteString]), Tag.parse(data: ["challenge", ch])],
              let ev = try? EventBuilder(kind: Kind(kind: V2.auth), content: "").tags(tags: tags).finalize(signer: authKeys)
        else { return }
        r.authSent = true
        r.authId = ev.id().toHex()
        if let j = try? ev.asJson() { send(r, "[\"AUTH\",\(j)]") }
        log("v2 \(r.host): NIP-42 AUTH sent")
    }
}

// MARK: - follower

/// Persistent follower state — lives in the Keychain (it holds F and the epoch read keys).
struct FollowerV2State: Codable {
    var f: String                      // nsec of follower key F
    var i: String                      // hex identity key I
    var relays: [String]
    var token: String
    var name: String                   // how this Mac introduced itself
    var avatar: String? = nil          // JSON of this Mac's generated picture (Avatar.swift), sent with `name`
    var wearer: String? = nil          // JSON `{"name","avatar"}` of the person we follow: invite, then each roster
    var code: String
    var sid: String? = nil
    var epochs: [Epoch] = []
    var devices: [String] = []
    var writers: [String]? = nil       // roster `writers` (follower keys allowed to add treatments)
    var rosterAt: Int = 0
    var status: String = "requested"   // requested | active | revoked
    var requestedAt: Int = Int(Date().timeIntervalSince1970)
    struct Epoch: Codable { var n: Int; var g: String }

    static let account = "v2-follower"
    static func load() -> FollowerV2State? {
        Keychain.read(account).flatMap { try? JSONDecoder().decode(FollowerV2State.self, from: $0) }
    }
    func save() { if let d = try? JSONEncoder().encode(self) { Keychain.write(Self.account, d) } }
    static func clear() { Keychain.delete(account) }
}

@MainActor final class FollowerV2 {
    enum Phase: Equatable { case waiting(code: String), active(epoch: Int), revoked }

    var onReading: ((Reading, String) -> Void)?
    var onHistory: (([Reading], String) -> Void)?
    var onPhase: ((Phase) -> Void)?
    /// Called for events we must send (requests, rekeys, treatments) besides the relays (which always get them): the
    /// BLE link to the wearer's phone. `done(true)` once the phone acknowledged the write; nil = nobody listening.
    var extraPublish: ((String, ((Bool) -> Void)?) -> Void)?
    /// A treatment event that passed every check (SPEC §5): author ∈ roster writers ∪ devices, known gk, d tag
    /// matches the id. `fromWearer` = signed by a wearer device key (its merged log), not by another writer.
    var onTreatment: ((Treatment, _ fromWearer: Bool, _ via: String) -> Void)?
    /// One of our treatment events reached a relay (OK true) or the wearer's phone over BLE (write acknowledged).
    var onTreatmentSent: ((_ id: String, _ u: Int64, _ via: String) -> Void)?
    /// A new epoch key arrived (BLE asks the owner for its backlog again — it is readable now).
    var onNewKey: (() -> Void)?

    private(set) var st: FollowerV2State
    private let F: Keys
    private var epochKeys: [String: (n: Int, g: Keys)] = [:]   // gk → epoch
    private var pool: RelayPool
    private var seen: [String: Date] = [:]
    private var rekeyAsked: [String: Date] = [:]
    private var subscribedDevices: [String] = []
    private var subscribedTreatmentAuthors: [String] = []
    private var sentTreatments: [String: (id: String, u: Int64)] = [:]    // event id → treatment it carries
    private var lastCreatedAt: [String: Int] = [:]                          // d → created_at of our last event
    private(set) var accepted = 0, rejected = 0
    private(set) var lastVia = ""

    var phase: Phase {
        switch st.status {
        case "active": .active(epoch: epochKeys.values.map(\.n).max() ?? 0)
        case "revoked": .revoked
        default: .waiting(code: st.code)
        }
    }
    var code: String { st.code }
    var canWrite: Bool { st.status == "active" && (st.writers ?? []).contains(F.publicKey().toHex()) }

    /// Treatment as a writer (docs/07 § Treatments): kind 30078 signed by F, d = H("cgm2:tr:"+sid+":"+id),
    /// c = H("cgm2:treatments:"+sid), gk, expiration +30 d; content = NIP-44(F → G_n.pub) of the treatment JSON.
    /// Sent to the relays and to the wearer's phone over BLE; the owner accepts it only if F ∈ roster writers.
    /// Progress: `onTreatmentSent` (a relay / the phone took it), then the wearer's own copy comes back through
    /// `onTreatment(fromWearer: true)` once it merged it.
    @discardableResult
    func publishTreatment(_ t: Treatment) -> Bool {
        guard canWrite, let sid = st.sid, let (_, G) = epochKeys.values.max(by: { $0.n < $1.n }) else { return false }
        var body = t.dict
        body["v"] = 1                                      // PROTOCOL-v2 "Treatments"
        // created_at strictly increasing per (author, d): relays drop a same-second replacement (an edit or delete
        // right after the entry would otherwise be lost).
        let d = V2.treatmentD(sid, t.id)
        let createdAt = max(Int(Date().timeIntervalSince1970), (lastCreatedAt[d] ?? 0) + 1)
        lastCreatedAt[d] = createdAt
        do {
            let ct = try nip44Encrypt(secretKey: F.secretKey(), publicKey: G.publicKey(), content: V2.json(body), version: .v2)
            let tags = try [["d", d], ["c", V2.treatmentsC(sid)],
                            ["gk", V2.gk(G.publicKey().toHex())],
                            ["expiration", String(createdAt + 30 * 86400)]].map { try Tag.parse(data: $0) }
            let ev = try EventBuilder(kind: Kind(kind: V2.chunk), content: ct).tags(tags: tags)
                .customCreatedAt(createdAt: Timestamp.fromSecs(secs: UInt64(createdAt))).finalize(signer: F)
            let evId = ev.id().toHex(), json = try ev.asJson()
            sentTreatments[evId] = (t.id, t.u)
            pool.publish(json)
            extraPublish?(json) { [weak self] ok in
                if ok { self?.onTreatmentSent?(t.id, t.u, "ble") }
            }
            log("v2: treatment \(t.k) sent as writer (\(t.id.prefix(8)), u \(t.u))")
            return true
        } catch {
            log("v2: treatment not sent: \(error)")
            return false
        }
    }
    var relayHosts: [String] { pool.relays.map(\.host) }
    /// What the wearer sees of this Mac.
    var myProfile: [String: Any] {
        ["name": st.name, "avatar": st.avatar.flatMap { try? JSONSerialization.jsonObject(with: Data($0.utf8)) } ?? NSNull()]
    }
    /// The person we follow: name ("" if unknown) and avatar JSON object.
    var wearer: (name: String, avatar: [String: Any]?) {
        let p = st.wearer.flatMap { try? JSONSerialization.jsonObject(with: Data($0.utf8)) as? [String: Any] }
        return ((p?["name"] as? String) ?? "", p?["avatar"] as? [String: Any])
    }
    var followerPub: String { F.publicKey().toHex() }     // public — fine to show/log
    var connectedRelays: Int { pool.connected }

    init?(state: FollowerV2State) {
        guard let f = try? Keys.parse(secretKey: state.f) else { return nil }
        st = state
        F = f
        pool = RelayPool(urls: UserDefaults.standard.bool(forKey: "v2NoRelays") ? [] : state.relays, authKeys: f)   // test: BLE only
        for e in state.epochs { addEpoch(e.n, e.g) }
        pool.onEvent = { [weak self] json, via in self?.onEvent(json, via: via) }
        pool.onOk = { [weak self] evId, ok, host in
            guard ok, let self, let t = sentTreatments[evId] else { return }
            onTreatmentSent?(t.id, t.u, "relay:\(host)")
        }
    }

    /// New pairing from an invite link: generate F and persist it. Caller wires callbacks, then `start()` +
    /// `sendRequest()`.
    struct JoinError: Error { let text: String }

    static func join(_ inv: V2.Invite, name: String) -> Result<FollowerV2, JoinError> {
        if inv.x < Int(Date().timeIntervalSince1970) { return .failure(JoinError(text: "This invite has expired — ask for a new one.")) }
        let f = Keys.generate()
        guard let nsec = try? f.secretKey().toBech32() else { return .failure(JoinError(text: "Couldn't create a key.")) }
        var st = FollowerV2State(f: nsec, i: inv.i, relays: inv.r.isEmpty ? V2.relays : inv.r, token: inv.t, name: name,
                                 code: V2.matchCode(f.publicKey().toHex(), inv.t))
        st.avatar = V2.json(Avatar.random())
        st.wearer = inv.profile.map { V2.json($0) }
        st.save()
        guard FollowerV2State.load() != nil, let node = FollowerV2(state: st) else {
            return .failure(JoinError(text: "Couldn't store the follower key in the Keychain."))
        }
        return .success(node)
    }

    func start() {
        pool.subscribe("w", ["kinds": [Int(V2.wrap)], "#p": [F.publicKey().toHex()],
                             "since": Int(Date().timeIntervalSince1970) - 3 * 86400])
        if st.sid != nil { subscribeData() }
        pool.restart("start")
        onPhase?(phase)
    }

    func restart(_ why: String) { pool.restart(why) }

    func stop() { pool.stop() }

    func sendRequest() {
        guard let g = V2.gift(F, st.i, kind: V2.toOwner, ["v": 2, "type": "request", "token": st.token, "profile": myProfile])
        else { return }
        pool.publish(g)
        extraPublish?(g, nil)
        log("v2: access request sent as \"\(st.name)\" (\(pool.relays.count) relays, + Bluetooth if the wearer's phone is connected) — match code \(st.code)")
    }

    /// Who may author treatment events: roster writers ∪ the wearer's device keys.
    private var treatmentAuthors: [String] { (st.writers ?? []) + st.devices }

    private func addEpoch(_ n: Int, _ nsec: String) {
        guard let g = try? Keys.parse(secretKey: nsec) else { return }
        epochKeys[V2.gk(g.publicKey().toHex())] = (n, g)
    }

    private func subscribeData() {
        guard let sid = st.sid else { return }
        pool.subscribe("r", ["kinds": [Int(V2.chunk)], "authors": [st.i], "#d": [V2.rosterD(sid)]])
        // Treatments (SPEC §5): writers' own events and the wearer's merged log, republished by its device key(s).
        let trAuthors = Array(Set(treatmentAuthors)).sorted()
        if !trAuthors.isEmpty, trAuthors != subscribedTreatmentAuthors {
            subscribedTreatmentAuthors = trAuthors
            pool.subscribe("t", ["kinds": [Int(V2.chunk)], "authors": trAuthors, "#c": [V2.treatmentsC(sid)],
                                 "since": Int(Date().timeIntervalSince1970) - 30 * 86400])
        }
        guard !st.devices.isEmpty, st.devices != subscribedDevices else { return }
        subscribedDevices = st.devices
        let now = Int(Date().timeIntervalSince1970)
        pool.subscribe("c", ["kinds": [Int(V2.chunk)], "authors": st.devices, "#c": [V2.chunkC(sid)], "since": now - 13 * 3600])
        pool.subscribe("l", ["kinds": [Int(V2.live)], "authors": st.devices, "since": now - 120])
    }

    // MARK: the one entry point for every transport

    private func reject(_ why: String, _ id: String) {
        rejected += 1
        log("v2 reject \(id.prefix(8)): \(why)")
    }

    /// `via`: "relay:<host>" or "ble:<name>". Acceptance rules: PROTOCOL-v2 § Acceptance rules.
    func onEvent(_ json: String, via: String) {
        guard let o = try? JSONSerialization.jsonObject(with: Data(json.utf8)) as? [String: Any],
              let id = o["id"] as? String, seen[id] == nil else { return }
        guard let ev = try? Event.fromJson(json: json), ev.verify() else { return reject("bad signature/id", id) }
        if seen.count > 5000 { seen = seen.filter { Date().timeIntervalSince($0.value) < 6 * 3600 } }
        let kind = ev.kind().asU16()
        let author = ev.author().toHex()
        let tags = (o["tags"] as? [[String]]) ?? []
        func tag(_ n: String) -> String? { tags.first { $0.count > 1 && $0[0] == n }?[1] }
        if kind == V2.wrap {
            seen[id] = Date()                // only after verifying, so a forged copy can't shadow the real one
            return onWrap(ev, id)
        }

        guard let g = tag("gk") else { return reject("no gk tag", id) }
        guard let (epoch, G) = epochKeys[g] else {
            if st.status == "active", Date().timeIntervalSince(rekeyAsked[g] ?? .distantPast) > 300,
               let w = V2.gift(F, st.i, kind: V2.toOwner, ["v": 2, "type": "rekey", "have": g]) {
                rekeyAsked[g] = Date()
                pool.publish(w)
                extraPublish?(w, nil)
                log("v2: unknown key epoch \(g) — rekey request sent")
            }
            return
        }
        guard let sid = st.sid else { return }
        seen[id] = Date()                    // gk known: this event's fate is final
        if kind == V2.chunk, tag("d") == V2.rosterD(sid) {
            guard author == st.i else { return reject("roster not signed by identity", id) }
            let at = Int(ev.createdAt().asSecs())
            guard at >= st.rosterAt,
                  let plain = try? nip44Decrypt(secretKey: G.secretKey(), publicKey: ev.author(), payload: ev.content()),
                  let body = try? JSONSerialization.jsonObject(with: Data(plain.utf8)) as? [String: Any],
                  body["v"] as? Int == 2, let devices = body["devices"] as? [String] else { return }
            st.rosterAt = at
            if let p = body["profile"] as? [String: Any], V2.json(p) != st.wearer {
                st.wearer = V2.json(p)
                log("v2: wearer profile '\(p["name"] as? String ?? "")'")
                onPhase?(phase)                          // the menu shows their name and picture
            }
            if let r = body["relays"] as? [String], !r.isEmpty { st.relays = r }   // used on next launch
            let writers = body["writers"] as? [String] ?? []
            let wasWriter = canWrite
            if writers != (st.writers ?? []) {
                st.writers = writers
                log("v2: roster writers changed — this Mac \(canWrite ? "MAY" : "may not") add treatments")
            }
            if devices != st.devices {
                st.devices = devices
                log("v2: roster epoch \(body["epoch"] ?? "?") devices \(devices.map { String($0.prefix(12)) })")
            }
            subscribeData()
            st.save()
            if wasWriter != canWrite { onPhase?(phase) }        // the UI shows whether we may add entries
            return
        }
        if kind == V2.chunk, tag("c") == V2.treatmentsC(sid) {
            return onTreatmentEvent(ev, author: author, G: G, dTag: tag("d"), sid: sid, id: id, via: via)
        }
        guard st.devices.contains(author) else { return reject("author not in roster", id) }
        guard let plain = try? nip44Decrypt(secretKey: G.secretKey(), publicKey: ev.author(), payload: ev.content()),
              let body = try? JSONSerialization.jsonObject(with: Data(plain.utf8)) as? [String: Any],
              body["v"] as? Int == 2 else { return reject("decrypt failed", id) }
        accepted += 1
        lastVia = via
        let label = Self.label(via)
        func num(_ a: Any?) -> Double? { (a as? NSNumber)?.doubleValue }
        switch kind {
        case V2.live:
            guard let t = num(body["t"]), let sgv = num(body["sgv"]) else { return }
            onReading?(Reading(date: Date(timeIntervalSince1970: t), mgdl: sgv, rate: num(body["r"])), label)
            log("v2 live \(Int(sgv)) mg/dL epoch \(epoch) via \(via)")
        case V2.chunk:
            guard let h = num(body["h"]), let rows = body["r"] as? [[Any]] else { return }
            let pts = rows.compactMap { row -> Reading? in
                guard row.count >= 2, let dt = num(row[0]), let sgv = num(row[1]) else { return nil }
                return Reading(date: Date(timeIntervalSince1970: h + dt), mgdl: sgv, rate: row.count > 2 ? num(row[2]).map { $0 / 100 } : nil)
            }
            log("v2 chunk \(pts.count) readings (hour \(Int(h))) epoch \(epoch) via \(via)")
            if !pts.isEmpty { onHistory?(pts, label) }
        default: break
        }
    }

    /// SPEC §5 follower rules: author ∈ roster writers ∪ wearer device keys, decrypts with the epoch named by `gk`,
    /// the §1 JSON parses, and `d` = H("cgm2:tr:"+sid+":"+id) (an author can't overwrite another id's slot).
    private func onTreatmentEvent(_ ev: Event, author: String, G: Keys, dTag: String?, sid: String, id: String, via: String) {
        guard treatmentAuthors.contains(author) else { return reject("treatment author not a roster writer or device", id) }
        guard let plain = try? nip44Decrypt(secretKey: G.secretKey(), publicKey: ev.author(), payload: ev.content()),
              let body = try? JSONSerialization.jsonObject(with: Data(plain.utf8)) as? [String: Any],
              let t = Treatment(dict: body) else { return reject("treatment decrypt/parse failed", id) }
        guard dTag == V2.treatmentD(sid, t.id) else { return reject("treatment d tag doesn't match its id", id) }
        guard Treatment.kinds.contains(t.k) else { return reject("treatment kind \(t.k) unknown", id) }
        accepted += 1
        let fromWearer = st.devices.contains(author)
        log("v2 treatment \(t.k) \(t.id.prefix(8)) u \(t.u)\(t.del ? " (deleted)" : "") from \(fromWearer ? "wearer" : "writer \(author.prefix(8))") via \(via)")
        onTreatment?(t, fromWearer, via)
    }

    static func label(_ via: String) -> String {
        if via.hasPrefix("relay:") { return "Nostr · " + via.dropFirst(6) }
        if via.hasPrefix("ble:") { return "Bluetooth · " + via.dropFirst(4) }
        return via
    }

    private func onWrap(_ ev: Event, _ id: String) {
        guard let u = try? UnwrappedGift.fromGiftWrap(signer: F, giftWrap: ev) else { return }   // not for us
        let sender = u.sender().toHex(), rumor = u.rumor()
        // Only the stream identity may send keys / notices — a forged delivery sealed by anyone else is dropped.
        guard rumor.kind().asU16() == V2.toFollower, sender == st.i else {
            return reject("key delivery not sealed by the identity key (sender \(sender.prefix(12)))", id)
        }
        guard let body = try? JSONSerialization.jsonObject(with: Data(rumor.content().utf8)) as? [String: Any],
              body["v"] as? Int == 2 else { return }
        switch body["type"] as? String {
        case "key":
            guard let g = body["g"] as? String, let gKeys = try? Keys.parse(secretKey: g),
                  let n = body["epoch"] as? Int, let sid = body["sid"] as? String else { return }
            let k = V2.gk(gKeys.publicKey().toHex())
            guard epochKeys[k] == nil else { return }
            epochKeys[k] = (n, gKeys)
            st.epochs.append(.init(n: n, g: g))
            if st.sid == nil || n >= (st.epochs.map(\.n).max() ?? 0) {
                st.sid = sid
                if let d = body["devices"] as? [String] { st.devices = d }
                if let r = body["relays"] as? [String], !r.isEmpty { st.relays = r }   // used on next launch
            }
            if st.status != "revoked" { st.status = "active" }
            st.save()
            log("v2: key delivery accepted — epoch \(n), \(st.devices.count) device(s)")
            subscribeData()
            onPhase?(phase)
            onNewKey?()
        case "revoked":
            st.status = "revoked"
            st.save()
            log("v2: owner removed our access")
            for s in ["c", "l", "r"] { pool.close(s) }
            onPhase?(phase)
        default: break
        }
    }
}
