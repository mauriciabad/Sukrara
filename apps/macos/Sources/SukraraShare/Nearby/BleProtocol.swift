// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation

/// The GATT share link (poc/spec/ogs-ble.md B§2–B§6, docs/07-bluetooth-followers.md): the wearer's phone is the
/// peripheral, this Mac a central. No bonding: trust comes only from the events, exactly as over relays.
public enum BleUUIDs {
    public static let service = "7c6a0001-5c3e-4f0a-9a57-c95e0b1e0007"
    /// Write with response: follower → wearer frames.
    public static let rx = "7c6a0002-5c3e-4f0a-9a57-c95e0b1e0007"
    /// Notify: wearer → follower frames.
    public static let tx = "7c6a0003-5c3e-4f0a-9a57-c95e0b1e0007"
}

/// Frame = `[msgId u16][idx u16][count u16]` big-endian + payload; per frame at most `min(mtu − 3, 512) − 6` bytes.
public enum BleFraming {
    public static let header = 6
    static let maxAttribute = 512
    static let minPayload = 14

    /// Splits `payload` for an ATT `mtu` (an attribute value is at most 512 bytes even with MTU 517).
    public static func frames(msgId: UInt16, payload: Data, mtu: Int) -> [Data] {
        frames(msgId: msgId, payload: payload, perFrame: min(mtu - 3, maxAttribute) - header)
    }

    /// Splits `payload` into frames carrying `perFrame` payload bytes each (CoreBluetooth reports the writable length
    /// directly: `maximumWriteValueLength − 6`).
    public static func frames(msgId: UInt16, payload: Data, perFrame: Int) -> [Data] {
        let per = max(perFrame, minPayload)
        let bytes = [UInt8](payload)
        let count = max(1, (bytes.count + per - 1) / per)
        precondition(count <= Int(UInt16.max), "BLE message too large")
        return (0..<count).map { idx in
            var frame = Data(capacity: header + per)
            for value in [msgId, UInt16(idx), UInt16(count)] { frame.append(contentsOf: [UInt8(value >> 8), UInt8(value & 0xff)]) }
            frame.append(contentsOf: bytes[min(bytes.count, idx * per)..<min(bytes.count, (idx + 1) * per)])
            return frame
        }
    }
}

/// Per-peer reassembly with the B§4.3 limits: a buffer idle 30 s is dropped, a message over 256 KiB is dropped (its
/// later frames swallowed), at most 16 buffers are open (the oldest goes).
public struct BleReassembler: Sendable {
    public static let maxMessage = 256 * 1024
    public static let maxBuffers = 16
    public static let idle: TimeInterval = 30

    private struct Buffer {
        let count: Int
        var touched: Date
        var parts: [Int: Data] = [:]
        var size = 0
        var dead = false
    }

    private var buffers: [UInt16: Buffer] = [:]
    private var order: [UInt16] = []
    /// Messages dropped for a limit, for logs and tests.
    public private(set) var dropped = 0

    public init() {}

    /// The whole message once its last missing frame arrives; nil until then, for an invalid frame or a dropped one.
    public mutating func add(_ frame: Data, now: Date) -> Data? {
        let b = [UInt8](frame)
        guard b.count >= BleFraming.header else { return nil }
        let msgId = UInt16(b[0]) << 8 | UInt16(b[1])
        let idx = Int(UInt16(b[2]) << 8 | UInt16(b[3]))
        let count = Int(UInt16(b[4]) << 8 | UInt16(b[5]))
        guard count > 0, idx < count else { return nil }
        for (id, buffer) in buffers where now.timeIntervalSince(buffer.touched) > Self.idle {
            if !buffer.dead { dropped += 1 }
            remove(id)
        }
        if idx == 0 || buffers[msgId]?.count != count {
            remove(msgId)
            if buffers.count >= Self.maxBuffers, let oldest = order.first { remove(oldest) }
            buffers[msgId] = Buffer(count: count, touched: now)
            order.append(msgId)
        }
        guard var buffer = buffers[msgId] else { return nil }
        buffer.touched = now
        guard !buffer.dead else {
            buffers[msgId] = buffer
            return nil
        }
        let part = Data(b[BleFraming.header...])
        buffer.size += part.count - (buffer.parts.updateValue(part, forKey: idx)?.count ?? 0)
        if buffer.size > Self.maxMessage {
            buffer.dead = true
            buffer.parts = [:]
            dropped += 1
            buffers[msgId] = buffer
            return nil
        }
        guard buffer.parts.count == count else {
            buffers[msgId] = buffer
            return nil
        }
        remove(msgId)
        return (0..<count).reduce(into: Data()) { $0.append(buffer.parts[$1]!) }
    }

    private mutating func remove(_ id: UInt16) {
        buffers[id] = nil
        order.removeAll { $0 == id }
    }
}

/// What the wearer's phone says in INFO: `nonce` + `m` always; `i`, `relays`, `pair` only while "Pair nearby" is open.
public struct BleInfo: Equatable, Sendable {
    public struct Pairing: Equatable, Sendable {
        public let token: String
        public let expiry: Int
        public let profile: Profile?
    }

