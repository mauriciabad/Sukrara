// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.sensor.libre3

import app.mauri.sukrara.core.model.Reading
import app.mauri.sukrara.core.model.SourceId
import app.mauri.sukrara.sensor.ble.BleLink
import app.mauri.sukrara.sensor.ble.LinkStatus
import app.mauri.sukrara.sensor.ble.SensorEvents
import app.mauri.sukrara.sensor.ble.SensorGlucose
import app.mauri.sukrara.sensor.libre3.Libre3Protocol.CH_CERT
import app.mauri.sukrara.sensor.libre3.Libre3Protocol.CH_CHALLENGE
import app.mauri.sukrara.sensor.libre3.Libre3Protocol.CH_COMMAND
import app.mauri.sukrara.sensor.libre3.Libre3Protocol.CH_GLUCOSE
import app.mauri.sukrara.sensor.libre3.Libre3Protocol.CH_HISTORIC
import app.mauri.sukrara.sensor.libre3.Libre3Protocol.CH_PATCH_STATUS
import app.mauri.sukrara.sensor.pairing.Pairing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.UUID

/**
 * Replays Libre 3 sessions through [Libre3Driver] with a scripted link and security core: every byte the driver writes,
 * the order of its subscribes, its timers and states are pinned to the field-tested driver's (its `Libre3DriverTest`,
 * expectations unchanged except the cut experiment knobs). The ciphertexts are synthetic (the core is faked), so no
 * sensor data of anyone's is in here.
 */
class Libre3DriverTest {
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    private val pin = hex("9A948F6E")
    private val start = 1_700_000_000L
    private val nowMs = 1_700_001_000_000L
    private val savedAuth = ByteArray(149) { 7 }
    private val kEnc = ByteArray(16) { 0x11 }
    private val ivEnc = ByteArray(8) { 0x22 }
    private val r1 = ByteArray(16) { (0x40 + it).toByte() }
    private val r2 = ByteArray(16) { 0xAB.toByte() } // what the driver's `random` yields below
    private val nonce7 = hex("01020304050607")

    private fun pairing(kAuth: ByteArray?) =
        Pairing("3MH0001", "18:32:19:B7:1D:11", start, 0, 21_600, 60, pin = pin, kAuth = kAuth)

    private class FakeLink : BleLink {
        val ops = ArrayList<String>()
        var pending: Runnable? = null

        override fun subscribe(characteristic: UUID) {
            ops += "sub ${short(characteristic)}"
        }

        override fun write(characteristic: UUID, value: ByteArray) {
            ops += "write ${short(characteristic)} ${value.joinToString("") { "%02x".format(it) }}"
        }

        override fun disconnect() {
            ops += "disconnect"
        }

        override fun close() {
            ops += "close"
        }

        override fun schedule(delayMs: Long, action: Runnable) {
            ops += "schedule $delayMs"
            pending = action
        }

        override fun cancel(action: Runnable) {
            ops += "cancel"
            if (pending === action) pending = null
        }

        fun fire() {
            val a = pending ?: error("no timer pending")
            pending = null
            a.run()
        }

        fun take(): List<String> = ArrayList(ops).also { ops.clear() }

        private fun short(c: UUID) = c.toString().substring(4, 8).uppercase()
    }

    private class FakeEvents : SensorEvents {
        val statuses = ArrayList<LinkStatus>()
        val readings = ArrayList<Reading>()
        val history = ArrayList<List<Reading>>()
        val sensorGlucose = ArrayList<Pair<SensorGlucose?, List<SensorGlucose>>>()
        val ended = ArrayList<String>()
        val endedForGood = ArrayList<String>()
        val lost = ArrayList<String>()
        val pairingState = ArrayList<ByteArray>()

        override fun onStatus(status: LinkStatus) {
            statuses += status
        }

        override fun onReading(reading: Reading) {
            readings += reading
        }

        override fun onHistory(readings: List<Reading>) {
            history += readings
        }

        override fun onSensorGlucose(current: SensorGlucose?, history: List<SensorGlucose>) {
            sensorGlucose += current to history
        }

