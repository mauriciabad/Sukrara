// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.spike.glucobridge

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import dev.spike.glucobridge.libreview.LibreViewPayload
import dev.spike.glucobridge.treatments.Treatment
import dev.spike.glucobridge.treatments.TreatmentJson
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * Foreground service: takes readings from a [GlucoseSource] and publishes them over a BLE GATT
 * server so a nearby Mac can read them with no shared network.
 *
 * Power notes (the phone is the battery-constrained side):
 *  - advertise in LOW_POWER mode, and only while no central is connected;
 *  - no polling, no wakelocks: everything is driven by source callbacks;
 *  - the 364-byte history blob is built on demand (on a read request), not per reading.
 */
@SuppressLint("MissingPermission") // permissions are requested by MainActivity before the service starts
class BridgeService : Service() {
    private val tag = "GlucoBridge"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var bt: BluetoothManager
    private var gatt: BluetoothGattServer? = null
    private var readingChar: BluetoothGattCharacteristic? = null
    private var treatChar: BluetoothGattCharacteristic? = null
    private val treatSubscribers = ConcurrentHashMap.newKeySet<BluetoothDevice>()
    private val mtu = ConcurrentHashMap<BluetoothDevice, Int>()
    private val longWrites = ConcurrentHashMap<BluetoothDevice, java.io.ByteArrayOutputStream>()
    /** Android allows one outstanding notification per link: queue them, send the next on onNotificationSent. */
    private val outbox = ArrayDeque<Triple<BluetoothDevice, BluetoothGattCharacteristic, ByteArray>>()
    private var sending: BluetoothDevice? = null     // link with a notification in flight
    private val connected = ConcurrentHashMap.newKeySet<BluetoothDevice>()
    private val subscribers = ConcurrentHashMap.newKeySet<BluetoothDevice>()
    private var advertising = false

