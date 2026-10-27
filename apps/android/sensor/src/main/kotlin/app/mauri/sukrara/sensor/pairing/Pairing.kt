// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.sensor.pairing

import app.mauri.sukrara.sensor.SensorLife
import app.mauri.sukrara.sensor.SensorProfile

/**
 * The saved Libre 3 sensor: what the source needs to (re)connect to it. The NFC tap ([app.mauri.sukrara.sensor.libre3.Libre3Nfc])
 * gives address, PIN and start time; the first Bluetooth connection after it runs a fresh pairing and adds [kAuth].
 *
 * [pin] and [kAuth] are secrets: the whole record is stored only in the SecureStore ([PairingStore]), never logged
 * (see [toString]) and never copied off the device.
 */
data class Pairing(
    val serial: String,
    /** Sensor BLE MAC, e.g. "AA:BB:CC:DD:EE:FF". */
    val address: String,
    /** Activation time (unix s); a reading's time = start + 60·(lifeCount + 1). */
    val startEpoch: Long,
    /** 0 = Libre 3 / 3 Plus; non-zero = Lingo (not supported). */
    val securityVersion: Int,
    val wearMin: Int,
    val warmupMin: Int,
    val profile: SensorProfile = SensorProfile.Libre3,
    /** 4 bytes from the NFC answer. */
    val pin: ByteArray,
    /** 149 bytes; null until the first Bluetooth pairing after a scan completes. */
    val kAuth: ByteArray?,
    /** The sensor has ended: don't connect to it again until a new sensor is scanned. */
    val ended: Boolean = false,
) {
    val info get() = SensorLife.fromStart(serial, startEpoch, wearMin, warmupMin)

    override fun equals(other: Any?): Boolean =
        other is Pairing && serial == other.serial && address == other.address && startEpoch == other.startEpoch &&
            securityVersion == other.securityVersion && wearMin == other.wearMin && warmupMin == other.warmupMin &&
            profile == other.profile && pin.contentEquals(other.pin) && kAuth.contentEquals(other.kAuth) && ended == other.ended

    override fun hashCode(): Int = serial.hashCode() * 31 + startEpoch.hashCode()

    /** Safe for logs: no secrets. */
    override fun toString() = "Pairing(serial=$serial, address=$address, start=$startEpoch, paired=${kAuth != null}, ended=$ended)"
}
