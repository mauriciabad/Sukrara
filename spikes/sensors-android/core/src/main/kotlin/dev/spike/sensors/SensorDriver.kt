// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.spike.sensors

import java.util.UUID

/**
 * One sensor family's protocol, independent of Android (investigation 23).
 *
 * A driver never touches Bluetooth itself: it asks a [BleLink] for GATT operations and is fed what comes back.
 * That keeps every driver testable on the JVM against recorded traces, and lets Sukrara's existing Libre 3 client
 * become one more driver (see [SensorProfiles.LIBRE_3] and docs/23 "Architecture").
 *
 * Call order from the transport: [onConnected] once the services are discovered, then [onNotification] for every
 * notification / indication, [onBondChanged] when Android's bond state settles, [onDisconnected] when the link drops.
 * All calls come from one thread.
 */
interface SensorDriver {
    val profile: SensorProfile

    /** Should the transport connect to this advertisement? (Dexcom: by name; standard CGMS: by service + serial.) */
    fun matches(advert: Advert): Boolean

    fun onConnected(link: BleLink)
    fun onNotification(characteristic: UUID, value: ByteArray)
    fun onBondChanged(bonded: Boolean) {}
    fun onDisconnected() {}
}

/** What the transport saw in a scan result. */
data class Advert(val address: String, val name: String?, val serviceUuids: List<UUID> = emptyList())

/**
 * The transport. Operations run one at a time in call order (Android allows one outstanding GATT operation), so a
 * driver may queue "subscribe A, subscribe B, write C" in one go.
 */
interface BleLink {
    val isBonded: Boolean
    fun subscribe(characteristic: UUID, indicate: Boolean = false)
    fun write(characteristic: UUID, value: ByteArray, withResponse: Boolean = true)
    /** Android `createBond` (LE). The result comes back through [SensorDriver.onBondChanged]. */
    fun bond()
    fun disconnect()
}

/** What a driver reports. One sink per sensor; Sukrara's GlucoseSource.Sink maps onto it 1:1. */
interface SensorEvents {
    fun onReading(reading: Reading)
    fun onHistory(readings: List<Reading>) {}
    /** Session timing as the sensor reports it: start, warm-up end, expected end (epoch ms; null = unknown). */
    fun onSession(session: Session) {}
    fun onStatus(status: DriverStatus) {}
    /** Secrets to keep for the next connection (e.g. the G7 J-PAKE key). Opaque to the app. */
    fun onPairingState(state: ByteArray) {}
}

data class Reading(
    val timeMs: Long,
    /** Calibrated glucose straight from the sensor. Drivers that would need an algorithm are not written. */
    val mgdl: Int,
    /** mg/dL per minute, null when the sensor gives none. */
    val trendMgdlPerMin: Float? = null,
    val sensorState: String? = null,
    /** Sensor-relative minute or sequence number, for de-duplication and backfill gaps. */
    val index: Int? = null,
)

data class Session(val startMs: Long?, val warmupEndMs: Long?, val endMs: Long?, val state: String? = null)

sealed interface DriverStatus {
    data class Progress(val step: String) : DriverStatus
    data object Streaming : DriverStatus
    /** A failure the user can act on (wrong code, sensor held by another app, …). */
    data class Failed(val reason: String) : DriverStatus
    data class Ended(val reason: String) : DriverStatus
}

fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
fun String.unhex(): ByteArray = replace(Regex("[^0-9a-fA-F]"), "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
fun ByteArray.u8(at: Int): Int = this[at].toInt() and 0xFF
fun ByteArray.u16le(at: Int): Int = u8(at) or (u8(at + 1) shl 8)
fun ByteArray.u32le(at: Int): Long = (u16le(at).toLong()) or (u16le(at + 2).toLong() shl 16)
fun ByteArray.i32le(at: Int): Int = u32le(at).toInt()
