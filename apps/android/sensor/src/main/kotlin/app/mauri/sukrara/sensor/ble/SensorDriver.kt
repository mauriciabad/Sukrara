// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.sensor.ble

import app.mauri.sukrara.core.model.Reading
import java.util.UUID

/**
 * One sensor family's protocol, independent of Bluetooth. A driver never touches `BluetoothGatt`: it asks its [BleLink]
 * for subscribes and writes and is fed what comes back, so it runs on the JVM against a replayed session
 * (`Libre3DriverTest`, `G7DriverTest`, …). The transport ([GattLink]) owns the connection, the operation queue,
 * timeouts, reconnects and Bluetooth on/off; the driver owns the handshake, the packets and the sensor-level states.
 *
 * Call order from the transport, always on the link's one serial dispatcher: [onConnected] once services are
 * discovered (every connection: the driver starts its handshake over), then [onNotification] per notification,
 * [onRead] per completed read, [onBondChanged] when a requested bond settled, [onLinkFailed] when a GATT operation
 * fails or times out, [onDisconnected] when the connection is gone (the transport reconnects by itself), [onClosed]
 * when the link is closed for good.
 */
internal interface SensorDriver {
    /** Services discovered: [characteristics] = every characteristic the device offers, [status] = GATT discovery status. */
    fun onConnected(link: BleLink, characteristics: Set<UUID>, status: Int)

    fun onNotification(characteristic: UUID, value: ByteArray)

    /** The value of a [BleLink.read]. The Libre 3 never reads. */
    fun onRead(characteristic: UUID, value: ByteArray) {}

    /** Android's bond with the device settled after [BleLink.bond]. The Libre 3 never bonds. */
    fun onBondChanged(bonded: Boolean) {}

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
    /** This phone has a Bluetooth bond with the device (asked when the driver connects). */
    val isBonded: Boolean get() = false

    fun subscribe(characteristic: UUID)

    /** Subscribe with indications (acknowledged notifications): Dexcom's authentication, the CGM control points. */
    fun indicate(characteristic: UUID): Unit = throw UnsupportedOperationException("indicate $characteristic")

    fun write(characteristic: UUID, value: ByteArray)

    /** Write without response (Aidex X). */
    fun writeNoResponse(characteristic: UUID, value: ByteArray): Unit = throw UnsupportedOperationException("write $characteristic")

    /** Read a characteristic; the value comes back through [SensorDriver.onRead]. */
    fun read(characteristic: UUID): Unit = throw UnsupportedOperationException("read $characteristic")

    /** Android's `createBond`; the result comes back through [SensorDriver.onBondChanged]. */
    fun bond(): Unit = throw UnsupportedOperationException("bond")

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

    /** Readings by the sensor's own index (the Libre 3's life count), for the LibreView upload. */
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

    /**
     * The sensor said when its session started (epoch s): the families whose start isn't known at pairing (all but the
     * Libre 3). The warm-up, the end and the reminders count from it.
     */
    fun onSessionStart(startEpoch: Long) {}
}

/** One glucose value by the sensor's life count (minutes since its start), as LibreView takes them. */
data class SensorGlucose(val lifeCount: Int, val mgdl: Int, val epochSec: Long, val rate: Double? = null)

/** Where the link and the sensor are; the UI words for these live in the sensor screens. */
sealed interface LinkStatus {
    data object WaitingForBluetooth : LinkStatus

    /** The Nearby devices permission (BLUETOOTH_CONNECT / _SCAN) is missing: the link stopped until the source restarts. */
    data object PermissionMissing : LinkStatus

    /** Looking for the sensor (a family whose address isn't known yet): short filtered scans. */
    data object Scanning : LinkStatus

    /** Still scanning, but the sensor hasn't shown up for a long while: check the id, bring the phone closer. */
    data object SensorNotFound : LinkStatus

    data object Connecting : LinkStatus

    data object DiscoveringServices : LinkStatus

    /** Disconnected; the next connection is armed (the sensor is out of range or restarting). */
    data object WaitingForSensor : LinkStatus

    data object Pairing : LinkStatus

    /** Android's pairing dialog is up: the person types the PIN from the sensor's cap (SmartGuide). */
    data object EnterPin : LinkStatus

    data object Authorizing : LinkStatus

    data object Authorized : LinkStatus

    /** Connected to a Dexcom G7, waiting for the Dexcom app's session (Sukrara only reads along). */
    data object WaitingForVendorApp : LinkStatus

    /** The transmitter runs but no sensor session is started (the Dexcom app or receiver starts it). */
    data object NoSession : LinkStatus

    data object Live : LinkStatus

    data class WarmingUp(val minutesLeft: Int) : LinkStatus

    /** The sensor is connected but its current message carries no value (calibration, noise, a sensor error). */
    data class NoValue(val why: NoValueReason) : LinkStatus

    data object SensorEnded : LinkStatus

    /** Something the person has to fix (wrong id, refused PIN): the link is closed until they pair again. */
    data class Failed(val why: FailReason) : LinkStatus

    /** A protocol or link error; the link reconnects. [why] is for the log / diagnostics, never a glucose value. */
    data class Reconnecting(val why: String) : LinkStatus
}

/** Why a connected sensor sends no value right now. */
enum class NoValueReason {
    /** Dexcom: the sensor asks for a finger-stick calibration problem to be solved, or too much noise. */
    Calibration,

    /** A temporary sensor issue; values come back by themselves. */
    Temporary,

    /** The sensor reports an error. */
    SensorError,

    /** The message carries no number. */
    Missing,

    /** Lower than the sensor can measure ("LO"): no number is shown, never a made-up floor value. */
    BelowRange,

    /** Higher than the sensor can measure ("HI"). */
    AboveRange,
}

/** Why a scanned family's pairing can't go on without the person. */
enum class FailReason {
    /** The typed transmitter id / serial isn't this device's (the keys don't match). */
    WrongId,

    /** The device refused the authentication. */
    Refused,

    /** Android's Bluetooth pairing (the PIN) didn't complete. */
    BondFailed,
}