        override fun onSensorEnded(serial: String) {
            ended += serial
        }

        override fun onEndedForGood(serial: String) {
            endedForGood += serial
        }

        override fun onOwnershipLost(serial: String) {
            lost += serial
        }

        override fun onPairingState(state: ByteArray) {
            pairingState += state
        }

        fun lastStatus() = statuses.last()
    }

    private inner class FakeSecurity : Libre3Security {
        val calls = ArrayList<String>()
        val plaintexts = ArrayDeque<ByteArray>() // what the next ccmDecrypt returns
        var beginResult = 42L
        var response: ByteArray? = null

        override fun begin(kAuth: ByteArray?, securityVersion: Int): Long {
            calls += "begin ${kAuth?.size} v$securityVersion"
            return beginResult
        }

        override fun setPatchCertificate(ctx: Long, cert140: ByteArray): Boolean {
            calls += "cert ${cert140.hex()}"
            return true
        }

        override fun createEphemeralPublicKey(ctx: Long) = ByteArray(64) { 0x33 }

        override fun deriveAuthorizationRoot(ctx: Long, patchEphemeral65: ByteArray): Boolean {
            calls += "derive ${patchEphemeral65.hex()}"
            return true
        }

        override fun exportSavedAuthorization(ctx: Long) = ByteArray(149) { 0x44 }

        override fun encryptChallengeReply(ctx: Long, nonce7: ByteArray, plain36: ByteArray): ByteArray {
            calls += "encrypt ${nonce7.hex()} ${plain36.hex()}"
            return ByteArray(40) { 0x55 }
        }

        override fun decryptChallengeResponse(ctx: Long, nonce7: ByteArray, cipher60: ByteArray): ByteArray {
            calls += "decrypt ${nonce7.hex()} ${cipher60.hex()}"
            return response ?: (r2 + r1 + kEnc + ivEnc)
        }

        override fun free(ctx: Long) {
            calls += "free"
        }

        override fun ccmDecrypt(key16: ByteArray, nonce13: ByteArray, cipherAndTag: ByteArray): ByteArray? {
            calls += "ccmDecrypt ${key16.hex()} ${nonce13.hex()} ${cipherAndTag.hex()}"
            return plaintexts.removeFirstOrNull()
        }

        override fun ccmEncrypt(key16: ByteArray, nonce13: ByteArray, plain: ByteArray): ByteArray {
            calls += "ccmEncrypt ${key16.hex()} ${nonce13.hex()} ${plain.hex()}"
            return plain + hex("66666666")
        }
    }

    private inner class Session(kAuth: ByteArray? = savedAuth, lastLifeCount: Int = -1) {
        val link = FakeLink()
        val events = FakeEvents()
        val security = FakeSecurity()
        var clockMs = nowMs
        val driver = Libre3Driver(
            pairing(kAuth), events, security, clockMs = { clockMs }, random = { it.fill(0xAB.toByte()) }, lastLifeCount = lastLifeCount,
        )
        val allChars = setOf(
            CH_COMMAND, CH_CHALLENGE, CH_CERT, Libre3Protocol.CH_PATCH_CONTROL, CH_PATCH_STATUS, Libre3Protocol.CH_EVENT_LOG,
            CH_GLUCOSE, CH_HISTORIC, Libre3Protocol.CH_CLINICAL, Libre3Protocol.CH_FACTORY,
        )

        fun connect() = driver.onConnected(link, allChars, 0)

        fun notify(ch: UUID, bytes: ByteArray) = driver.onNotification(ch, bytes)

        /** Security data in: a header on Command, then `[seq][≤19 bytes]` fragments like the sensor sends them. */
        fun securityData(cmd: Int, data: ByteArray, on: UUID) {
            notify(CH_COMMAND, byteArrayOf(cmd.toByte(), data.size.toByte()))
            data.toList().chunked(19).forEachIndexed { i, part -> notify(on, byteArrayOf(i.toByte()) + part.toByteArray()) }
        }

        /** The sensor's challenge (cmd 8, 23 bytes) and its answer to ours (cmd 8, 67 bytes). */
        fun challenge() {
            securityData(8, r1 + nonce7, CH_CHALLENGE)
            securityData(8, ByteArray(60) { 0x60 } + nonce7, CH_CHALLENGE)
        }

        /** An encrypted packet as the driver sees it: `cipher‖tag` then the LE16 sequence, in BLE-sized chunks. */
        fun packet(ch: UUID, plain: ByteArray, cipherLen: Int, seq: Int) {
            security.plaintexts += plain
            val bytes = ByteArray(cipherLen) { 0x99.toByte() } + byteArrayOf(seq.toByte(), (seq shr 8).toByte())
            bytes.toList().chunked(20).forEach { notify(ch, it.toByteArray()) }
        }
    }