    public let nonce: String?
    public let m: String?
    public let identity: String?
    public let relays: [String]
    public let pairing: Pairing?

    /// Whose phone sent this INFO (B§6).
    public enum Owner: Equatable, Sendable {
        /// Our wearer's: `i` is our identity, or `m` proves our `sid` over one of our HELLO nonces.
        case ours
        /// Another identity, or a missing / wrong proof while we know the `sid`.
        case other
        /// No `sid` yet (request pending) and no `i`: receive, don't write.
        case unknown
    }

    public func owner(identity ours: String?, sid: String?, nonces: [String]) -> Owner {
        if let ours, let identity { return identity == ours ? .ours : .other }
        guard let sid else { return .unknown }
        guard let nonce, nonces.contains(nonce), m == BleMessage.infoM(sid: sid, nonce: nonce) else { return .other }
        return .ours
    }

    /// The invite a nearby pairing offer stands for (the token is the invite token; the code is checked by the wearer).
    public var invite: Invite? {
        guard let identity, Hex.isKey(identity), let pairing else { return nil }
        return Invite(
            identity: identity, token: pairing.token, expiry: pairing.expiry, relays: relays,
            profile: pairing.profile)
    }
}

/// BLE share messages: the UTF-8 of a JSON array (B§3).
public enum BleMessage: Equatable, Sendable {
    case hello(nonce: String, id: String?)
    case info(BleInfo)
    case event(SignedEvent)

    /// `m = H("cgm2:ble:" + sid + ":" + nonce)`: the phone proves it holds the stream's `sid` without naming it.
    public static func infoM(sid: String, nonce: String) -> String { Wire.h("cgm2:ble:\(sid):\(nonce)") }

    public func encoded() -> Data {
        switch self {
        case .hello(let nonce, let id):
            var body: [String: Any] = ["v": Wire.version, "nonce": nonce]
            if let id { body["id"] = id }
            return Data(JSONText.encode(["HELLO", body]).utf8)
        case .event(let event):
            return Data("[\"EVENT\",\(event.json())]".utf8)
        case .info(let info):
            var body: [String: Any] = ["v": Wire.version]
            if let nonce = info.nonce { body["nonce"] = nonce }
            if let m = info.m { body["m"] = m }
            if let identity = info.identity { body["i"] = identity }
            if !info.relays.isEmpty { body["relays"] = info.relays }
            if let pairing = info.pairing {
                var pair: [String: Any] = ["t": pairing.token, "x": pairing.expiry]
                if let profile = pairing.profile { pair["profile"] = profile.wireObject() }
                body["pair"] = pair
            }
            return Data(JSONText.encode(["INFO", body]).utf8)
        }
    }

    /// nil for anything that isn't a well-formed `[type, body]` this Mac understands.
    public static func parse(_ data: Data) -> BleMessage? {
        guard let array = try? JSONSerialization.jsonObject(with: data) as? [Any], array.count > 1,
            let type = array[0] as? String
        else { return nil }
        switch type {
        case "EVENT":
            guard let object = array[1] as? [String: Any] else { return nil }
            return SignedEvent.decode(json: JSONText.encode(object)).map(BleMessage.event)
        case "INFO":
            guard let o = array[1] as? [String: Any] else { return nil }
            let pair = o["pair"] as? [String: Any]
            let pairing = pair.flatMap { p -> BleInfo.Pairing? in
                guard let t = p["t"] as? String, let x = (p["x"] as? NSNumber)?.intValue else { return nil }
                return .init(
                    token: t, expiry: x, profile: (p["profile"] as? [String: Any]).flatMap(Profile.init(wire:)))
            }
            return .info(
                BleInfo(
                    nonce: o["nonce"] as? String, m: o["m"] as? String, identity: o["i"] as? String,
                    relays: Relays.secure(o["relays"]), pairing: pairing))
        case "HELLO":
            guard let o = array[1] as? [String: Any], let nonce = o["nonce"] as? String else { return nil }
            return .hello(nonce: nonce, id: o["id"] as? String)
        default: return nil
        }
    }
}

/// The HELLO identity proof (B§5): the follower key's BIP-340 signature of a kind-21080 event (created_at 0, no tags,
/// never sent) whose content is `H("cgm2:ble-id:" + sid + ":" + nonce + ":" + dev)`; this Mac's `dev` is `mac`.
public enum HelloProof {
    public static let device = "mac"

    public static func content(sid: String, nonce: String, dev: String = device) -> String {
        Wire.h("cgm2:ble-id:\(sid):\(nonce):\(dev)")
    }

    public static func sign(sid: String, nonce: String, follower: SecretKeyHex, crypto: any NostrCrypto)
        throws(CryptoError) -> String
    {
        try crypto.sign(
            kind: Wire.kindBleId, content: content(sid: sid, nonce: nonce), tags: [], createdAt: 0, with: follower
        ).sig
    }
}
