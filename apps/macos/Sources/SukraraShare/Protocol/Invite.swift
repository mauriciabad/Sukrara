// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation

/// The v3 short invite link (PROTOCOL-identity "Short invites"):
/// `https://sukrara.mauri.app/join#<base64url(I.pub[0:8] ‖ secret[16])>` or the app's own `sukrara:join#<32>`.
/// Nothing else is an invite.
public struct InviteLink: Equatable, Sendable {
    /// First 8 bytes of the wearer's identity key, hex.
    public let fingerprint: String
    public let secret: [UInt8]

    /// The request token `t` = base64url(secret).
    public var token: String { Base64URL.encode(secret) }

    public static func parse(_ text: String) -> InviteLink? {
        let s = text.trimmingCharacters(in: .whitespacesAndNewlines)
        let code: Substring
        if s.hasPrefix(Wire.inviteBase) {
            code = s.dropFirst(Wire.inviteBase.count)
        } else if s.hasPrefix(Wire.inviteScheme) {
            code = s.dropFirst(Wire.inviteScheme.count)
        } else {
            return nil
        }
        guard code.count == 32,
            code.unicodeScalars.allSatisfy({ $0.isASCII && (CharacterSet.alphanumerics.contains($0) || $0 == "-" || $0 == "_") }),
            let raw = Base64URL.decode(String(code)), raw.count == 24
        else { return nil }
        return InviteLink(fingerprint: Hex.encode(raw[0..<8]), secret: Array(raw[8..<24]))
    }

    public var url: String { Wire.inviteBase + Base64URL.encode((Hex.decode(fingerprint) ?? []) + secret) }

    /// The key the invite's gift wrap is addressed to.
    public var inviteKey: SecretKeyHex { Derive.inviteKey(secret: secret) }
}

/// What a follower needs to ask for access: identity, token, expiry, relays and the wearer's profile.
public struct Invite: Equatable, Sendable {
    public let identity: String
    public let token: String
    public let expiry: Int
    public let relays: [String]
    public let profile: Profile?

    public init(identity: String, token: String, expiry: Int, relays: [String], profile: Profile?) {
        self.identity = identity
        self.token = token
        self.expiry = expiry
        self.relays = relays
        self.profile = profile
    }
}

public enum InviteFailure: String, Error, Equatable, Sendable {
    /// The wrap doesn't open with this link's key, or holds something else.
    case notAnInvite = "not-an-invite"
    /// Sealed by a key that doesn't start with the link's fingerprint (someone pointing at their own identity).
    case fingerprintMismatch = "fingerprint-mismatch"
    case expired
}

/// Opens invite gift wraps (fetched from relays for `#p = inviteKey.pub` by the caller).
public struct InviteOpener: Sendable {
    let crypto: any NostrCrypto

    public init(crypto: any NostrCrypto) { self.crypto = crypto }

    public func open(_ wrap: SignedEvent, link: InviteLink, now: Int) -> Result<Invite, InviteFailure> {
        guard wrap.kind == Wire.kindWrap, crypto.verify(wrap),
            let rumor = try? crypto.unwrap(wrap, with: link.inviteKey), rumor.kind == Wire.kindInvite,
            let body = try? JSONObject(json: rumor.content), body.string("type") == "invite",
            (try? body.int("v")) == 3, let expiry = try? body.int("x")
        else { return .failure(.notAnInvite) }
        guard rumor.sender.hasPrefix(link.fingerprint) else { return .failure(.fingerprintMismatch) }
        guard expiry > now else { return .failure(.expired) }
        return .success(
            Invite(
                identity: rumor.sender, token: link.token, expiry: expiry, relays: Relays.secure(body.raw["r"]),
                profile: body.object("profile").flatMap(Profile.init(wire:))))
    }

    /// The first wrap that opens; otherwise the most telling failure (fingerprint > expired > not an invite).
    public func open(_ wraps: [SignedEvent], link: InviteLink, now: Int) -> Result<Invite, InviteFailure> {
        var failures: Set<InviteFailure> = []
        for wrap in wraps {
            switch open(wrap, link: link, now: now) {
            case .success(let invite): return .success(invite)
            case .failure(let why): failures.insert(why)
            }
        }
        for why in [InviteFailure.fingerprintMismatch, .expired] where failures.contains(why) { return .failure(why) }
        return .failure(.notAnInvite)
    }
}
