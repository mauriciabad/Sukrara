// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.sensor.pairing

import app.mauri.sukrara.sensor.SensorInfo
import app.mauri.sukrara.sensor.SensorLife
import app.mauri.sukrara.sensor.SensorProfile

/**
 * The saved sensor: what the source needs to (re)connect to it. For a Libre 3 the NFC tap
 * ([app.mauri.sukrara.sensor.libre3.Libre3Nfc]) gives address, PIN and start time; the first Bluetooth connection after
 * it runs a fresh pairing and adds [kAuth]. The other families ([SensorProfile.needsScan]) start with the typed id as
 * [serial] (empty when the family has none: Dexcom G7, SmartGuide), no PIN, no address and no start: the first scan
 * that finds the sensor adds [address] and [deviceName], the sensor's first message adds [startEpoch].
 *
 * [pin] and [kAuth] are secrets: the whole record is stored only in the SecureStore ([PairingStore]), never logged
 * (see [toString]) and never copied off the device.
 */
data class Pairing(
    val serial: String,
    /** Sensor BLE MAC, e.g. "AA:BB:CC:DD:EE:FF"; empty until a scan found a family that needs one. */
    val address: String,
    /** Activation time (unix s; 0 = not known yet); a Libre 3 reading's time = start + 60·(lifeCount + 1). */
    val startEpoch: Long,
    /** 0 = Libre 3 / 3 Plus; non-zero = Lingo (not supported). */
    val securityVersion: Int,
    val wearMin: Int,
    val warmupMin: Int,
    val profile: SensorProfile = SensorProfile.Libre3,
    /** 4 bytes from the Libre 3's NFC answer; empty for the other families. */
    val pin: ByteArray,
    /** 149 bytes; null until the first Bluetooth pairing after a scan completes. */
    val kAuth: ByteArray?,
    /** The sensor has ended: don't connect to it again until a new sensor is scanned. */
    val ended: Boolean = false,
    /** The name the sensor advertises, once a scan found it (what the person sees for a family without an id). */
    val deviceName: String = "",
    /** When a scanned family's pairing was made (epoch ms): tells two pairings of the same id-less family apart. */
    val pairedAt: Long = 0,
) {
    /** What the screens call this sensor: its id, else its advertised name, else the family. */
    val label: String get() = serial.ifEmpty { deviceName.ifEmpty { profile.label } }

    /** Null while the start isn't known (a scanned family before its first message). */
    val info: SensorInfo? get() = if (startEpoch > 0) SensorLife.fromStart(label, startEpoch, wearMin, warmupMin) else null

    override fun equals(other: Any?): Boolean =
        other is Pairing && serial == other.serial && address == other.address && startEpoch == other.startEpoch &&
            securityVersion == other.securityVersion && wearMin == other.wearMin && warmupMin == other.warmupMin &&
            profile == other.profile && pin.contentEquals(other.pin) && kAuth.contentEquals(other.kAuth) &&
            ended == other.ended && deviceName == other.deviceName && pairedAt == other.pairedAt

    override fun hashCode(): Int = serial.hashCode() * 31 + startEpoch.hashCode()

    /** Safe for logs: no secrets. */
    override fun toString() =
        "Pairing(${profile.id}, serial=$serial, address=$address, start=$startEpoch, paired=${kAuth != null}, ended=$ended)"

    companion object {
        /** A new sensor of a family that scans ([SensorProfile.needsScan]): [id] is the typed id, or empty. */
        fun scanned(profile: SensorProfile, id: String, pairedAtMs: Long): Pairing {
            require(profile.needsScan) { "${profile.id} isn't paired by a scan" }
            return Pairing(id, "", 0, 0, profile.wearMin, profile.warmupMin, profile, ByteArray(0), null, pairedAt = pairedAtMs)
        }
    }
}
