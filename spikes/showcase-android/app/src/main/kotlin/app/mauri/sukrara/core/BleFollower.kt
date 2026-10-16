// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.core

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import java.util.ArrayDeque
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import org.nostrdevkit.sdk.Event

data class NearbyOwner(val address: String, val name: String, val rssi: Int)

/**
 * Investigation 07 — follower side of the BLE transport: scan for [Ble.SERVICE], connect, MTU 517, subscribe to
 * [Ble.TX], send `HELLO`, then every `EVENT` goes to the engine's `onEvent(json, "ble:<addr>")` (same acceptance rules
 * as relay events). Reconnects by itself while a target address is set. [publish] writes events to the owner.
 */
@SuppressLint("MissingPermission")
class BleFollower(
    private val ctx: Context,
    private val log: (String) -> Unit,
    private val onEvent: (JSONObject, String) -> Unit,
) : Transport {
    private val bm = ctx.getSystemService(BluetoothManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var rx: BluetoothGattCharacteristic? = null
    private var target: String? = null
    private var mtu = 23
    private val queue = ArrayDeque<ByteArray>()
    private var writing = false
    private var msgId = 0
    private var reasm = Ble.Reassembler()
    private val _state = MutableStateFlow("off")
    val state: StateFlow<String> = _state
    @Volatile var lastInfo: JSONObject? = null; private set
    var onInfo: ((JSONObject) -> Unit)? = null
    var eventsIn = 0; private set
    var bytesIn = 0L; private set

    // ============================================================================================ scan

    private var scanCb: ScanCallback? = null

    fun scan(onFound: (NearbyOwner) -> Unit) {
        stopScan()
        val sc = bm.adapter?.bluetoothLeScanner ?: return log("BLE: no scanner")
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                onFound(NearbyOwner(result.device.address, result.scanRecord?.deviceName ?: result.device.name ?: "Sukrara owner", result.rssi))
            }
            override fun onScanFailed(errorCode: Int) = log("BLE: scan failed $errorCode")
        }
        scanCb = cb
        sc.startScan(listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(Ble.SERVICE)).build()),
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb)
        log("BLE: scanning for Sukrara owners")
    }

    fun stopScan() {
        scanCb?.let { runCatching { bm.adapter?.bluetoothLeScanner?.stopScan(it) } }
        scanCb = null
    }

    // ============================================================================================ connection

    fun connect(address: String) { main.post { connectNow(address) } }

    private fun connectNow(address: String) {
        target = address
        if (gatt != null) return
        val dev: BluetoothDevice = bm.adapter?.getRemoteDevice(address) ?: return
        _state.value = "connecting $address"
        gatt = dev.connectGatt(ctx, false, cb, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect() {
        main.post {
            target = null
            gatt?.close(); gatt = null; rx = null
            _state.value = "off"
        }
    }

    private fun retry() {
        main.post {
            gatt?.close(); gatt = null; rx = null
            synchronized(queue) { queue.clear(); writing = false }
            val t = target ?: return@post
            _state.value = "reconnecting"
            // Android peripherals advertise from rotating private addresses: the stored one may be stale after a
            // restart, so re-discover by service UUID (first owner found; events are verified anyway) and fall back
            // to the stored address.
            var found = false
            scan { o -> if (!found) { found = true; stopScan(); main.post { if (gatt == null) connectNow(o.address) } } }
            main.postDelayed({ if (!found) { stopScan(); if (gatt == null && target == t) connectNow(t) } }, 8_000)
        }
    }

    private val cb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                log("BLE: connected to ${g.device.address}")
                g.requestMtu(517)
            } else {
                log("BLE: disconnected from ${g.device.address} (status $status)")
                retry()
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, m: Int, status: Int) {
            mtu = m
            log("BLE: MTU $m")
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val svc = g.getService(Ble.SERVICE) ?: return log("BLE: no Sukrara service on ${g.device.address}").also { retry() }
            rx = svc.getCharacteristic(Ble.RX)
            val tx = svc.getCharacteristic(Ble.TX)
            g.setCharacteristicNotification(tx, true)
            g.writeDescriptor(tx.getDescriptor(Ble.CCCD), BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            _state.value = "connected ${g.device.address}"
            reasm = Ble.Reassembler()
            send(Ble.msg("HELLO", JSONObject().put("v", 2)))
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            bytesIn += value.size
            val m = reasm.add(value) ?: return
            when (m.optString(0)) {
                "INFO" -> m.optJSONObject(1)?.let { lastInfo = it; onInfo?.invoke(it) }
                "EVENT" -> m.optJSONObject(1)?.let { eventsIn++; onEvent(it, "ble:${g.device.address}") }
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            synchronized(queue) { writing = false }
            pump()
        }
    }

    private fun send(payload: ByteArray) {
        val id = synchronized(this) { msgId = (msgId + 1) and 0xFFFF; msgId }
        synchronized(queue) { Ble.frames(id, payload, mtu).forEach { queue.add(it) } }
        pump()
    }

    private fun pump() {
        val g = gatt ?: return
        val c = rx ?: return
        val f = synchronized(queue) {
            if (writing) return
            queue.poll()?.also { writing = true }
        } ?: return
        val rc = g.writeCharacteristic(c, f, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        if (rc != BluetoothGatt.GATT_SUCCESS) {
            synchronized(queue) { writing = false; queue.addFirst(f) }
            main.postDelayed({ pump() }, 20)
        }
    }

    /** Ask the owner for INFO + its cached events again (e.g. after a new key made them readable). */
    fun hello() { if (rx != null) send(Ble.msg("HELLO", JSONObject().put("v", 2))) }

    // ============================================================================================ Transport

    override fun publish(ev: Event, lane: Lane, key: String?) {
        if (rx == null) return
        send(Ble.event(ev.asJson()))
    }

    override fun subscribe(id: String, filters: () -> List<JSONObject>, onEvent: (JSONObject, String) -> Unit) = Unit
    override fun unsubscribe(id: String) = Unit
    override fun hasSubscription(id: String) = false
    override suspend fun fetch(filters: List<JSONObject>, timeoutMs: Long): List<Pair<JSONObject, String>> = emptyList()
    override fun reconnect() { target?.let { t -> if (gatt == null) connect(t) } }
    override fun close() { stopScan(); disconnect() }
}
