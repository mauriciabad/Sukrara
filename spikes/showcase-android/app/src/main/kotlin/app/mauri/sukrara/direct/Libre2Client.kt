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
import android.os.Handler
import android.os.Looper
import android.util.Log
import app.mauri.sukrara.bridge.Bridge
import app.mauri.sukrara.bridge.GlucoseSource
import java.util.UUID

/**
 * Bluetooth link to a Libre 2 / 2 Plus after [Libre2Nfc] enabled streaming. Per connection (Juggluco
 * Libre2GattCallback.java:240-270, 330-370; DiaBLE BluetoothDelegate.swift:533-540):
 *  1. write the streaming unlock payload (unlock count + 1) to F001;
 *  2. once written, subscribe to F002;
 *  3. every minute the sensor sends 46 bytes as 20 + 18 + 8 notifications (Libre2GattCallback.java:415-445) →
 *     [Libre2Crypto.decryptBle] → [Libre2Sensor.parsePacket] → mg/dL with the FRAM's factory calibration.
 * No bonding. Values are not sent to LibreView ([GlucoseSource.Sink.onSensorGlucose] isn't called): they come from
 * the open approximation, not Abbott's algorithm.
 */
@SuppressLint("MissingPermission")
class Libre2Client(
    private val context: Context,
    private var pairing: Libre2Pairing,
    private val sink: GlucoseSource.Sink,
    private val prefs: SharedPreferences,
) {
    companion object {
        private const val TAG = "Sukrara.libre2"
        private val SERVICE = UUID.fromString("0000fde3-0000-1000-8000-00805f9b34fb")
        private val CH_LOGIN = UUID.fromString("0000f001-0000-1000-8000-00805f9b34fb")
        private val CH_DATA = UUID.fromString("0000f002-0000-1000-8000-00805f9b34fb")
        private val CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        /** The sensor sends once a minute; a silent link this long is dropped and re-armed. */
        private const val SILENCE_MS = 4 * 60_000L
        fun endedKey(serial: String) = "ended2:$serial"
        const val ENDED_HINT = "Replace the sensor, then scan the new one: Settings → Sensor"
    }

    private val handler = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var closing = false
    private var buf = ByteArray(0)
    private var lastAge = -1
    private var endedReported = false
    private val silence = Runnable { Log.w(TAG, "no data for ${SILENCE_MS / 1000}s, reconnecting"); gatt?.disconnect() }

    fun connect() {
        closing = false
        val adapter = context.getSystemService(BluetoothManager::class.java).adapter
        if (adapter == null || !adapter.isEnabled) return sink.onStatus("waiting for Bluetooth", "Bluetooth is off")
        sink.onStatus("connecting to ${pairing.serial}")
        // autoConnect: the controller attaches whenever the sensor advertises (as for the Libre 3).
        gatt = adapter.getRemoteDevice(pairing.address).connectGatt(context, true, callback, BluetoothDevice.TRANSPORT_LE)
    }

    fun close() {
        closing = true
        handler.removeCallbacks(silence)
        gatt?.close()
        gatt = null
    }

    private fun fail(why: String) {
        Log.w(TAG, "sensor: $why")
        sink.onStatus("reconnecting", why)
        gatt?.disconnect()
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(TAG, "sensor connected")
                sink.onStatus("discovering services")
                handler.post { g.discoverServices() }
            } else {
                Log.i(TAG, "sensor disconnected (status $status)")
                handler.removeCallbacks(silence)
                buf = ByteArray(0)
                if (!closing) {
                    sink.onStatus("waiting for sensor")
                    handler.postDelayed({ if (!closing) g.connect() }, 1_000)
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val login = g.getService(SERVICE)?.getCharacteristic(CH_LOGIN)
            if (login == null || g.getService(SERVICE)?.getCharacteristic(CH_DATA) == null)
                return fail("Libre 2 service not found (status $status)")
            pairing = pairing.copy(unlockCount = pairing.unlockCount + 1).also { it.save(context) }
            val payload = Libre2Crypto.streamingUnlockPayload(pairing.uid, pairing.patchInfo, pairing.unlockCode,
                pairing.unlockCount)
            Log.i(TAG, "unlocking (count ${pairing.unlockCount})")
            sink.onStatus("unlocking")
            if (g.writeCharacteristic(login, payload, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) != BluetoothGatt.GATT_SUCCESS)
                fail("unlock write rejected")
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return fail("unlock write failed: $status")
            val data = g.getService(SERVICE)?.getCharacteristic(CH_DATA) ?: return fail("F002 missing")
            g.setCharacteristicNotification(data, true)
            val d = data.getDescriptor(CCCD) ?: return fail("CCCD missing")
            if (g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) != BluetoothGatt.GATT_SUCCESS)
                fail("subscribe rejected")
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return fail("subscribe failed: $status")
            Log.i(TAG, "unlocked, waiting for data")
            sink.onStatus("connected, waiting for the next reading")
            handler.removeCallbacks(silence)
            handler.postDelayed(silence, SILENCE_MS)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
            if (ch.uuid == CH_DATA) handler.post { onFragment(value) }
        }
    }

    private fun onFragment(v: ByteArray) {
        when (v.size) {
            20 -> buf = v
            18 -> buf = if (buf.size == 20) buf + v else ByteArray(0)
            8 -> {
                if (buf.size == 38) onPacket(buf + v)
                buf = ByteArray(0)
            }
            else -> Log.w(TAG, "unexpected ${v.size}-byte notification")
        }
    }

    private fun onPacket(packet: ByteArray) {
        handler.removeCallbacks(silence)
        handler.postDelayed(silence, SILENCE_MS)
        val d = Libre2Crypto.decryptBle(pairing.uid, packet) ?: return fail("packet didn't decrypt (CRC)")
        val p = Libre2Sensor.parsePacket(d)
        if (p.wearMin == lastAge) return
        lastAge = p.wearMin
        val now = System.currentTimeMillis() / 1000
        Log.i(TAG, "packet: age ${p.wearMin} min, raw ${p.trend.map { it.raw }} / ${p.history.map { it.raw }}")

        // The sensor's own age is the truth for its start (a takeover only knew the FRAM age at scan time).
        val start = now - 60L * p.wearMin
        if (kotlin.math.abs(start - pairing.startEpoch) > 180) {
            pairing = pairing.copy(startEpoch = start).also { it.save(context) }
            sink.onSensor(pairing.info)
        }
        if (p.wearMin >= pairing.wearMin) return ended()
        if (p.wearMin < pairing.warmupMin)
            return sink.onStatus("warming up — readings in ${pairing.warmupMin - p.wearMin} min")

        val cal = pairing.calibration
        val trend = p.trend.mapNotNull { r -> Libre2Sensor.glucose(r, cal)?.let { r to it } }
        val current = trend.firstOrNull { it.first.ageMin == p.wearMin }
        if (current == null) {
            Log.w(TAG, "no valid current value (raw ${p.trend[0].raw})")
            return sink.onStatus("live", "the sensor sent no valid value this minute")
        }
        val rate = Libre2Sensor.rate(trend.map { it.first.ageMin - p.wearMin to it.second })
        sink.onStatus("live")
        sink.onReading(Bridge.Reading(epochSec = now, mgdl = current.second, rate = rate))
        val hist = p.history.mapNotNull { r ->
            Libre2Sensor.glucose(r, cal)?.takeIf { r.ageMin >= pairing.warmupMin }
                ?.let { Bridge.Reading(start + 60L * r.ageMin, it, null) }
        } + trend.drop(1).filter { it.first.ageMin >= pairing.warmupMin }
            .map { Bridge.Reading(start + 60L * it.first.ageMin, it.second, null) }
        if (hist.isNotEmpty()) sink.onHistory(hist)
    }

    private fun ended() {
        if (endedReported) return
        endedReported = true
        Log.w(TAG, "sensor ${pairing.serial} reached its wear length: not reconnecting")
        prefs.edit().putBoolean(endedKey(pairing.serial), true).apply()
        sink.onSensorEnded(pairing.serial)
        sink.onStatus("sensor ended", ENDED_HINT)
        close()
    }
}
