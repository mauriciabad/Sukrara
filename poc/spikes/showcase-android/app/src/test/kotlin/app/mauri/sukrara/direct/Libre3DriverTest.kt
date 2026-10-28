// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.direct

import android.bluetooth.BluetoothGatt
import app.mauri.sukrara.bridge.Bridge
import app.mauri.sukrara.libreview.LibreViewPayload
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Replays Libre 3 sessions through [Libre3Driver] with a scripted link and security core (investigation 23, step 1):
 * every byte the driver writes, the order of its subscribes, its timers and status words are pinned to what
 * investigation 02's `Libre3Client` did, so the move behind [SensorDriver] changed nothing over the air. The
 * expected values are read off the old client's code (Juggluco's Libre3GattCallback); the ciphertexts are synthetic
 * (the core is faked, the real one is JNI), so no sensor data of anyone's is in here.
 */
class Libre3DriverTest {
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
    private fun name(c: UUID) = c.toString().substring(4, 8).uppercase()

    private val pin = hex("9A948F6E")
    private val start = 1_700_000_000L
    private val nowMs = 1_700_001_000_000L
    private val savedAuth = ByteArray(149) { 7 }
    private val kEnc = ByteArray(16) { 0x11 }
    private val ivEnc = ByteArray(8) { 0x22 }
    private val r1 = ByteArray(16) { (0x40 + it).toByte() }
    private val r2 = ByteArray(16) { 0xAB.toByte() }   // what the driver's `random` yields below
    private val nonce7 = hex("01020304050607")

    private fun pairing(kAuth: ByteArray?) = SensorPairing("3MH0001", "18:32:19:B7:1D:11", start, pin, kAuth, 0, 21_600, 60)

    private class FakeLink : BleLink {
        val ops = ArrayList<String>()
        var pending: Runnable? = null
        override val isBonded = false
        override fun subscribe(characteristic: UUID, indicate: Boolean) { ops += "sub ${short(characteristic)}" }
        override fun write(characteristic: UUID, value: ByteArray, withResponse: Boolean) {
            ops += "write ${short(characteristic)} ${value.joinToString("") { "%02x".format(it) }}" + if (withResponse) "" else " noresp"
        }
        override fun bond() { ops += "bond" }
        override fun requestConnectionPriority(priority: Int) { ops += "priority $priority" }
        override fun disconnect() { ops += "disconnect" }
        override fun close() { ops += "close" }
        override fun schedule(delayMs: Long, action: Runnable) { ops += "schedule $delayMs"; pending = action }
        override fun cancel(action: Runnable) { ops += "cancel"; if (pending === action) pending = null }
        fun fire() { val a = pending ?: error("no timer pending"); pending = null; a.run() }
        fun take(): List<String> = ArrayList(ops).also { ops.clear() }
        private fun short(c: UUID) = c.toString().substring(4, 8).uppercase()
    }

    private class FakeEvents : SensorEvents {
        val statuses = ArrayList<Pair<String, String?>>()
        val readings = ArrayList<Bridge.Reading>()
        val history = ArrayList<List<Bridge.Reading>>()
        val sensorGlucose = ArrayList<Pair<LibreViewPayload.Glucose?, List<LibreViewPayload.Glucose>>>()
        val ended = ArrayList<String>()
        val pairingState = ArrayList<ByteArray>()
        override fun onStatus(state: String, detail: String?) { statuses += state to detail }
        override fun onReading(reading: Bridge.Reading) { readings += reading }
        override fun onHistory(readings: List<Bridge.Reading>) { history += readings }
        override fun onSensorGlucose(current: LibreViewPayload.Glucose?, history: List<LibreViewPayload.Glucose>) { sensorGlucose += current to history }
        override fun onSensorEnded(serial: String) { ended += serial }
        override fun onPairingState(state: ByteArray) { pairingState += state }
        fun lastStatus() = statuses.last()
    }

    private class FakePrefs : DriverPrefs {
        val map = HashMap<String, Boolean>()
        override fun getBoolean(key: String, default: Boolean) = map[key] ?: default
        override fun putBoolean(key: String, value: Boolean) { map[key] = value }
    }

