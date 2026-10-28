// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.protocol

import app.mauri.sukrara.protocol.ble.BleFraming
import app.mauri.sukrara.protocol.ble.RateLimit
import app.mauri.sukrara.protocol.ble.Reassembler
import app.mauri.sukrara.protocol.owner.RecoveryCode
import app.mauri.sukrara.protocol.testing.OgsVectors
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BleAndRecoveryTest {
    private var now = 0L
    private val clock = { now }

    @Test fun reassemblesOutOfOrderFrames() {
        val payload = ByteArray(1000) { it.toByte() }
        val frames = BleFraming.frames(3, payload, 23)
        val r = Reassembler(clock)
        assertNull(r.add(frames.first()))
        frames.drop(2).reversed().forEach { assertNull(r.add(it)) }
        assertArrayEquals(payload, r.add(frames[1]))
    }

    @Test fun dropsBadHeadersIdleBuffersAndOversizedMessages() {
        val r = Reassembler(clock)
        assertNull(r.add(byteArrayOf(0, 1, 0)))
        assertNull(r.add(byteArrayOf(0, 1, 0, 2, 0, 2)))
        val frames = BleFraming.frames(1, ByteArray(100), 23)
        r.add(frames[0])
        now += BleFraming.IDLE_MS + 1
        assertNull(r.add(frames[1]))
        assertEquals(1, r.dropped)
        val big = BleFraming.frames(2, ByteArray(BleFraming.MAX_MESSAGE + 1), 517)
        big.forEach { assertNull(r.add(it)) }
        assertEquals(2, r.dropped)
    }

    @Test fun keepsAtMostSixteenOpenBuffers() {
        val r = Reassembler(clock)
        val firsts = (0..16).map { BleFraming.frames(it, ByteArray(20), 23) }
        firsts.forEach { r.add(it[0]) }
        assertNull(r.add(firsts[0][1]))
        assertArrayEquals(ByteArray(20), r.add(firsts[16][1]))
    }

    @Test fun rateLimitRefillsOverTheWindow() {
        val limit = RateLimit(messages = 2, bytes = 100, windowMs = 1000, nowMs = clock)
        assertTrue(limit.message())
        assertTrue(limit.message())
        assertFalse(limit.message())
        assertFalse(limit.frame(101))
        now += 500
        assertTrue(limit.message())
    }

    @Test fun recoveryCodeRestoresTheIdentity() {
        val crypto = SdkTestCrypto()
        val i = OgsVectors().key("I")
        val code = RecoveryCode.export(crypto, i.secret, "long passphrase")
        assertEquals(RecoveryCode.Import.NeedsPassword, RecoveryCode.import(crypto, code, null))
        assertEquals(RecoveryCode.Import.WrongPassword, RecoveryCode.import(crypto, code, "nope"))
        assertEquals(RecoveryCode.Import.Restored(i.secret), RecoveryCode.import(crypto, " $code\n", "long passphrase"))
        assertEquals(RecoveryCode.Import.Restored(i.secret), RecoveryCode.import(crypto, crypto.nsec(i.secret), null))
        assertEquals(RecoveryCode.Import.Restored(i.secret), RecoveryCode.import(crypto, i.secret.revealHex().uppercase(), null))
        assertEquals(RecoveryCode.Import.NotAKey, RecoveryCode.import(crypto, "hello", null))
        assertEquals("SecretKey(redacted)", i.secret.toString())
    }
}
