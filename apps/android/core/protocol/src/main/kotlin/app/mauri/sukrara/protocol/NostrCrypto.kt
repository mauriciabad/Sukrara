// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.protocol

/**
 * Every cryptographic operation the protocol needs, implemented outside this module by a vetted library: Android
 * `:share` wraps `org.nostrdevkit:nostr-sdk` (the same rust-nostr core the Mac uses), the unit tests wrap its JVM
 * build. This module never implements a primitive itself. Implementations must be thread-safe.
 */
interface NostrCrypto {
    fun generate(): KeyPair

    /** The key pair of [secret]; null if it isn't a valid secp256k1 scalar. */
    fun keyPair(secret: SecretKey): KeyPair?

    /** NIP-01: computes the id and the BIP-340 signature. */
    fun sign(signer: KeyPair, event: UnsignedEvent): SignedEvent

    /** NIP-01: the id matches the content and the signature matches the id and pubkey. */
    fun verify(event: SignedEvent): Boolean

    /** NIP-44 v2. */
    fun encrypt(sender: SecretKey, recipient: PublicKeyHex, plaintext: String): String

    /** NIP-44 v2; null if it doesn't decrypt (wrong key, tampered, malformed). */
    fun decrypt(recipient: SecretKey, sender: PublicKeyHex, payload: String): String?

    /**
     * NIP-59: rumor (unsigned, author = sender, created at [rumorCreatedAt]) → seal signed by [sender] → kind-1059 wrap
     * signed by a one-time key, `p`-tagged to [recipient], with an absolute NIP-40 [expiration]. The seal's and wrap's
     * own `created_at` are randomised into the past by the implementation, as NIP-59 says.
     */
    fun giftWrap(sender: KeyPair, recipient: PublicKeyHex, rumorKind: Int, rumorContent: String, rumorCreatedAt: Long, expiration: Long): SignedEvent

    /** Opens a gift wrap addressed to [recipient]; null if it isn't ours, doesn't open, or the seal is invalid. */
    fun unwrap(recipient: SecretKey, wrap: SignedEvent): Unwrapped?

    fun nsec(secret: SecretKey): String

    /** nsec1… → key; null if not a valid nsec. */
    fun parseNsec(nsec: String): SecretKey?

    fun npub(key: PublicKeyHex): String

    /** NIP-49 with the given scrypt cost and key-security byte. */
    fun ncryptsec(secret: SecretKey, password: String, logN: Int, keySecurity: Int): String

    /** NIP-49; null for a wrong password or a malformed ncryptsec. */
    fun parseNcryptsec(ncryptsec: String, password: String): SecretKey?
}

/** An opened gift wrap: [sender] is the seal's signer, which the implementation authenticated (and = rumor author). */
data class Unwrapped(val sender: PublicKeyHex, val kind: Int, val content: String, val createdAt: Long)
