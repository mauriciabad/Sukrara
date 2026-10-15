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
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

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
        startGattServer()
        startAdvertising()
        source = createGlucoseSource(this)
        update { it.copy(source = source.name) }
        source.start(scope, sink)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
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
            update { it.copy(latest = reading, error = null) }
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
            update { it.copy(latest = newest ?: it.latest) }
        }

        override fun onStatus(state: String, error: String?) = update { it.copy(sourceDetail = state, error = error) }
    }

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
        for (d in subscribers) {
            if (Build.VERSION.SDK_INT >= 33) gatt?.notifyCharacteristicChanged(d, ch, false, value)
            else @Suppress("DEPRECATION") { ch.value = value; gatt?.notifyCharacteristicChanged(d, ch, false) }
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
        service.addCharacteristic(reading)
        service.addCharacteristic(hist)
        server.addService(service)
        gatt = server
        readingChar = reading
    }

    private val gattCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothGatt.STATE_CONNECTED) {
                connected += device
                stopAdvertising()           // a central is here; no need to keep the radio advertising
            } else {
                connected -= device
                subscribers -= device
                if (connected.isEmpty()) startAdvertising()
            }
            Log.i(tag, "central ${device.address} state $newState")
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
            if (d.uuid == Bridge.CCCD) {
                if (value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)) subscribers += device else subscribers -= device
            }
            if (responseNeeded) gatt?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            update { it.copy(subscribed = subscribers.size) }
        }

        override fun onDescriptorReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, d: BluetoothGattDescriptor) {
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
