// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.sensor.libre3

import app.mauri.sukrara.core.model.Reading
import app.mauri.sukrara.core.model.SourceId
import app.mauri.sukrara.sensor.ble.BleLink
import app.mauri.sukrara.sensor.ble.LinkStatus
import app.mauri.sukrara.sensor.ble.SensorDriver
import app.mauri.sukrara.sensor.ble.SensorEvents
import app.mauri.sukrara.sensor.ble.SensorGlucose
import app.mauri.sukrara.sensor.libre3.Libre3Protocol.APP_CERTIFICATE
import app.mauri.sukrara.sensor.libre3.Libre3Protocol.BACKFILL_MINUTES
import app.mauri.sukrara.sensor.libre3.Libre3Protocol.CH_CERT
import app.mauri.sukrara.sensor.libre3.Libre3Protocol.CH_CHALLENGE
import app.mauri.sukrara.sensor.libre3.Libre3Protocol.CH_COMMAND
import app.mauri.sukrara.sensor.libre3.Libre3Protocol.CH_GLUCOSE
import app.mauri.sukrara.sensor.libre3.Libre3Protocol.CH_HISTORIC
import app.mauri.sukrara.sensor.libre3.Libre3Protocol.CH_PATCH_CONTROL
import app.mauri.sukrara.sensor.libre3.Libre3Protocol.CH_PATCH_STATUS
import app.mauri.sukrara.sensor.libre3.Libre3Protocol.DESCRIPTORS
import app.mauri.sukrara.sensor.libre3.Libre3Protocol.EARLY_DROP_MS
import app.mauri.sukrara.sensor.libre3.Libre3Protocol.GLUCOSE_TIMEOUT_MS
import app.mauri.sukrara.sensor.libre3.Libre3Protocol.HISTORY_STEP
import app.mauri.sukrara.sensor.libre3.Libre3Protocol.KIND_CONTROL
import app.mauri.sukrara.sensor.libre3.Libre3Protocol.KIND_GLUCOSE
import app.mauri.sukrara.sensor.libre3.Libre3Protocol.KIND_HISTORIC
import app.mauri.sukrara.sensor.libre3.Libre3Protocol.KIND_PATCH_STATUS
import app.mauri.sukrara.sensor.libre3.Libre3Protocol.SUBSCRIPTIONS
import app.mauri.sukrara.sensor.libre3.Libre3Protocol.VALID_MGDL
import app.mauri.sukrara.sensor.pairing.Pairing
import java.security.SecureRandom
import java.time.Instant
import java.util.UUID

/**
 * The FreeStyle Libre 3 / 3 Plus protocol over a [BleLink], using the saved [Pairing]. Right after an NFC scan the
 * pairing has no authorization yet: the first connection runs the fresh pairing (certificates + ephemeral keys, after
 * Juggluco's Libre3GattCallback) and hands the result to [SensorEvents.onPairingState]; later connections resume with it.
 *
 * Read-only: nothing is written to the sensor beyond the security handshake and history requests.
 *
 * Ported from the PoC's `Libre3Driver` with the same operations in the same order, the same timers and states
 * (`Libre3DriverTest`). Dropped: the minimal-subscription and connection-priority experiments (the PoC's defaults —
 * every notification, no priority request — are what V1 always does). Runs on the link's serial dispatcher only.
 */
