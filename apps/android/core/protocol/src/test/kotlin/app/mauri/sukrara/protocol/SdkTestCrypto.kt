// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.protocol

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
 * [NostrCrypto] over the JVM build of the same rust-nostr core (`org.nostrdevkit:nostr-sdk-jvm`, test classpath only).
 * The app's adapter (`:share`, Android AAR) has the same calls and passes the same [NostrCryptoContractTest].
 */
class SdkTestCrypto : NostrCrypto {
    private fun keys(s: SecretKey) = Keys.parse(s.revealHex())

    override fun generate(): KeyPair = Keys.generate().use { k ->
        KeyPair(SecretKey.ofHex(k.secretKey().toHex())!!, PublicKeyHex(k.publicKey().toHex()))
    }

    override fun keyPair(secret: SecretKey): KeyPair? =
        runCatching { keys(secret).use { KeyPair(secret, PublicKeyHex(it.publicKey().toHex())) } }.getOrNull()

    override fun sign(signer: KeyPair, event: UnsignedEvent): SignedEvent = keys(signer.secret).use { k ->
        val ev = EventBuilder(Kind(event.kind.toUShort()), event.content)
            .tags(event.tags.map { Tag.parse(it) })
            .customCreatedAt(Timestamp.fromSecs(event.createdAt.toULong()))
            .finalize(k)
        SignedEvent.parse(ev.asJson())!!
    }

    override fun verify(event: SignedEvent): Boolean = runCatching { Event.fromJson(event.toJson()).use { it.verify() } }.getOrDefault(false)

    override fun encrypt(sender: SecretKey, recipient: PublicKeyHex, plaintext: String): String =
        nip44Encrypt(SdkSecretKey.parse(sender.revealHex()), PublicKey.parse(recipient.hex), plaintext, Nip44Version.V2)

    override fun decrypt(recipient: SecretKey, sender: PublicKeyHex, payload: String): String? =
        runCatching { nip44Decrypt(SdkSecretKey.parse(recipient.revealHex()), PublicKey.parse(sender.hex), payload) }.getOrNull()

    override fun giftWrap(sender: KeyPair, recipient: PublicKeyHex, rumorKind: Int, rumorContent: String, expiration: Long): SignedEvent =
        keys(sender.secret).use { k ->
            val rumor = EventBuilder(Kind(rumorKind.toUShort()), rumorContent).finalizeUnsigned(k.publicKey())
            val wrap = nip59MakeGiftWrap(k, PublicKey.parse(recipient.hex), rumor, null, listOf(Tag.parse(listOf("expiration", expiration.toString()))))
            SignedEvent.parse(wrap.asJson())!!
        }

    override fun unwrap(recipient: SecretKey, wrap: SignedEvent): Unwrapped? = runCatching {
        keys(recipient).use { k ->
            UnwrappedGift.fromGiftWrap(k, Event.fromJson(wrap.toJson())).use { u ->
                val r = u.rumor()
                Unwrapped(PublicKeyHex(u.sender().toHex()), r.kind().asU16().toInt(), r.content(), r.createdAt().asSecs().toLong())
            }
        }
    }.getOrNull()

    override fun nsec(secret: SecretKey): String = SdkSecretKey.parse(secret.revealHex()).toBech32()

    override fun parseNsec(nsec: String): SecretKey? =
        if (!nsec.startsWith("nsec1")) null else runCatching { SecretKey.ofHex(SdkSecretKey.parse(nsec).toHex()) }.getOrNull()

    override fun npub(key: PublicKeyHex): String = PublicKey.parse(key.hex).toBech32()

    override fun ncryptsec(secret: SecretKey, password: String, logN: Int, keySecurity: Int): String =
        EncryptedSecretKey(SdkSecretKey.parse(secret.revealHex()), password, logN.toUByte(), KeySecurity.entries[keySecurity]).toBech32()

    override fun parseNcryptsec(ncryptsec: String, password: String): SecretKey? =
        runCatching { SecretKey.ofHex(EncryptedSecretKey.fromBech32(ncryptsec).decrypt(password).toHex()) }.getOrNull()
}
