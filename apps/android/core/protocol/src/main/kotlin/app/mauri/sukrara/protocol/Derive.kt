// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.protocol

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Key derivation of PROTOCOL-identity: RFC 5869 HKDF-SHA256 on the JCA's HMAC, reduced to a valid secp256k1 scalar.
 * Pinned by RFC 5869 test case 1 and contracts/protocol/identity/vectors.json (the Python reference).
 */
object Derive {
    private val N = BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141", 16)

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(data)
        }

    fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int = 32): ByteArray {
        val prk = hmac(salt, ikm)
        val out = ByteArrayOutputStream()
        var block = ByteArray(0)
        var counter = 1
        while (out.size() < length) {
            block = hmac(prk, block + info + byteArrayOf(counter.toByte()))
            out.write(block)
            counter++
        }
        return out.toByteArray().copyOf(length)
    }

    /** First HKDF output with 1 ≤ k < n, retrying with a counter byte appended to [info] (≈2⁻¹²⁸ odds). */
    private fun scalar(ikm: ByteArray, salt: String, info: String): SecretKey {
        for (ctr in 0 until 256) {
            val suffix = if (ctr == 0) ByteArray(0) else byteArrayOf(ctr.toByte())
            val k = hkdf(ikm, salt.toByteArray(), info.toByteArray() + suffix)
            val v = BigInteger(1, k)
            if (v.signum() > 0 && v < N) return SecretKey.ofBytes(k)!!
        }
        error("no valid scalar in 256 tries")
    }

    /** The key an invite gift wrap is addressed to; anyone holding the link can derive it. */
    fun inviteKey(secret: ByteArray): SecretKey = scalar(secret, "sukrara/invite/v3", "wrap-recipient")
}