    private inner class FakeSecurity : Libre3Security {
        val calls = ArrayList<String>()
        val plaintexts = ArrayDeque<ByteArray>()   // what the next ccmDecrypt returns
        override fun begin(kAuth: ByteArray?, securityVersion: Int): Long { calls += "begin ${kAuth?.size} v$securityVersion"; return 42 }
        override fun setPatchCertificate(ctx: Long, cert140: ByteArray): Boolean { calls += "cert ${cert140.hex()}"; return true }
        override fun createEphemeralPublicKey(ctx: Long) = ByteArray(64) { 0x33 }
        override fun deriveAuthorizationRoot(ctx: Long, patchEphemeral65: ByteArray): Boolean { calls += "derive ${patchEphemeral65.hex()}"; return true }
        override fun exportSavedAuthorization(ctx: Long) = ByteArray(149) { 0x44 }
        override fun encryptChallengeReply(ctx: Long, nonce7: ByteArray, plain36: ByteArray): ByteArray {
            calls += "encrypt ${nonce7.hex()} ${plain36.hex()}"; return ByteArray(40) { 0x55 }
        }
        override fun decryptChallengeResponse(ctx: Long, nonce7: ByteArray, cipher60: ByteArray): ByteArray {
            calls += "decrypt ${nonce7.hex()} ${cipher60.hex()}"; return r2 + r1 + kEnc + ivEnc
        }
        override fun free(ctx: Long) { calls += "free" }
        override fun ccmDecrypt(key16: ByteArray, nonce13: ByteArray, cipherAndTag: ByteArray): ByteArray? {
            calls += "ccmDecrypt ${key16.hex()} ${nonce13.hex()} ${cipherAndTag.hex()}"; return plaintexts.removeFirstOrNull()
        }
        override fun ccmEncrypt(key16: ByteArray, nonce13: ByteArray, plain: ByteArray): ByteArray {
            calls += "ccmEncrypt ${key16.hex()} ${nonce13.hex()} ${plain.hex()}"; return plain + hex("66666666")
        }
    }

    private inner class Session(kAuth: ByteArray? = savedAuth, connPriority: String = "default", val prefs: FakePrefs = FakePrefs()) {
        val link = FakeLink()
        val events = FakeEvents()
        val security = FakeSecurity()
        var clockMs = nowMs
        val driver = Libre3Driver(pairing(kAuth), events, prefs, security, connPriority = { connPriority },
            clock = { clockMs }, random = { it.fill(0xAB.toByte()) }, log = { _, _ -> })
        val allChars = setOf(Libre3Driver.CH_COMMAND, Libre3Driver.CH_CHALLENGE, Libre3Driver.CH_CERT, Libre3Driver.CH_PATCH_CONTROL,
            Libre3Driver.CH_PATCH_STATUS, Libre3Driver.CH_EVENT_LOG, Libre3Driver.CH_GLUCOSE, Libre3Driver.CH_HISTORIC,
            Libre3Driver.CH_CLINICAL, Libre3Driver.CH_FACTORY)

        fun connect() = driver.onConnected(link, allChars, 0)
        fun notify(ch: UUID, bytes: ByteArray) = driver.onNotification(ch, bytes)
        /** Security data in: a header on Command, then `[seq][≤19 bytes]` fragments like the sensor sends them. */
        fun securityData(cmd: Int, data: ByteArray, on: UUID) {
            notify(Libre3Driver.CH_COMMAND, byteArrayOf(cmd.toByte(), data.size.toByte()))
            data.toList().chunked(19).forEachIndexed { i, part -> notify(on, byteArrayOf(i.toByte()) + part.toByteArray()) }
        }
        /** The sensor's challenge (cmd 8, 23 bytes) and its answer to ours (cmd 8, 67 bytes). */
        fun challenge() {
            securityData(8, r1 + nonce7, Libre3Driver.CH_CHALLENGE)
            securityData(8, ByteArray(60) { 0x60 } + nonce7, Libre3Driver.CH_CHALLENGE)
        }
        /** An encrypted packet as the driver sees it: `cipher‖tag` then the LE16 sequence, in BLE-sized chunks. */
        fun packet(ch: UUID, plain: ByteArray, cipherLen: Int, seq: Int) {
            security.plaintexts += plain
            val bytes = ByteArray(cipherLen) { 0x99.toByte() } + byteArrayOf(seq.toByte(), (seq shr 8).toByte())
            bytes.toList().chunked(20).forEach { notify(ch, it.toByteArray()) }
        }
    }

    /** The 40-byte challenge reply as three 20-byte frames: LE16 offset + 18 bytes, zero padded. */
    private val replyFrames = listOf(
        "write 22CE 0000" + "55".repeat(18), "write 22CE 1200" + "55".repeat(18), "write 22CE 2400" + "55".repeat(4) + "00".repeat(14))
    private val fullSubscriptions = listOf("sub 1338", "sub 1BEE", "sub 195A", "sub 1AB8", "sub 1D24", "sub 177A", "sub 1482")

