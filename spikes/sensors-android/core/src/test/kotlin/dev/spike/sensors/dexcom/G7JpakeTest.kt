// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.spike.sensors.dexcom

import dev.spike.sensors.hex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.security.SecureRandom

/**
 * The EC-J-PAKE port is checked by running **both halves** against each other: the same key must come out of the
 * phone's side and the sensor's side, a mismatched pairing code must give different keys (which the sensor's AES
 * challenge then rejects — see [G7MessagesTest]), and the 160-byte wire format must survive a round trip.
 *
 * Juggluco's two embedded round packets are not used: their proofs do not verify under any party id or hash
 * variant tried, so they are no cross-check (docs/23).
 */
class G7JpakeTest {
    @Test fun `both sides end up with the same key`() {
        val code = "1234"
        val key = exchange(code, code)
        assertNotNull(key)
        assertEquals(16, key!!.size)
    }

    @Test fun `a wrong pairing code gives a different key, which the sensor's challenge will reject`() {
        val random = SecureRandom.getInstance("SHA1PRNG").apply { setSeed(11L) }
        val phone = G7Jpake("1234", random)
        val sensor = G7Jpake("4321", random, ours = G7Jpake.SENSOR, theirs = G7Jpake.PHONE)
        for (round in 0..1) {
            assertTrue(phone.acceptRound(round, sensor.round(round)))
            assertTrue(sensor.acceptRound(round, phone.round(round)))
        }
        val fromSensor = sensor.round(2)
        val fromPhone = phone.round(2)
        // Round 3's proof still verifies — J-PAKE only binds the code into the *key*, never into the proof.
        assertTrue(sensor.acceptRound(2, fromPhone))
        assertTrue(phone.acceptRound(2, fromSensor))
        assertFalse("a wrong code must not produce the sensor's key", phone.key!!.hex() == sensor.key!!.hex())
    }

    @Test fun `six-digit codes are padded the way xDrip does it`() {
        // "00" in front, as xDrip+'s Context.getPasswordBytes does: ASCII "00" + "012345".
        assertEquals(BigInteger(1, "00012345".toByteArray()), G7Jpake.passwordNumber("012345"))
        assertEquals(BigInteger(1, "1234".toByteArray()), G7Jpake.passwordNumber("1234"))
    }

    /**
     * Run both halves: the phone with [phoneCode] and the sensor with [sensorCode]. The sensor's half is this same
     * implementation with the two party ids swapped, which is enough to check that the maths closes and that a
     * wrong code cannot produce a key.
     */
    private fun exchange(phoneCode: String, sensorCode: String): ByteArray? {
        val random = SecureRandom.getInstance("SHA1PRNG").apply { setSeed(23L) }
        val phone = G7Jpake(phoneCode, random)
        val sensor = G7Jpake(sensorCode, random, ours = G7Jpake.SENSOR, theirs = G7Jpake.PHONE)

        // Rounds 0 and 1: each side proves it knows its own two keys.
        for (round in 0..1) {
            if (!phone.acceptRound(round, sensor.round(round))) return null
            if (!sensor.acceptRound(round, phone.round(round))) return null
        }
        // Round 2 mixes in the code. With different codes the proof fails here.
        val fromSensor = sensor.round(2)
        val fromPhone = phone.round(2)
        if (!sensor.acceptRound(2, fromPhone)) return null
        if (!phone.acceptRound(2, fromSensor)) return null
        val key = phone.key ?: return null
        assertEquals("both sides must derive the same key", key.hex(), sensor.key?.hex())
        return key
    }
}