    private lateinit var source: GlucoseSource
    private val history = ArrayDeque<Bridge.Reading>()      // ascending, ≤ HISTORY_MINUTES entries
    @Volatile private var latestBytes = ByteArray(0)

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        startForeground(1, notification("starting…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        bt = getSystemService(BluetoothManager::class.java)
        Spike.init(this)
        Spike.store.onChange = { tr, seq -> treatSubscribers.forEach { send(it, recMessage(tr, seq, it)) } }
        Spike.uploader.start(scope)
        startGattServer()
        startAdvertising()
        source = createGlucoseSource(this)
        update { it.copy(source = source.name) }
        source.start(scope, sink)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        Spike.store.onChange = null
        source.stop()
        scope.cancel()
        stopAdvertising()
        gatt?.close()
        update { it.copy(advertising = false, subscribed = 0, sourceDetail = "stopped") }
        super.onDestroy()
    }

    // MARK: glucose in

    private val sink = object : GlucoseSource.Sink {
        override fun onReading(reading: Bridge.Reading) {
            synchronized(history) { insert(reading) }
            latestBytes = Bridge.encode(reading)
            notifySubscribers()
            Log.i(tag, "reading ${reading.mgdl} rate ${reading.rate} -> ${subscribers.size} subscriber(s)")
            update { it.copy(latest = reading, history = snapshot(), error = null) }
        }

        override fun onHistory(readings: List<Bridge.Reading>) {
            if (readings.isEmpty()) return
            val newest = synchronized(history) {
                readings.forEach { insert(it) }
                history.lastOrNull()
            }
            newest?.let { latestBytes = Bridge.encode(it) }
            notifySubscribers()
            Log.i(tag, "history +${readings.size} -> ${history.size} points")
            update { it.copy(latest = newest ?: it.latest, history = snapshot()) }
        }

        override fun onStatus(state: String, error: String?) = update { it.copy(sourceDetail = state, error = error) }

        override fun onSensor(serial: String, startEpoch: Long) = Spike.uploader.onSensor(serial, startEpoch)

        override fun onSensorGlucose(current: LibreViewPayload.Glucose?, history: List<LibreViewPayload.Glucose>) {
            if (history.isNotEmpty()) Spike.uploader.onHistory(history)
            current?.let { Spike.uploader.onCurrent(it) }
        }
    }

    private fun snapshot(): List<Bridge.Reading> = synchronized(history) { history.toList() }

    /** Keep [history] ascending, deduplicated by minute, bounded. */
    private fun insert(r: Bridge.Reading) {
        val existing = history.indexOfLast { kotlin.math.abs(it.epochSec - r.epochSec) < 20 }
        if (existing >= 0) {
            if (r.rate != null || history.elementAt(existing).rate == null) history.set(existing, r)
            return
        }
        if (history.isEmpty() || r.epochSec >= history.last().epochSec) history.addLast(r)
        else {
            val at = history.indexOfFirst { it.epochSec > r.epochSec }
            history.add(at, r)
        }
        val cutoff = history.last().epochSec - Bridge.HISTORY_MINUTES * 60L
        while (history.isNotEmpty() && history.first().epochSec < cutoff) history.removeFirst()
    }

    private fun notifySubscribers() {
        val ch = readingChar ?: return
        val value = latestBytes
        for (d in subscribers) enqueue(d, ch, value)
    }

    private fun enqueue(d: BluetoothDevice, ch: BluetoothGattCharacteristic, value: ByteArray) {
        synchronized(outbox) { outbox.addLast(Triple(d, ch, value)) }
        pumpOutbox()
    }

    private fun pumpOutbox() {
        val (d, ch, value) = synchronized(outbox) {
            if (sending != null) return
            outbox.removeFirstOrNull()?.also { sending = it.first } ?: return
        }
        val ok = if (Build.VERSION.SDK_INT >= 33) gatt?.notifyCharacteristicChanged(d, ch, false, value) == android.bluetooth.BluetoothStatusCodes.SUCCESS
            else @Suppress("DEPRECATION") { ch.value = value; gatt?.notifyCharacteristicChanged(d, ch, false) == true }
        if (!ok) {           // link gone or stack busy: drop it, the Mac re-pulls on reconnect
            Log.w(tag, "notify to ${d.address} failed")
            synchronized(outbox) { sending = null }
            pumpOutbox()
        }
    }

    // MARK: treatment sync (SPEC §2)

    private fun send(d: BluetoothDevice, msg: String) {
        val ch = treatChar ?: return
        enqueue(d, ch, msg.toByteArray())
    }

    /** `rec` message; clips the note if this link's MTU is small. */
    private fun recMessage(tr: Treatment, seq: Long, d: BluetoothDevice): String {
        fun build(t: Treatment) = JsonObject(mapOf("op" to JsonPrimitive("rec"), "s" to JsonPrimitive(seq)) +
            TreatmentJson.encodeToJsonElement(Treatment.serializer(), t).jsonObject).toString()
        val limit = (mtu[d] ?: 23) - 3
        var msg = build(tr)
        if (msg.toByteArray().size > limit) {
            val over = msg.toByteArray().size - limit
            msg = build(tr.copy(n = Treatment.clip(tr.n, maxOf(0, tr.n.toByteArray().size - over))))
            if (msg.toByteArray().size > limit) Log.w(tag, "rec ${tr.id} is ${msg.length} B > MTU-3 $limit")
        }
        return msg
    }

    private fun onTreatmentWrite(d: BluetoothDevice, bytes: ByteArray) {
        val o = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(bytes.decodeToString()).jsonObject }.getOrNull()
            ?: run { Log.w(tag, "bad treatment message (${bytes.size} B)"); return }
        when (o["op"]?.jsonPrimitive?.content) {
            "pull" -> {
                val since = o["since"]?.jsonPrimitive?.long ?: 0
                val rows = Spike.store.since(since)
                Log.i(tag, "treatments: pull since $since → ${rows.size} row(s)")
                rows.forEach { (seq, tr) -> send(d, recMessage(tr, seq, d)) }
                send(d, JsonObject(mapOf("op" to JsonPrimitive("end"), "s" to JsonPrimitive(Spike.store.seq),
                    "dev" to JsonPrimitive(Spike.uploader.deviceId))).toString())
            }
            "rec" -> {
                val tr = TreatmentJson.decodeFromJsonElement(Treatment.serializer(), JsonObject(o - "op" - "s"))
                val merged = Spike.store.merge(tr)      // onChange notifies every subscriber, incl. the writer
                Log.i(tag, "treatments: from mac ${tr.k} ${tr.a} del=${tr.del} → ${if (merged != null) "merged" else "no change"}")
                if (merged != null) Spike.uploader.kick()
            }
        }
    }

    // MARK: status + notification

    private fun update(f: (Bridge.Status) -> Bridge.Status) {
        val s = f(Bridge.status.value)
        Bridge.status.value = s
        val text = (s.latest?.let { "${it.mgdl} mg/dL ${it.rate?.let { r -> "%+.1f/min".format(r) } ?: ""} · " } ?: "") +
            "${s.subscribed} subscribed · ${s.sourceDetail ?: s.source}"
        getSystemService(NotificationManager::class.java).notify(1, notification(text))
    }

    private fun notification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("bridge", "Glucose bridge", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, "bridge")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("GlucoBridge")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    // MARK: BLE GATT server + advertising

