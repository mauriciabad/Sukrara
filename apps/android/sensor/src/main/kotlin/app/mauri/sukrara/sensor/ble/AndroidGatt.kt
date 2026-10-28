// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.sensor.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * [GattConnector] over Android's `BluetoothGatt` (API 33+ write calls that return a status). Thin on purpose: the queue,
 * timeouts and recovery are [GattLink]'s, tested on the JVM. Device-tested only (no emulator Bluetooth).
 */
@SuppressLint("MissingPermission") // callers hold BLUETOOTH_CONNECT; a revoked one throws SecurityException, handled by GattLink
internal class AndroidGatt(private val context: Context) : GattConnector {
    private companion object {
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private val adapter get() = context.getSystemService(BluetoothManager::class.java)?.adapter

    override val isBluetoothOn: Boolean get() = adapter?.isEnabled == true

    override fun connect(address: String, generation: Int, onEvent: (GattEvent) -> Unit): GattConnection {
        val device = checkNotNull(adapter) { "no Bluetooth adapter" }.getRemoteDevice(address)
        val chars = ConcurrentHashMap<UUID, BluetoothGattCharacteristic>()
        val callback =
            object : BluetoothGattCallback() {
                override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) =
                    onEvent(GattEvent.ConnectionChanged(generation, newState == BluetoothProfile.STATE_CONNECTED, status))

                override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                    chars.clear()
                    g.services.forEach { s -> s.characteristics.forEach { chars[it.uuid] = it } }
                    onEvent(GattEvent.ServicesDiscovered(generation, status, chars.keys.toSet()))
                }

                override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) =
                    onEvent(GattEvent.OperationDone(generation, d.characteristic.uuid, status))

                override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) =
                    onEvent(GattEvent.OperationDone(generation, ch.uuid, status))

                override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) =
                    onEvent(GattEvent.Notification(generation, ch.uuid, value))
            }
        // autoConnect = true: the controller reconnects whenever the sensor advertises, with no app-level scanning.
        val gatt =
            device.connectGatt(context, true, callback, BluetoothDevice.TRANSPORT_LE)
                ?: error("connectGatt returned null")
        return object : GattConnection {
            override fun discoverServices() = gatt.discoverServices()

            override fun enableNotifications(characteristic: UUID): Boolean {
                val ch = chars[characteristic] ?: return false
                val d = ch.getDescriptor(CCCD) ?: return false
                return gatt.setCharacteristicNotification(ch, true) &&
                    gatt.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
            }

            override fun write(characteristic: UUID, value: ByteArray): Boolean {
                val ch = chars[characteristic] ?: return false
                return gatt.writeCharacteristic(ch, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                    BluetoothStatusCodes.SUCCESS
            }

            override fun close() {
                gatt.disconnect()
                gatt.close()
            }
        }
    }
}
