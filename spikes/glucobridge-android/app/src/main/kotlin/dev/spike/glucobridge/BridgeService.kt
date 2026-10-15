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
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Foreground service: follows Juggluco's web server on localhost (works with "Local only" ticked)
 * and re-publishes each reading over a BLE GATT server so a nearby Mac can read it with no shared network.
 */
@SuppressLint("MissingPermission") // permissions are requested by MainActivity before the service starts
class BridgeService : Service() {
    private val tag = "GlucoBridge"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val juggluco = "http://127.0.0.1:17580"

    private lateinit var bt: BluetoothManager
    private var gatt: BluetoothGattServer? = null
    private var readingChar: BluetoothGattCharacteristic? = null
    private val connected = ConcurrentHashMap.newKeySet<BluetoothDevice>()
    private val subscribers = ConcurrentHashMap.newKeySet<BluetoothDevice>()

    @Volatile private var history: List<Bridge.Reading> = emptyList()
    @Volatile private var latestBytes = ByteArray(0)
    @Volatile private var historyBytes = ByteArray(0)

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        startForeground(1, notification("starting…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        bt = getSystemService(BluetoothManager::class.java)
        startGattServer()
        startAdvertising()
        scope.launch { jugglucoLoop() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        scope.cancel()
        bt.adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
        gatt?.close()
        update { it.copy(advertising = false, centrals = 0, subscribed = 0, juggluco = "stopped") }
        super.onDestroy()
    }

    // MARK: status + notification

    private fun update(f: (Bridge.Status) -> Bridge.Status) {
        val s = f(Bridge.status.value)
        Bridge.status.value = s
        val text = (s.latest?.let { "${it.mgdl} mg/dL ${it.rate?.let { r -> "%+.1f/min".format(r) } ?: ""} · " } ?: "") +
            "${s.centrals} connected · ${s.juggluco}"
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

    // MARK: Juggluco (localhost)

    private suspend fun jugglucoLoop() {
        while (scope.isActive) {
            try {
                update { it.copy(juggluco = "fetching history") }
                history = fetchHistory()
                history.lastOrNull()?.let { publish(it) }
                update { it.copy(juggluco = "live", error = null) }
                followLive()
                update { it.copy(juggluco = "stream ended") }
            } catch (e: Exception) {
                Log.w(tag, "juggluco", e)
                update { it.copy(juggluco = "unreachable", error = e.message) }
            }
            delay(5_000)
        }
    }

    private fun open(path: String, readTimeoutMs: Int) =
        (URL(juggluco + path).openConnection() as HttpURLConnection).apply {
            connectTimeout = 3_000
            readTimeout = readTimeoutMs
        }

    private fun fetchHistory(): List<Bridge.Reading> {
        val c = open("/api/v1/entries.json?count=${Bridge.HISTORY_MINUTES}&interval=0", 10_000)
        val arr = JSONArray(c.inputStream.bufferedReader().use { it.readText() })
        return (0 until arr.length()).map { arr.getJSONObject(it) }.map { o ->
            val dir = o.optString("direction")
            Bridge.Reading(o.getLong("date") / 1000, o.getInt("sgv"),
                if (dir.isEmpty()) null else o.optDouble("delta") / 5) // Juggluco delta = rate × 5
        }.sortedBy { it.epochSec }
    }

    /** Server-Sent Events: one `glucose` event per new 1-minute reading. Heartbeat every 20 s. */
    private fun followLive() {
        val c = open("/x/live", 60_000)
        c.inputStream.bufferedReader().use { reader ->
            var event = "message"
            while (scope.isActive) {
                val line = reader.readLine() ?: return
                when {
                    line.startsWith("event:") -> event = line.substringAfter(':').trim()
                    line.startsWith("data:") && event == "glucose" -> {
                        val o = JSONObject(line.substringAfter(':'))
                        val r = Bridge.Reading(o.getLong("timestamp"), o.getInt("glucoseMgDl"),
                            if (o.isNull("rateMgDlPerMinute")) null else o.getDouble("rateMgDlPerMinute"))
                        history = (history.filter { r.epochSec - it.epochSec in 20..Bridge.HISTORY_MINUTES * 60L } + r)
                        publish(r)
                        event = "message"
                    }
                }
            }
        }
    }

    private fun publish(r: Bridge.Reading) {
        latestBytes = Bridge.encode(r)
        historyBytes = Bridge.encodeHistory(history)
        val ch = readingChar
        if (ch != null) for (d in subscribers) {
            if (Build.VERSION.SDK_INT >= 33) gatt?.notifyCharacteristicChanged(d, ch, false, latestBytes)
            else @Suppress("DEPRECATION") { ch.value = latestBytes; gatt?.notifyCharacteristicChanged(d, ch, false) }
        }
        Log.i(tag, "reading ${r.mgdl} rate ${r.rate} -> ${subscribers.size} subscriber(s)")
        update { it.copy(latest = r) }
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
            if (newState == BluetoothProfile.STATE_CONNECTED) connected += device
            else { connected -= device; subscribers -= device; startAdvertising() }
            Log.i(tag, "central ${device.address} state $newState")
            update { it.copy(centrals = connected.size, subscribed = subscribers.size) }
        }

        override fun onCharacteristicReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, ch: BluetoothGattCharacteristic) {
            val value = when (ch.uuid) { Bridge.READING -> latestBytes; Bridge.HISTORY -> historyBytes; else -> null }
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
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) = update { it.copy(advertising = true) }
        override fun onStartFailure(errorCode: Int) {
            if (errorCode == ADVERTISE_FAILED_ALREADY_STARTED) return update { it.copy(advertising = true) }
            update { it.copy(advertising = false, error = "advertise failed: $errorCode") }
        }
    }

    private fun startAdvertising() {
        val adv = bt.adapter?.bluetoothLeAdvertiser ?: return update { it.copy(error = "Bluetooth off / no advertiser") }
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        val data = AdvertiseData.Builder().addServiceUuid(ParcelUuid(Bridge.SERVICE)).setIncludeDeviceName(false).build()
        adv.startAdvertising(settings, data, advertiseCallback)
    }
}
