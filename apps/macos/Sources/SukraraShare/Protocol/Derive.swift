// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import CryptoKit
import Foundation

/// Key derivation of PROTOCOL-identity ("Derivation"): RFC 5869 HKDF-SHA256 (CryptoKit), retried with a counter byte
/// until the output is a valid secp256k1 secret (1 ≤ k < n).
public enum Derive {
    /// secp256k1 group order n, big endian.
    private static let order: [UInt8] = Hex.decode("fffffffffffffffffffffffffffffffebaaedce6af48a03bbfd25e8cd0364141")!

    public static func scalar(ikm: [UInt8], salt: String, info: String) -> SecretKeyHex {
        for counter in 0...255 {
            let infoBytes = Array(info.utf8) + (counter == 0 ? [] : [UInt8(counter)])
            let key = HKDF<SHA256>.deriveKey(
                inputKeyMaterial: SymmetricKey(data: ikm), salt: Array(salt.utf8), info: infoBytes, outputByteCount: 32)
            let bytes = key.withUnsafeBytes { Array($0) }
            if bytes.contains(where: { $0 != 0 }), bytes.lexicographicallyPrecedes(order) {
                return SecretKeyHex(unchecked: Hex.encode(bytes))
            }
        }
        // 256 consecutive HKDF outputs ≥ n has probability ~2^-32768.
        preconditionFailure("HKDF never produced a secp256k1 scalar")
    }

    /// The key an invite's gift wrap is addressed to; anyone holding the link can derive it.
    public static func inviteKey(secret: [UInt8]) -> SecretKeyHex {
        scalar(ikm: secret, salt: "sukrara/invite/v3", info: "wrap-recipient")
    }
}