    private fun reading(epochSec: Long, mgdl: Int, rate: Double?) = Reading(Instant.ofEpochSecond(epochSec), mgdl, rate, SourceId.Libre3)

    /** The 40-byte challenge reply as three 20-byte frames: LE16 offset + 18 bytes, zero padded. */
    private val replyFrames = listOf(
        "write 22CE 0000" + "55".repeat(18), "write 22CE 1200" + "55".repeat(18), "write 22CE 2400" + "55".repeat(4) + "00".repeat(14),
    )
    private val fullSubscriptions = listOf("sub 1338", "sub 1BEE", "sub 195A", "sub 1AB8", "sub 1D24", "sub 177A", "sub 1482")

    @Test
    fun resumeWithTheSavedAuthorization() {
        val s = Session()
        s.connect()
        assertEquals(listOf("begin 149 v1"), s.security.calls)
        assertEquals(listOf("sub 2198", "sub 23FA", "sub 22CE", "write 2198 11"), s.link.take())
        assertEquals(LinkStatus.Authorizing, s.events.lastStatus())

        s.securityData(8, r1 + nonce7, CH_CHALLENGE)
        assertEquals("encrypt ${nonce7.hex()} ${(r1 + r2 + pin).hex()}", s.security.calls.last())
        assertEquals(replyFrames + "write 2198 08", s.link.take())

        s.securityData(8, ByteArray(60) { 0x60 } + nonce7, CH_CHALLENGE)
        assertEquals("decrypt ${nonce7.hex()} ${"60".repeat(60)}", s.security.calls.last())
        assertEquals(LinkStatus.Authorized, s.events.lastStatus())
        // every notification, then the glucose timer; no connection-priority request
        assertEquals(fullSubscriptions + "schedule 120000", s.link.take())
        assertTrue(s.events.pairingState.isEmpty()) // a resume saves nothing new
    }

    @Test
    fun freshPairingAfterTheNfcScan() {
        val s = Session(kAuth = null)
        s.connect()
        assertEquals(listOf("begin null v1"), s.security.calls)
        assertEquals(LinkStatus.Pairing, s.events.lastStatus())
        val cert = Libre3Protocol.APP_CERTIFICATE
        assertEquals(162, cert.size)
        val certFrames = (0 until 9).map { i ->
            "write 23FA %02x%02x".format(i * 18 and 0xFF, i * 18 shr 8) + cert.copyOfRange(i * 18, i * 18 + 18).hex()
        }
        assertEquals(listOf("sub 2198", "sub 23FA", "sub 22CE", "write 2198 01", "write 2198 02") + certFrames + "write 2198 03", s.link.take())

        s.notify(CH_COMMAND, byteArrayOf(0x04)) // certificate accepted → "send your certificate"
        assertEquals(listOf("write 2198 09"), s.link.take())

        val patchCert = ByteArray(140) { (it + 1).toByte() }
        s.securityData(10, patchCert, CH_CERT)
        assertEquals("cert ${patchCert.hex()}", s.security.calls.last())
        val eph = byteArrayOf(0x04) + ByteArray(64) { 0x33 } // 65 bytes → 4 frames, the last 11 + 7 zero bytes
        val ephFrames = (0 until 4).map { i ->
            "write 23FA %02x00".format(i * 18) + (eph.copyOfRange(i * 18, minOf(i * 18 + 18, 65)) + ByteArray(maxOf(0, i * 18 + 18 - 65))).hex()
        }
        assertEquals(listOf("write 2198 0d") + ephFrames + "write 2198 0e", s.link.take())

        val patchEph = ByteArray(65) { 0x77 }
        s.securityData(15, patchEph, CH_CERT)
        assertEquals("derive ${patchEph.hex()}", s.security.calls.last())
        assertEquals(listOf("write 2198 11"), s.link.take())

        s.challenge()
        assertEquals(LinkStatus.Authorized, s.events.lastStatus())
        assertEquals(1, s.events.pairingState.size)
        assertEquals(ByteArray(149) { 0x44 }.hex(), s.events.pairingState[0].hex())
        assertEquals(replyFrames + "write 2198 08" + fullSubscriptions + "schedule 120000", s.link.take())
    }

