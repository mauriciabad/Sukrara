// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.spike.nostrid

import java.math.BigInteger
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Pure key derivation + short-link codec (no native code, unit-tested on the JVM against identity/vectors.json from
 * the Python reference spikes/nostr/identity/ident.py). Spec: spikes/nostr/PROTOCOL-identity.md.
 */
object Derive {
    const val APP_ID = "app.mauri.sukrara"
    private val N = BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141", 16)

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(key, "HmacSHA256")); doFinal(data) }

    /** RFC 5869 HKDF-SHA256. */
    fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int = 32): ByteArray {
        val prk = hmac(salt, ikm)
        var t = ByteArray(0)
        val out = java.io.ByteArrayOutputStream()
        var i = 1
        while (out.size() < length) {
            t = hmac(prk, t + info + byteArrayOf(i.toByte()))
            out.write(t)
            i++
        }
        return out.toByteArray().copyOf(length)
    }

    /** HKDF → valid secp256k1 secret (1 ≤ k < n); a counter suffix on the ~2^-128 out-of-range case. */
    private fun scalar(ikm: ByteArray, salt: String, info: String): ByteArray {
        for (ctr in 0 until 256) {
            val inf = info.toByteArray() + (if (ctr == 0) ByteArray(0) else byteArrayOf(ctr.toByte()))
            val k = hkdf(ikm, salt.toByteArray(), inf)
            val v = BigInteger(1, k)
            if (v.signum() > 0 && v < N) return k
        }
        error("unreachable")
    }

    /** Derived account: a new, unlinkable secret for [app], computed one-way from the user's main Nostr secret. */
    fun derivedAccount(mainSec: ByteArray, app: String = APP_ID, index: Int = 0): ByteArray =
        scalar(mainSec, "nostr/derived-account/v1", "$app#$index")

    /** Per-purpose key from a Sukrara root (generated or derived): "profile" → the key that carries kind 0. */
    fun subkey(rootSec: ByteArray, purpose: String): ByteArray = scalar(rootSec, "sukrara/subkey/v1", purpose)

    /** The throwaway key an invite gift wrap is addressed to; anyone with the link can derive it. */
    fun inviteKey(secret: ByteArray): ByteArray = scalar(secret, "sukrara/invite/v3", "wrap-recipient")

    // ------------------------------------------------------------------------------------------- short links (v3)

    const val INVITE_BASE = "https://sukrara.mauri.app/join#"
    const val FP_LEN = 8
    const val SECRET_LEN = 16

    fun b64u(b: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    fun unb64u(s: String): ByteArray = Base64.getUrlDecoder().decode(s)
    fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    fun unhex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    fun shortLink(iPubHex: String, secret: ByteArray): String =
        INVITE_BASE + b64u(unhex(iPubHex).copyOf(FP_LEN) + secret)

    /** [fp] = first 8 bytes of the identity (hex), [secret] = the invite secret (also the access-request token). */
    class Link(val fp: String, val secret: ByteArray)

    /** Only `https://sukrara.mauri.app/join#<32 base64url chars>`; anything else isn't an invite. */
    fun parse(link: String): Link? {
        val code = link.trim().takeIf { it.startsWith(INVITE_BASE) }?.removePrefix(INVITE_BASE) ?: return null
        if (!Regex("[A-Za-z0-9_-]{32}").matches(code)) return null
        val raw = unb64u(code)
        return Link(hex(raw.copyOf(FP_LEN)), raw.copyOfRange(FP_LEN, FP_LEN + SECRET_LEN))
    }
}
