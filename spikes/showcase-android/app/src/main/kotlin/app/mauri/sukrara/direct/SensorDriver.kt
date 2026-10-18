// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.direct

import android.content.SharedPreferences
import app.mauri.sukrara.bridge.Bridge
import app.mauri.sukrara.core.SensorProfile
import app.mauri.sukrara.libreview.LibreViewPayload
import java.util.UUID

/**
 * One sensor family's protocol, independent of Bluetooth (investigation 23, step 1). A driver never touches
 * `BluetoothGatt`: it asks its [BleLink] for subscribes and writes and is fed what comes back, so every driver runs
 * on the JVM against a replayed session (`Libre3DriverTest`). The transport ([GattLink]) owns the connection, the
 * operation queue, reconnects and the transport-level status words; the driver owns the handshake, the packets and
 * the sensor-level status words. [DirectSource] picks the driver for the saved sensor from [Drivers].
 *
 * Call order from the transport: [onConnected] once services are discovered (every connection, so the driver starts
 * its handshake over), then [onNotification] per notification, [onLinkFailed] when a GATT operation fails,
 * [onDisconnected] when the link drops (the transport reconnects by itself), [onClosed] when the link is closed for
 * good. Notifications arrive on the Bluetooth binder thread, timers ([BleLink.schedule]) on the main thread — the
 * same threads the Libre 3 client always ran on.
 */
internal interface SensorDriver {
    val profile: SensorProfile

    /** Should the transport connect to this device? Libre 3: the address from the NFC tap; Dexcom: by name. */
    fun matches(advert: Advert): Boolean

    /** A reading carried in an advertisement (Aidex X), without a connection. Most families have none. */
    fun onAdvertisement(advert: Advert, manufacturerData: ByteArray) {}

    /** Services discovered: [characteristics] = every characteristic the device offers, [status] = GATT discovery status. */
    fun onConnected(link: BleLink, characteristics: Set<UUID>, status: Int)
    fun onNotification(characteristic: UUID, value: ByteArray)

    /** A GATT operation the driver queued could not be issued or failed; [why] is for the log and the status line. */
    fun onLinkFailed(why: String)

    /** Android's bond state settled after [BleLink.bond] (SmartGuide). The Libre 3 never bonds. */
    fun onBondChanged(bonded: Boolean) {}

    /** The connection dropped; the transport reconnects and calls [onConnected] again. */
    fun onDisconnected()

    /** The link is closed for good ([BleLink.close] or the source stopping): free session state. */
    fun onClosed()
}

/** What a transport saw in a scan result (families with [SensorProfile.needsScan]) or knows from the pairing. */
internal data class Advert(val address: String, val name: String? = null, val serviceUuids: List<UUID> = emptyList())

/**
 * The transport as a driver sees it. Operations run one at a time in call order (Android allows one outstanding GATT
 * operation per connection), so a driver may queue "subscribe A, subscribe B, write C" in one go.
 */
internal interface BleLink {
    val isBonded: Boolean
    fun subscribe(characteristic: UUID, indicate: Boolean = false)
    fun write(characteristic: UUID, value: ByteArray, withResponse: Boolean = true)
    /** Android `createBond` (LE). The result comes back through [SensorDriver.onBondChanged]. */
    fun bond()
    /** `BluetoothGatt.CONNECTION_PRIORITY_*`, queued behind the operations issued before it. */
    fun requestConnectionPriority(priority: Int)
    /** Drop the connection: the transport reconnects and the driver starts over at [SensorDriver.onConnected]. */
    fun disconnect()
    /** Give the device up until the source is started again (an ended sensor): no reconnects. */
    fun close()
    /** Run [action] on the transport's thread after [delayMs]; [cancel] drops a pending one (same `Runnable`). */
    fun schedule(delayMs: Long, action: Runnable)
    fun cancel(action: Runnable)
}

/**
 * What a driver reports. [DirectSource] forwards it to the app's [app.mauri.sukrara.bridge.GlucoseSource.Sink] field
 * for field and keeps [onPairingState].
 */
internal interface SensorEvents {
    /** One-line state for the UI / notification (`NewSensorFlow` reads these words); [detail] is shown separately. */
    fun onStatus(state: String, detail: String? = null)
    fun onReading(reading: Bridge.Reading)
    /** Bulk readings (backfill); may include duplicates. */
    fun onHistory(readings: List<Bridge.Reading>)
    /** Readings by the sensor's own index (the Libre 3's life count), for the LibreView upload. */
    fun onSensorGlucose(current: LibreViewPayload.Glucose?, history: List<LibreViewPayload.Glucose>) {}
    /** The sensor has reached its end (patch state, life count): the driver stops connecting to it. */
    fun onSensorEnded(serial: String) {}
    /** A secret to keep for the next connection (Libre 3: the authorization record after a fresh pairing). Opaque. */
    fun onPairingState(state: ByteArray) {}
}

/** The direct source's own prefs (`"direct"`): per-sensor ended flags and the subscription-set experiments. */
internal interface DriverPrefs {
    fun getBoolean(key: String, default: Boolean): Boolean
    fun putBoolean(key: String, value: Boolean)

    companion object {
        /** Set when a sensor has ended ([PatchState]): [DirectSource] doesn't connect to it again. */
        fun endedKey(serial: String) = "ended:$serial"
        /** Source status detail for an ended sensor (shown under the card and on the Sensor page). */
        const val ENDED_HINT = "Replace sensor: tap Pair new sensor on the home screen"

        fun of(prefs: SharedPreferences): DriverPrefs = object : DriverPrefs {
            override fun getBoolean(key: String, default: Boolean) = prefs.getBoolean(key, default)
            override fun putBoolean(key: String, value: Boolean) { prefs.edit().putBoolean(key, value).apply() }
        }
    }
}