    @Test
    fun glucosePacketBecomesAReadingAndItsHistoricPoint() {
        val s = Session()
        s.connect()
        s.challenge()
        s.link.take()
        // lifeCount 1000, reading 120, rate +0.50/min, historic 118 @ 995, uncapped 121 (wins)
        val plain = hex("e803" + "7800" + "3200" + "00000000" + "e303" + "7600" + "00" + "7900") + ByteArray(12)
        s.packet(CH_GLUCOSE, plain, cipherLen = 33, seq = 5)
        assertEquals("ccmDecrypt ${kEnc.hex()} 0500000f00${ivEnc.hex()} ${"99".repeat(33)}", s.security.calls.last())
        assertEquals(listOf("cancel"), s.link.take())
        assertEquals(LinkStatus.Live, s.events.lastStatus())
        val now = nowMs / 1000
        assertEquals(listOf(reading(now, 121, 0.5)), s.events.readings)
        assertEquals(listOf(listOf(reading(start + 60L * 996, 118, null))), s.events.history)
        assertEquals(
            listOf(SensorGlucose(1000, 121, now, 0.5) to listOf(SensorGlucose(995, 118, start + 60L * 996))),
            s.events.sensorGlucose,
        )

        // the same life count again (a repeated notification) is not a second reading
        s.packet(CH_GLUCOSE, plain, cipherLen = 33, seq = 6)
        assertEquals(1, s.events.readings.size)
        // a flat rate code (-32768) means "no rate"
        s.packet(CH_GLUCOSE, hex("e903" + "7800" + "0080" + "00000000" + "0000" + "0000" + "00" + "0000") + ByteArray(12), 33, 7)
        assertNull(s.events.readings.last().rateMgdlPerMin)
        assertEquals(120, s.events.readings.last().mgdl)
    }

    /** A restarted driver (watchdog, Bluetooth on) doesn't report the last life count again as a new reading. */
    @Test
    fun aRestartedDriverDoesNotRepeatTheLastValue() {
        val s = Session(lastLifeCount = 1000)
        s.connect()
        s.challenge()
        s.packet(CH_GLUCOSE, hex("e803" + "7800" + "3200" + "00000000" + "e303" + "7600" + "00" + "7900") + ByteArray(12), 33, 5)
        assertTrue(s.events.readings.isEmpty())
        s.packet(CH_GLUCOSE, hex("e903" + "7800" + "3200" + "00000000" + "e303" + "7600" + "00" + "7900") + ByteArray(12), 33, 6)
        assertEquals(1, s.events.readings.size)
    }

    /** Out-of-range values are dropped and do NOT stop the glucose timer: a stream of them ends in a reconnect, not silence. */
    @Test
    fun outOfRangeValuesAreDroppedAndKeepTheTimerRunning() {
        val s = Session()
        s.connect()
        s.challenge()
        s.link.take()
        s.packet(CH_GLUCOSE, hex("ea03" + "0a00" + "0000" + "00000000" + "0000" + "0000" + "00" + "0000") + ByteArray(12), 33, 8)
        assertTrue(s.events.readings.isEmpty())
        assertEquals(emptyList<String>(), s.link.take()) // no "cancel": the 120 s timer still runs
        s.link.fire()
        assertEquals(LinkStatus.Reconnecting("no glucose data after authorizing"), s.events.lastStatus())
        assertEquals(listOf("disconnect"), s.link.take())
    }

