// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.share.crypto

import app.mauri.sukrara.protocol.KeyPair
import app.mauri.sukrara.protocol.NostrCrypto
import app.mauri.sukrara.protocol.PublicKeyHex
import app.mauri.sukrara.protocol.SecretKey
import app.mauri.sukrara.protocol.SignedEvent
import app.mauri.sukrara.protocol.UnsignedEvent
import app.mauri.sukrara.protocol.Unwrapped
import org.nostrdevkit.sdk.EncryptedSecretKey
import org.nostrdevkit.sdk.Event
import org.nostrdevkit.sdk.EventBuilder
import org.nostrdevkit.sdk.KeySecurity
import org.nostrdevkit.sdk.Keys
import org.nostrdevkit.sdk.Kind
import org.nostrdevkit.sdk.Nip44Version
import org.nostrdevkit.sdk.PublicKey
import org.nostrdevkit.sdk.Tag
import org.nostrdevkit.sdk.Timestamp
import org.nostrdevkit.sdk.UnwrappedGift
import org.nostrdevkit.sdk.nip44Decrypt
import org.nostrdevkit.sdk.nip44Encrypt
import org.nostrdevkit.sdk.nip59MakeGiftWrap
import org.nostrdevkit.sdk.SecretKey as SdkSecretKey

/**
 * [NostrCrypto] over `org.nostrdevkit:nostr-sdk` (rust-nostr, the same core the Mac uses): NIP-01 signing, NIP-44 v2,
 * NIP-59 gift wraps, bech32 and NIP-49. Native handles are closed after every call. Passes `NostrCryptoContractTest`.
 */
class SdkNostrCrypto : NostrCrypto {
    private inline fun <R> keys(s: SecretKey, block: (Keys) -> R): R = Keys.parse(s.revealHex()).use(block)

    private inline fun <R> sdkSecret(s: SecretKey, block: (SdkSecretKey) -> R): R = SdkSecretKey.parse(s.revealHex()).use(block)

    override fun generate(): KeyPair =
        Keys.generate().use { k ->
            KeyPair(SecretKey.ofHex(k.secretKey().toHex())!!, PublicKeyHex(k.publicKey().toHex()))
        }

    override fun keyPair(secret: SecretKey): KeyPair? =
        runCatching { keys(secret) { KeyPair(secret, PublicKeyHex(it.publicKey().toHex())) } }.getOrNull()

    override fun sign(signer: KeyPair, event: UnsignedEvent): SignedEvent =
        keys(signer.secret) { k ->
            EventBuilder(Kind(event.kind.toUShort()), event.content)
                .tags(event.tags.map { Tag.parse(it) })
                .customCreatedAt(Timestamp.fromSecs(event.createdAt.toULong()))
                .finalize(k)
                .use { SignedEvent.parse(it.asJson())!! }
        }

    override fun verify(event: SignedEvent): Boolean =
        runCatching { Event.fromJson(event.toJson()).use { it.verify() } }.getOrDefault(false)

    override fun encrypt(sender: SecretKey, recipient: PublicKeyHex, plaintext: String): String =
        sdkSecret(sender) { s ->
            PublicKey.parse(recipient.hex).use { p -> nip44Encrypt(s, p, plaintext, Nip44Version.V2) }
        }

    override fun decrypt(recipient: SecretKey, sender: PublicKeyHex, payload: String): String? =
        runCatching {
            sdkSecret(recipient) { s -> PublicKey.parse(sender.hex).use { p -> nip44Decrypt(s, p, payload) } }
        }.getOrNull()

    override fun giftWrap(
        sender: KeyPair,
        recipient: PublicKeyHex,
        rumorKind: Int,
        rumorContent: String,
        rumorCreatedAt: Long,
        expiration: Long,
    ): SignedEvent =
        keys(sender.secret) { k ->
            val rumor =
                EventBuilder(Kind(rumorKind.toUShort()), rumorContent)
                    .customCreatedAt(Timestamp.fromSecs(rumorCreatedAt.toULong()))
                    .finalizeUnsigned(k.publicKey())
            val expirationTag = Tag.parse(listOf("expiration", expiration.toString()))
            PublicKey.parse(recipient.hex).use { p ->
                nip59MakeGiftWrap(k, p, rumor, null, listOf(expirationTag)).use { SignedEvent.parse(it.asJson())!! }
            }
        }

    override fun unwrap(recipient: SecretKey, wrap: SignedEvent): Unwrapped? =
        runCatching {
            keys(recipient) { k ->
                Event.fromJson(wrap.toJson()).use { ev ->
                    UnwrappedGift.fromGiftWrap(k, ev).use { u ->
                        val r = u.rumor()
                        Unwrapped(PublicKeyHex(u.sender().toHex()), r.kind().asU16().toInt(), r.content(), r.createdAt().asSecs().toLong())
                    }
                }
            }
        }.getOrNull()

    override fun nsec(secret: SecretKey): String = sdkSecret(secret) { it.toBech32() }

    override fun parseNsec(nsec: String): SecretKey? =
        if (!nsec.startsWith("nsec1")) {
            null
        } else {
            runCatching { SdkSecretKey.parse(nsec).use { SecretKey.ofHex(it.toHex()) } }.getOrNull()
        }

    override fun npub(key: PublicKeyHex): String = PublicKey.parse(key.hex).use { it.toBech32() }

    override fun ncryptsec(secret: SecretKey, password: String, logN: Int, keySecurity: Int): String =
        sdkSecret(secret) { s ->
            EncryptedSecretKey(s, password, logN.toUByte(), KeySecurity.entries[keySecurity]).use { it.toBech32() }
        }

    override fun parseNcryptsec(ncryptsec: String, password: String): SecretKey? =
        runCatching {
            EncryptedSecretKey.fromBech32(ncryptsec).use { e -> e.decrypt(password).use { SecretKey.ofHex(it.toHex()) } }
        }.getOrNull()
}
