// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import CryptoKit
import Foundation

/// Constants and tag derivations of CGM-over-Nostr protocol v2 (contracts/protocol/PROTOCOL-v2.md). Wire-compatible
/// with Sukrara 0.11 (the showcase): changing anything here breaks following an existing wearer.
public enum Wire {
    // Event kinds.
    public static let kindLive: UInt16 = 21078
    public static let kindAlert: UInt16 = 21079
    public static let kindData: UInt16 = 30078
    public static let kindWrap: UInt16 = 1059
    public static let kindToOwner: UInt16 = 30500
    public static let kindToFollower: UInt16 = 30501
    public static let kindInvite: UInt16 = 30502
    public static let kindAuth: UInt16 = 22242
    public static let kindBleId: UInt16 = 21080

    /// Version inside every v2 content body (treatments carry their own `v`: 1).
    public static let version = 2
    public static let treatmentVersion = 1

    // Gift-wrap TTLs (absolute expiration = now + TTL), seconds.
    public static let ttlRequest = 7 * 86_400
    public static let ttlHistory = 86_400
    public static let ttlTreatment = 30 * 86_400

    /// A follower drops an alert whose `at` is older than this (seconds).
    public static let alertMaxAge = 600
    /// A reading further than this in the future is refused (seconds).
    public static let futureTolerance = 300
    /// One rekey request per unknown epoch per this many seconds.
    public static let rekeyInterval = 300

    /// The only default relays (Sukrara 0.11 set, measured 2026-10-17: take new keys' events and serve gift wraps).
    public static let defaultRelays = [
        "wss://nos.lol", "wss://relay.primal.net", "wss://nostr.mom", "wss://relay.snort.social",
    ]

    /// Invite link prefixes (PROTOCOL-identity "Short invites"): the web link and the app's own scheme.
    public static let inviteBase = "https://sukrara.mauri.app/join#"
    public static let inviteScheme = "sukrara:join#"

    /// `H(x) = hex(sha256(x))[:32]`.
    public static func h(_ text: String) -> String { String(Hex.encode(SHA256.hash(data: Data(text.utf8))).prefix(32)) }

    /// `gk = hex(sha256(G.pub bytes))[:8]`, the epoch name in tags.
    public static func gk(groupPublicKey: String) -> String {
        String(Hex.encode(SHA256.hash(data: Data(Hex.decode(groupPublicKey) ?? []))).prefix(8))
    }

    public static func rosterD(sid: String) -> String { h("cgm2:roster:\(sid)") }
    public static func glucoseC(sid: String) -> String { h("cgm2:glucose:\(sid)") }
    public static func chunkD(sid: String, hour: Int) -> String { h("cgm2:h:\(sid):\(hour)") }
    public static func treatmentsC(sid: String) -> String { h("cgm2:treatments:\(sid)") }
    public static func treatmentD(sid: String, id: String) -> String { h("cgm2:tr:\(sid):\(id)") }
    public static func settingsD(sid: String) -> String { h("cgm2:settings:\(sid)") }
    public static func backupD(identity: String) -> String { h("cgm2:backup:\(identity)") }

    /// `int(sha256(F.pub_hex + token)) mod 10000`, zero-padded: what the wearer types for a nearby pairing.
    public static func matchCode(followerPublicKey: String, token: String) -> String {
        let digest = SHA256.hash(data: Data((followerPublicKey + token).utf8))
        let value = digest.reduce(0) { ($0 * 256 + Int($1)) % 10_000 }
        return String(format: "%04d", value)
    }
}
