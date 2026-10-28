// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation

/// The protocol over one Bluetooth connection to a wearer's phone, without CoreBluetooth (so tests drive it with a
/// fake peripheral): HELLO with a fresh nonce on connect (and when the link goes silent), INFO checked against our
/// nonces, EVENTs handed out, frames written one at a time (the next after the write response). Nothing is written
/// to a phone before its INFO proved it's our wearer's (`owner == .ours`).
public struct NearbyLink: Sendable {
    /// Who we are, as far as the follower state knows (nil fields before joining / before the first key).
    public struct Identity: Equatable, Sendable {
        public var identity: String?
        public var sid: String?
        public var follower: SecretKeyHex?

        public init(identity: String? = nil, sid: String? = nil, follower: SecretKeyHex? = nil) {
            self.identity = identity
            self.sid = sid
            self.follower = follower
        }
    }

    public enum Effect: Equatable, Sendable {
        /// Write this frame to RX (with response).
        case write(Data)
        case event(SignedEvent)
        case info(BleInfo, BleInfo.Owner)
        /// The message sent with `token` reached the phone (true) or can't be sent (false).
        case sent(token: Int, ok: Bool)
    }

    private struct Pending: Sendable {
        let frame: Data
        let token: Int?
    }

    public var identity: Identity
    private let crypto: any NostrCrypto
    private var perFrame: Int?
    private var reassembler = BleReassembler()
    private var outbox: [Pending] = []
    private var inFlight: Pending?
    private var msgId: UInt16 = 0
    /// Our last HELLO nonces: an INFO must prove `sid` over one of them.
    public private(set) var nonces: [String] = []
    /// The phone proved it's our wearer's.
    public private(set) var verified = false

    public init(identity: Identity, crypto: any NostrCrypto) {
        self.identity = identity
        self.crypto = crypto
    }

    public var isConnected: Bool { perFrame != nil }

    /// Notifications are on: start the protocol. `perFrame` = writable bytes per frame minus the 6-byte header.
    public mutating func connected(perFrame: Int, nonce: String) -> [Effect] {
        self.perFrame = perFrame
        return hello(nonce: nonce)
    }

    /// HELLO with `nonce` (the last 4 are kept) and, once we know `sid`, the identity proof.
    public mutating func hello(nonce: String) -> [Effect] {
        nonces = Array((nonces + [nonce]).suffix(4))
        var proof: String?
        if let sid = identity.sid, let follower = identity.follower {
            proof = try? HelloProof.sign(sid: sid, nonce: nonce, follower: follower, crypto: crypto)
        }
        return enqueue(BleMessage.hello(nonce: nonce, id: proof).encoded(), token: nil)
    }

    /// An EVENT for the wearer's phone; refused (`sent false`) until the phone proved it's the wearer's.
    public mutating func send(_ event: SignedEvent, token: Int) -> [Effect] {
        guard isConnected, verified else { return [.sent(token: token, ok: false)] }
        return enqueue(BleMessage.event(event).encoded(), token: token)
    }

    /// A notification on TX.
    public mutating func received(_ frame: Data, now: Date) -> [Effect] {
        guard let whole = reassembler.add(frame, now: now), let message = BleMessage.parse(whole) else { return [] }
        switch message {
        case .event(let event): return [.event(event)]
        case .info(let info):
            let owner = info.owner(identity: identity.identity, sid: identity.sid, nonces: nonces)
            verified = owner == .ours
            return [.info(info, owner)]
        case .hello: return []
        }
    }

    /// The write response for the frame in flight.
    public mutating func wrote(ok: Bool) -> [Effect] {
        var effects: [Effect] = []
        if let token = inFlight?.token { effects.append(.sent(token: token, ok: ok)) }
        inFlight = nil
        return effects + pump()
    }

    /// The connection is gone: everything queued fails.
    public mutating func disconnected() -> [Effect] {
        let failed = ([inFlight].compactMap { $0 } + outbox).compactMap(\.token)
        perFrame = nil
        outbox = []
        inFlight = nil
        verified = false
        reassembler = BleReassembler()
        return failed.map { .sent(token: $0, ok: false) }
    }

    private mutating func enqueue(_ payload: Data, token: Int?) -> [Effect] {
        guard let perFrame else { return token.map { [.sent(token: $0, ok: false)] } ?? [] }
        msgId &+= 1
        let frames = BleFraming.frames(msgId: msgId, payload: payload, perFrame: perFrame)
        for (i, frame) in frames.enumerated() {
            outbox.append(Pending(frame: frame, token: i == frames.count - 1 ? token : nil))
        }
        return pump()
    }

    private mutating func pump() -> [Effect] {
        guard inFlight == nil, !outbox.isEmpty else { return [] }
        let next = outbox.removeFirst()
        inFlight = next
        return [.write(next.frame)]
    }
}