internal class Libre3Driver(
    private val pairing: Pairing,
    private val events: SensorEvents,
    private val security: Libre3Security = Libre3Crypto,
    private val clockMs: () -> Long = System::currentTimeMillis,
    private val random: (ByteArray) -> Unit = { SecureRandom().nextBytes(it) },
    private val log: (String) -> Unit = {},
) : SensorDriver {
    private var link: BleLink? = null

    // security session
    private var ctx = 0L
    private var kAuth = pairing.kAuth // null until the first fresh pairing completes
    private var fresh = false // this session is a fresh pairing
    private var kEnc: ByteArray? = null
    private var ivEnc: ByteArray? = null
    private var r1 = ByteArray(0)
    private var r2 = ByteArray(0)
    private var secCmd = 0
    private var secLen = 0
    private var secSeq = -1
    private var secBuf = ByteArray(0)
    private var secAt = 0

    private var glucoseBuf = ByteArray(0)
    private var lastLifeCount = -1
    private var authorizedAt = 0L
    private var outSeq = 1 // outgoing CCM sequence, restarts at 1 each session
    private var backfillRequested = false
    private var endedSeen = false // the patch status said the sensor has ended (PatchState)
    private var rejections = 0 // authorization rejections in a row (another app took the sensor over)
    private val glucoseTimeout = Runnable { onGlucoseTimeout() }

    // MARK: link lifecycle

    override fun onConnected(link: BleLink, characteristics: Set<UUID>, status: Int) {
        this.link = link
        if (CH_COMMAND !in characteristics || CH_GLUCOSE !in characteristics) {
            return fail("Libre 3 services not found (status $status)")
        }
        startAuthorization()
    }

    override fun onLinkFailed(why: String) = fail(why)

    override fun onNotification(characteristic: UUID, value: ByteArray) = handleNotification(characteristic, value)

    override fun onDisconnected() {
        val alive = clockMs() - authorizedAt
        log("sensor disconnected " + if (authorizedAt > 0) "after ${alive / 1000}s" else "before authorizing")
        if (authorizedAt > 0 && alive < EARLY_DROP_MS) log("early drop: the sensor did not accept the session")
        authorizedAt = 0
        resetSession()
        link?.cancel(glucoseTimeout)
    }

    override fun onClosed() {
        link?.cancel(glucoseTimeout)
        resetSession()
    }

    private fun resetSession() {
        if (ctx != 0L) security.free(ctx)
        ctx = 0L
        kEnc = null
        ivEnc = null
        outSeq = 1
        backfillRequested = false
        glucoseBuf = ByteArray(0)
        secSeq = -1
        secAt = 0
        secBuf = ByteArray(0)
    }

    private fun subscribe(uuid: UUID) = link?.subscribe(uuid) ?: Unit

    private fun write(uuid: UUID, value: ByteArray) = link?.write(uuid, value) ?: Unit

    private fun fail(why: String) {
        log("sensor: $why")
        events.onStatus(LinkStatus.Reconnecting(why))
        link?.disconnect() // the transport reconnects with a new connection
    }

    /**
     * The sensor refused our authorization. Once is a glitch (reconnect, like the PoC); [MAX_REJECTIONS] in a row means
     * the sensor now belongs to another app or receiver (the showcase took it back over NFC, a new PIN): stop
     * reconnecting for ever and say so, so the person can scan the sensor again.
     */
    private fun rejected(why: String) {
        if (++rejections < MAX_REJECTIONS) return fail(why)
        log("sensor: $why ($rejections times in a row): not reconnecting")
        events.onOwnershipLost(pairing.serial)
        link?.close()
    }

    // MARK: security handshake

    private fun startAuthorization() {
        resetSession()
        fresh = kAuth == null
        ctx = security.begin(kAuth, 1)
        if (ctx == 0L) return rejected("saved authorization rejected by the security core")
        events.onStatus(if (fresh) LinkStatus.Pairing else LinkStatus.Authorizing)
        subscribe(CH_COMMAND)
        subscribe(CH_CERT)
        subscribe(CH_CHALLENGE)
        if (fresh) {
            // fresh pairing: 1, 2, our certificate, 3; the sensor answers with 0x04 (certificate accepted)
            write(CH_COMMAND, byteArrayOf(0x01))
            write(CH_COMMAND, byteArrayOf(0x02))
            writeFragments(CH_CERT, APP_CERTIFICATE)
            write(CH_COMMAND, byteArrayOf(0x03))
        } else {
            write(CH_COMMAND, byteArrayOf(0x11)) // resume with the saved authorization
        }
    }

    /** Security data out: 20-byte writes, offset (LE16) followed by 18 payload bytes, zero padded. */
    private fun writeFragments(uuid: UUID, data: ByteArray) {
        var off = 0
        while (off < data.size) {
            val part = data.copyOfRange(off, minOf(off + 18, data.size))
            write(uuid, byteArrayOf((off and 0xFF).toByte(), (off shr 8).toByte()) + part + ByteArray(18 - part.size))
            off += 18
        }
    }

    private fun handleNotification(uuid: UUID, value: ByteArray) {
        if (value.isEmpty()) return
        when (uuid) {
            CH_COMMAND -> {
                if (value.size == 1) {
                    if (fresh && value[0].toInt() == 0x04) {
                        write(CH_COMMAND, byteArrayOf(0x09)) // send your certificate
                    } else {
                        fail("unexpected security event ${value[0]}")
                    }
                    return
                }
                secCmd = value[0].toInt() and 0xFF
                secLen = value[1].toInt() and 0xFF
                secSeq = -1
                secAt = 0
                secBuf = ByteArray(secLen)
            }
            CH_CHALLENGE, CH_CERT -> {
                val seq = value[0].toInt() and 0xFF
                if (seq != secSeq + 1) return fail("security fragment out of order")
                secSeq = seq
                val n = minOf(value.size - 1, secBuf.size - secAt)
                if (n <= 0) return fail("security fragment overflow")
                System.arraycopy(value, 1, secBuf, secAt, n)
                secAt += n
                if (secAt >= secLen) securityDataComplete()
            }
            CH_GLUCOSE -> {
                glucoseBuf += value
                if (glucoseBuf.size >= 35) {
                    val packet = glucoseBuf.copyOf(35)
                    glucoseBuf = ByteArray(0)
                    onGlucosePacket(packet)
                }
            }
            CH_PATCH_STATUS -> onPatchStatus(value)
            CH_HISTORIC -> onHistoric(value)
            else -> Unit // subscribed (the sensor requires it) but not parsed
        }
    }

    private fun securityDataComplete() {
        if (fresh && secCmd == 10 && secLen == 140) {
            // the sensor's certificate → ask for its ephemeral key and send ours
            if (!security.setPatchCertificate(ctx, secBuf)) return rejected("sensor certificate rejected")
            val eph = security.createEphemeralPublicKey(ctx) ?: return fail("could not create pairing keys")
            write(CH_COMMAND, byteArrayOf(0x0D))
            writeFragments(CH_CERT, byteArrayOf(0x04) + eph)
            write(CH_COMMAND, byteArrayOf(0x0E))
            return
        }
        if (fresh && secCmd == 15 && secLen == 65) {
            // the sensor's ephemeral key → shared authorization, then the same challenge as a resume
            if (!security.deriveAuthorizationRoot(ctx, secBuf)) return fail("pairing key agreement failed")
            write(CH_COMMAND, byteArrayOf(0x11))
            return
        }
        if (secCmd != 8) return fail("unexpected security data cmd=$secCmd len=$secLen")
        when (secLen) {
            23 -> answerChallenge()
            67 -> checkChallengeResponse()
            else -> fail("unexpected challenge length $secLen")
        }
    }

    private fun answerChallenge() {
        r1 = secBuf.copyOfRange(0, 16)
        val nonce = secBuf.copyOfRange(16, 23)
        r2 = ByteArray(16).also { random(it) }
        val reply = security.encryptChallengeReply(ctx, nonce, r1 + r2 + pairing.pin)
            ?: return fail("challenge encryption failed")
        writeFragments(CH_CHALLENGE, reply)
        write(CH_COMMAND, byteArrayOf(0x08))
    }

    private fun checkChallengeResponse() {
        val plain = security.decryptChallengeResponse(ctx, secBuf.copyOfRange(60, 67), secBuf.copyOfRange(0, 60))
            ?: return fail("challenge response could not be decrypted")
        if (!plain.copyOfRange(0, 16).contentEquals(r2) || !plain.copyOfRange(16, 32).contentEquals(r1)) {
            return rejected("challenge mismatch")
        }
        kEnc = plain.copyOfRange(32, 48)
        ivEnc = plain.copyOfRange(48, 56)
        if (fresh) {
            val saved = security.exportSavedAuthorization(ctx) ?: return fail("could not save the pairing")
            kAuth = saved
            fresh = false
            log("sensor paired")
            events.onPairingState(saved)
        }
        log("sensor authorized")
        rejections = 0
        events.onStatus(LinkStatus.Authorized)
        authorizedAt = clockMs()
        SUBSCRIPTIONS.forEach { subscribe(it) }
        link?.schedule(GLUCOSE_TIMEOUT_MS, glucoseTimeout)
    }

    // MARK: data

    private fun decrypt(kind: Int, packet: ByteArray): ByteArray? {
        val key = kEnc ?: return null
        val iv = ivEnc ?: return null
        if (packet.size <= 6) return null
        val n = packet.size
        val nonce = byteArrayOf(packet[n - 2], packet[n - 1]) + DESCRIPTORS[kind] + iv
        return security.ccmDecrypt(key, nonce, packet.copyOfRange(0, n - 2))
    }

    private fun onGlucosePacket(packet: ByteArray) {
        val d = decrypt(KIND_GLUCOSE, packet) ?: return fail("glucose packet could not be decrypted")
        if (d.size < 29) return fail("short glucose packet (${d.size})")
        fun u16(o: Int) = (d[o].toInt() and 0xFF) or ((d[o + 1].toInt() and 0xFF) shl 8)

        val lifeCount = u16(0)
        val rawRate = u16(4).toShort().toInt()
        val reading = u16(2)
        val uncapped = u16(15)
        val mgdl = if (uncapped in VALID_MGDL) uncapped else reading
        if (mgdl !in VALID_MGDL) {
            log("ignoring out-of-range glucose")
            return
        }

        link?.cancel(glucoseTimeout)
        if (lifeCount == lastLifeCount) return
        lastLifeCount = lifeCount
        events.onStatus(LinkStatus.Live)
        val now = clockMs() / 1000
        val rate = if (rawRate == -32768) null else rawRate / 100.0
        events.onReading(reading(now, mgdl, rate))

        // The packet also carries the matching 5-minute value, which fills a follower's chart a bit faster.
        val histLifeCount = u16(10)
        val hist = u16(12)
        val histPoint = if (hist in VALID_MGDL && histLifeCount in 1..lifeCount) {
            SensorGlucose(histLifeCount, hist, pairing.startEpoch + 60L * (histLifeCount + 1))
        } else {
            null
        }
        histPoint?.let { events.onHistory(listOf(reading(it.epochSec, it.mgdl, null))) }
        events.onSensorGlucose(SensorGlucose(lifeCount, mgdl, now, rate), listOfNotNull(histPoint))
    }

    /** Encrypts a command and writes it to PatchControl (same framing as incoming packets). */
    private fun sendControl(command: ByteArray) {
        val key = kEnc ?: return
        val iv = ivEnc ?: return
        val seq = outSeq++
        val nonce = byteArrayOf((seq and 0xFF).toByte(), (seq shr 8).toByte()) + DESCRIPTORS[KIND_CONTROL] + iv
        val body = security.ccmEncrypt(key, nonce, command) ?: return
        write(CH_PATCH_CONTROL, body + byteArrayOf((seq and 0xFF).toByte(), (seq shr 8).toByte()))
    }

    /** `{1, 0, arg, from(i32)}`: send stored 5-minute values starting at historic life count [from]. */
    private fun requestHistory(from: Int) {
        log("requesting history from lifeCount $from")
        sendControl(
            byteArrayOf(
                1, 0, 1,
                (from and 0xFF).toByte(), ((from shr 8) and 0xFF).toByte(),
                ((from shr 16) and 0xFF).toByte(), ((from shr 24) and 0xFF).toByte(),
            ),
        )
    }

    /** `Patchstatus`: i16 lifeCount, i16 errorData, i16 eventData, i8 index, i8 patchState, i16 currentLifeCount, … */
    private fun onPatchStatus(packet: ByteArray) {
        val d = decrypt(KIND_PATCH_STATUS, packet) ?: return
        if (d.size < 12) return
        val currentLifeCount = (d[8].toInt() and 0xFF) or ((d[9].toInt() and 0xFF) shl 8)
        val state = d[7].toInt() and 0xFF
        log("patch status: state=$state currentLifeCount=$currentLifeCount")
        if (PatchState.ended(state, currentLifeCount, pairing.wearMin)) {
            if (!endedSeen) {
                endedSeen = true
                events.onSensorEnded(pairing.serial)
            }
            if (PatchState.stopNow(state)) return stopForGood("patch state $state")
            events.onStatus(LinkStatus.SensorEnded) // an expired sensor gets one glucose wait (grace period)
        }
        if (currentLifeCount in 0 until pairing.warmupMin) {
            // no glucose yet: wait for the end of the warm-up instead of reconnecting every 2 minutes
            events.onStatus(LinkStatus.WarmingUp(pairing.warmupMin - currentLifeCount))
            link?.cancel(glucoseTimeout)
            link?.schedule((pairing.warmupMin - currentLifeCount) * 60_000L + GLUCOSE_TIMEOUT_MS, glucoseTimeout)
            return
        }
        if (backfillRequested || currentLifeCount <= 0) return
        backfillRequested = true
        // Juggluco's rule for the newest complete historic sample, then walk back BACKFILL_MINUTES.
        val newest = ((currentLifeCount - 16) / HISTORY_STEP) * HISTORY_STEP
        requestHistory(maxOf(HISTORY_STEP, newest - BACKFILL_MINUTES))
    }

    /** Historic notification: u16 start life count, then u16 mg/dL every [HISTORY_STEP] minutes. */
    private fun onHistoric(packet: ByteArray) {
        val d = decrypt(KIND_HISTORIC, packet) ?: return
        if (d.size < 4) return
        fun u16(o: Int) = (d[o].toInt() and 0xFF) or ((d[o + 1].toInt() and 0xFF) shl 8)
        val start = u16(0)
        val out = ArrayList<SensorGlucose>()
        var i = 2
        var lifeCount = start
        while (i + 1 < d.size) {
            val v = u16(i)
            if (v in VALID_MGDL) out += SensorGlucose(lifeCount, v, pairing.startEpoch + 60L * (lifeCount + 1))
            i += 2
            lifeCount += HISTORY_STEP
        }
        if (out.isNotEmpty()) {
            log("history: ${out.size} values from lifeCount $start")
            events.onHistory(out.map { reading(it.epochSec, it.mgdl, null) })
            events.onSensorGlucose(null, out)
        }
    }

    /** Authorized but no glucose: reconnect. */
    private fun onGlucoseTimeout() {
        if (endedSeen) return stopForGood("no glucose after the sensor's end")
        fail("no glucose data after authorizing")
    }

    /** The sensor has ended: no more reconnects (each one costs battery) until a new sensor is scanned. */
    private fun stopForGood(why: String) {
        log("sensor ${pairing.serial} ended ($why): not reconnecting")
        events.onEndedForGood(pairing.serial)
        link?.close()
        events.onStatus(LinkStatus.SensorEnded)
    }

    companion object {
        /** Authorization rejections in a row that mean the sensor is no longer ours. */
        const val MAX_REJECTIONS = 3
    }

    private fun reading(epochSec: Long, mgdl: Int, rate: Double?) =
        Reading(Instant.ofEpochSecond(epochSec), mgdl, rate?.toFloat(), SourceId.Libre3)
}
