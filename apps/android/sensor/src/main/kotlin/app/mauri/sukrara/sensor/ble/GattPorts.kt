// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.sensor.ble

import java.util.UUID

/**
 * What [GattLink] needs from Android's Bluetooth stack, so the link's queue, timeouts and recovery run on the JVM against
 * a fake (`GattLinkTest`). [AndroidGatt] is the real one. Every call may throw `SecurityException` when the
 * Nearby devices permission (BLUETOOTH_CONNECT) was revoked.
 */
internal interface GattConnector {
    val isBluetoothOn: Boolean

    /**
     * A NEW connection to [address] (autoConnect: the controller attaches whenever the sensor advertises; no scanning).
     * Its callbacks arrive on any thread as [GattEvent]s tagged with [generation].
     */
    fun connect(address: String, generation: Int, onEvent: (GattEvent) -> Unit): GattConnection
}

/** One `BluetoothGatt`. Used once: after [close] the link makes a new one (never `connect()` on a stale object). */
internal interface GattConnection {
    /** False = the stack refused to start it. */
    fun discoverServices(): Boolean

    /** Enable notifications (CCCD write); completes with [GattEvent.OperationDone]. False = not issued. */
    fun enableNotifications(characteristic: UUID): Boolean

    /** Write with response; completes with [GattEvent.OperationDone]. False = not issued. */
    fun write(characteristic: UUID, value: ByteArray): Boolean

    /** Disconnect and release the client slot; no callbacks are expected afterwards (late ones are ignored). */
    fun close()
}

/** A GATT callback, tagged with the connection it belongs to. */
internal sealed interface GattEvent {
    val generation: Int

    data class ConnectionChanged(override val generation: Int, val connected: Boolean, val status: Int) : GattEvent

    data class ServicesDiscovered(override val generation: Int, val status: Int, val characteristics: Set<UUID>) : GattEvent

    /** A descriptor or characteristic write finished; [status] 0 = success. */
    data class OperationDone(override val generation: Int, val characteristic: UUID, val status: Int) : GattEvent

    class Notification(override val generation: Int, val characteristic: UUID, val value: ByteArray) : GattEvent
}
