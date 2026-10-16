// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import CoreBluetooth
import Foundation

/// Protocol v2 over Bluetooth (docs/07-bluetooth-followers.md): the CGM Share owner phone is a GATT server; this Mac
/// is a central. No BLE pairing/bonding — every event goes through the same `FollowerV2.onEvent(_:via:)` acceptance
/// rules as relay events (seal signer = I, roster devices, known gk, valid signature). The Bluetooth connection is
/// `BLELink`'s, shared with the legacy path (one link per phone).
///   frame = [msgId u16][idx u16][count u16][payload]  (big endian); message = JSON array
///   follower → owner: ["HELLO",{"v":2}], ["EVENT",<event>]   owner → follower: ["INFO",{…}], ["EVENT",<event>]
final class BLEV2Central: NSObject, BLEService {
    static let service = CBUUID(string: "7c6a0001-5c3e-4f0a-9a57-c95e0b1e0007")
    static let rx = CBUUID(string: "7c6a0002-5c3e-4f0a-9a57-c95e0b1e0007")
    static let tx = CBUUID(string: "7c6a0003-5c3e-4f0a-9a57-c95e0b1e0007")

    struct Info { let i: String; let relays: [String]; let token: String?; let expiry: Int? }

    var onEvent: ((String, String) -> Void)?        // (event json, via "ble:<name>")
    var onInfo: ((Info, String) -> Void)?           // (info, peripheral name)
    var onState: ((String) -> Void)?

    // BLEService: the connection itself belongs to BLELink (shared with the legacy path, one link per phone).
    let uuid = BLEV2Central.service
    let characteristics = [BLEV2Central.rx, BLEV2Central.tx]
    let name = "v2"
    let silenceLimit: TimeInterval = 180            // the owner pushes a live event every minute

    private weak var link: BLELink?
    private var peripheral: CBPeripheral?
    private var rxChar: CBCharacteristic?
    private var txChar: CBCharacteristic?
    private var parts: [UInt16: [Data?]] = [:]
    /// Frames to write; `done` (on the last frame of a message) runs after its write response.
    private var outbox: [(frame: Data, done: ((Bool) -> Void)?)] = []
    private var inFlight: (frame: Data, done: ((Bool) -> Void)?)?
    private var msgId: UInt16 = 0
    private(set) var connectedName: String?
    /// Identity key (hex) of the connected phone's wearer, from INFO.
    private(set) var ownerI: String?
    private var phoneName: String { peripheral?.name ?? "phone" }

    init(link: BLELink) {
        self.link = link
        super.init()
        link.register(self)
    }

    /// Stop listening to this phone and look again later (e.g. a phone that isn't offering pairing while we follow
    /// nobody, or another wearer's phone) — keeps both radios idle instead of streaming events we would discard.
    /// The Bluetooth link itself stays up if the legacy path uses it.
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
        peripheral = nil; rxChar = nil; txChar = nil; parts = [:]; outbox = []; inFlight = nil
        connectedName = nil; ownerI = nil
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

    func hello() { send(["HELLO", ["v": 2]]) }

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
        guard d.count >= 6 else { return }
        let b = [UInt8](d)
        let mid = UInt16(b[0]) << 8 | UInt16(b[1]), idx = Int(UInt16(b[2]) << 8 | UInt16(b[3])),
            count = Int(UInt16(b[4]) << 8 | UInt16(b[5]))
        guard count > 0, idx < count else { return }
        if idx == 0 || parts[mid]?.count != count { parts[mid] = Array(repeating: nil, count: count) }
        parts[mid]![idx] = Data(b[6...])
        guard let all = parts[mid], all.allSatisfy({ $0 != nil }) else { return }
        parts[mid] = nil
        let payload = all.compactMap { $0 }.reduce(Data(), +)
        guard let msg = try? JSONSerialization.jsonObject(with: payload) as? [Any], let type = msg.first as? String,
              msg.count > 1 else { return }
        switch type {
        case "INFO":
            guard let o = msg[1] as? [String: Any], let i = o["i"] as? String else { return }
            let pair = o["pair"] as? [String: Any]
            ownerI = i
            onInfo?(Info(i: i, relays: o["relays"] as? [String] ?? [], token: pair?["t"] as? String,
                         expiry: (pair?["x"] as? NSNumber)?.intValue), phoneName)
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
