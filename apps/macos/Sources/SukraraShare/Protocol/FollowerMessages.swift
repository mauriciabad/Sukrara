// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation

/// Builds what a follower sends (PROTOCOL-v2 "Events"): gift wraps to the identity (sealed by `F`) and, as a writer,
/// treatment events. Bodies and TTLs are wire-identical to Sukrara 0.11.
public struct FollowerMessages: Sendable {
    let crypto: any NostrCrypto
    let follower: SecretKeyHex

    public init(crypto: any NostrCrypto, follower: SecretKeyHex) {
        self.crypto = crypto
        self.follower = follower
    }

    public static func requestBody(token: String, profile: Profile) -> [String: Any] {
        ["v": Wire.version, "type": "request", "token": token, "profile": profile.wireObject()]
    }

    public static func profileBody(_ profile: Profile) -> [String: Any] {
        ["v": Wire.version, "type": "profile", "profile": profile.wireObject()]
    }

    public static func rekeyBody(unknownEpoch gk: String) -> [String: Any] {
        ["v": Wire.version, "type": "rekey", "have": gk]
    }

    public static func historyBody(since hourStart: Int) -> [String: Any] {
        ["v": Wire.version, "type": "history", "since": hourStart]
    }

    /// A rumor (kind 30500) to the identity, wrapped with an absolute expiration of now + `ttl`.
    public func toOwner(_ body: [String: Any], identity: String, ttl: Int, now: Int) throws(CryptoError) -> SignedEvent
    {
        try crypto.giftWrap(
            kind: Wire.kindToOwner, content: JSONText.encode(body), from: follower, to: identity, expiration: now + ttl)
    }

    /// A writer's treatment: kind 30078 signed by `F`, tags `d, c, gk, expiration` (created_at + 30 d), content
    /// NIP-44 from `F` to the newest read key. `o` is always `follower`. `createdAt` must strictly increase per id
    /// (relays drop a same-second replacement) — the caller passes `max(now, last + 1)`.
    public func treatment(
        _ entry: TreatmentPayload, sid: String, groupSecret: SecretKeyHex, createdAt: Int
    ) throws(CryptoError) -> SignedEvent {
        var body = entry
        body.origin = "follower"
        let groupPublic = try crypto.publicKey(of: groupSecret)
        let content = try crypto.nip44Encrypt(JSONText.encode(body.wireObject()), from: follower, to: groupPublic)
        let tags = [
            ["d", Wire.treatmentD(sid: sid, id: entry.id)], ["c", Wire.treatmentsC(sid: sid)],
            ["gk", Wire.gk(groupPublicKey: groupPublic)], ["expiration", String(createdAt + Wire.ttlTreatment)],
        ]
        return try crypto.sign(kind: Wire.kindData, content: content, tags: tags, createdAt: createdAt, with: follower)
    }
}

/// Compact JSON with sorted keys and unescaped slashes, as both apps write it.
public enum JSONText {
    public static func encode(_ object: Any) -> String {
        let options: JSONSerialization.WritingOptions = [.sortedKeys, .withoutEscapingSlashes, .fragmentsAllowed]
        return String(
            decoding: (try? JSONSerialization.data(withJSONObject: object, options: options)) ?? Data(), as: UTF8.self)
    }
}