    @Test fun resumeWithTheSavedAuthorization() {
        val s = Session(connPriority = "low")
        s.connect()
        assertEquals(listOf("begin 149 v1"), s.security.calls)
        assertEquals(listOf("sub 2198", "sub 23FA", "sub 22CE", "write 2198 11"), s.link.take())
        assertEquals("authorizing" to null, s.events.lastStatus())

        s.securityData(8, r1 + nonce7, Libre3Driver.CH_CHALLENGE)
        assertEquals("encrypt ${nonce7.hex()} ${(r1 + r2 + pin).hex()}", s.security.calls.last())
        assertEquals(replyFrames + "write 2198 08", s.link.take())

        s.securityData(8, ByteArray(60) { 0x60 } + nonce7, Libre3Driver.CH_CHALLENGE)
        assertEquals("decrypt ${nonce7.hex()} ${"60".repeat(60)}", s.security.calls.last())
        assertEquals("authorized, subscribing" to null, s.events.lastStatus())
        assertEquals(fullSubscriptions + listOf("schedule 120000", "priority ${BluetoothGatt.CONNECTION_PRIORITY_LOW_POWER}"), s.link.take())
        assertTrue(s.events.pairingState.isEmpty())   // a resume saves nothing new
    }

    @Test fun connectionPriorityFollowsTheSetting() {
        fun last(prio: String, lowPowerPref: Boolean = false): String {
            val s = Session(connPriority = prio, prefs = FakePrefs().also { it.map[Libre3Driver.PREF_LOW_POWER] = lowPowerPref })
            s.connect(); s.challenge()
            return s.link.ops.last()
        }
        assertEquals("schedule 120000", last("default"))   // no priority request at all
        assertEquals("priority ${BluetoothGatt.CONNECTION_PRIORITY_LOW_POWER}", last("default", lowPowerPref = true))
        assertEquals("priority ${BluetoothGatt.CONNECTION_PRIORITY_BALANCED}", last("balanced"))
        assertEquals("priority ${BluetoothGatt.CONNECTION_PRIORITY_HIGH}", last("high"))
    }

    @Test fun freshPairingAfterTheNfcScan() {
        val s = Session(kAuth = null)
        s.connect()
        assertEquals(listOf("begin null v1"), s.security.calls)
        assertEquals("pairing with the sensor" to null, s.events.lastStatus())
        val cert = Libre3Driver.APP_CERTIFICATE
        assertEquals(162, cert.size)
        val certFrames = (0 until 9).map { i -> "write 23FA %02x%02x".format(i * 18 and 0xFF, i * 18 shr 8) + cert.copyOfRange(i * 18, i * 18 + 18).hex() }
        assertEquals(listOf("sub 2198", "sub 23FA", "sub 22CE", "write 2198 01", "write 2198 02") + certFrames + "write 2198 03", s.link.take())

        s.notify(Libre3Driver.CH_COMMAND, byteArrayOf(0x04))   // certificate accepted → "send your certificate"
        assertEquals(listOf("write 2198 09"), s.link.take())

        val patchCert = ByteArray(140) { (it + 1).toByte() }
        s.securityData(10, patchCert, Libre3Driver.CH_CERT)
        assertEquals("cert ${patchCert.hex()}", s.security.calls.last())
        val eph = byteArrayOf(0x04) + ByteArray(64) { 0x33 }   // 65 bytes → 4 frames, the last 11 + 7 zero bytes
        val ephFrames = (0 until 4).map { i -> "write 23FA %02x00".format(i * 18) + (eph.copyOfRange(i * 18, minOf(i * 18 + 18, 65)) + ByteArray(maxOf(0, i * 18 + 18 - 65))).hex() }
        assertEquals(listOf("write 2198 0d") + ephFrames + "write 2198 0e", s.link.take())

        val patchEph = ByteArray(65) { 0x77 }
        s.securityData(15, patchEph, Libre3Driver.CH_CERT)
        assertEquals("derive ${patchEph.hex()}", s.security.calls.last())
        assertEquals(listOf("write 2198 11"), s.link.take())

        s.challenge()
        assertEquals("authorized, subscribing" to null, s.events.lastStatus())
        assertEquals(1, s.events.pairingState.size)
        assertEquals(ByteArray(149) { 0x44 }.hex(), s.events.pairingState[0].hex())
        assertEquals(replyFrames + "write 2198 08" + fullSubscriptions + "schedule 120000", s.link.take())
    }

