// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.direct

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import app.mauri.sukrara.bridge.Bridge
import app.mauri.sukrara.bridge.GlucoseSource
import app.mauri.sukrara.libreview.LibreViewPayload
import java.security.SecureRandom
import java.util.UUID

/**
 * Reads a FreeStyle Libre 3 / 3 Plus sensor directly over BLE, using the pairing data in
 * [SensorPairing]. Right after an NFC scan the pairing has no saved authorization yet: the first connection then
 * runs the fresh pairing (certificates + ephemeral keys, after Juggluco's Libre3GattCallback) and hands the
 * resulting authorization to [onAuthorized]; later connections resume with it.
 *
 * Read-only: nothing is ever written to the sensor beyond the security handshake and history requests.
 *
 * Power: no app-level scanning (connect by address with autoConnect, which the Bluetooth controller
 * handles), a low-power connection interval once live, and only the notifications we actually parse.
 */
@SuppressLint("MissingPermission")
internal class Libre3Client(
    private val context: Context,
    private val pairing: SensorPairing,
    private val sink: GlucoseSource.Sink,
    private val prefs: SharedPreferences,
    private val onAuthorized: (ByteArray) -> Unit = {},
) {
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
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        /**
         * Every notifying characteristic, in Juggluco's order. The sensor runs a full data cycle
         * every minute and drops the link if we are not subscribed to all of them: subscribing to
         * only the ones we parse cost a disconnect at exactly 60 s (measured 2026-10-15).
         */
        private val FULL = listOf(CH_PATCH_CONTROL, CH_EVENT_LOG, CH_HISTORIC, CH_CLINICAL, CH_FACTORY,
                                  CH_GLUCOSE, CH_PATCH_STATUS)
        /** Only what we parse. Kept for battery experiments; see [PREF_MINIMAL]. */
        private val MINIMAL = listOf(CH_PATCH_CONTROL, CH_GLUCOSE, CH_PATCH_STATUS)

        private const val PREF_MINIMAL = "useMinimalSubscriptions"
        /** Set when a sensor has ended ([PatchState]): [DirectSource] doesn't connect to it again. */
        fun endedKey(serial: String) = "ended:$serial"
        /** Source status detail for an ended sensor (shown under the card and on the Sensor page). */
        const val ENDED_HINT = "Replace the sensor, then scan the new one: Settings → Sensor"
        private const val PREF_LOW_POWER = "useLowPowerInterval"
        private const val GLUCOSE_TIMEOUT_MS = 120_000L
        /** A drop this soon after authorizing means the session setup was not acceptable to the sensor. */
        private const val EARLY_DROP_MS = 90_000L

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
        /** A Libre 3 gives no glucose for its first hour. */

        /**
         * App certificate for security level 1, sent during a fresh pairing (public; from Juggluco's KEYSCrypto.java,
         * LIBRE3_APP_CERTIFICATES_B[1]).
         */
        private val APP_CERTIFICATE = (
            "03030102030405060708090a0b0c0d0e0f10000161897655010000000000000000" +
            "048242be33f1a330880112fa62cc4842a43d1204922ad201d8775bb226f611f75b0ef3d5bc6cc4317caa457584ab003f" +
            "1712336089d3a4f29838ed0dc666deaea2" +
            "d65a00dfff5d7bcae21655e302e3458e774daaaaca87af75f1b87884b18d4ce875d0d108c903a834471a4ff674b2d30b" +
            "cba062373014b7786e4437b177aec3c8").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    private val handler = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var closing = false
    private val chars = HashMap<UUID, BluetoothGattCharacteristic>()

    // serialized GATT operations (Android allows one outstanding operation per connection)
    private val ops = ArrayDeque<() -> Unit>()
    private var opRunning = false

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

    // MARK: lifecycle

    fun connect() {
        closing = false
        val adapter = context.getSystemService(BluetoothManager::class.java).adapter
        if (adapter == null || !adapter.isEnabled) {
            sink.onStatus("waiting for Bluetooth", "Bluetooth is off")
            return
        }
        val device = adapter.getRemoteDevice(pairing.address)
        sink.onStatus("connecting to ${pairing.serial}")
        // autoConnect = true: the controller reconnects whenever the sensor advertises, with no
        // app-level scanning. Slower to attach, far cheaper than scanning ourselves.
        gatt = device.connectGatt(context, true, callback, BluetoothDevice.TRANSPORT_LE)
    }

    fun close() {
        closing = true
        handler.removeCallbacks(glucoseTimeout)
        resetSession()
        gatt?.close()
        gatt = null
    }

    private fun resetSession() {
        if (ctx != 0L) Libre3Crypto.free(ctx)
        ctx = 0L
        kEnc = null; ivEnc = null
        outSeq = 1
        backfillRequested = false
        glucoseBuf = ByteArray(0)
        secSeq = -1; secAt = 0; secBuf = ByteArray(0)
        synchronized(this) { ops.clear(); opRunning = false }
    }

    // MARK: operation queue

    private fun enqueue(op: () -> Unit) {
        synchronized(this) { ops.addLast(op) }
        pump()
    }

    private fun pump() {
        val next = synchronized(this) {
            if (opRunning) return
            val op = ops.removeFirstOrNull() ?: return
            opRunning = true
            op
        }
        next()
    }

    private fun opDone() {
        synchronized(this) { opRunning = false }
        pump()
    }

    private fun enableNotify(uuid: UUID) = enqueue {
        val g = gatt
        val ch = chars[uuid]
        if (g == null || ch == null) return@enqueue fail("characteristic $uuid missing")
        g.setCharacteristicNotification(ch, true)
        val d = ch.getDescriptor(CCCD) ?: return@enqueue fail("CCCD missing on $uuid")
        val value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        @Suppress("DEPRECATION")
        val ok = if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(d, value) == BluetoothGatt.GATT_SUCCESS
                 else { d.value = value; g.writeDescriptor(d) }
        if (!ok) fail("writeDescriptor($uuid) rejected")
    }

    private fun write(uuid: UUID, value: ByteArray) = enqueue {
        val g = gatt
        val ch = chars[uuid]
        if (g == null || ch == null) return@enqueue fail("characteristic $uuid missing")
        @Suppress("DEPRECATION")
        val ok = if (Build.VERSION.SDK_INT >= 33)
            g.writeCharacteristic(ch, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothGatt.GATT_SUCCESS
        else {
            ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            ch.value = value
            g.writeCharacteristic(ch)
        }
        if (!ok) fail("writeCharacteristic($uuid) rejected")
    }

    private fun fail(why: String) {
        Log.w(TAG, "sensor: $why")
        sink.onStatus("reconnecting", why)
        gatt?.disconnect()   // callback re-arms the connection
    }

    // MARK: GATT callbacks

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(TAG, "sensor connected")
                sink.onStatus("discovering services")
                handler.post { g.discoverServices() }
            } else {
                val alive = System.currentTimeMillis() - authorizedAt
                Log.i(TAG, "sensor disconnected (status $status) " +
                    if (authorizedAt > 0) "after ${alive / 1000}s" else "before authorizing")
                if (authorizedAt > 0 && alive < EARLY_DROP_MS && prefs.getBoolean(PREF_MINIMAL, false)) {
                    Log.w(TAG, "early drop with the minimal subscription set; reverting to all notifications")
                    prefs.edit().putBoolean(PREF_MINIMAL, false).apply()
                }
                authorizedAt = 0
                resetSession()
                handler.removeCallbacks(glucoseTimeout)
                if (!closing) {
                    sink.onStatus("waiting for sensor")
                    handler.postDelayed({ if (!closing) g.connect() }, 1_000)
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            chars.clear()
            g.services.forEach { s -> s.characteristics.forEach { chars[it.uuid] = it } }
            if (!chars.containsKey(CH_COMMAND) || !chars.containsKey(CH_GLUCOSE))
                return fail("Libre 3 services not found (status $status)")
            startAuthorization()
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return fail("descriptor write failed: $status")
            opDone()
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return fail("write to ${ch.uuid} failed: $status")
            opDone()
        }

        // API 33+
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) =
            handleNotification(ch.uuid, value)

        @Deprecated("Deprecated in Java")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            handleNotification(ch.uuid, ch.value ?: return)
        }
    }

    // MARK: security handshake

    private fun startAuthorization() {
        resetSession()
        fresh = kAuth == null
        ctx = Libre3Crypto.begin(kAuth, 1)
        if (ctx == 0L) return fail("saved authorization rejected by the security core")
        sink.onStatus(if (fresh) "pairing with the sensor" else "authorizing")
        enableNotify(CH_COMMAND)
        enableNotify(CH_CERT)
        enableNotify(CH_CHALLENGE)
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
            if (!Libre3Crypto.setPatchCertificate(ctx, secBuf)) return fail("sensor certificate rejected")
            val eph = Libre3Crypto.createEphemeralPublicKey(ctx) ?: return fail("could not create pairing keys")
            write(CH_COMMAND, byteArrayOf(0x0D))
            writeFragments(CH_CERT, byteArrayOf(0x04) + eph)
            write(CH_COMMAND, byteArrayOf(0x0E))
            return
        }
        if (fresh && secCmd == 15 && secLen == 65) {
            // the sensor's ephemeral key → shared authorization, then the same challenge as a resume
            if (!Libre3Crypto.deriveAuthorizationRoot(ctx, secBuf)) return fail("pairing key agreement failed")
            write(CH_COMMAND, byteArrayOf(0x11))
            return
        }
        if (secCmd != 8) return fail("unexpected security data cmd=$secCmd len=$secLen")
        when (secLen) {
            23 -> {
                r1 = secBuf.copyOfRange(0, 16)
                val nonce = secBuf.copyOfRange(16, 23)
                r2 = ByteArray(16).also { SecureRandom().nextBytes(it) }
                val reply = Libre3Crypto.encryptChallengeReply(ctx, nonce, r1 + r2 + pairing.pin)
                    ?: return fail("challenge encryption failed")
                writeFragments(CH_CHALLENGE, reply)
                write(CH_COMMAND, byteArrayOf(0x08))
            }
            67 -> {
                val plain = Libre3Crypto.decryptChallengeResponse(ctx, secBuf.copyOfRange(60, 67), secBuf.copyOfRange(0, 60))
                    ?: return fail("challenge response could not be decrypted")
                if (!plain.copyOfRange(0, 16).contentEquals(r2) || !plain.copyOfRange(16, 32).contentEquals(r1))
                    return fail("challenge mismatch")
                kEnc = plain.copyOfRange(32, 48)
                ivEnc = plain.copyOfRange(48, 56)
                if (fresh) {
                    val saved = Libre3Crypto.exportSavedAuthorization(ctx) ?: return fail("could not save the pairing")
                    kAuth = saved
                    fresh = false
                    Log.i(TAG, "sensor paired")
                    onAuthorized(saved)
                }
                Log.i(TAG, "sensor authorized")
                sink.onStatus("authorized, subscribing")
                authorizedAt = System.currentTimeMillis()
                subscriptions = if (prefs.getBoolean(PREF_MINIMAL, false)) MINIMAL else FULL
                subscriptions.forEach { enableNotify(it) }
                handler.postDelayed(glucoseTimeout, GLUCOSE_TIMEOUT_MS)
                // Sukrara: Features.connPriority (settings / DebugReceiver); the old pref still means "low".
                val prio = when (app.mauri.sukrara.features.Features.value.connPriority) {
                    "low" -> BluetoothGatt.CONNECTION_PRIORITY_LOW_POWER
                    "balanced" -> BluetoothGatt.CONNECTION_PRIORITY_BALANCED
                    "high" -> BluetoothGatt.CONNECTION_PRIORITY_HIGH
                    else -> if (prefs.getBoolean(PREF_LOW_POWER, false)) BluetoothGatt.CONNECTION_PRIORITY_LOW_POWER else null
                }
                if (prio != null) enqueue {
                    // Readings arrive once a minute, so a long connection interval costs no latency.
                    gatt?.requestConnectionPriority(prio)
                    opDone()
                }
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
        return Libre3Crypto.ccmDecrypt(key, nonce, packet.copyOfRange(0, n - 2))
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
            Log.w(TAG, "ignoring out-of-range glucose $mgdl")
            return
        }

        handler.removeCallbacks(glucoseTimeout)
        if (lifeCount == lastLifeCount) return
        lastLifeCount = lifeCount
        sink.onStatus("live")
        val now = System.currentTimeMillis() / 1000
        val rate = if (rawRate == -32768) null else rawRate / 100.0
        sink.onReading(Bridge.Reading(epochSec = now, mgdl = mgdl, rate = rate))

        // The packet also carries the matching 5-minute value, which fills the Mac's chart a bit faster.
        val histLifeCount = u16(10)
        val hist = u16(12)
        val histPoint = if (hist in 39..501 && histLifeCount in 1..lifeCount)
            LibreViewPayload.Glucose(histLifeCount, hist, pairing.startEpoch + 60L * (histLifeCount + 1)) else null
        histPoint?.let { sink.onHistory(listOf(Bridge.Reading(it.epochSec, it.mgdl, null))) }
        sink.onSensorGlucose(LibreViewPayload.Glucose(lifeCount, mgdl, now, rate), listOfNotNull(histPoint))
    }

    /** Encrypts a command and writes it to PatchControl (same framing as incoming packets). */
    private fun sendControl(command: ByteArray) {
        val key = kEnc ?: return
        val iv = ivEnc ?: return
        val seq = outSeq++
        val nonce = byteArrayOf((seq and 0xFF).toByte(), (seq shr 8).toByte()) + DESCRIPTORS[KIND_CONTROL] + iv
        val body = Libre3Crypto.ccmEncrypt(key, nonce, command) ?: return
        write(CH_PATCH_CONTROL, body + byteArrayOf((seq and 0xFF).toByte(), (seq shr 8).toByte()))
    }

    /** `{1, 0, arg, from(i32)}`: send stored 5-minute values starting at historic life count [from]. */
    private fun requestHistory(from: Int) {
        Log.i(TAG, "requesting history from lifeCount $from")
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
        Log.i(TAG, "patch status: state=$state currentLifeCount=$currentLifeCount")
        if (PatchState.ended(state, currentLifeCount, pairing.wearMin)) {
            if (!endedSeen) { endedSeen = true; sink.onSensorEnded(pairing.serial) }
            if (PatchState.stopNow(state)) return stopForGood("patch state $state")
            sink.onStatus("sensor ended", ENDED_HINT)   // an expired sensor gets one glucose wait (grace period)
        }
        if (currentLifeCount in 0 until pairing.warmupMin) {
            // no glucose yet: wait for the end of the warm-up instead of reconnecting every 2 minutes
            sink.onStatus("warming up — readings in ${pairing.warmupMin - currentLifeCount} min")
            handler.removeCallbacks(glucoseTimeout)
            handler.postDelayed(glucoseTimeout, (pairing.warmupMin - currentLifeCount) * 60_000L + GLUCOSE_TIMEOUT_MS)
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
            Log.i(TAG, "history: ${out.size} values from lifeCount $start")
            sink.onHistory(out.map { Bridge.Reading(it.epochSec, it.mgdl, null) })
            sink.onSensorGlucose(null, out)
        }
    }

    /** Authorized but no glucose: reconnect, falling back to all notifications if we trimmed them. */
    private fun onGlucoseTimeout() {
        if (endedSeen) return stopForGood("no glucose after the sensor's end")
        if (prefs.getBoolean(PREF_MINIMAL, false)) prefs.edit().putBoolean(PREF_MINIMAL, false).apply()
        fail("no glucose data after authorizing")
    }

    /** The sensor has ended: no more reconnects (each one costs battery) until a new sensor is scanned. */
    private fun stopForGood(why: String) {
        Log.w(TAG, "sensor ${pairing.serial} ended ($why): not reconnecting")
        prefs.edit().putBoolean(endedKey(pairing.serial), true).apply()
        close()
        sink.onStatus("sensor ended", ENDED_HINT)
    }
}
