// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.sensor.ble

import app.mauri.sukrara.core.model.Reading
import java.util.UUID

/**
 * One sensor family's protocol, independent of Bluetooth. A driver never touches `BluetoothGatt`: it asks its [BleLink]
 * for subscribes and writes and is fed what comes back, so it runs on the JVM against a replayed session
 * (`Libre3DriverTest`). The transport ([GattLink]) owns the connection, the operation queue, timeouts, reconnects and
 * Bluetooth on/off; the driver owns the handshake, the packets and the sensor-level states.
 *
 * Call order from the transport, always on the link's one serial dispatcher: [onConnected] once services are
 * discovered (every connection: the driver starts its handshake over), then [onNotification] per notification,
 * [onLinkFailed] when a GATT operation fails or times out, [onDisconnected] when the connection is gone (the transport
 * reconnects by itself), [onClosed] when the link is closed for good.
 */
internal interface SensorDriver {
    /** Services discovered: [characteristics] = every characteristic the device offers, [status] = GATT discovery status. */
    fun onConnected(link: BleLink, characteristics: Set<UUID>, status: Int)

    fun onNotification(characteristic: UUID, value: ByteArray)

    /** A GATT operation the driver queued could not be issued, failed or timed out; [why] is for the log. */
    fun onLinkFailed(why: String)

    /** The connection dropped; the transport reconnects and calls [onConnected] again. */
    fun onDisconnected()

    /** The link is closed for good ([BleLink.close] or the source stopping): free session state. */
    fun onClosed()
}

/**
 * The transport as a driver sees it. Operations run one at a time in call order (Android allows one outstanding GATT
 * operation per connection), so a driver may queue "subscribe A, subscribe B, write C" in one go.
 */
internal interface BleLink {
    fun subscribe(characteristic: UUID)

    fun write(characteristic: UUID, value: ByteArray)

    /** Drop the connection: the transport reconnects with a new one and the driver starts over at [SensorDriver.onConnected]. */
    fun disconnect()

    /** Give the device up until the source is started again (an ended sensor): no reconnects. */
    fun close()

    /** Run [action] on the link's dispatcher after [delayMs]; [cancel] drops a pending one (same instance). */
    fun schedule(delayMs: Long, action: Runnable)

    fun cancel(action: Runnable)
}

/** What a driver reports; the source turns it into readings and sensor state. */
internal interface SensorEvents {
    fun onStatus(status: LinkStatus)

    fun onReading(reading: Reading)

    /** Bulk readings (backfill); may include duplicates. */
    fun onHistory(readings: List<Reading>)

    /** Readings by the sensor's own index (the Libre 3's life count), for the LibreView upload (chunk 26). */
    fun onSensorGlucose(current: SensorGlucose?, history: List<SensorGlucose>) {}

    /** The sensor said it has reached its end (patch state, life count). It may still send in a grace period. */
    fun onSensorEnded(serial: String) {}

    /** The sensor has ended and will not be connected again until a new sensor is scanned: persist it. */
    fun onEndedForGood(serial: String) {}

    /**
     * The sensor keeps refusing our authorization: another app or receiver owns it now (it was taken over by NFC). The
     * link is closed; only a new NFC scan (take over) brings it back.
     */
    fun onOwnershipLost(serial: String) {}

    /** A secret to keep for the next connection (Libre 3: the authorization record after a fresh pairing). Opaque. */
    fun onPairingState(state: ByteArray) {}
}

/** One glucose value by the sensor's life count (minutes since its start), as LibreView takes them. */
data class SensorGlucose(val lifeCount: Int, val mgdl: Int, val epochSec: Long, val rate: Double? = null)

/** Where the link and the sensor are; the UI words for these live in chunk 18. */
sealed interface LinkStatus {
    data object WaitingForBluetooth : LinkStatus

    /** The Nearby devices permission (BLUETOOTH_CONNECT) is missing: the link stopped until the source restarts. */
    data object PermissionMissing : LinkStatus

    data object Connecting : LinkStatus

    data object DiscoveringServices : LinkStatus

    /** Disconnected; the next connection is armed (the sensor is out of range or restarting). */
    data object WaitingForSensor : LinkStatus

    data object Pairing : LinkStatus

    data object Authorizing : LinkStatus

    data object Authorized : LinkStatus

    data object Live : LinkStatus

    data class WarmingUp(val minutesLeft: Int) : LinkStatus

    data object SensorEnded : LinkStatus

    /** A protocol or link error; the link reconnects. [why] is for the log / diagnostics, never a glucose value. */
    data class Reconnecting(val why: String) : LinkStatus
}