    @Test fun glucosePacketBecomesAReadingAndItsHistoricPoint() {
        val s = Session()
        s.connect(); s.challenge(); s.link.take()
        // lifeCount 1000, reading 120, rate +0.50/min, historic 118 @ 995, uncapped 121 (wins)
        val plain = hex("e803" + "7800" + "3200" + "00000000" + "e303" + "7600" + "00" + "7900") + ByteArray(12)
        s.packet(Libre3Driver.CH_GLUCOSE, plain, cipherLen = 33, seq = 5)
        assertEquals("ccmDecrypt ${kEnc.hex()} 0500000f00${ivEnc.hex()} ${"99".repeat(33)}", s.security.calls.last())
        assertEquals(listOf("cancel"), s.link.take())
        assertEquals("live" to null, s.events.lastStatus())
        val now = nowMs / 1000
        assertEquals(listOf(Bridge.Reading(now, 121, 0.5)), s.events.readings)
        assertEquals(listOf(listOf(Bridge.Reading(start + 60L * 996, 118, null))), s.events.history)
        assertEquals(listOf(LibreViewPayload.Glucose(1000, 121, now, 0.5) to listOf(LibreViewPayload.Glucose(995, 118, start + 60L * 996))), s.events.sensorGlucose)

        // the same life count again (a repeated notification) is not a second reading
        s.packet(Libre3Driver.CH_GLUCOSE, plain, cipherLen = 33, seq = 6)
        assertEquals(1, s.events.readings.size)
        // a flat rate code (-32768) means "no rate"; an out-of-range value is dropped
        s.packet(Libre3Driver.CH_GLUCOSE, hex("e903" + "7800" + "0080" + "00000000" + "0000" + "0000" + "00" + "0000") + ByteArray(12), 33, 7)
        assertNull(s.events.readings.last().rate)
        assertEquals(120, s.events.readings.last().mgdl)
        s.packet(Libre3Driver.CH_GLUCOSE, hex("ea03" + "0a00" + "0000" + "00000000" + "0000" + "0000" + "00" + "0000") + ByteArray(12), 33, 8)
        assertEquals(2, s.events.readings.size)
    }

    @Test fun patchStatusRequestsOneBackfill() {
        val s = Session()
        s.connect(); s.challenge(); s.link.take()
        // lifeCount 1000, state 4 (running), currentLifeCount 1000
        s.packet(Libre3Driver.CH_PATCH_STATUS, hex("e803" + "0000" + "0000" + "00" + "04" + "e803" + "0000"), cipherLen = 12, seq = 6)
        assertEquals("ccmDecrypt ${kEnc.hex()} 06000000f0${ivEnc.hex()} ${"99".repeat(12)}", s.security.calls[s.security.calls.size - 2])
        // newest complete sample ((1000 − 16) / 5 × 5 = 980) minus 24 h, floored at 5 → {1, 0, 1, 5 LE32}, CCM seq 1
        assertEquals("ccmEncrypt ${kEnc.hex()} 0100000000${ivEnc.hex()} 01000105000000", s.security.calls.last())
        assertEquals(listOf("write 1338 01000105000000" + "66666666" + "0100"), s.link.take())
        // only once per session
        s.packet(Libre3Driver.CH_PATCH_STATUS, hex("e903" + "0000" + "0000" + "00" + "04" + "e903" + "0000"), cipherLen = 12, seq = 7)
        assertEquals(emptyList<String>(), s.link.take())
        assertTrue(s.events.ended.isEmpty())
    }

    @Test fun warmUpWaitsInsteadOfReconnecting() {
        val s = Session()
        s.connect(); s.challenge(); s.link.take()
        s.packet(Libre3Driver.CH_PATCH_STATUS, hex("1400" + "0000" + "0000" + "00" + "04" + "1400" + "0000"), cipherLen = 12, seq = 2)
        assertEquals("warming up — readings in 40 min" to null, s.events.lastStatus())
        assertEquals(listOf("cancel", "schedule ${40 * 60_000L + 120_000L}"), s.link.take())
    }

    @Test fun historicValuesEvery5Minutes() {
        val s = Session()
        s.connect(); s.challenge(); s.link.take()
        // from lifeCount 948: 112, 114, then an out-of-range 65535 (skipped)
        s.packet(Libre3Driver.CH_HISTORIC, hex("b403" + "7000" + "7200" + "ffff"), cipherLen = 8, seq = 3)
        assertEquals("ccmDecrypt ${kEnc.hex()} 030000f000${ivEnc.hex()} ${"99".repeat(8)}", s.security.calls.last())
        val expected = listOf(LibreViewPayload.Glucose(948, 112, start + 60L * 949), LibreViewPayload.Glucose(953, 114, start + 60L * 954))
        assertEquals(listOf(expected.map { Bridge.Reading(it.epochSec, it.mgdl, null) }), s.events.history)
        assertEquals(listOf(null to expected), s.events.sensorGlucose)
    }

