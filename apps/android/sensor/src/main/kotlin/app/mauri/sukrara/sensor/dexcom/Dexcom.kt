// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.sensor.dexcom

import app.mauri.sukrara.sensor.ble.NoValueReason
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * What the G5, G6, ONE, G7, ONE+ and Stelo share: the CGM GATT service, the 8-byte AES challenge and the algorithm
 * states. Sources: Juggluco DexGattCallback.java (GPL-3), xDrip+ g5model/BluetoothServices.java (GPL-3), LoopKit
 * `AlgorithmState.swift` (MIT). Untested on a real sensor.
 */
internal object Dexcom {
    /** Advertised by every Dexcom transmitter / G7 sensor. */
    val ADVERTISEMENT: UUID = UUID.fromString("0000febc-0000-1000-8000-00805f9b34fb")

    /** Commands and glucose (0x30/0x31 G6, 0x4E all). */
    val CONTROL: UUID = UUID.fromString("f8083534-849e-531c-c594-30f1f86a4ea5")

    /** Authentication (indications). */
    val AUTHENTICATION: UUID = UUID.fromString("f8083535-849e-531c-c594-30f1f86a4ea5")

    /** Backfill records. */
    val BACKFILL: UUID = UUID.fromString("f8083536-849e-531c-c594-30f1f86a4ea5")

    /** Dexcom sensors report 40–400 mg/dL; anything else is "LO" / "HI", never a number. */
    val VALID_MGDL = 40..400

    /** The challenge answer: AES-128-ECB over the 8 bytes twice, first 8 bytes out (xDrip+ calculateHash). */
    fun challengeHash(
        key: ByteArray,
        data: ByteArray,
    ): ByteArray {
        require(key.size == 16 && data.size == 8)
        val aes = Cipher.getInstance("AES/ECB/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES")) }
        return aes.doFinal(data + data).copyOf(8)
    }

    /** What the algorithm-state byte of a glucose message means for the person. */
    enum class State { Stopped, WarmingUp, Ok, NoValue, Ended, Unknown }

    /**
     * The algorithm-state byte (LoopKit / xDrip+ agree on the numbers). Only 6 ("ok") carries a value the person may act
     * on (LoopKit's rule; 7 "needs calibration" is not trusted here). "Stopped" (1) is not an end: a G7 reports it
     * before the vendor app starts the session.
     */
    fun state(raw: Int): State =
        when (raw) {
            1 -> State.Stopped
            2 -> State.WarmingUp
            6 -> State.Ok
            3, 4, 5, 7, 8, 9, 10, 13, 14, 18 -> State.NoValue
            11, 12, 15, 16, 17, 19, 20, 21, 22, 24, 25, 26 -> State.Ended
            else -> State.Unknown
        }

    /** Why a "no value" state has none. */
    fun noValueReason(raw: Int): NoValueReason =
        when (raw) {
            18 -> NoValueReason.Temporary
            3, 4, 5, 7, 8, 9, 10, 13, 14 -> NoValueReason.Calibration
            else -> NoValueReason.SensorError
        }

    /** Trend byte: signed tenths of mg/dL per minute, 0x7F = none. */
    fun trend(raw: Byte): Double? = if (raw.toInt() == 0x7F) null else raw / 10.0
}
