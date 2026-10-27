// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.spike.sensors.android

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.util.Log
import dev.spike.sensors.BleLink
import dev.spike.sensors.SensorDriver
import java.util.UUID

/**
 * The only Android-specific part of the spike: it turns [BleLink] calls into `BluetoothGatt` operations and feeds
 * notifications back to a [SensorDriver]. One outstanding GATT operation at a time, like Sukrara's Libre 3 client.
 *
 * **This spike never connected it to a sensor** (investigation 23: the only sensor around is the user's running
 * Libre 3 Plus, which must not be touched). It is here to show the drivers fit Android's API unchanged.
 */
@SuppressLint("MissingPermission")
class GattTransport(
    private val context: Context,
    private val device: BluetoothDevice,
    private val driver: SensorDriver,
    private val serviceUuid: UUID,
) : BleLink {
    private companion object {
        const val TAG = "sensors.gatt"
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private var gatt: BluetoothGatt? = null
    private val chars = HashMap<UUID, BluetoothGattCharacteristic>()
    private val queue = ArrayDeque<() -> Unit>()
    private var running = false

    override val isBonded get() = device.bondState == BluetoothDevice.BOND_BONDED

    fun connect() {
        gatt = device.connectGatt(context, true, callback, BluetoothDevice.TRANSPORT_LE)
    }

    fun close() {
        gatt?.close()
        gatt = null
        queue.clear()
        running = false
        driver.onDisconnected()
    }

    override fun subscribe(characteristic: UUID, indicate: Boolean) = enqueue {
        val g = gatt ?: return@enqueue done()
        val ch = chars[characteristic] ?: return@enqueue done()
        g.setCharacteristicNotification(ch, true)
        val cccd = ch.getDescriptor(CCCD) ?: return@enqueue done()
        val value = if (indicate) BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                    else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        if (g.writeDescriptor(cccd, value) != BluetoothGatt.GATT_SUCCESS) done()
    }

    override fun write(characteristic: UUID, value: ByteArray, withResponse: Boolean) = enqueue {
        val g = gatt ?: return@enqueue done()
        val ch = chars[characteristic] ?: return@enqueue done()
        val type = if (withResponse) BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                   else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        if (g.writeCharacteristic(ch, value, type) != BluetoothGatt.GATT_SUCCESS) done()
    }

    override fun bond() {
        if (!isBonded) device.createBond()
    }

    override fun disconnect() {
        gatt?.disconnect()
    }

    private fun enqueue(op: () -> Unit) {
        queue.addLast(op)
        pump()
    }

    private fun pump() {
        if (running) return
        val op = queue.removeFirstOrNull() ?: return
        running = true
        op()
    }

    private fun done() {
        running = false
        pump()
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> g.discoverServices()
                BluetoothProfile.STATE_DISCONNECTED -> { queue.clear(); running = false; driver.onDisconnected() }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val service = g.getService(serviceUuid) ?: run {
                Log.w(TAG, "no service $serviceUuid on ${device.address}")
                g.disconnect()
                return
            }
            chars.clear()
            service.characteristics.forEach { chars[it.uuid] = it }
            driver.onConnected(this@GattTransport)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) = done()
        override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) = done()
        override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            driver.onNotification(ch.uuid, value)
            done()
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
            driver.onNotification(ch.uuid, value)
        }
    }
}
