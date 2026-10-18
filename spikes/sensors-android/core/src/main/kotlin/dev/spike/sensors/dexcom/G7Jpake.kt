// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.spike.sensors.dexcom

import org.bouncycastle.asn1.x9.ECNamedCurveTable
import org.bouncycastle.math.ec.ECPoint
import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * EC-J-PAKE on P-256 as the Dexcom G7 / ONE+ / Stelo speaks it: the 4-digit applicator code is the password, the
 * result is the 16-byte key of the [Dexcom.challengeHash] challenge.
 *
 * Re-implemented in Kotlin from two GPL-3 sources that agree with each other:
 *  - Juggluco `Common/src/main/cpp/dexcom/ecJPake.cpp` (OpenSSL): mkhash, getproof, validateZeroKnowledgeProof,
 *    makeRound3Cert, mkSharedKey;
 *  - xDrip+ `libkeks/src/main/java/jamorham/keks/Calc.java` (BouncyCastle).
 *
 * Wire format of a round packet (160 bytes): X (x‖y, 32+32) ‖ V = g·v (x‖y) ‖ r (32), a Schnorr proof that the
 * sender knows log_g(X). Hash: SHA-256 over (len‖g)(len‖V)(len‖X)(len‖party), points uncompressed (65 bytes), lengths
 * 4-byte big-endian, reduced mod n.
 *
 * Two deliberate differences from Juggluco (both bugs there, see docs/23):
 *  - the round-3 proof uses a fresh random nonce (Juggluco: a constant from its source, which lets anyone who sees
 *    one round-3 packet solve for x2·s);
 *  - coordinates and the shared-key x are left-padded to 32 bytes (Juggluco's BN_bn2bin drops leading zeros).
 */
class G7Jpake(
    password: String,
    private val random: SecureRandom = SecureRandom(),
    /** Party id this side proves under; the sensor's own half uses [SENSOR] (see G7JpakeTest). */
    private val ours: ByteArray = PHONE,
    /** Party id the other side proves under. */
    private val theirs: ByteArray = SENSOR,
) {
    companion object {
        private val params = ECNamedCurveTable.getByName("secp256r1")
        val G: ECPoint = params.g
        val N: BigInteger = params.n
        /** Party ids hashed into the proofs: "client" (phone), and the sensor's 6 bytes. */
        val PHONE = "client".toByteArray()
        val SENSOR = byteArrayOf(0x37, 0x56, 0x27, 0x67, 0x56, 0x27)
        const val PACKET = 160

        /**
         * The password as a number: the code's ASCII bytes; a 6-digit code gets "00" in front (xDrip+ Context
         * getPasswordBytes; Juggluco only handles 4 digits).
         */
        fun passwordNumber(code: String): BigInteger {
            require(code.length == 4 || code.length == 6) { "G7 codes are 4 or 6 digits" }
            val bytes = (if (code.length == 6) "00$code" else code).toByteArray(Charsets.US_ASCII)
            return BigInteger(1, bytes)
        }

        fun point(x: ByteArray, y: ByteArray): ECPoint =
            params.curve.createPoint(BigInteger(1, x), BigInteger(1, y)).also { require(it.isValid) { "point not on P-256" } }

        fun ECPoint.raw(): ByteArray = normalize().let { it.affineXCoord.encoded + it.affineYCoord.encoded }

        fun hash(g: ECPoint, v: ECPoint, x: ECPoint, party: ByteArray): BigInteger {
            val md = MessageDigest.getInstance("SHA-256")
            for (p in listOf(g, v, x)) {
                val enc = p.normalize().getEncoded(false)
                md.update(ByteBuffer.allocate(4).putInt(enc.size).array()); md.update(enc)
            }
            md.update(ByteBuffer.allocate(4).putInt(party.size).array()); md.update(party)
            return BigInteger(1, md.digest()).mod(N)
        }

        fun prove(g: ECPoint, secret: BigInteger, public: ECPoint, party: ByteArray, nonce: BigInteger): Packet {
            val v = g.multiply(nonce).normalize()
            val r = nonce.subtract(hash(g, v, public, party).multiply(secret)).mod(N)
            return Packet(public.normalize(), v, r)
        }

        /** g·r + X·c == V (RFC 8235 §2.2). */
        fun verify(g: ECPoint, p: Packet, party: ByteArray): Boolean {
            val c = hash(g, p.v, p.x, party)
            return g.multiply(p.r).add(p.x.multiply(c)).normalize() == p.v.normalize()
        }
    }

    class Packet(val x: ECPoint, val v: ECPoint, val r: BigInteger) {
        fun bytes(): ByteArray = x.raw() + v.raw() + r.toByteArray().let { b ->
            // unsigned, left-padded to 32 bytes
            val u = if (b.size > 32) b.copyOfRange(b.size - 32, b.size) else b
            ByteArray(32 - u.size) + u
        }

        companion object {
            fun parse(b: ByteArray): Packet {
                require(b.size == PACKET) { "J-PAKE packet is $PACKET bytes, got ${b.size}" }
                return Packet(point(b.copyOfRange(0, 32), b.copyOfRange(32, 64)),
                              point(b.copyOfRange(64, 96), b.copyOfRange(96, 128)),
                              BigInteger(1, b.copyOfRange(128, 160)))
            }
        }
    }

    private val s = passwordNumber(password)
    private val x1 = scalar()
    private val x2 = scalar()
    private val X1 = G.multiply(x1).normalize()
    private val X2 = G.multiply(x2).normalize()
    private var X3: ECPoint? = null
    private var X4: ECPoint? = null

    /** The 16-byte challenge key once [acceptRound] has accepted the sensor's third packet. */
    var key: ByteArray? = null
        private set

    private fun scalar(): BigInteger {
        while (true) {
            val k = BigInteger(256, random)
            if (k.signum() > 0 && k < N) return k
        }
    }

    /** Our packet for round 0, 1 (key proofs) or 2 (the password-bound A). Round 2 needs both sensor keys first. */
    fun round(n: Int): ByteArray = when (n) {
        0 -> prove(G, x1, X1, ours, scalar()).bytes()
        1 -> prove(G, x2, X2, ours, scalar()).bytes()
        2 -> {
            val base = X1.add(checkNotNull(X3)).add(checkNotNull(X4)).normalize()
            val x2s = x2.multiply(s).mod(N)
            prove(base, x2s, base.multiply(x2s).normalize(), ours, scalar()).bytes()
        }
        else -> error("round $n")
    }

    /** Check and keep the sensor's packet of round [n]. False = bad proof (wrong code in round 2, or corrupt data). */
    fun acceptRound(n: Int, bytes: ByteArray): Boolean {
        val p = runCatching { Packet.parse(bytes) }.getOrElse { return false }
        when (n) {
            0, 1 -> { if (!verify(G, p, theirs)) return false; if (n == 0) X3 = p.x else X4 = p.x }
            2 -> {
                val base = X1.add(X2).add(checkNotNull(X3)).normalize()
                if (!verify(base, p, theirs)) return false
                // K = (B − X4·(x2·s))·x2 ; key = SHA-256(K.x)[0..16)
                val k = p.x.subtract(checkNotNull(X4).multiply(x2.multiply(s).mod(N))).multiply(x2).normalize()
                key = MessageDigest.getInstance("SHA-256").digest(k.affineXCoord.encoded).copyOf(16)
            }
            else -> return false
        }
        return true
    }
}
