// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.sensor.ble

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * A [BleLink] to one device known by address (the Libre 3's NFC tap gave it). It connects with autoConnect, discovers
 * services, runs GATT operations one at a time and hands everything else to the [driver].
 *
 * Reliability:
 * - every operation and the service discovery time out after [opTimeoutMs] → the driver is told and drops the link;
 * - a dropped link is closed at once and a NEW connection is made after a backoff (1 s, doubling to 30 s, reset when
 *   the sensor sends data) — never `connect()` on a stale `BluetoothGatt`;
 * - Bluetooth off → everything closed, [LinkStatus.WaitingForBluetooth], no retries (Bluetooth on: the source starts a
 *   new link and driver);
 * - callbacks of a closed connection (late or duplicated by the stack) are ignored by their generation;
 * - a revoked Nearby devices permission → [LinkStatus.PermissionMissing], no retry loop; any other exception from the
 *   stack (adapter gone, Bluetooth turning off mid-call) → treated as a dropped link, never a crash.
 *
 * Threading: [scope] runs on ONE serial dispatcher; callbacks from Binder threads are queued into an unlimited channel
 * and handled there, as are the driver and its timers. Call every public method from that dispatcher.
 */
internal class GattLink(
    private val connector: GattConnector,
    private val address: String,
    private val driver: SensorDriver,
    private val events: SensorEvents,
    private val scope: CoroutineScope,
    private val opTimeoutMs: Long = OP_TIMEOUT_MS,
    private val log: (String) -> Unit = {},
) : BleLink {
    companion object {
        const val OP_TIMEOUT_MS = 10_000L
        const val FIRST_BACKOFF_MS = 1_000L
        const val MAX_BACKOFF_MS = 30_000L
    }

    private sealed interface Op {
        val characteristic: UUID

        data class Subscribe(override val characteristic: UUID) : Op

        class Write(override val characteristic: UUID, val value: ByteArray) : Op
    }

    private val inbox = Channel<GattEvent>(Channel.UNLIMITED)
    private var generation = 0
    private var connection: GattConnection? = null
    private var closed = false
    private var bluetoothOff = false
    private var backoffMs = FIRST_BACKOFF_MS
    private var reconnect: Job? = null
    private var timeout: Job? = null
    private val ops = ArrayDeque<Op>()
    private var opRunning = false
    private val timers = HashMap<Runnable, Job>()
    private val pump: Job = scope.launch { for (e in inbox) handle(e) }

    fun start() = connectNow()

    /** Bluetooth went off (BluetoothStateReceiver, via the source): close now, no retries. */
    fun bluetoothOff() {
        if (closed) return
        bluetoothOff = true
        reconnect?.cancel()
        drop(reconnectAfter = false)
        events.onStatus(LinkStatus.WaitingForBluetooth)
    }

    // MARK: BleLink

    override fun subscribe(characteristic: UUID) = enqueue(Op.Subscribe(characteristic))

    override fun write(characteristic: UUID, value: ByteArray) = enqueue(Op.Write(characteristic, value))

    override fun disconnect() = drop(reconnectAfter = true)

    override fun close() {
        if (closed) return
        closed = true
        reconnect?.cancel()
        teardown()
        driver.onClosed()
        timers.values.forEach { it.cancel() }
        timers.clear()
        inbox.close()
        pump.cancel()
    }

    override fun schedule(delayMs: Long, action: Runnable) {
        timers.remove(action)?.cancel()
        timers[action] =
            scope.launch {
                delay(delayMs)
                timers.remove(action)
                action.run()
            }
    }

    override fun cancel(action: Runnable) {
        timers.remove(action)?.cancel()
    }

    // MARK: connection

    private fun connectNow() {
        if (closed || connection != null) return
        if (!connector.isBluetoothOn) {
            bluetoothOff = true
            events.onStatus(LinkStatus.WaitingForBluetooth)
            return
        }
        val gen = ++generation
        guarded {
            connection = connector.connect(address, gen) { inbox.trySend(it) }
            events.onStatus(LinkStatus.Connecting)
        }
    }

    private fun scheduleReconnect() {
        events.onStatus(LinkStatus.WaitingForSensor)
        val wait = backoffMs
        backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
        reconnect?.cancel()
        reconnect =
            scope.launch {
                delay(wait)
                connectNow()
            }
    }

    /** Close the current connection now; reconnect with a new one after the backoff unless closing / Bluetooth off. */
    private fun drop(reconnectAfter: Boolean) {
        val hadConnection = connection != null
        teardown()
        if (hadConnection) driver.onDisconnected()
        if (!reconnectAfter || closed || bluetoothOff) return
        scheduleReconnect()
    }

    private fun teardown() {
        timeout?.cancel()
        ops.clear()
        opRunning = false
        val c = connection ?: return
        connection = null
        generation++ // whatever the old connection still reports is ignored
        runCatching { c.close() }.onFailure { log("close failed: ${it.javaClass.simpleName}") }
    }

    private fun handle(e: GattEvent) {
        val c = connection
        if (c == null || e.generation != generation) return
        when (e) {
            is GattEvent.ConnectionChanged -> {
                if (e.connected) {
                    events.onStatus(LinkStatus.DiscoveringServices)
                    armTimeout("service discovery") { drop(reconnectAfter = true) }
                    guarded { if (!c.discoverServices()) drop(reconnectAfter = true) }
                } else {
                    log("sensor disconnected (status ${e.status})")
                    drop(reconnectAfter = true)
                }
            }

            is GattEvent.ServicesDiscovered -> {
                timeout?.cancel()
                driver.onConnected(this, e.characteristics, e.status)
            }

            is GattEvent.OperationDone -> {
                timeout?.cancel()
                if (e.status != 0) return driver.onLinkFailed("operation on ${e.characteristic} failed: ${e.status}")
                opRunning = false
                pumpOps()
            }

            is GattEvent.Notification -> {
                backoffMs = FIRST_BACKOFF_MS // the sensor is talking: the next drop retries quickly again
                driver.onNotification(e.characteristic, e.value)
            }
        }
    }

    // MARK: operation queue (one outstanding GATT operation per connection)

    private fun enqueue(op: Op) {
        if (connection == null) return
        ops.addLast(op)
        pumpOps()
    }

    private fun pumpOps() {
        if (opRunning) return
        val c = connection ?: return
        val op = ops.removeFirstOrNull() ?: return
        opRunning = true
        armTimeout(op.toString()) { driver.onLinkFailed("$op timed out") }
        guarded {
            val issued =
                when (op) {
                    is Op.Subscribe -> c.enableNotifications(op.characteristic)
                    is Op.Write -> c.write(op.characteristic, op.value)
                }
            if (!issued) {
                timeout?.cancel()
                driver.onLinkFailed("$op on ${op.characteristic} rejected")
            }
        }
    }

    private fun armTimeout(what: String, onTimeout: () -> Unit) {
        timeout?.cancel()
        timeout =
            scope.launch {
                delay(opTimeoutMs)
                log("$what timed out")
                onTimeout()
            }
    }

    /**
     * Runs a Bluetooth call. A revoked permission stops the link with a state the person can act on; anything else
     * the stack throws is a dropped link (reconnect with backoff), never an exception that would stop the source.
     */
    private inline fun guarded(block: () -> Unit) {
        try {
            block()
        } catch (_: SecurityException) {
            log("Bluetooth permission missing")
            close()
            events.onStatus(LinkStatus.PermissionMissing)
        } catch (e: Exception) {
            log("Bluetooth call failed: ${e.javaClass.simpleName}")
            if (connection != null) {
                drop(reconnectAfter = true)
            } else if (!closed && !bluetoothOff) {
                scheduleReconnect()
            }
        }
    }
}
