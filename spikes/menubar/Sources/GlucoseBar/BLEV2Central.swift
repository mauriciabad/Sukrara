// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import CoreBluetooth
import NostrSDK
import Foundation

/// Protocol v2 over Bluetooth (docs/07-bluetooth-followers.md): the CGM Share owner phone is a GATT server; this Mac
/// is a central. No BLE pairing/bonding — every event goes through the same `FollowerV2.onEvent(_:via:)` acceptance
/// rules as relay events (seal signer = I, roster devices, known gk, valid signature). The Bluetooth connection is
/// `BLELink`'s (one link per phone).
///   frame = [msgId u16][idx u16][count u16][payload]  (big endian); message = JSON array
///   follower → owner: ["HELLO",{"v":2,"nonce":<hex>,"id":<proof>}], ["EVENT",<event>]   owner → follower: ["INFO",{…}], ["EVENT",<event>]
/// INFO proves the stream's `sid` over our HELLO's nonce (`m`) and names the identity `I` only while "Pair nearby" is
/// open (P12); nothing is written to a phone before it proved it's the wearer's (`verifiedOwner`, set by the app).
final class BLEV2Central: NSObject, BLEService {
    static let service = CBUUID(string: "7c6a0001-5c3e-4f0a-9a57-c95e0b1e0007")
    static let rx = CBUUID(string: "7c6a0002-5c3e-4f0a-9a57-c95e0b1e0007")
    static let tx = CBUUID(string: "7c6a0003-5c3e-4f0a-9a57-c95e0b1e0007")

    /// `i`, `relays` and `profile` (the wearer's `{"name","avatar"}`, inside `pair`) only while "Pair nearby" is open;
    /// `nonce` + `m` = H("cgm2:ble:"+sid+":"+nonce) always.
    struct Info {
        let i: String?; let relays: [String]; let token: String?; let expiry: Int?; let profile: [String: Any]?
        var nonce: String? = nil; var m: String? = nil
    }

    enum Owner { case ours, other, unknown }

    /// spec/ogs-ble.md B§5: `m = H("cgm2:ble:" + sid + ":" + nonce)` (Android `Ble.infoM`).
    static func infoM(sid: String, nonce: String) -> String { V2.H("cgm2:ble:\(sid):\(nonce)") }

    /// spec/ogs-ble.md B§5 identity proof (Android `Ble.idProof`): HELLO `id` = F's BIP-340 signature of the kind-21080
    /// event `{created_at 0, tags [], content H("cgm2:ble-id:"+sid+":"+nonce+":"+dev)}`. The event is never sent; the
    /// wearer's phone rebuilds it for each follower to name this link ("<name> · Mac").
    static let idKind: UInt16 = 21080
    static func idContent(sid: String, nonce: String, dev: String) -> String { V2.H("cgm2:ble-id:\(sid):\(nonce):\(dev)") }
    /// The proof event's id (NIP-01 serialization; the content is hex, nothing to escape): what F signs.
    static func idEventId(fPub: String, sid: String, nonce: String, dev: String) -> String {
        V2.hex(V2.sha("[0,\"\(fPub)\",0,\(idKind),[],\"\(idContent(sid: sid, nonce: nonce, dev: dev))\"]"))
    }
    static func idProof(f: Keys, sid: String, nonce: String, dev: String) -> String? {
        guard let ev = try? EventBuilder(kind: Kind(kind: idKind), content: idContent(sid: sid, nonce: nonce, dev: dev))
                .customCreatedAt(createdAt: Timestamp.fromSecs(secs: 0)).finalize(signer: f),
              let json = try? ev.asJson(),
              let o = try? JSONSerialization.jsonObject(with: Data(json.utf8)) as? [String: Any] else { return nil }
        return o["sig"] as? String
    }

    /// B§6 "Which owner" (Android `Ble.owner`): ours = `m` proves our `sid` for one of our HELLO nonces, or `i` is our
    /// identity; other = another identity, or a missing/wrong proof while we know the `sid`; unknown = our request is
    /// pending (no `sid` yet) and the phone isn't pairing — receive, don't write.
    static func owner(_ info: Info, i: String?, sid: String?, nonces: [String]) -> Owner {
        if let i, let theirs = info.i { return theirs == i ? .ours : .other }
        guard let sid else { return .unknown }
        guard let n = info.nonce, nonces.contains(n), info.m == infoM(sid: sid, nonce: n) else { return .other }
        return .ours
    }

