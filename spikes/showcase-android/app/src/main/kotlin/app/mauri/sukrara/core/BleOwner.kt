// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.core

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.os.ParcelUuid
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import org.nostrdevkit.sdk.Event

/**
 * Investigation 07 — owner side of the BLE transport: GATT server + advertiser (see [Ble] for the framing).
 * Every event the owner publishes is also pushed to subscribed centrals; a central's `HELLO` gets INFO + the cached
 * roster, recent chunks and the latest live event. Incoming `EVENT`s go to the engine's `onEvent(json, "ble:<addr>")`.
 */
@SuppressLint("MissingPermission")
class BleOwner(
    private val ctx: Context,
    private val log: (String) -> Unit,
    private val onEvent: (JSONObject, String) -> Unit,
    private val info: () -> JSONObject,
) : Transport {
    private val bm = ctx.getSystemService(BluetoothManager::class.java)
    private var server: BluetoothGattServer? = null
    private lateinit var tx: BluetoothGattCharacteristic
    private val peers = ConcurrentHashMap<String, Peer>()
    private val cache = object : LinkedHashMap<String, String>(64, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 40
    }
    /** Treatment events by entry (key `tr:<id>`, SPEC §5): the owner's last 30 days, newest 200. */
    private val treatments = object : LinkedHashMap<String, String>(64, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 200
    }
    private var msgId = 0
    private val wraps = ArrayDeque<String>()
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val _connected = MutableStateFlow(0)
    val connected: StateFlow<Int> = _connected
    var bytesOut = 0L; private set

    private class Peer(val dev: BluetoothDevice) {
        var mtu = 23
        var subscribed = false
        val queue = ArrayDeque<ByteArray>()
        var inFlight = false
        val rx = Ble.Reassembler()
    }

    fun start(): Boolean {
        if (server != null) return true
        val adapter = bm.adapter ?: return false.also { log("BLE: no adapter") }
        if (!adapter.isEnabled) return false.also { log("BLE: Bluetooth is off") }
        val s = runCatching { bm.openGattServer(ctx, cb) }.getOrElse { log("BLE: openGattServer failed: $it"); return false }
            ?: return false.also { log("BLE: openGattServer returned null") }
        val svc = BluetoothGattService(Ble.SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val rx = BluetoothGattCharacteristic(Ble.RX,
            BluetoothGattCharacteristic.PROPERTY_WRITE, BluetoothGattCharacteristic.PERMISSION_WRITE)
        tx = BluetoothGattCharacteristic(Ble.TX,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY, BluetoothGattCharacteristic.PERMISSION_READ)
        tx.addDescriptor(BluetoothGattDescriptor(Ble.CCCD,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE))
        svc.addCharacteristic(rx)
        svc.addCharacteristic(tx)
        s.addService(svc)
        server = s
        adapter.bluetoothLeAdvertiser?.startAdvertising(
            AdvertiseSettings.Builder().setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM).setConnectable(true).build(),
            AdvertiseData.Builder().addServiceUuid(ParcelUuid(Ble.SERVICE)).setIncludeDeviceName(false).build(),
            AdvertiseData.Builder().setIncludeDeviceName(true).build(),
            adv,
        ) ?: log("BLE: no advertiser")
        log("BLE: GATT server up, advertising ${Ble.SERVICE}")
        return true
    }

    private val adv = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) = log("BLE: advertising started")
        override fun onStartFailure(errorCode: Int) = log("BLE: advertising FAILED $errorCode")
    }

    private val cb = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                peers[device.address] = Peer(device)
                log("BLE: central ${device.address} connected")
            } else {
                peers.remove(device.address)
                log("BLE: central ${device.address} disconnected (status $status)")
            }
            _connected.value = peers.values.count { it.subscribed }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            peers[device.address]?.mtu = mtu
            log("BLE: ${device.address} MTU $mtu")
        }

        override fun onDescriptorWriteRequest(device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
                                              preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?) {
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            val p = peers[device.address] ?: return
            p.subscribed = value?.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == true ||
                value?.contentEquals(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE) == true
            _connected.value = peers.values.count { it.subscribed }
            log("BLE: ${device.address} notifications ${if (p.subscribed) "on" else "off"}")
        }

        override fun onCharacteristicWriteRequest(device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
                                                  preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?) {
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
            if (characteristic.uuid != Ble.RX || value == null) return
            val p = peers[device.address] ?: Peer(device).also { peers[device.address] = it }
            val m = p.rx.add(value) ?: return
            when (m.optString(0)) {
                "HELLO" -> sendBacklog(p)
                "EVENT" -> m.optJSONObject(1)?.let { onEvent(it, "ble:${device.address}") }
            }
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            val p = peers[device.address] ?: return
            synchronized(p) { p.inFlight = false }
            pump(p)
        }
    }

    private fun sendBacklog(p: Peer) {
        val items = synchronized(cache) { cache.entries.map { it.key to it.value } }
        val trs = synchronized(treatments) { treatments.entries.map { it.key to it.value } }
        // roster first (followers need the device list before data), then chunks oldest → newest, treatments, then live
        val ordered = items.filter { it.first == "roster" } + items.filter { it.first != "roster" && it.first != "live" } +
            trs + items.filter { it.first == "live" } + synchronized(wraps) { wraps.map { "wrap" to it } }
        log("BLE: HELLO from ${p.dev.address} → INFO + ${ordered.size} cached event(s)")
        enqueue(p, Ble.msg("INFO", info()))
        ordered.forEach { enqueue(p, Ble.event(it.second)) }
    }

    private fun enqueue(p: Peer, payload: ByteArray) {
        val id = synchronized(this) { msgId = (msgId + 1) and 0xFFFF; msgId }
        synchronized(p) { Ble.frames(id, payload, p.mtu).forEach { p.queue.add(it) } }
        bytesOut += payload.size
        pump(p)
    }

    private fun pump(p: Peer) {
        val s = server ?: return
        val f = synchronized(p) {
            if (p.inFlight) return
            p.queue.poll()?.also { p.inFlight = true }
        } ?: return
        val rc = runCatching { s.notifyCharacteristicChanged(p.dev, tx, false, f) }.getOrElse { log("BLE: notify threw $it"); -1 }
        if (rc != BluetoothGatt.GATT_SUCCESS) {
            // busy / disconnected: put it back and retry when the next callback (or publish) comes
            synchronized(p) { p.inFlight = false; p.queue.addFirst(f) }
            if (rc == 201 /* ERROR_GATT_WRITE_REQUEST_BUSY */) handler.postDelayed({ pump(p) }, 15)
            else { log("BLE: notify to ${p.dev.address} failed rc=$rc"); synchronized(p) { p.queue.clear() } }
        }
    }

    // ============================================================================================ Transport

    override fun publish(ev: Event, lane: Lane, key: String?) {
        val json = ev.asJson()
        val k = if (lane == Lane.LIVE) key ?: "live" else key   // "alert" keeps its own backlog slot
        if (k != null && k.startsWith("tr:")) synchronized(treatments) { treatments.remove(k); treatments[k] = json }
        else if (k != null) synchronized(cache) { cache.remove(k); cache[k] = json }
        // recent gift wraps (key deliveries…) too, so a follower that reconnects within minutes still gets them
        if (ev.kind().asU16().toInt() == Proto.WRAP) synchronized(wraps) { wraps.addLast(json); while (wraps.size > 10) wraps.removeFirst() }
        val payload = Ble.event(json)
        peers.values.filter { it.subscribed }.forEach { enqueue(it, payload) }
    }

    /** Push a raw event (e.g. an accepted treatment) to every connected follower. */
    fun forward(json: JSONObject, exceptAddr: String?) {
        val payload = Ble.event(json.toString())
        peers.values.filter { it.subscribed && it.dev.address != exceptAddr }.forEach { enqueue(it, payload) }
    }

    override fun subscribe(id: String, filters: () -> List<JSONObject>, onEvent: (JSONObject, String) -> Unit) = Unit
    override fun unsubscribe(id: String) = Unit
    override fun hasSubscription(id: String) = false
    override suspend fun fetch(filters: List<JSONObject>, timeoutMs: Long): List<Pair<JSONObject, String>> = emptyList()
    override fun reconnect() = Unit

    override fun close() {
        runCatching { bm.adapter?.bluetoothLeAdvertiser?.stopAdvertising(adv) }
        runCatching { server?.close() }
        server = null
        peers.clear()
    }
}
