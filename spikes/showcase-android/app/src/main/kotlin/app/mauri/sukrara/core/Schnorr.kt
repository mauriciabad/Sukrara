// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.core

import java.math.BigInteger
import java.security.MessageDigest

/**
 * BIP-340 signature verification on secp256k1, plain Kotlin (BigInteger, Jacobian coordinates). Used where the
 * Nostr SDK has no call for it: checking a HELLO's identity proof against each follower key (spec/ogs-ble.md B§6),
 * and so testable on the JVM. Verification only; signing stays with the Nostr SDK. Not constant-time (it only handles
 * public data).
 */
object Schnorr {
    private val P = BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFC2F", 16)
    private val N = BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141", 16)
    private val G = Pt(BigInteger("79BE667EF9DCBBAC55A06295CE870B07029BFCDB2DCE28D959F2815B16F81798", 16),
        BigInteger("483ADA7726A3C4655DA4FBFC0E1108A8FD17B448A68554199C47D08FFB10D4B8", 16), BigInteger.ONE)
    private val SEVEN = BigInteger.valueOf(7)
    private val TWO = BigInteger.TWO
    private val THREE = BigInteger.valueOf(3)

    /** A point in Jacobian coordinates; z = 0 is the point at infinity. */
    private class Pt(val x: BigInteger, val y: BigInteger, val z: BigInteger) {
        val inf get() = z.signum() == 0
    }
    private val INF = Pt(BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO)

    private fun dbl(a: Pt): Pt {
        if (a.inf || a.y.signum() == 0) return INF
        val ysq = a.y.pow(2).mod(P)
        val s = (BigInteger.valueOf(4) * a.x * ysq).mod(P)
        val m = (THREE * a.x.pow(2)).mod(P)
        val x = (m.pow(2) - TWO * s).mod(P)
        val y = (m * (s - x) - BigInteger.valueOf(8) * ysq.pow(2)).mod(P)
        return Pt(x, y, (TWO * a.y * a.z).mod(P))
    }

    private fun add(a: Pt, b: Pt): Pt {
        if (a.inf) return b
        if (b.inf) return a
        val z1 = a.z.pow(2).mod(P); val z2 = b.z.pow(2).mod(P)
        val u1 = (a.x * z2).mod(P); val u2 = (b.x * z1).mod(P)
        val s1 = (a.y * z2 * b.z).mod(P); val s2 = (b.y * z1 * a.z).mod(P)
        if (u1 == u2) return if (s1 == s2) dbl(a) else INF
        val h = (u2 - u1).mod(P); val r = (s2 - s1).mod(P)
        val h2 = h.pow(2).mod(P); val h3 = (h * h2).mod(P); val u1h2 = (u1 * h2).mod(P)
        val x = (r.pow(2) - h3 - TWO * u1h2).mod(P)
        val y = (r * (u1h2 - x) - s1 * h3).mod(P)
        return Pt(x, y, (h * a.z * b.z).mod(P))
    }

    private fun mul(k: BigInteger, a: Pt): Pt {
        var r = INF
        for (i in k.bitLength() - 1 downTo 0) {
            r = dbl(r)
            if (k.testBit(i)) r = add(r, a)
        }
        return r
    }

    /** BIP-340 lift_x: the point with x-coordinate [x] and an even y, or null. */
    private fun liftX(x: BigInteger): Pt? {
        if (x >= P) return null
        val c = (x.pow(3) + SEVEN).mod(P)
        val y = c.modPow((P + BigInteger.ONE).shiftRight(2), P)
        if (y.modPow(TWO, P) != c) return null
        return Pt(x, if (y.testBit(0)) P - y else y, BigInteger.ONE)
    }

    private fun taggedHash(tag: String, data: ByteArray): ByteArray {
        val t = MessageDigest.getInstance("SHA-256").digest(tag.toByteArray())
        return MessageDigest.getInstance("SHA-256").run { update(t); update(t); update(data); digest() }
    }

    /** Is [sig] (64 bytes) a valid BIP-340 signature of the 32-byte [msg] by the x-only key [pub] (32 bytes)? */
    fun verify(msg: ByteArray, pub: ByteArray, sig: ByteArray): Boolean {
        if (msg.size != 32 || pub.size != 32 || sig.size != 64) return false
        val pk = liftX(BigInteger(1, pub)) ?: return false
        val r = BigInteger(1, sig.copyOfRange(0, 32))
        val s = BigInteger(1, sig.copyOfRange(32, 64))
        if (r >= P || s >= N) return false
        val e = BigInteger(1, taggedHash("BIP0340/challenge", sig.copyOfRange(0, 32) + pub + msg)).mod(N)
        val rp = add(mul(s, G), mul(N - e, pk))
        if (rp.inf) return false
        val zi = rp.z.modInverse(P)
        val x = (rp.x * zi.pow(2)).mod(P)
        val y = (rp.y * zi.pow(3)).mod(P)
        return !y.testBit(0) && x == r
    }
}
