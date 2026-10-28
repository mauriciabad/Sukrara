// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.share.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
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
import app.mauri.sukrara.protocol.ble.BleMessages
import app.mauri.sukrara.protocol.ble.BleUuids
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * The follower's GATT client for the wearer's phone (ogs-ble B§6; the protocol is [NearbyLink]). Scans by service
 * UUID (the wearer's address rotates, B§3), one connection at a time with a [CONNECT_TIMEOUT_MS] timeout (that phone
 * skipped for a minute), MTU 517, notifications on TX, every GATT operation with an [OP_TIMEOUT_MS] timeout (a stuck
 * one drops the link), HELLO again after [SILENCE_HELLO_MS] without a notification, link dropped after
 * [SILENCE_DROP_MS], rescan [RESCAN_MS] after a disconnect, a NEW `BluetoothGatt` per connection (closed, never
 * reused). Bluetooth off → [NearbyStatus.BluetoothOff], on → scan again. A revoked permission stops it with
 * [NearbyStatus.NeedsPermission], never a crash. Every callback runs on one handler thread.
 */
@SuppressLint("MissingPermission") // behind granted(); every stack call is wrapped for a SecurityException
class AndroidNearbyCentral(
    private val context: Context,
    crypto: NostrCrypto,
    identity: NearbyLink.Identity,
) : NearbyTransport {
    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val thread = HandlerThread("sukrara-nearby-central").apply { start() }
    private val handler = Handler(thread.looper)
    private val link = NearbyLink(identity, crypto, System::currentTimeMillis)
    private val channel = Channel<NearbyEvent>(Channel.UNLIMITED)
    override val events: Flow<NearbyEvent> = channel.receiveAsFlow()
    private val _status = MutableStateFlow<NearbyStatus>(NearbyStatus.Stopped)
    override val status: StateFlow<NearbyStatus> = _status.asStateFlow()

    // handler thread only
    private var stopped = false
    private var scanning = false
    private var gatt: BluetoothGatt? = null
    private var device: String? = null
    private var rx: BluetoothGattCharacteristic? = null
    private var mtu = 23
    private var generation = 0
    private val skip = HashMap<String, Long>()
    private var lastData = 0L
    private var lastHello = 0L
    private var nextToken = 0
    private val sends = HashMap<Int, CompletableDeferred<Boolean>>()

    /** Every send not answered yet (also those whose runnable [stop] removed): failed on stop. */
    private val outstanding = ConcurrentHashMap.newKeySet<CompletableDeferred<Boolean>>()
    private var opTimeout: Runnable? = null
    private var receiverOn = false
    private val scanStarts = ArrayDeque<Long>()
    private var failures = 0

    private val receiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                handler.post { evaluate() }
            }
        }

    override fun start() =
        post {
            if (stopped || receiverOn) return@post
            context.registerReceiver(receiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), Context.RECEIVER_NOT_EXPORTED)
            receiverOn = true
            handler.postDelayed(watchdog, WATCHDOG_MS)
            evaluate()
        }

    override fun stop() =
        post {
            stopped = true
            if (receiverOn) runCatching { context.unregisterReceiver(receiver) }
            receiverOn = false
            handler.removeCallbacksAndMessages(null)
            drop()
            outstanding.forEach { it.complete(false) }
            _status.value = NearbyStatus.Stopped
            channel.close()
            thread.quitSafely()
        }

    /** A new key (first one, new epoch): HELLO again so the phone answers an INFO we can check now (B§5). */
    override fun update(identity: NearbyLink.Identity) =
        post {
            if (link.identity == identity) return@post
            link.identity = identity
            if (link.isConnected) {
                lastHello = now()
                apply(link.hello(NearbyLink.nonce()))
            }
        }

    override suspend fun send(event: SignedEvent): Boolean {
        val done = CompletableDeferred<Boolean>()
        outstanding += done
        val queued =
            handler.post {
                guarded {
                    if (stopped) return@guarded done.complete(false)
                    val token = ++nextToken
                    sends[token] = done
                    apply(link.send(event, token))
                }
            }
        // the thread is gone (stopped): nothing will answer
        if (!queued || stopped) done.complete(false)
        return try {
            withTimeoutOrNull(SEND_TIMEOUT_MS) { done.await() } ?: false
        } finally {
            outstanding -= done
        }
    }

    override fun leave(phone: String, seconds: Long) =
        post {
            skip[phone] = now() + seconds * 1000
            // the link may have moved on to another phone (ours) since that INFO: leave only that phone
            if (device != phone) return@post
            drop()
            if (_status.value is NearbyStatus.Connected) _status.value = NearbyStatus.Searching
            retryIn(RESCAN_MS)
        }

    override fun refresh() = post { evaluate() }

    /** Scan (or keep the link) for what holds now, and say why not in [status]. */
    private fun evaluate() {
        if (stopped) return
        val adapter = manager?.adapter
        val blocked =
            when {
                adapter == null || !context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE) -> NearbyStatus.Unsupported
                !adapter.isEnabled -> NearbyStatus.BluetoothOff
                !granted() -> NearbyStatus.NeedsPermission
                else -> null
            }
        if (blocked != null) {
            drop()
            _status.value = blocked
            return
        }
        if (gatt == null && !scanning) scan(adapter!!)
    }

    private fun granted() = PERMISSIONS.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    /**
     * Android allows 5 scan starts per 30 s (more fail silently) and turns a scan older than 30 min into an
     * opportunistic one: starts are spaced, and a long search restarts every [SCAN_RESTART_MS].
     */
    private fun scan(adapter: BluetoothAdapter) {
        // null right after Bluetooth comes on: try again shortly
        val scanner = adapter.bluetoothLeScanner ?: return retryIn(RESCAN_MS)
        val t = now()
        scanStarts.removeAll { t - it > SCAN_WINDOW_MS }
        if (scanStarts.size >= SCAN_STARTS) {
            _status.value = NearbyStatus.Searching
            retryIn(SCAN_WINDOW_MS - (t - scanStarts.first()) + 100)
            return
        }
        scanStarts += t
        handler.removeCallbacks(restartScan)
        handler.postDelayed(restartScan, SCAN_RESTART_MS)
        guarded {
            scanner.startScan(
                listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(BleUuids.SERVICE)).build()),
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_BALANCED).build(),
                scanCallback,
            )
            scanning = true
            _status.value = NearbyStatus.Searching
        }
    }

    private fun stopScan() {
        if (scanning) runCatching { manager?.adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
        scanning = false
    }

    private val scanCallback =
        object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) = post { found(result.device) }

            override fun onScanFailed(errorCode: Int) =
                post {
                    scanning = false
                    retryIn(SCAN_RETRY_MS)
                }
        }

    private fun found(d: BluetoothDevice) {
        skip.entries.removeIf { it.value <= now() }
        if (stopped || gatt != null || (skip[d.address] ?: 0) > now()) return
        stopScan()
        val g = generation + 1
        generation = g
        device = d.address
        mtu = 23
        gatt = guarded { d.connectGatt(context, false, callback(g), BluetoothDevice.TRANSPORT_LE) }
        if (gatt == null) return reconnectLater()
        armTimeout(CONNECT_TIMEOUT_MS) {
            device?.let { skip[it] = now() + SKIP_MS }
        }
    }

    /** One callback object per connection: a late callback of a closed one is ignored by its [g]. */
    private fun callback(g: Int) =
        object : BluetoothGattCallback() {
            private fun on(block: (BluetoothGatt) -> Unit) =
                post {
                    val current = gatt
                    if (g == generation && current != null) block(current)
                }

            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) =
                on { gt ->
                    if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                        armTimeout(OP_TIMEOUT_MS)
                        if (guarded { gt.requestMtu(MTU) } != true) discover(gt)
                    } else {
                        reconnectLater()
                    }
                }

            override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) =
                on { gt ->
                    if (status == BluetoothGatt.GATT_SUCCESS) this@AndroidNearbyCentral.mtu = mtu
                    discover(gt)
                }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) =
                on { gt ->
                    val service = gt.getService(BleUuids.SERVICE)
                    val r = service?.getCharacteristic(BleUuids.RX)
                    val t = service?.getCharacteristic(BleUuids.TX)
                    val cccd = t?.getDescriptor(BleUuids.CCCD)
                    if (status != BluetoothGatt.GATT_SUCCESS || r == null || t == null || cccd == null) return@on leaveNow()
                    rx = r
                    armTimeout(OP_TIMEOUT_MS)
                    val ok =
                        guarded {
                            gt.setCharacteristicNotification(t, true) &&
                                gt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
                        }
                    if (ok != true) reconnectLater()
                }

            override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) =
                on {
                    disarmTimeout()
                    if (status != BluetoothGatt.GATT_SUCCESS) return@on reconnectLater()
                    lastData = now()
                    lastHello = now()
                    apply(link.connected(mtu, NearbyLink.nonce()))
                }

            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) =
                on {
                    if (characteristic.uuid != BleUuids.TX) return@on
                    lastData = now()
                    apply(link.received(value))
                }

            override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) =
                on {
                    disarmTimeout()
                    val ok = status == BluetoothGatt.GATT_SUCCESS
                    apply(link.wrote(ok))
                    if (!ok) reconnectLater()
                }
        }

    private fun discover(gt: BluetoothGatt) {
        armTimeout(OP_TIMEOUT_MS)
        if (guarded { gt.discoverServices() } != true) reconnectLater()
    }

    /** Carries out the link's effects. */
    private fun apply(effects: List<NearbyLink.Effect>) {
        for (e in effects) {
            when (e) {
                is NearbyLink.Effect.Write -> {
                    val gt = gatt
                    val r = rx
                    if (gt == null || r == null) continue
                    armTimeout(OP_TIMEOUT_MS)
                    val rc = guarded { gt.writeCharacteristic(r, e.frame, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) }
                    if (rc != BluetoothStatusCodes.SUCCESS) {
                        disarmTimeout()
                        apply(link.wrote(false))
                        reconnectLater()
                    }
                }

                is NearbyLink.Effect.Event -> {
                    channel.trySend(NearbyEvent.Event(e.event))
                }

                is NearbyLink.Effect.Info -> {
                    // "Connected" only once the phone proved it's our wearer's (never someone else's phone)
                    if (e.owner == BleMessages.Owner.Ours) {
                        _status.value = NearbyStatus.Connected(1)
                        failures = 0
                    }
                    device?.let { channel.trySend(NearbyEvent.Info(e.owner, it, e.pairing)) }
                }

                is NearbyLink.Effect.Sent -> {
                    sends.remove(e.token)?.complete(e.ok)
                }
            }
        }
    }

    private val retry = Runnable { guarded { evaluate() } }

    /** One pending retry at a time. */
    private fun retryIn(ms: Long) {
        handler.removeCallbacks(retry)
        handler.postDelayed(retry, ms)
    }

    /** A long search starts a fresh scan (Android makes a 30-minute scan opportunistic). */
    private val restartScan =
        Runnable {
            guarded {
                if (scanning && gatt == null) {
                    stopScan()
                    evaluate()
                }
            }
        }

    private val watchdog: Runnable =
        object : Runnable {
            override fun run() {
                if (stopped) return
                guarded { check() }
                handler.postDelayed(this, WATCHDOG_MS)
            }

            private fun check() {
                if (link.isConnected) {
                    val silent = now() - lastData
                    if (silent > SILENCE_DROP_MS) {
                        reconnectLater()
                    } else if (silent > SILENCE_HELLO_MS && now() - lastHello > SILENCE_HELLO_MS) {
                        lastHello = now()
                        apply(link.hello(NearbyLink.nonce()))
                    }
                }
            }
        }

    /** A GATT step that must complete within [ms]; else the link drops ([onTimeout] first). */
    private fun armTimeout(ms: Long, onTimeout: () -> Unit = {}) {
        disarmTimeout()
        val g = generation
        val r =
            Runnable {
                if (g != generation) return@Runnable
                guarded {
                    onTimeout()
                    reconnectLater()
                }
            }
        opTimeout = r
        handler.postDelayed(r, ms)
    }

    private fun disarmTimeout() {
        opTimeout?.let(handler::removeCallbacks)
        opTimeout = null
    }

    private fun leaveNow() {
        device?.let { skip[it] = now() + SKIP_MS }
        reconnectLater()
    }

    private fun reconnectLater() {
        drop()
        if (stopped) return
        if (_status.value is NearbyStatus.Connected) _status.value = NearbyStatus.Searching
        // 3 s, doubling while connections keep failing (status 133 and the like), up to a minute
        retryIn(minOf(RESCAN_MS shl minOf(failures, 5), MAX_BACKOFF_MS))
        failures++
    }

    /** Closes the connection (a new one next time) and fails what was queued. */
    private fun drop() {
        disarmTimeout()
        stopScan()
        generation++
        gatt?.let { g ->
            runCatching { g.disconnect() }
            runCatching { g.close() }
        }
        gatt = null
        rx = null
        device = null
        apply(link.disconnected())
    }

    private fun post(block: () -> Unit) {
        handler.post { guarded(block) }
    }

    private fun <T> guarded(block: () -> T): T? =
        try {
            block()
        } catch (_: SecurityException) {
            handler.post {
                drop()
                _status.value = NearbyStatus.NeedsPermission
            }
            null
        } catch (_: RuntimeException) {
            null
        }

    private fun now() = System.currentTimeMillis()

    companion object {
        /** The follower's Bluetooth path needs both (Android 12+; scan is `neverForLocation`). */
        val PERMISSIONS = listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        const val CONNECT_TIMEOUT_MS = 25_000L
        const val OP_TIMEOUT_MS = 10_000L
        const val SKIP_MS = 60_000L
        const val SILENCE_HELLO_MS = 180_000L
        const val SILENCE_DROP_MS = 300_000L
        const val RESCAN_MS = 3_000L
        private const val SCAN_RETRY_MS = 30_000L
        private const val SCAN_WINDOW_MS = 30_000L
        private const val SCAN_STARTS = 4
        private const val SCAN_RESTART_MS = 25 * 60_000L
        private const val MAX_BACKOFF_MS = 60_000L
        private const val SEND_TIMEOUT_MS = 60_000L
        private const val WATCHDOG_MS = 30_000L
        private const val MTU = 517
    }
}
