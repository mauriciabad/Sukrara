// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation
import NostrSDK

/// `NostrCrypto` on rust-nostr 0.45.1 (nostr-sdk-swift, the same core Android uses). The only file importing NostrSDK.
public struct NostrSDKCrypto: NostrCrypto {
    public init() {}

    public func generateSecret() -> SecretKeyHex {
        SecretKeyHex(unchecked: Keys.generate().secretKey().toHex())
    }

    public func publicKey(of secret: SecretKeyHex) throws(CryptoError) -> String {
        try keys(secret).publicKey().toHex()
    }

    public func verify(_ event: SignedEvent) -> Bool {
        guard let parsed = try? Event.fromJson(json: event.json()) else { return false }
        return parsed.verify() && parsed.id().toHex() == event.id
    }

    public func sign(kind: UInt16, content: String, tags: [[String]], createdAt: Int, with secret: SecretKeyHex)
        throws(CryptoError) -> SignedEvent
    {
        let signer = try keys(secret)
        return try wrapErrors {
            let event = try EventBuilder(kind: Kind(kind: kind), content: content)
                .tags(tags: tags.map { try Tag.parse(data: $0) })
                .customCreatedAt(createdAt: Timestamp.fromSecs(secs: UInt64(max(0, createdAt))))
                .finalize(signer: signer)
            return try Self.signedEvent(event)
        }
    }

    public func nip44Encrypt(_ plaintext: String, from secret: SecretKeyHex, to publicKey: String)
        throws(CryptoError) -> String
    {
        let sender = try keys(secret)
        return try wrapErrors {
            try NostrSDK.nip44Encrypt(
                secretKey: sender.secretKey(), publicKey: PublicKey.parse(publicKey: publicKey), content: plaintext,
                version: .v2)
        }
    }

    public func nip44Decrypt(_ payload: String, with secret: SecretKeyHex, from publicKey: String)
        throws(CryptoError) -> String
    {
        let receiver = try keys(secret)
        return try wrapErrors {
            try NostrSDK.nip44Decrypt(
                secretKey: receiver.secretKey(), publicKey: PublicKey.parse(publicKey: publicKey), payload: payload)
        }
    }

    public func giftWrap(kind: UInt16, content: String, from sender: SecretKeyHex, to recipient: String, expiration: Int)
        throws(CryptoError) -> SignedEvent
    {
        let signer = try keys(sender)
        return try wrapErrors {
            let rumor = EventBuilder(kind: Kind(kind: kind), content: content)
                .finalizeUnsigned(publicKey: signer.publicKey())
            let wrap = try nip59MakeGiftWrap(
                signer: signer, receiverPubkey: PublicKey.parse(publicKey: recipient), rumor: rumor,
                extraTags: [Tag.parse(data: ["expiration", String(expiration)])])
            return try Self.signedEvent(wrap)
        }
    }

    public func unwrap(_ wrap: SignedEvent, with recipient: SecretKeyHex) throws(CryptoError) -> Rumor {
        let receiver = try keys(recipient)
        return try wrapErrors {
            let event = try Event.fromJson(json: wrap.json())
            let gift = try UnwrappedGift.fromGiftWrap(signer: receiver, giftWrap: event)
            let rumor = gift.rumor()
            return Rumor(
                sender: gift.sender().toHex(), kind: rumor.kind().asU16(), content: rumor.content(),
                createdAt: Int(rumor.createdAt().asSecs()))
        }
    }

    public func nsecDecode(_ nsec: String) throws(CryptoError) -> SecretKeyHex {
        guard nsec.hasPrefix("nsec1") else { throw .badKey }
        return SecretKeyHex(unchecked: try keys(nsec).secretKey().toHex())
    }

    public func nsecEncode(_ secret: SecretKeyHex) throws(CryptoError) -> String {
        let k = try keys(secret)
        return try wrapErrors { try k.secretKey().toBech32() }
    }

    // MARK: - helpers

    private func keys(_ secret: SecretKeyHex) throws(CryptoError) -> Keys { try keys(secret.hex) }

    private func keys(_ text: String) throws(CryptoError) -> Keys {
        do { return try Keys.parse(secretKey: text) } catch { throw .badKey }
    }

    private func wrapErrors<T>(_ body: () throws -> T) throws(CryptoError) -> T {
        do { return try body() } catch { throw .failed }
    }

    private static func signedEvent(_ event: Event) throws -> SignedEvent {
        guard let parsed = SignedEvent.decode(json: try event.asJson()) else { throw CryptoError.failed }
        return parsed
    }
}