    /// Reassembly with the B§4.3 limits (P13): a buffer idle 30 s is dropped, a message over 256 KiB is dropped, at
    /// most 16 buffers are open (the oldest goes). Android `Ble.Reassembler`.
    struct Reassembler {
        static let maxMessage = 256 * 1024, maxBuffers = 16
        static let idle: TimeInterval = 30
        private struct Buf { let count: Int; var touched: Date; var parts: [Int: Data] = [:]; var size = 0; var dead = false }
        private var bufs: [UInt16: Buf] = [:]
        private var order: [UInt16] = []
        private(set) var dropped = 0

        /// The message (a JSON array) once its last frame arrives; nil until then or if dropped.
        mutating func add(_ d: Data, now: Date = Date()) -> [Any]? {
            guard d.count >= 6 else { return nil }
            let b = [UInt8](d)
            let mid = UInt16(b[0]) << 8 | UInt16(b[1]), idx = Int(UInt16(b[2]) << 8 | UInt16(b[3])),
                count = Int(UInt16(b[4]) << 8 | UInt16(b[5]))
            guard count > 0, idx < count else { return nil }
            for (k, v) in bufs where now.timeIntervalSince(v.touched) > Self.idle {
                if !v.dead { dropped += 1 }
                bufs[k] = nil; order.removeAll { $0 == k }
            }
            if idx == 0 || bufs[mid]?.count != count {
                bufs[mid] = nil; order.removeAll { $0 == mid }
                if bufs.count >= Self.maxBuffers, let oldest = order.first { bufs[oldest] = nil; order.removeFirst() }
                bufs[mid] = Buf(count: count, touched: now)
                order.append(mid)
            }
            guard var buf = bufs[mid] else { return nil }
            buf.touched = now
            defer { if bufs[mid] != nil { bufs[mid] = buf } }
            if buf.dead { return nil }
            let part = Data(b[6...])
            buf.size += part.count - (buf.parts.updateValue(part, forKey: idx)?.count ?? 0)
            if buf.size > Self.maxMessage { buf.dead = true; buf.parts = [:]; dropped += 1; return nil }
            guard buf.parts.count == count else { return nil }
            bufs[mid] = nil; order.removeAll { $0 == mid }
            let payload = (0..<count).compactMap { buf.parts[$0] }.reduce(Data(), +)
            guard let msg = try? JSONSerialization.jsonObject(with: payload) as? [Any], msg.first is String, msg.count > 1 else { return nil }
            return msg
        }
    }

    var onEvent: ((String, String) -> Void)?        // (event json, via "ble:<name>")
    var onInfo: ((Info, String) -> Void)?           // (info, peripheral name)
    /// The identity proof for a HELLO nonce (`FollowerV2.bleIdProof`), nil while we don't know `sid`.
    var proof: ((String) -> String?)?
    var onState: ((String) -> Void)?

    // BLEService: the connection itself belongs to BLELink (one link per phone).
    let uuid = BLEV2Central.service
    let characteristics = [BLEV2Central.rx, BLEV2Central.tx]
    let name = "v2"
    let silenceLimit: TimeInterval = 180            // the owner pushes a live event every minute

    private weak var link: BLELink?
    private var peripheral: CBPeripheral?
    private var rxChar: CBCharacteristic?
    private var txChar: CBCharacteristic?
    private var reasm = Reassembler()
    /// Frames to write; `done` (on the last frame of a message) runs after its write response.
    private var outbox: [(frame: Data, done: ((Bool) -> Void)?)] = []
    private var inFlight: (frame: Data, done: ((Bool) -> Void)?)?
    private var msgId: UInt16 = 0
    private(set) var connectedName: String?
    /// Identity key (hex) of the wearer whose phone this is, once its INFO proved it (set by the app, P12): events are
    /// written only to that phone.
    var verifiedOwner: String?
    /// Nonces of our last HELLOs: an INFO must prove `sid` over one of them.
    private(set) var nonces: [String] = []
    private var phoneName: String { peripheral?.name ?? "phone" }

    init(link: BLELink) {
        self.link = link
        super.init()
        link.register(self)
    }

    /// Stop listening to this phone and look again later (e.g. a phone that isn't offering pairing while we follow
    /// nobody, or another wearer's phone) — keeps both radios idle instead of streaming events we would discard.
    /// BLELink closes the link once no path is attached.
    func disconnect(retryAfter s: TimeInterval) {
        guard let p = peripheral else { return }
        onState?("BLE v2: leaving \(p.name ?? "phone") for \(Int(s)) s")
        link?.release(self, for: s)
    }

