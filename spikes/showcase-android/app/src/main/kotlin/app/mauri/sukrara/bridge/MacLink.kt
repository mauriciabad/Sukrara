// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.bridge

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import app.mauri.sukrara.features.Features
import app.mauri.sukrara.treatments.Treat
import app.mauri.sukrara.treatments.Treatment
import app.mauri.sukrara.treatments.TreatmentJson
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * The legacy Mac link from GlucoBridge (investigations 01 + 04), unchanged on the air so today's GlucoseBar keeps
 * working: GATT service 6c1f0001-… with READING (read/notify), HISTORY (long read, 180 min) and TREATMENTS
 * (JSON sync, SPEC §2). Unencrypted, like GlucoBridge: anyone nearby can read the value (documented in docs/10).
 *
 * Entries: the link can't tell which Mac writes, so new entries, edits and deletes written over it are refused
 * (ATT "write not permitted") unless the wearer turned on Features.macLinkEntries; a Mac that follows over protocol
 * v2 sends its entries there, signed, and the roster `writers` ("Can add entries") decides. LibreView `lv`
 * watermarks for rows the phone already holds are always taken. The phone tells the Mac with `w` in `end` / `cfg`.
 *
 * Power: advertises (LOW_POWER) only while no central is subscribed; no polling, no wakelocks.
 */
@SuppressLint("MissingPermission")
class MacLink(private val ctx: Context) {
    private val tag = "Sukrara.mac"
    private val bt = ctx.getSystemService(BluetoothManager::class.java)
    private var gatt: BluetoothGattServer? = null
    private var readingChar: BluetoothGattCharacteristic? = null
    private var treatChar: BluetoothGattCharacteristic? = null
    private val treatSubscribers = ConcurrentHashMap.newKeySet<BluetoothDevice>()
    private val mtu = ConcurrentHashMap<BluetoothDevice, Int>()
    private val longWrites = ConcurrentHashMap<BluetoothDevice, java.io.ByteArrayOutputStream>()
    private val outbox = ArrayDeque<Triple<BluetoothDevice, BluetoothGattCharacteristic, ByteArray>>()
    private var sending: BluetoothDevice? = null
    private val subscribers = ConcurrentHashMap.newKeySet<BluetoothDevice>()
    @Volatile private var advertising = false
    private val history = ArrayDeque<Bridge.Reading>()
    @Volatile private var latestBytes = ByteArray(0)

    data class Status(val running: Boolean = false, val advertising: Boolean = false, val subscribed: Int = 0, val error: String? = null)
    val status = MutableStateFlow(Status())

    private val onTreatment: (Treatment, Long) -> Unit = { tr, seq -> treatSubscribers.forEach { send(it, recMessage(tr, seq, it)) } }

    fun start(): Boolean {
        if (gatt != null) return true
        val granted = listOf(android.Manifest.permission.BLUETOOTH_CONNECT, android.Manifest.permission.BLUETOOTH_ADVERTISE)
            .all { ctx.checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED }
        if (!granted) { status.value = Status(error = "Bluetooth permission not granted"); return false }
        Treat.init(ctx)
        Treat.store.onChange.addIfAbsent(onTreatment)
        if (!startGattServer()) return false
        startAdvertising()
        status.value = status.value.copy(running = true)
        Log.i(tag, "legacy Mac GATT service ${Bridge.SERVICE} up")
        return true
    }

    fun stop() {
        if (gatt == null) return
        Treat.store.onChange -= onTreatment
        stopAdvertising()
        runCatching { gatt?.clearServices(); gatt?.close() }
        gatt = null
        subscribers.clear(); treatSubscribers.clear()
        synchronized(outbox) { outbox.clear(); sending = null }
        status.value = Status()
        Log.i(tag, "legacy Mac GATT service stopped")
    }

    // ============================================================================================ glucose in

    fun onReading(r: Bridge.Reading) {
        synchronized(history) { insert(r) }
        latestBytes = Bridge.encode(r)
        notifySubscribers()
    }

    fun onHistory(list: List<Bridge.Reading>) {
        if (list.isEmpty()) return
        val newest = synchronized(history) { list.forEach { insert(it) }; history.lastOrNull() }
        if (newest != null && latestBytes.isEmpty()) latestBytes = Bridge.encode(newest)
    }

    private fun insert(r: Bridge.Reading) {
        val existing = history.indexOfLast { kotlin.math.abs(it.epochSec - r.epochSec) < 20 }
        if (existing >= 0) {
            if (r.rate != null || history.elementAt(existing).rate == null) history[existing] = r
            return
        }
        if (history.isEmpty() || r.epochSec >= history.last().epochSec) history.addLast(r)
        else history.add(history.indexOfFirst { it.epochSec > r.epochSec }, r)
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
        val ok = gatt?.notifyCharacteristicChanged(d, ch, false, value) == BluetoothStatusCodes.SUCCESS
        if (!ok) {
            synchronized(outbox) { sending = null }
            pumpOutbox()
        }
    }

    // ============================================================================================ treatments (SPEC §2)

    private fun send(d: BluetoothDevice, msg: String) {
        val ch = treatChar ?: return
        enqueue(d, ch, msg.toByteArray())
    }

    private fun recMessage(tr: Treatment, seq: Long, d: BluetoothDevice): String {
        fun build(t: Treatment) = JsonObject(mapOf("op" to JsonPrimitive("rec"), "s" to JsonPrimitive(seq)) +
            TreatmentJson.encodeToJsonElement(Treatment.serializer(), t).jsonObject).toString()
        val limit = (mtu[d] ?: 23) - 3
        var msg = build(tr)
        if (msg.toByteArray().size > limit) {
            val over = msg.toByteArray().size - limit
            msg = build(tr.copy(n = Treatment.clip(tr.n, maxOf(0, tr.n.toByteArray().size - over))))
        }
        return msg
    }

