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
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.UUID

/**
 * A [BleLink] over `BluetoothGatt` to one device known by address: the Bluetooth half of investigation 02's
 * `Libre3Client`, unchanged (investigation 23, step 1). It connects with `autoConnect` (the controller reconnects
 * whenever the device advertises; no app-level scanning), discovers services, serializes GATT operations (one
 * outstanding operation per connection), reconnects 1 s after a drop, and hands everything else to the [driver].
 *
 * Status words from here are the connection's ("connecting to …", "discovering services", "waiting for sensor",
 * "waiting for Bluetooth"); the driver's are the sensor's. `NewSensorFlow` reads both.
 */
@SuppressLint("MissingPermission")
internal class GattLink(
    private val context: Context,
    private val address: String,
    /** What "connecting to …" names: the sensor's serial. */
    private val label: String,
    private val driver: SensorDriver,
    private val events: SensorEvents,
) : BleLink {
    private companion object {
        const val TAG = "Sukrara.direct"
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private val handler = Handler(Looper.getMainLooper())
    private var device: BluetoothDevice? = null
    private var gatt: BluetoothGatt? = null
    private var closing = false
    private val chars = HashMap<UUID, BluetoothGattCharacteristic>()

    // serialized GATT operations (Android allows one outstanding operation per connection)
    private val ops = ArrayDeque<() -> Unit>()
    private var opRunning = false

    fun connect() {
        closing = false
        val adapter = context.getSystemService(BluetoothManager::class.java).adapter
        if (adapter == null || !adapter.isEnabled) {
            events.onStatus("waiting for Bluetooth", "Bluetooth is off")
            return
        }
        val device = adapter.getRemoteDevice(address).also { device = it }
        events.onStatus("connecting to $label")
        // autoConnect = true: the controller reconnects whenever the sensor advertises, with no
        // app-level scanning. Slower to attach, far cheaper than scanning ourselves.
        gatt = device.connectGatt(context, true, callback, BluetoothDevice.TRANSPORT_LE)
    }

    override fun close() {
        closing = true
        driver.onClosed()
        clearOps()
        gatt?.close()
        gatt = null
    }

    override val isBonded get() = device?.bondState == BluetoothDevice.BOND_BONDED

    override fun bond() {
        // The bond-state receiver (→ SensorDriver.onBondChanged) comes with the first bonding family (SmartGuide).
        if (!isBonded) device?.createBond()
    }

    override fun disconnect() {
        gatt?.disconnect()   // the callback re-arms the connection
    }

    override fun schedule(delayMs: Long, action: Runnable) { handler.postDelayed(action, delayMs) }
    override fun cancel(action: Runnable) = handler.removeCallbacks(action)

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

    private fun clearOps() = synchronized(this) { ops.clear(); opRunning = false }

    override fun subscribe(characteristic: UUID, indicate: Boolean) = enqueue {
        val g = gatt
        val ch = chars[characteristic]
        if (g == null || ch == null) return@enqueue driver.onLinkFailed("characteristic $characteristic missing")
        g.setCharacteristicNotification(ch, true)
        val d = ch.getDescriptor(CCCD) ?: return@enqueue driver.onLinkFailed("CCCD missing on $characteristic")
        val value = if (indicate) BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                    else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        if (g.writeDescriptor(d, value) != BluetoothGatt.GATT_SUCCESS) driver.onLinkFailed("writeDescriptor($characteristic) rejected")
    }

    override fun write(characteristic: UUID, value: ByteArray, withResponse: Boolean) = enqueue {
        val g = gatt
        val ch = chars[characteristic]
        if (g == null || ch == null) return@enqueue driver.onLinkFailed("characteristic $characteristic missing")
        val type = if (withResponse) BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                   else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        if (g.writeCharacteristic(ch, value, type) != BluetoothGatt.GATT_SUCCESS) driver.onLinkFailed("writeCharacteristic($characteristic) rejected")
    }

    override fun requestConnectionPriority(priority: Int) = enqueue {
        gatt?.requestConnectionPriority(priority)
        opDone()
    }

    // MARK: GATT callbacks

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(TAG, "sensor connected")
                events.onStatus("discovering services")
                handler.post { g.discoverServices() }
            } else {
                Log.i(TAG, "sensor disconnected (status $status)")
                driver.onDisconnected()
                clearOps()
                if (!closing) {
                    events.onStatus("waiting for sensor")
                    handler.postDelayed({ if (!closing) g.connect() }, 1_000)
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            chars.clear()
            g.services.forEach { s -> s.characteristics.forEach { chars[it.uuid] = it } }
            driver.onConnected(this@GattLink, chars.keys.toSet(), status)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return driver.onLinkFailed("descriptor write failed: $status")
            opDone()
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return driver.onLinkFailed("write to ${ch.uuid} failed: $status")
            opDone()
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) =
            driver.onNotification(ch.uuid, value)
    }
}
