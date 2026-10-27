// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation

/// The rumor inside a NIP-59 gift wrap and who sealed it (the authenticated sender).
public struct Rumor: Equatable, Sendable {
    public let sender: String
    public let kind: UInt16
    public let content: String
    public let createdAt: Int
}

public enum CryptoError: Error, Equatable, Sendable {
    case badKey
    case failed
}

/// Every cryptographic operation the protocol needs, behind one seam. The app uses `NostrSDKCrypto` (rust-nostr, the
/// same core as Android); protocol code never imports NostrSDK.
public protocol NostrCrypto: Sendable {
    func generateSecret() -> SecretKeyHex
    func publicKey(of secret: SecretKeyHex) throws(CryptoError) -> String
    /// id matches the content and the BIP-340 signature is the author's.
    func verify(_ event: SignedEvent) -> Bool
    func sign(kind: UInt16, content: String, tags: [[String]], createdAt: Int, with secret: SecretKeyHex)
        throws(CryptoError) -> SignedEvent
    func nip44Encrypt(_ plaintext: String, from secret: SecretKeyHex, to publicKey: String) throws(CryptoError) -> String
    func nip44Decrypt(_ payload: String, with secret: SecretKeyHex, from publicKey: String) throws(CryptoError) -> String
    /// NIP-59: rumor `kind`/`content` by `sender`, sealed by `sender`, wrapped to `recipient`, with an **absolute**
    /// NIP-40 expiration (NIP-59 backdates created_at by up to 2 days, so a relative one could arrive expired).
    func giftWrap(kind: UInt16, content: String, from sender: SecretKeyHex, to recipient: String, expiration: Int)
        throws(CryptoError) -> SignedEvent
    /// Opens a gift wrap addressed to `recipient`; throws if it isn't for that key or is malformed.
    func unwrap(_ wrap: SignedEvent, with recipient: SecretKeyHex) throws(CryptoError) -> Rumor
    func nsecDecode(_ nsec: String) throws(CryptoError) -> SecretKeyHex
    func nsecEncode(_ secret: SecretKeyHex) throws(CryptoError) -> String
}