    /** The wearer switched "Accept entries over the Mac link": tell the connected Macs at once. */
    fun entriesSettingChanged() {
        val msg = JsonObject(mapOf("op" to JsonPrimitive("cfg"), "w" to JsonPrimitive(Features.value.macLinkEntries))).toString()
        treatSubscribers.forEach { send(it, msg) }
    }

    /** Handles one TREATMENTS message; false = refused (answered with "write not permitted"). */
    private fun onTreatmentWrite(d: BluetoothDevice, bytes: ByteArray): Boolean {
        val o = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(bytes.decodeToString()).jsonObject }.getOrNull()
            ?: return true.also { Log.w(tag, "bad treatment message (${bytes.size} B)") }
        when (o["op"]?.jsonPrimitive?.content) {
            "pull" -> {
                val since = o["since"]?.jsonPrimitive?.long ?: 0
                Treat.store.since(since).forEach { (seq, tr) -> send(d, recMessage(tr, seq, d)) }
                send(d, JsonObject(mapOf("op" to JsonPrimitive("end"), "s" to JsonPrimitive(Treat.store.seq),
                    "dev" to JsonPrimitive(Treat.uploader.deviceId), "w" to JsonPrimitive(Features.value.macLinkEntries))).toString())
            }
            "rec" -> {
                val tr = runCatching { TreatmentJson.decodeFromJsonElement(Treatment.serializer(), JsonObject(o - "op" - "s")) }.getOrNull()
                    ?: return true.also { Log.w(tag, "bad treatment record") }
                val what = "${tr.k} ${tr.a} del=${tr.del} (${tr.id.take(8)})"
                if (!Features.value.macLinkEntries) {
                    val cur = Treat.store.get(tr.id)
                    if (cur == null || tr.u > cur.u) {
                        Log.w(tag, "treatment from mac $what REJECTED: entries over the legacy Mac link are off " +
                            "(${if (cur == null) "new entry" else "content change"})")
                        return false
                    }
                    val moved = Treat.store.mergeWatermark(tr)
                    Log.i(tag, "treatment from mac $what → ${if (moved != null) "lv watermark only" else "no change"}")
                    return true
                }
                val merged = Treat.store.merge(tr)
                Log.i(tag, "treatment from mac $what → ${if (merged != null) "merged" else "no change"}")
                if (merged != null) Treat.uploader.kick()
            }
        }
        return true
    }

    // ============================================================================================ GATT + advertising

    private fun startGattServer(): Boolean {
        val server = bt.openGattServer(ctx, callback) ?: run { status.value = Status(error = "openGattServer failed"); return false }
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
        return true
    }

    private fun publishStatus() { status.value = status.value.copy(advertising = advertising, subscribed = subscribers.size) }

    private val callback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState != BluetoothGatt.STATE_CONNECTED) {
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
            publishStatus()
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
                else -> gatt?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value.copyOfRange(offset, value.size))
            }
        }

        override fun onDescriptorWriteRequest(device: BluetoothDevice, requestId: Int, d: BluetoothGattDescriptor,
                                              preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray) {
            val on = value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            if (d.uuid == Bridge.CCCD && d.characteristic.uuid == Bridge.TREATMENTS) {
                if (on) treatSubscribers += device else treatSubscribers -= device
            } else if (d.uuid == Bridge.CCCD) {
                if (on) { subscribers += device; stopAdvertising() }
                else if (subscribers.remove(device) && subscribers.isEmpty()) startAdvertising()
            }
            if (responseNeeded) gatt?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            publishStatus()
        }

        override fun onCharacteristicWriteRequest(device: BluetoothDevice, requestId: Int, ch: BluetoothGattCharacteristic,
                                                  preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray) {
            if (ch.uuid != Bridge.TREATMENTS) {
                if (responseNeeded) gatt?.sendResponse(device, requestId, BluetoothGatt.GATT_WRITE_NOT_PERMITTED, offset, null)
                return
            }
            val ok = if (preparedWrite) true.also { longWrites.getOrPut(device) { java.io.ByteArrayOutputStream() }.write(value) }
                     else onTreatmentWrite(device, value)
            if (responseNeeded) gatt?.sendResponse(device, requestId,
                if (ok) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_WRITE_NOT_PERMITTED, offset, if (ok) value else null)
        }

        override fun onExecuteWrite(device: BluetoothDevice, requestId: Int, execute: Boolean) {
            val buf = longWrites.remove(device)
            val ok = if (execute && buf != null) onTreatmentWrite(device, buf.toByteArray()) else true
            gatt?.sendResponse(device, requestId, if (ok) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_WRITE_NOT_PERMITTED, 0, null)
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) { this@MacLink.mtu[device] = mtu }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            synchronized(outbox) { sending = null }
            pumpOutbox()
        }

        override fun onDescriptorReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, d: BluetoothGattDescriptor) {
            val set = if (d.characteristic.uuid == Bridge.TREATMENTS) treatSubscribers else subscribers
            val v = if (device in set) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            gatt?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, v)
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) { advertising = true; publishStatus() }
        override fun onStartFailure(errorCode: Int) {
            advertising = errorCode == ADVERTISE_FAILED_ALREADY_STARTED
            status.value = status.value.copy(advertising = advertising, error = if (advertising) null else "advertise failed: $errorCode")
        }
    }

    private fun startAdvertising() {
        if (advertising || gatt == null) return
        val adv = bt.adapter?.bluetoothLeAdvertiser ?: return Unit.also { status.value = status.value.copy(error = "Bluetooth off") }
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)
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
        publishStatus()
    }
}