    @Test
    fun patchStatusRequestsOneBackfill() {
        val s = Session()
        s.connect()
        s.challenge()
        s.link.take()
        // lifeCount 1000, state 4 (running), currentLifeCount 1000
        s.packet(CH_PATCH_STATUS, hex("e803" + "0000" + "0000" + "00" + "04" + "e803" + "0000"), cipherLen = 12, seq = 6)
        assertEquals("ccmDecrypt ${kEnc.hex()} 06000000f0${ivEnc.hex()} ${"99".repeat(12)}", s.security.calls[s.security.calls.size - 2])
        // newest complete sample ((1000 − 16) / 5 × 5 = 980) minus 24 h, floored at 5 → {1, 0, 1, 5 LE32}, CCM seq 1
        assertEquals("ccmEncrypt ${kEnc.hex()} 0100000000${ivEnc.hex()} 01000105000000", s.security.calls.last())
        assertEquals(listOf("write 1338 01000105000000" + "66666666" + "0100"), s.link.take())
        // only once per session
        s.packet(CH_PATCH_STATUS, hex("e903" + "0000" + "0000" + "00" + "04" + "e903" + "0000"), cipherLen = 12, seq = 7)
        assertEquals(emptyList<String>(), s.link.take())
        assertTrue(s.events.ended.isEmpty())
    }

    @Test
    fun warmUpWaitsInsteadOfReconnecting() {
        val s = Session()
        s.connect()
        s.challenge()
        s.link.take()
        s.packet(CH_PATCH_STATUS, hex("1400" + "0000" + "0000" + "00" + "04" + "1400" + "0000"), cipherLen = 12, seq = 2)
        assertEquals(LinkStatus.WarmingUp(40), s.events.lastStatus())
        assertEquals(listOf("cancel", "schedule ${40 * 60_000L + 120_000L}"), s.link.take())
    }

    @Test
    fun historicValuesEvery5Minutes() {
        val s = Session()
        s.connect()
        s.challenge()
        s.link.take()
        // from lifeCount 948: 112, 114, then an out-of-range 65535 (skipped)
        s.packet(CH_HISTORIC, hex("b403" + "7000" + "7200" + "ffff"), cipherLen = 8, seq = 3)
        assertEquals("ccmDecrypt ${kEnc.hex()} 030000f000${ivEnc.hex()} ${"99".repeat(8)}", s.security.calls.last())
        val expected = listOf(SensorGlucose(948, 112, start + 60L * 949), SensorGlucose(953, 114, start + 60L * 954))
        assertEquals(listOf(expected.map { reading(it.epochSec, it.mgdl, null) }), s.events.history)
        assertEquals(listOf(null to expected), s.events.sensorGlucose)
    }

    @Test
    fun expiredSensorGetsOneGlucoseWaitThenStops() {
        val s = Session()
        s.connect()
        s.challenge()
        s.link.take()
        // the owner's 2026-10-18 case: state 5, currentLifeCount 21635 ≥ wear length
        s.packet(CH_PATCH_STATUS, hex("8354" + "0000" + "0000" + "00" + "05" + "8354" + "0000"), cipherLen = 12, seq = 9)
        assertEquals(listOf("3MH0001"), s.events.ended)
        assertEquals(LinkStatus.SensorEnded, s.events.lastStatus())
        // an expired sensor still gets the 24 h backfill request (from 21615 − 1440 = 20175 = 0x4ECF) and the 120 s
        // glucose timer from the authorization keeps running
        assertEquals(listOf("write 1338 010001cf4e0000" + "66666666" + "0100"), s.link.take())
        assertTrue(s.events.endedForGood.isEmpty())
        s.link.fire()
        assertEquals(listOf("3MH0001"), s.events.endedForGood)
        assertEquals(listOf("close"), s.link.take())
        assertEquals(LinkStatus.SensorEnded, s.events.lastStatus())
    }

