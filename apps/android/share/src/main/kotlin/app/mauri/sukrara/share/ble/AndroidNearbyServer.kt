// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.share.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelUuid
import app.mauri.sukrara.protocol.NostrCrypto
import app.mauri.sukrara.protocol.SignedEvent
import app.mauri.sukrara.protocol.ble.BleUuids
import app.mauri.sukrara.share.owner.OwnerNearby
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The wearer's GATT server + advertiser for nearby followers (ogs-ble B§2–B§8; the protocol is [NearbyServerCore]).
 * Serves while the sharing engine runs, the switch ([enabled], Settings › Sharing › Bluetooth) is on, there is at least
 * one follower (B§3), Bluetooth is on and Nearby devices is allowed. Advertises connectable with the service UUID only
 * (no name, B§3). Bluetooth off → closed, on → opened again (a NEW server; never a stale one). A revoked permission or
 * any stack exception closes it with a visible state, never a crash. Every callback runs on one handler thread.
 */
@SuppressLint("MissingPermission") // every call is behind granted() and wrapped for a SecurityException
class AndroidNearbyServer(
    private val context: Context,
    private val crypto: NostrCrypto,
    private val enabled: Flow<Boolean>,
    private val scope: CoroutineScope,
) : OwnerNearby {
    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val thread = HandlerThread("sukrara-nearby-server").apply { start() }
    private val handler = Handler(thread.looper)

    private val _status = MutableStateFlow<NearbyStatus>(NearbyStatus.Stopped)
    override val status: StateFlow<NearbyStatus> = _status.asStateFlow()
    private val _links = MutableStateFlow<List<NearbyServerCore.Named>>(emptyList())
    override val links: StateFlow<List<NearbyServerCore.Named>> = _links.asStateFlow()

    // handler thread only
    private var host: OwnerNearby.Host? = null
    private var core: NearbyServerCore? = null
    private var server: BluetoothGattServer? = null
    private var tx: BluetoothGattCharacteristic? = null
    private var advertising = false
    private var switchOn = true
    private var enabledJob: Job? = null
    private var receiverOn = false
    private val devices = HashMap<String, BluetoothDevice>()

    /** Negotiated MTUs: the MTU exchange comes before the central's first write (when it becomes a peer). */
    private val mtus = HashMap<String, Int>()
    private var advertised = false

    private val receiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                handler.post { evaluate() }
            }
        }

    override fun start(host: OwnerNearby.Host) =
        post {
            if (this.host != null) return@post
            this.host = host
            core = NearbyServerCore(crypto, host, System::currentTimeMillis)
            if (!receiverOn) {
                context.registerReceiver(receiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), Context.RECEIVER_NOT_EXPORTED)
                receiverOn = true
            }
            enabledJob =
                scope.launch {
                    enabled.collect { on ->
                        post {
                            switchOn = on
                            evaluate()
                        }
                    }
                }
            evaluate()
        }

    override fun stop() =
        post {
            enabledJob?.cancel()
            enabledJob = null
            if (receiverOn) runCatching { context.unregisterReceiver(receiver) }
            receiverOn = false
            close()
            host = null
            core = null
            _status.value = NearbyStatus.Stopped
        }

    override fun refresh() = post { evaluate() }

    override fun publish(event: SignedEvent) = post { core?.let { run(it.publish(event)) } }

    override fun forward(event: SignedEvent, except: String) = post { core?.let { run(it.forward(event, except)) } }

    override fun forget(follower: String) = post { core?.let { run(it.forget(follower)) } }

    /** Opens or closes the server for what holds now, and says why in [status]. */
    private fun evaluate() {
        val h = host ?: return
        val adapter = manager?.adapter
        val wanted = switchOn && h.followers().isNotEmpty() && h.sid() != null
        val next =
            when {
                !wanted -> NearbyStatus.Stopped
                adapter == null || !context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE) -> NearbyStatus.Unsupported
                !adapter.isEnabled -> NearbyStatus.BluetoothOff
                !granted() -> NearbyStatus.NeedsPermission
                else -> null
            }
        if (next != null) {
            close()
            _status.value = next
            return
        }
        if (server == null && !open(adapter!!)) return
        renderLinks()
    }

    private fun granted() =
        PERMISSIONS.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private fun open(adapter: BluetoothAdapter): Boolean =
        guarded {
            val advertiser = adapter.bluetoothLeAdvertiser
            if (advertiser == null) {
                _status.value = NearbyStatus.Unsupported
                return@guarded false
            }
            val s = manager?.openGattServer(context, callback)
            if (s == null) {
                // the stack refused for now: say so and try again later
                _status.value = NearbyStatus.Unsupported
                later(ADVERTISE_RETRY_MS) { evaluate() }
                return@guarded false
            }
            val service = BluetoothGattService(BleUuids.SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
            val rx =
                BluetoothGattCharacteristic(
                    BleUuids.RX,
                    BluetoothGattCharacteristic.PROPERTY_WRITE,
                    BluetoothGattCharacteristic.PERMISSION_WRITE,
                )
            val t =
                BluetoothGattCharacteristic(
                    BleUuids.TX,
                    BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                    BluetoothGattCharacteristic.PERMISSION_READ,
                )
            t.addDescriptor(
                BluetoothGattDescriptor(BleUuids.CCCD, BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE),
            )
            service.addCharacteristic(rx)
            service.addCharacteristic(t)
            server = s
            tx = t
            // advertising starts once the service is there (onServiceAdded)
            s.addService(service)
            true
        } == true

    /** Connectable, the service UUID only: no name in the advertisement or a scan response (B§3). */
    private fun advertise() {
        if (advertising || server == null) return
        val advertiser = manager?.adapter?.bluetoothLeAdvertiser ?: return
        guarded {
            advertiser.startAdvertising(
                AdvertiseSettings
                    .Builder()
                    .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
                    .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
                    .setConnectable(true)
                    .build(),
                AdvertiseData
                    .Builder()
                    .addServiceUuid(ParcelUuid(BleUuids.SERVICE))
                    .setIncludeDeviceName(false)
                    .build(),
                advertise,
            )
            advertising = true
        }
    }

    private fun close() {
        // never through guarded(): a SecurityException here would post another close
        if (advertising) runCatching { manager?.adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertise) }
        advertising = false
        advertised = false
        mtus.clear()
        server?.let { s -> runCatching { s.close() } }
        server = null
        tx = null
        devices.clear()
        core?.reset()
        _links.value = emptyList()
    }

    private fun renderLinks() {
        val named = core?.named().orEmpty()
        _links.value = named
        // "on" only once the advertisement really started (onStartSuccess)
        if (server != null && advertised) {
            _status.value = if (named.isEmpty()) NearbyStatus.Searching else NearbyStatus.Connected(named.size)
        }
    }

    /** Carries out the core's effects on the stack. */
    private fun run(effects: List<NearbyServerCore.Effect>) {
        for (e in effects) {
            when (e) {
                is NearbyServerCore.Effect.Notify -> notify(e.peer, e.frame)
                is NearbyServerCore.Effect.Disconnect -> devices[e.peer]?.let { d -> guarded { server?.cancelConnection(d) } }
                is NearbyServerCore.Effect.Incoming -> host?.onEvent(e.event, e.peer)
                is NearbyServerCore.Effect.HelloLater -> later(e.delayMs) { core?.let { run(it.helloDue(e.peer)) } }
            }
        }
        renderLinks()
    }

    private fun notify(peer: String, frame: ByteArray) {
        val s = server
        val t = tx
        val d = devices[peer]
        val c = core ?: return
        if (s == null || t == null || d == null) return run(c.notifyFailed(peer, frame, busy = false))
        val rc = guarded { s.notifyCharacteristicChanged(d, t, false, frame) } ?: BluetoothStatusCodes.ERROR_UNKNOWN
        if (rc == BluetoothStatusCodes.SUCCESS) return
        val busy = rc == BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY
        run(c.notifyFailed(peer, frame, busy))
        if (busy) later(BUSY_RETRY_MS) { core?.let { run(it.retry(peer)) } }
    }

    private val advertise =
        object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) =
                post {
                    advertised = true
                    renderLinks()
                }

            override fun onStartFailure(errorCode: Int) {
                handler.post {
                    advertising = false
                    if (errorCode == ADVERTISE_FAILED_FEATURE_UNSUPPORTED) {
                        close()
                        _status.value = NearbyStatus.Unsupported
                    } else if (errorCode != ADVERTISE_FAILED_ALREADY_STARTED) {
                        // too many advertisers / internal error: say it isn't on, and a fresh start later
                        close()
                        _status.value = NearbyStatus.Unsupported
                        later(ADVERTISE_RETRY_MS) { evaluate() }
                    }
                }
            }
        }

    private val callback =
        object : BluetoothGattServerCallback() {
            override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) =
                post {
                    val c = core ?: return@post
                    // a connection becomes a peer only on its first write to our service: other links of this phone
                    // (the sensor) also show up here and are never counted or dropped
                    if (newState != BluetoothProfile.STATE_CONNECTED) {
                        devices.remove(device.address)
                        mtus.remove(device.address)
                        c.disconnected(device.address)
                        renderLinks()
                    }
                }

            override fun onMtuChanged(device: BluetoothDevice, mtu: Int) =
                post {
                    mtus[device.address] = mtu
                    core?.mtu(device.address, mtu)
                }

            override fun onServiceAdded(status: Int, service: BluetoothGattService) =
                post {
                    if (service.uuid != BleUuids.SERVICE) return@post
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        advertise()
                    } else {
                        close()
                        later(ADVERTISE_RETRY_MS) { evaluate() }
                    }
                }

            override fun onDescriptorWriteRequest(
                device: BluetoothDevice,
                requestId: Int,
                descriptor: BluetoothGattDescriptor,
                preparedWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray?,
            ) = post {
                val ok = descriptor.uuid == BleUuids.CCCD && !preparedWrite && offset == 0 && value != null
                if (responseNeeded) {
                    guarded {
                        server?.sendResponse(
                            device,
                            requestId,
                            if (ok) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_WRITE_NOT_PERMITTED,
                            offset,
                            null,
                        )
                    }
                }
                val c = core ?: return@post
                if (!ok) return@post
                devices[device.address] = device
                c.connected(device.address, mtus[device.address] ?: NearbyServerCore.DEFAULT_MTU).let(::run)
                val on =
                    value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ||
                        value.contentEquals(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE)
                run(c.subscribed(device.address, on))
            }

            override fun onDescriptorReadRequest(
                device: BluetoothDevice,
                requestId: Int,
                offset: Int,
                descriptor: BluetoothGattDescriptor,
            ) = post {
                guarded {
                    val off = BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                    server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, off)
                }
            }

            override fun onCharacteristicWriteRequest(
                device: BluetoothDevice,
                requestId: Int,
                characteristic: BluetoothGattCharacteristic,
                preparedWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray?,
            ) = post {
                val c = core ?: return@post
                devices[device.address] = device
                // checked before answering (B§8): a refused frame gets an error and the central is dropped
                val mtu = mtus[device.address] ?: NearbyServerCore.DEFAULT_MTU
                val (ok, effects) = c.write(device.address, value, characteristic.uuid == BleUuids.RX, preparedWrite, offset, mtu)
                if (responseNeeded) {
                    guarded {
                        server?.sendResponse(
                            device,
                            requestId,
                            if (ok) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_WRITE_NOT_PERMITTED,
                            offset,
                            null,
                        )
                    }
                }
                run(effects)
            }

            override fun onNotificationSent(device: BluetoothDevice, status: Int) =
                post { core?.let { run(it.notificationSent(device.address)) } }
        }

    private fun post(block: () -> Unit) {
        handler.post { guarded(block) }
    }

    /** A delayed step, guarded like every callback (an exception never reaches the process). */
    private fun later(ms: Long, block: () -> Unit) {
        handler.postDelayed({ guarded(block) }, ms)
    }

    /** A stack call: a revoked permission closes the server with a visible state; anything else is a dropped call. */
    private fun <T> guarded(block: () -> T): T? =
        try {
            block()
        } catch (_: SecurityException) {
            handler.post {
                close()
                _status.value = NearbyStatus.NeedsPermission
            }
            null
        } catch (_: RuntimeException) {
            null
        }

    companion object {
        /** The wearer's Bluetooth sharing needs both (Android 12+). */
        val PERMISSIONS = listOf(Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT)
        private const val BUSY_RETRY_MS = 15L
        private const val ADVERTISE_RETRY_MS = 30_000L
    }
}