    private fun startGattServer() {
        val server = bt.openGattServer(this, gattCallback) ?: run { update { it.copy(error = "openGattServer failed") }; return }
        val service = BluetoothGattService(Bridge.SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val reading = BluetoothGattCharacteristic(Bridge.READING,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ)
        reading.addDescriptor(BluetoothGattDescriptor(Bridge.CCCD,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE))
        val hist = BluetoothGattCharacteristic(Bridge.HISTORY,
            BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ)
        val treat = BluetoothGattCharacteristic(Bridge.TREATMENTS,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_WRITE)
        treat.addDescriptor(BluetoothGattDescriptor(Bridge.CCCD,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE))
        service.addCharacteristic(reading)
        service.addCharacteristic(hist)
        service.addCharacteristic(treat)
        server.addService(service)
        gatt = server
        readingChar = reading
        treatChar = treat
    }

    private val gattCallback = object : BluetoothGattServerCallback() {
        // NB: Android also reports the phone's *outgoing* links here (the sensor we read in the
        // direct flavor), so "connected" is not a count of Macs. Only a CCCD write identifies a
        // real client of ours — key everything off that.
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothGatt.STATE_CONNECTED) {
                connected += device
            } else {
                connected -= device
                treatSubscribers -= device
                mtu -= device
                longWrites -= device
                synchronized(outbox) {
                    outbox.removeAll { it.first == device }
                    if (sending == device) sending = null
                }
                pumpOutbox()
                if (subscribers.remove(device) && subscribers.isEmpty()) startAdvertising()
            }
            Log.i(tag, "gatt-server peer ${device.address} state $newState")
            update { it.copy(subscribed = subscribers.size) }
        }

        override fun onCharacteristicReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, ch: BluetoothGattCharacteristic) {
            val value = when (ch.uuid) {
                Bridge.READING -> latestBytes
                Bridge.HISTORY -> synchronized(history) { Bridge.encodeHistory(history.toList()) }
                else -> null
            }
            when {
                value == null -> gatt?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, offset, null)
                offset > value.size -> gatt?.sendResponse(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset, null)
                // Long reads: the central asks again with increasing offsets; the stack truncates to MTU.
                else -> gatt?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value.copyOfRange(offset, value.size))
            }
        }

        override fun onDescriptorWriteRequest(device: BluetoothDevice, requestId: Int, d: BluetoothGattDescriptor,
                                              preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray) {
            if (d.uuid == Bridge.CCCD && d.characteristic.uuid == Bridge.TREATMENTS) {
                if (value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)) treatSubscribers += device
                else treatSubscribers -= device
            } else if (d.uuid == Bridge.CCCD) {
                if (value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)) {
                    subscribers += device
                    stopAdvertising()       // a real client is subscribed; stop spending radio on adverts
                } else if (subscribers.remove(device) && subscribers.isEmpty()) {
                    startAdvertising()
                }
            }
            if (responseNeeded) gatt?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            update { it.copy(subscribed = subscribers.size) }
        }

        override fun onCharacteristicWriteRequest(device: BluetoothDevice, requestId: Int, ch: BluetoothGattCharacteristic,
                                                  preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray) {
            if (ch.uuid != Bridge.TREATMENTS) {
                if (responseNeeded) gatt?.sendResponse(device, requestId, BluetoothGatt.GATT_WRITE_NOT_PERMITTED, offset, null)
                return
            }
            if (preparedWrite) {    // long write: buffer until onExecuteWrite
                longWrites.getOrPut(device) { java.io.ByteArrayOutputStream() }.write(value)
            } else {
                onTreatmentWrite(device, value)
            }
            if (responseNeeded) gatt?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
        }

        override fun onExecuteWrite(device: BluetoothDevice, requestId: Int, execute: Boolean) {
            val buf = longWrites.remove(device)
            if (execute && buf != null) onTreatmentWrite(device, buf.toByteArray())
            gatt?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            this@BridgeService.mtu[device] = mtu
            Log.i(tag, "mtu ${device.address} = $mtu")
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            synchronized(outbox) { sending = null }
            pumpOutbox()
        }

        override fun onDescriptorReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, d: BluetoothGattDescriptor) {
            if (d.characteristic.uuid == Bridge.TREATMENTS) {
                val v = if (device in treatSubscribers) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                gatt?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, v)
                return
            }
            val v = if (device in subscribers) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    else BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            gatt?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, v)
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            advertising = true
            update { it.copy(advertising = true) }
        }
        override fun onStartFailure(errorCode: Int) {
            advertising = errorCode == ADVERTISE_FAILED_ALREADY_STARTED
            if (advertising) return update { it.copy(advertising = true) }
            update { it.copy(advertising = false, error = "advertise failed: $errorCode") }
        }
    }

    private fun startAdvertising() {
        if (advertising) return
        val adv = bt.adapter?.bluetoothLeAdvertiser ?: return update { it.copy(error = "Bluetooth off / no advertiser") }
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)   // ~1 s interval: the Mac scans continuously
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        val data = AdvertiseData.Builder().addServiceUuid(ParcelUuid(Bridge.SERVICE)).setIncludeDeviceName(false).build()
        adv.startAdvertising(settings, data, advertiseCallback)
    }

    private fun stopAdvertising() {
        if (!advertising) return
        bt.adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
        advertising = false
        update { it.copy(advertising = false) }
    }
}
