// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.protocol.invite

import app.mauri.sukrara.protocol.Derive
import app.mauri.sukrara.protocol.Hex
import app.mauri.sukrara.protocol.KeyPair
import app.mauri.sukrara.protocol.NostrCrypto
import app.mauri.sukrara.protocol.PublicKeyHex
import app.mauri.sukrara.protocol.SignedEvent
import app.mauri.sukrara.protocol.Wire
import app.mauri.sukrara.protocol.event.Profile
import app.mauri.sukrara.protocol.event.Rumor
import app.mauri.sukrara.protocol.event.RumorCodec
import java.math.BigInteger
import java.util.Base64

/**
 * A v3 short invite link (PROTOCOL-identity §Short invites): the identity's 8-byte [fingerprint] (hex) and the 16-byte
 * invite [secret]. Only `https://sukrara.mauri.app/join#<32 base64url>` (or the app's own `sukrara:join#<32>`) is one.
 */
class InviteLink private constructor(val fingerprint: String, private val secret: ByteArray) {
    /** The v2 request token: the secret's base64url. */
    val token: String get() = b64u(secret)

    val link: String get() = INVITE_BASE + b64u(Hex.decode(fingerprint)!! + secret)

    fun secretBytes(): ByteArray = secret.copyOf()

    override fun toString() = "InviteLink($fingerprint…)"

    companion object {
        const val INVITE_BASE = "https://sukrara.mauri.app/join#"

        /** What the web page's "Open in Sukrara" button opens (Mac, phones without verified App Links). */
        const val APP_SCHEME = "sukrara:join#"
        const val FINGERPRINT_LEN = 8
        const val SECRET_LEN = 16
        private val CODE = Regex("[A-Za-z0-9_-]{32}")

        fun of(identity: PublicKeyHex, secret: ByteArray): InviteLink {
            require(secret.size == SECRET_LEN) { "invite secret must be 16 bytes" }
            return InviteLink(identity.fingerprint, secret.copyOf())
        }

        /** null for anything that isn't exactly an invite link (pasted text is trimmed). */
        fun parse(text: String): InviteLink? {
            val t = text.trim()
            val code = when {
                t.startsWith(INVITE_BASE) -> t.removePrefix(INVITE_BASE)
                t.startsWith(APP_SCHEME) -> t.removePrefix(APP_SCHEME)
                else -> return null
            }
            if (!CODE.matches(code)) return null
            val raw = Base64.getUrlDecoder().decode(code)
            return InviteLink(Hex.encode(raw.copyOf(FINGERPRINT_LEN)), raw.copyOfRange(FINGERPRINT_LEN, FINGERPRINT_LEN + SECRET_LEN))
        }

        private fun b64u(b: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    }
}

/** Everything a follower needs to ask for access, from an opened invite wrap or a phone's BLE INFO. */
data class Invite(val identity: PublicKeyHex, val token: String, val expiry: Long, val relays: List<String>, val profile: Profile?)

/** Why no invite wrap opened, for the error message. */
enum class InviteFailure { NothingFound, DoesNotOpen, NotAnInvite, FingerprintMismatch, Expired }

object Invites {
    /**
     * The owner's side: a new link and the invite wrap to publish (sealed by [identity], addressed to the invite key,
     * expiring with the invite). [secret] = 16 random bytes from the caller's SecureRandom.
     */
    fun create(crypto: NostrCrypto, identity: KeyPair, secret: ByteArray, expiry: Long, relays: List<String>, profile: Profile): Pair<InviteLink, SignedEvent> {
        val link = InviteLink.of(identity.public, secret)
        val recipient = crypto.keyPair(Derive.inviteKey(secret))!!.public
        val wrap = crypto.giftWrap(identity, recipient, Wire.KIND_INVITE, RumorCodec.encode(Rumor.Invite(expiry, relays, profile)), expiry)
        return link to wrap
    }

    /** The key that opens (and answers NIP-42 AUTH for) the invite wraps of [link]. */
    fun inviteKey(crypto: NostrCrypto, link: InviteLink): KeyPair = crypto.keyPair(Derive.inviteKey(link.secretBytes()))!!

    /**
     * The follower's side: the first of [wraps] that opens with the link's key, is an invite sealed by a key starting
     * with the link's fingerprint, and hasn't expired. Otherwise the most telling failure.
     */
    fun open(crypto: NostrCrypto, link: InviteLink, wraps: List<SignedEvent>, now: Long): Result {
        val key = inviteKey(crypto, link)
        var failure = InviteFailure.NothingFound
        for (w in wraps) {
            if (!crypto.verify(w)) continue
            val u = crypto.unwrap(key.secret, w)
            val rumor = u?.let { runCatching { RumorCodec.decode(it.kind, it.content) }.getOrNull() as? Rumor.Invite }
            failure = when {
                u == null -> worst(failure, InviteFailure.DoesNotOpen)
                rumor == null -> worst(failure, InviteFailure.NotAnInvite)
                !u.sender.hex.startsWith(link.fingerprint) -> worst(failure, InviteFailure.FingerprintMismatch)
                rumor.expiry <= now -> worst(failure, InviteFailure.Expired)
                else -> return Result.Opened(Invite(u.sender, link.token, rumor.expiry, rumor.relays, rumor.profile))
            }
        }
        return Result.Failed(failure)
    }

    private fun worst(a: InviteFailure, b: InviteFailure) = if (b.ordinal > a.ordinal) b else a

    sealed interface Result {
        data class Opened(val invite: Invite) : Result

        data class Failed(val reason: InviteFailure) : Result
    }

    /** The 4 digits the follower shows and the owner types: `int(sha256(F.pub_hex + token)) mod 10000`, zero-padded. */
    fun matchCode(follower: PublicKeyHex, token: String): String =
        BigInteger(1, Hex.sha256((follower.hex + token).toByteArray())).mod(BigInteger.valueOf(10_000)).toString().padStart(4, '0')
}