    @Test
    fun terminatedSensorStopsAtOnce() {
        val s = Session()
        s.connect()
        s.challenge()
        s.link.take()
        s.packet(CH_PATCH_STATUS, hex("6400" + "0000" + "0000" + "00" + "06" + "6400" + "0000"), cipherLen = 12, seq = 9)
        assertEquals(listOf("3MH0001"), s.events.ended)
        assertEquals(listOf("close"), s.link.take())
        assertEquals(listOf("3MH0001"), s.events.endedForGood)
    }

    @Test
    fun noGlucoseAfterAuthorizingReconnects() {
        val s = Session()
        s.connect()
        s.challenge()
        s.link.take()
        s.link.fire()
        assertEquals(LinkStatus.Reconnecting("no glucose data after authorizing"), s.events.lastStatus())
        assertEquals(listOf("disconnect"), s.link.take())
    }

    @Test
    fun disconnectFreesTheNativeSession() {
        val s = Session()
        s.connect()
        s.challenge()
        s.link.take()
        s.clockMs += 30_000
        s.driver.onDisconnected()
        assertEquals("free", s.security.calls.last())
        assertEquals(listOf("cancel"), s.link.take())
    }

    @Test
    fun protocolErrorsDropTheLink() {
        val s = Session()
        s.connect()
        s.link.take()
        s.notify(CH_COMMAND, byteArrayOf(8, 23))
        s.notify(CH_CHALLENGE, byteArrayOf(1) + ByteArray(19)) // seq 1 before 0
        assertEquals(LinkStatus.Reconnecting("security fragment out of order"), s.events.lastStatus())
        assertEquals(listOf("disconnect"), s.link.take())

        val t = Session()
        t.driver.onConnected(t.link, setOf(CH_COMMAND), 0)
        assertEquals(LinkStatus.Reconnecting("Libre 3 services not found (status 0)"), t.events.lastStatus())
        assertEquals(listOf("disconnect"), t.link.take())

        val u = Session()
        u.connect()
        u.link.take()
        u.driver.onLinkFailed("descriptor write failed: 133")
        assertEquals(LinkStatus.Reconnecting("descriptor write failed: 133"), u.events.lastStatus())
        assertEquals(listOf("disconnect"), u.link.take())
    }

    /** Another app took the sensor over (new PIN): after 3 rejections in a row the driver stops instead of looping. */
    @Test
    fun repeatedAuthorizationRejectionsMeanTheSensorIsNoLongerOurs() {
        val s = Session()
        s.security.response = ByteArray(56) // decrypts, but r1/r2 don't match: the sensor holds another PIN / key
        repeat(Libre3Driver.MAX_REJECTIONS - 1) {
            s.connect()
            s.challenge()
            assertEquals(LinkStatus.Reconnecting("challenge mismatch"), s.events.lastStatus())
            assertEquals("disconnect", s.link.take().last())
            s.driver.onDisconnected()
        }
        assertTrue(s.events.lost.isEmpty())
        s.connect()
        s.challenge()
        assertEquals(listOf("3MH0001"), s.events.lost)
        assertEquals("close", s.link.take().last())
    }

    @Test
    fun aSuccessfulAuthorizationResetsTheRejectionCount() {
        val s = Session()
        s.security.response = ByteArray(56)
        repeat(Libre3Driver.MAX_REJECTIONS - 1) {
            s.connect()
            s.challenge()
            s.driver.onDisconnected()
        }
        s.security.response = null
        s.connect()
        s.challenge()
        assertEquals(LinkStatus.Authorized, s.events.lastStatus())
        s.driver.onDisconnected()
        s.security.response = ByteArray(56)
        s.connect()
        s.challenge()
        assertTrue(s.events.lost.isEmpty())
    }

    @Test
    fun aGlitchInTheSecurityEventsIsNotCountedAsARejection() {
        val s = Session()
        repeat(Libre3Driver.MAX_REJECTIONS + 1) {
            s.connect()
            s.notify(CH_COMMAND, byteArrayOf(0x07))
            s.driver.onDisconnected()
        }
        assertTrue(s.events.lost.isEmpty())
    }
}