    @Test fun expiredSensorGetsOneGlucoseWaitThenStops() {
        val s = Session()
        s.connect(); s.challenge(); s.link.take()
        // the user's 2026-10-18 case: state 5, currentLifeCount 21635 ≥ wear length
        s.packet(Libre3Driver.CH_PATCH_STATUS, hex("8354" + "0000" + "0000" + "00" + "05" + "8354" + "0000"), cipherLen = 12, seq = 9)
        assertEquals(listOf("3MH0001"), s.events.ended)
        assertEquals("sensor ended" to DriverPrefs.ENDED_HINT, s.events.lastStatus())
        // as before: an expired sensor still gets the 24 h backfill request (from 21615 − 1440 = 20175 = 0x4ECF) and
        // the 120 s glucose timer from the authorization keeps running
        assertEquals(listOf("write 1338 010001cf4e0000" + "66666666" + "0100"), s.link.take())
        assertNull(s.prefs.map["ended:3MH0001"])
        s.link.fire()
        assertEquals(true, s.prefs.map["ended:3MH0001"])
        assertEquals(listOf("close"), s.link.take())
        assertEquals("sensor ended" to DriverPrefs.ENDED_HINT, s.events.lastStatus())
    }

    @Test fun terminatedSensorStopsAtOnce() {
        val s = Session()
        s.connect(); s.challenge(); s.link.take()
        s.packet(Libre3Driver.CH_PATCH_STATUS, hex("6400" + "0000" + "0000" + "00" + "06" + "6400" + "0000"), cipherLen = 12, seq = 9)
        assertEquals(listOf("3MH0001"), s.events.ended)
        assertEquals(listOf("close"), s.link.take())
        assertEquals(true, s.prefs.map["ended:3MH0001"])
    }

    @Test fun noGlucoseAfterAuthorizingReconnects() {
        val s = Session(prefs = FakePrefs().also { it.map[Libre3Driver.PREF_MINIMAL] = true })
        s.connect(); s.challenge()
        assertEquals(listOf("sub 1338", "sub 177A", "sub 1482"), s.link.take().filter { it.startsWith("sub 1") })   // the minimal set
        s.link.fire()
        assertEquals(false, s.prefs.map[Libre3Driver.PREF_MINIMAL])   // trimmed notifications are given up
        assertEquals("reconnecting" to "no glucose data after authorizing", s.events.lastStatus())
        assertEquals(listOf("disconnect"), s.link.take())
    }

    @Test fun earlyDropWithTheMinimalSetRevertsIt() {
        val s = Session(prefs = FakePrefs().also { it.map[Libre3Driver.PREF_MINIMAL] = true })
        s.connect(); s.challenge(); s.link.take()
        s.clockMs += 30_000
        s.driver.onDisconnected()
        assertEquals(false, s.prefs.map[Libre3Driver.PREF_MINIMAL])
        assertEquals("free", s.security.calls.last())   // the native session is released
        assertEquals(listOf("cancel"), s.link.take())
    }

    @Test fun protocolErrorsDropTheLink() {
        val s = Session()
        s.connect(); s.link.take()
        s.notify(Libre3Driver.CH_COMMAND, byteArrayOf(8, 23))
        s.notify(Libre3Driver.CH_CHALLENGE, byteArrayOf(1) + ByteArray(19))   // seq 1 before 0
        assertEquals("reconnecting" to "security fragment out of order", s.events.lastStatus())
        assertEquals(listOf("disconnect"), s.link.take())

        val t = Session()
        t.driver.onConnected(t.link, setOf(Libre3Driver.CH_COMMAND), 0)
        assertEquals("reconnecting" to "Libre 3 services not found (status 0)", t.events.lastStatus())
        assertEquals(listOf("disconnect"), t.link.take())

        val u = Session()
        u.connect(); u.link.take()
        u.driver.onLinkFailed("descriptor write failed: 133")
        assertEquals("reconnecting" to "descriptor write failed: 133", u.events.lastStatus())
        assertEquals(listOf("disconnect"), u.link.take())
    }

    @Test fun matchesOnlyItsOwnAddress() {
        val s = Session()
        assertTrue(s.driver.matches(Advert("18:32:19:b7:1d:11")))
        assertEquals(false, s.driver.matches(Advert("18:32:19:B7:1D:12", "ABBOTT")))
        assertEquals("libre3", s.driver.profile.id)
    }
}
