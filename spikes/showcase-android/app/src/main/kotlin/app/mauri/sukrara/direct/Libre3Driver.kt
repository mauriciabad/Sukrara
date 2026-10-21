// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.direct

import android.bluetooth.BluetoothGatt
import android.util.Log
import app.mauri.sukrara.bridge.Bridge
import app.mauri.sukrara.core.SensorProfiles
import app.mauri.sukrara.features.Features
import app.mauri.sukrara.libreview.LibreViewPayload
import java.security.SecureRandom
import java.util.UUID

/**
 * The FreeStyle Libre 3 / 3 Plus protocol over a [BleLink], using the pairing data in [SensorPairing]. Right after
 * an NFC scan the pairing has no saved authorization yet: the first connection then runs the fresh pairing
 * (certificates + ephemeral keys, after Juggluco's Libre3GattCallback) and hands the resulting authorization to
 * [SensorEvents.onPairingState]; later connections resume with it.
 *
 * Read-only: nothing is ever written to the sensor beyond the security handshake and history requests.
 *
 * Power: only the notifications we actually parse where the sensor allows it, and a low-power connection interval
 * once live; the connection itself (connect by address, no scanning) is [GattLink]'s.
 *
 * This is investigation 02's `Libre3Client` with the Bluetooth half moved to [GattLink] (investigation 23, step 1):
 * the same operations in the same order, the same timers and the same status words. Replayed in `Libre3DriverTest`.
 */