    func attach(_ p: CBPeripheral, _ s: CBService) {
        rxChar = s.characteristics?.first { $0.uuid == Self.rx }
        txChar = s.characteristics?.first { $0.uuid == Self.tx }
        guard let tx = txChar, rxChar != nil else { onState?("BLE v2: characteristics not found"); return }
        peripheral = p
        p.setNotifyValue(true, for: tx)
    }

    func detach() {
        if let p = peripheral, let tx = txChar, p.state == .connected { p.setNotifyValue(false, for: tx) }
        let pending = ([inFlight].compactMap { $0 } + outbox).compactMap(\.done)
        peripheral = nil; rxChar = nil; txChar = nil; reasm = Reassembler(); outbox = []; inFlight = nil
        connectedName = nil; verifiedOwner = nil
        pending.forEach { $0(false) }
    }

    func silent() {
        onState?("BLE v2: nothing for \(Int(silenceLimit)) s on a live link — HELLO again")
        hello()
    }

    // MARK: sending

    /// Queue a message; frames are written one at a time (next after the write response). `done(true)` once the
    /// phone acknowledged the last frame, `done(false)` if the message can't be sent (no link, or the link dropped).
    func send(_ msg: [Any], done: ((Bool) -> Void)? = nil) {
        guard let p = peripheral, rxChar != nil, connectedName != nil,
              let payload = try? JSONSerialization.data(withJSONObject: msg, options: [.withoutEscapingSlashes]) else {
            done?(false)
            return
        }
        msgId &+= 1
        let per = max(20, p.maximumWriteValueLength(for: .withResponse) - 6)
        let count = max(1, (payload.count + per - 1) / per)
        for i in 0..<count {
            var f = Data()
            for v in [msgId, UInt16(i), UInt16(count)] { f.append(UInt8(v >> 8)); f.append(UInt8(v & 0xff)) }
            f.append(payload.subdata(in: payload.startIndex + i * per..<min(payload.endIndex, payload.startIndex + (i + 1) * per)))
            outbox.append((f, i == count - 1 ? done : nil))
        }
        pump()
    }

    func sendEvent(_ json: String, done: ((Bool) -> Void)? = nil) {
        guard let o = try? JSONSerialization.jsonObject(with: Data(json.utf8)) else { done?(false); return }
        send(["EVENT", o], done: done)
    }

    /// HELLO with a fresh 16-byte nonce (the last 4 are kept for the INFO check) and, once we know `sid`, our identity proof.
    func hello() {
        let n = V2.hex((0..<16).map { _ in UInt8.random(in: 0...255) })
        nonces = Array((nonces + [n]).suffix(4))
        var body: [String: Any] = ["v": 2, "nonce": n]
        if let id = proof?(n) { body["id"] = id }     // once we know `sid`: who we are (B§5)
        send(["HELLO", body])
    }

    private func pump() {
        guard inFlight == nil, !outbox.isEmpty, let p = peripheral, let c = rxChar else { return }
        let next = outbox.removeFirst()
        inFlight = next
        p.writeValue(next.frame, for: c, type: .withResponse)
    }

    func wrote(_ c: CBCharacteristic, error: Error?) {
        if let error { onState?("BLE v2: write failed: \(error.localizedDescription)") }
        let done = inFlight?.done
        inFlight = nil
        done?(error == nil)
        pump()
    }

    // MARK: receiving

    private func frame(_ d: Data) {
        guard let msg = reasm.add(d), let type = msg.first as? String else { return }
        switch type {
        case "INFO":
            guard let o = msg[1] as? [String: Any] else { return }
            let pair = o["pair"] as? [String: Any]
            onInfo?(Info(i: o["i"] as? String, relays: o["relays"] as? [String] ?? [], token: pair?["t"] as? String,
                         expiry: (pair?["x"] as? NSNumber)?.intValue, profile: pair?["profile"] as? [String: Any],
                         nonce: o["nonce"] as? String, m: o["m"] as? String), phoneName)
        case "EVENT":
            guard let ev = msg[1] as? [String: Any], let data = try? JSONSerialization.data(withJSONObject: ev) else { return }
            onEvent?(String(decoding: data, as: UTF8.self), "ble:\(phoneName)")
        default: break
        }
    }

    func received(_ c: CBCharacteristic, _ p: CBPeripheral) {
        guard c.uuid == Self.tx, let d = c.value else { return }
        frame(d)
    }

    func notifying(_ c: CBCharacteristic, error: Error?) {
        guard c.uuid == Self.tx, c.isNotifying, let p = peripheral else { return }
        connectedName = p.name ?? "phone"
        onState?("BLE v2: connected to \(connectedName!), MTU write \(p.maximumWriteValueLength(for: .withResponse)) B")
        hello()
    }
}
