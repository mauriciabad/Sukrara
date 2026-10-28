// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.protocol

/** An x-only secp256k1 public key as 64 lowercase hex characters (the Nostr wire form). */
@JvmInline
value class PublicKeyHex(val hex: String) {
    init {
        require(Hex.isKey(hex)) { "not a 64-hex public key" }
    }

    fun bytes(): ByteArray = Hex.decode(hex)!!

    /** First 8 bytes as hex: what an invite link carries. */
    val fingerprint: String get() = hex.substring(0, 16)

    override fun toString() = hex

    companion object {
        fun parse(s: String?): PublicKeyHex? = s?.takeIf(Hex::isKey)?.let(::PublicKeyHex)
    }
}

/**
 * A secp256k1 secret key (identity I, device D, read key G_n, follower F, invite key). Never printed: [toString] is
 * redacted, and the hex is only reachable through [revealHex] for storage and the crypto implementation.
 */
class SecretKey private constructor(private val hex: String) {
    fun revealHex(): String = hex

    override fun equals(other: Any?) = other is SecretKey && other.hex == hex

    override fun hashCode() = hex.hashCode()

    override fun toString() = "SecretKey(redacted)"

    companion object {
        /** 64 lowercase hex characters; range checks (0 < k < n) are the crypto implementation's. */
        fun ofHex(hex: String): SecretKey? = hex.takeIf(Hex::isKey)?.let(::SecretKey)

        fun ofBytes(bytes: ByteArray): SecretKey? = if (bytes.size == 32) SecretKey(Hex.encode(bytes)) else null
    }
}

/** A secret key with its public key, as the codecs need both. */
class KeyPair(val secret: SecretKey, val public: PublicKeyHex) {
    override fun toString() = "KeyPair($public)"
}