internal class Libre3Driver(
    private val pairing: SensorPairing,
    private val events: SensorEvents,
    private val prefs: DriverPrefs,
    private val security: Libre3Security = Libre3Crypto,
    /** Sukrara: Features.connPriority (settings / DebugReceiver). */
    private val connPriority: () -> String = { Features.value.connPriority },
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: (ByteArray) -> Unit = { SecureRandom().nextBytes(it) },
    private val log: (priority: Int, message: String) -> Unit = { p, m -> Log.println(p, TAG, m) },
) : SensorDriver {
    companion object {
        private const val TAG = "Sukrara.direct"
        private fun u(short: String) = UUID.fromString("0898$short-EF89-11E9-81B4-2A2AE2DBCCE4")
        val DATA_SERVICE: UUID = u("10CC")
        val SECURITY_SERVICE: UUID = u("203A")
        val CH_COMMAND: UUID = u("2198")
        val CH_CHALLENGE: UUID = u("22CE")
        val CH_CERT: UUID = u("23FA")
        val CH_PATCH_CONTROL: UUID = u("1338")
        val CH_PATCH_STATUS: UUID = u("1482")
        val CH_EVENT_LOG: UUID = u("1BEE")
        val CH_GLUCOSE: UUID = u("177A")
        val CH_HISTORIC: UUID = u("195A")
        val CH_CLINICAL: UUID = u("1AB8")
        val CH_FACTORY: UUID = u("1D24")

        /**
         * Every notifying characteristic, in Juggluco's order. The sensor runs a full data cycle
         * every minute and drops the link if we are not subscribed to all of them: subscribing to
         * only the ones we parse cost a disconnect at exactly 60 s (measured 2026-10-15).
         */
        val FULL = listOf(CH_PATCH_CONTROL, CH_EVENT_LOG, CH_HISTORIC, CH_CLINICAL, CH_FACTORY,
                          CH_GLUCOSE, CH_PATCH_STATUS)
        /** Only what we parse. Kept for battery experiments; see [PREF_MINIMAL]. */
        val MINIMAL = listOf(CH_PATCH_CONTROL, CH_GLUCOSE, CH_PATCH_STATUS)

        const val PREF_MINIMAL = "useMinimalSubscriptions"
        const val PREF_LOW_POWER = "useLowPowerInterval"
        const val GLUCOSE_TIMEOUT_MS = 120_000L
        /** A drop this soon after authorizing means the session setup was not acceptable to the sensor. */
        const val EARLY_DROP_MS = 90_000L

        /** AES-CCM packet descriptors by kind (index = kind). */
        private val DESCRIPTORS = arrayOf(
            byteArrayOf(0, 0, 0), byteArrayOf(0, 0, 0x0F), byteArrayOf(0, 0, 0xF0.toByte()),
            byteArrayOf(0, 0x0F, 0), byteArrayOf(0, 0xF0.toByte(), 0), byteArrayOf(0x0F, 0, 0),
            byteArrayOf(0xF0.toByte(), 0, 0), byteArrayOf(0x44, 0, 0))
        private const val KIND_CONTROL = 0
        private const val KIND_PATCH_CONTROL = 1
        private const val KIND_PATCH_STATUS = 2
        private const val KIND_GLUCOSE = 3
        private const val KIND_HISTORIC = 4

        /** The sensor stores one historic value per 5 minutes. */
        private const val HISTORY_STEP = 5
        /** How far back to backfill on connect (minutes): 24 h, the span of CGM+'s watch graph (investigation 08). */
        private const val BACKFILL_MINUTES = 24 * 60

        /**
         * App certificate for security level 1, sent during a fresh pairing (public; from Juggluco's KEYSCrypto.java,
         * LIBRE3_APP_CERTIFICATES_B[1]).
         */
        val APP_CERTIFICATE = (
            "03030102030405060708090a0b0c0d0e0f10000161897655010000000000000000" +
            "048242be33f1a330880112fa62cc4842a43d1204922ad201d8775bb226f611f75b0ef3d5bc6cc4317caa457584ab003f" +
            "1712336089d3a4f29838ed0dc666deaea2" +
            "d65a00dfff5d7bcae21655e302e3458e774daaaaca87af75f1b87884b18d4ce875d0d108c903a834471a4ff674b2d30b" +
            "cba062373014b7786e4437b177aec3c8").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    override val profile = SensorProfiles.LIBRE_3

    /** The NFC tap gave the address: that device and no other. */
    override fun matches(advert: Advert) = advert.address.equals(pairing.address, ignoreCase = true)

    private var link: BleLink? = null

    // security session
    private var ctx = 0L
    private var kAuth = pairing.kAuth      // null until the first fresh pairing completes
    private var fresh = false              // this session is a fresh pairing
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
    private var outSeq = 1              // outgoing CCM sequence, restarts at 1 each session
    private var backfillRequested = false
    private var subscriptions = emptyList<UUID>()
    private var endedSeen = false          // the patch status said the sensor has ended (PatchState)
    private val glucoseTimeout = Runnable { onGlucoseTimeout() }

    // MARK: link lifecycle

    override fun onConnected(link: BleLink, characteristics: Set<UUID>, status: Int) {
        this.link = link
        if (CH_COMMAND !in characteristics || CH_GLUCOSE !in characteristics)
            return fail("Libre 3 services not found (status $status)")
        startAuthorization()
    }

    override fun onLinkFailed(why: String) = fail(why)

    override fun onNotification(characteristic: UUID, value: ByteArray) = handleNotification(characteristic, value)

    override fun onDisconnected() {
        val alive = clock() - authorizedAt
        log(Log.INFO, "sensor disconnected " + if (authorizedAt > 0) "after ${alive / 1000}s" else "before authorizing")
        if (authorizedAt > 0 && alive < EARLY_DROP_MS && prefs.getBoolean(PREF_MINIMAL, false)) {
            log(Log.WARN, "early drop with the minimal subscription set; reverting to all notifications")
            prefs.putBoolean(PREF_MINIMAL, false)
        }
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
        kEnc = null; ivEnc = null
        outSeq = 1
        backfillRequested = false
        glucoseBuf = ByteArray(0)
        secSeq = -1; secAt = 0; secBuf = ByteArray(0)
    }

    private fun subscribe(uuid: UUID) = link?.subscribe(uuid) ?: Unit
    private fun write(uuid: UUID, value: ByteArray) = link?.write(uuid, value) ?: Unit

    private fun fail(why: String) {
        log(Log.WARN, "sensor: $why")
        events.onStatus("reconnecting", why)
        link?.disconnect()   // the transport re-arms the connection
    }

    // MARK: security handshake

    private fun startAuthorization() {
        resetSession()
        fresh = kAuth == null
        ctx = security.begin(kAuth, 1)
        if (ctx == 0L) return fail("saved authorization rejected by the security core")
        events.onStatus(if (fresh) "pairing with the sensor" else "authorizing")
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
            write(CH_COMMAND, byteArrayOf(0x11))   // resume with the saved authorization
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
                    if (fresh && value[0].toInt() == 0x04) write(CH_COMMAND, byteArrayOf(0x09))   // send your certificate
                    else fail("unexpected security event ${value[0]}")
                    return
                }
                secCmd = value[0].toInt() and 0xFF
                secLen = value[1].toInt() and 0xFF
                secSeq = -1; secAt = 0; secBuf = ByteArray(secLen)
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
            else -> Unit   // subscribed but unparsed (only in the FULL fallback set)
        }
    }

    private fun securityDataComplete() {
        if (fresh && secCmd == 10 && secLen == 140) {
            // the sensor's certificate → ask for its ephemeral key and send ours
            if (!security.setPatchCertificate(ctx, secBuf)) return fail("sensor certificate rejected")
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
            23 -> {
                r1 = secBuf.copyOfRange(0, 16)
                val nonce = secBuf.copyOfRange(16, 23)
                r2 = ByteArray(16).also { random(it) }
                val reply = security.encryptChallengeReply(ctx, nonce, r1 + r2 + pairing.pin)
                    ?: return fail("challenge encryption failed")
                writeFragments(CH_CHALLENGE, reply)
                write(CH_COMMAND, byteArrayOf(0x08))
            }
            67 -> {
                val plain = security.decryptChallengeResponse(ctx, secBuf.copyOfRange(60, 67), secBuf.copyOfRange(0, 60))
                    ?: return fail("challenge response could not be decrypted")
                if (!plain.copyOfRange(0, 16).contentEquals(r2) || !plain.copyOfRange(16, 32).contentEquals(r1))
                    return fail("challenge mismatch")
                kEnc = plain.copyOfRange(32, 48)
                ivEnc = plain.copyOfRange(48, 56)
                if (fresh) {
                    val saved = security.exportSavedAuthorization(ctx) ?: return fail("could not save the pairing")
                    kAuth = saved
                    fresh = false
                    log(Log.INFO, "sensor paired")
                    events.onPairingState(saved)
                }
                log(Log.INFO, "sensor authorized")
                events.onStatus("authorized, subscribing")
                authorizedAt = clock()
                subscriptions = if (prefs.getBoolean(PREF_MINIMAL, false)) MINIMAL else FULL
                subscriptions.forEach { subscribe(it) }
                link?.schedule(GLUCOSE_TIMEOUT_MS, glucoseTimeout)
                // Features.connPriority (settings / DebugReceiver); the old pref still means "low".
                val prio = when (connPriority()) {
                    "low" -> BluetoothGatt.CONNECTION_PRIORITY_LOW_POWER
                    "balanced" -> BluetoothGatt.CONNECTION_PRIORITY_BALANCED
                    "high" -> BluetoothGatt.CONNECTION_PRIORITY_HIGH
                    else -> if (prefs.getBoolean(PREF_LOW_POWER, false)) BluetoothGatt.CONNECTION_PRIORITY_LOW_POWER else null
                }
                // Readings arrive once a minute, so a long connection interval costs no latency.
                if (prio != null) link?.requestConnectionPriority(prio)
            }
            else -> fail("unexpected challenge length $secLen")
        }
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
        val mgdl = if (uncapped in 39..501) uncapped else reading
        if (mgdl !in 39..501) {
            log(Log.WARN, "ignoring out-of-range glucose $mgdl")
            return
        }

        link?.cancel(glucoseTimeout)
        if (lifeCount == lastLifeCount) return
        lastLifeCount = lifeCount
        events.onStatus("live")
        val now = clock() / 1000
        val rate = if (rawRate == -32768) null else rawRate / 100.0
        events.onReading(Bridge.Reading(epochSec = now, mgdl = mgdl, rate = rate))

        // The packet also carries the matching 5-minute value, which fills the Mac's chart a bit faster.
        val histLifeCount = u16(10)
        val hist = u16(12)
        val histPoint = if (hist in 39..501 && histLifeCount in 1..lifeCount)
            LibreViewPayload.Glucose(histLifeCount, hist, pairing.startEpoch + 60L * (histLifeCount + 1)) else null
        histPoint?.let { events.onHistory(listOf(Bridge.Reading(it.epochSec, it.mgdl, null))) }
        events.onSensorGlucose(LibreViewPayload.Glucose(lifeCount, mgdl, now, rate), listOfNotNull(histPoint))
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
        log(Log.INFO, "requesting history from lifeCount $from")
        sendControl(byteArrayOf(1, 0, 1,
            (from and 0xFF).toByte(), ((from shr 8) and 0xFF).toByte(),
            ((from shr 16) and 0xFF).toByte(), ((from shr 24) and 0xFF).toByte()))
    }

    /** `Patchstatus`: i16 lifeCount, i16 errorData, i16 eventData, i8 index, i8 patchState, i16 currentLifeCount, … */
    private fun onPatchStatus(packet: ByteArray) {
        val d = decrypt(KIND_PATCH_STATUS, packet) ?: return
        if (d.size < 12) return
        val currentLifeCount = (d[8].toInt() and 0xFF) or ((d[9].toInt() and 0xFF) shl 8)
        val state = d[7].toInt() and 0xFF
        log(Log.INFO, "patch status: state=$state currentLifeCount=$currentLifeCount")
        if (PatchState.ended(state, currentLifeCount, pairing.wearMin)) {
            if (!endedSeen) { endedSeen = true; events.onSensorEnded(pairing.serial) }
            if (PatchState.stopNow(state)) return stopForGood("patch state $state")
            events.onStatus("sensor ended", DriverPrefs.ENDED_HINT)   // an expired sensor gets one glucose wait (grace period)
        }
        if (currentLifeCount in 0 until pairing.warmupMin) {
            // no glucose yet: wait for the end of the warm-up instead of reconnecting every 2 minutes
            events.onStatus("warming up — readings in ${app.mauri.sukrara.core.Durations.text(pairing.warmupMin - currentLifeCount)}")
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
        val out = ArrayList<LibreViewPayload.Glucose>()
        var i = 2
        var lifeCount = start
        while (i + 1 < d.size) {
            val v = u16(i)
            if (v in 39..501) out += LibreViewPayload.Glucose(lifeCount, v, pairing.startEpoch + 60L * (lifeCount + 1))
            i += 2
            lifeCount += HISTORY_STEP
        }
        if (out.isNotEmpty()) {
            log(Log.INFO, "history: ${out.size} values from lifeCount $start")
            events.onHistory(out.map { Bridge.Reading(it.epochSec, it.mgdl, null) })
            events.onSensorGlucose(null, out)
        }
    }

    /** Authorized but no glucose: reconnect, falling back to all notifications if we trimmed them. */
    private fun onGlucoseTimeout() {
        if (endedSeen) return stopForGood("no glucose after the sensor's end")
        if (prefs.getBoolean(PREF_MINIMAL, false)) prefs.putBoolean(PREF_MINIMAL, false)
        fail("no glucose data after authorizing")
    }

    /** The sensor has ended: no more reconnects (each one costs battery) until a new sensor is scanned. */
    private fun stopForGood(why: String) {
        log(Log.WARN, "sensor ${pairing.serial} ended ($why): not reconnecting")
        prefs.putBoolean(DriverPrefs.endedKey(pairing.serial), true)
        link?.close()
        events.onStatus("sensor ended", DriverPrefs.ENDED_HINT)
    }
}
