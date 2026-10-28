// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.direct.dexcom

import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * What the G5, G6, ONE, G7, ONE+ and Stelo share: the CGM GATT service, the 8-byte AES challenge and the algorithm
 * states. Sources: Juggluco DexGattCallback.java:308-316 (GPL-3), xDrip+ g5model/BluetoothServices.java (GPL-3), LoopKit
 * `AlgorithmState.swift` (MIT). Ported from `spikes/sensors-android` (investigation 23). Untested on hardware.
 */
internal object Dexcom {
    /** Advertised by every Dexcom transmitter / G7 sensor. */
    val ADVERTISEMENT: UUID = UUID.fromString("0000febc-0000-1000-8000-00805f9b34fb")
    val CGM_SERVICE: UUID = UUID.fromString("f8083532-849e-531c-c594-30f1f86a4ea5")
    /** Commands and glucose (0x30/0x31 G6, 0x4E all). */
    val CONTROL: UUID = UUID.fromString("f8083534-849e-531c-c594-30f1f86a4ea5")
    /** Authentication (indications). */
    val AUTHENTICATION: UUID = UUID.fromString("f8083535-849e-531c-c594-30f1f86a4ea5")
    /** Backfill records. */
    val BACKFILL: UUID = UUID.fromString("f8083536-849e-531c-c594-30f1f86a4ea5")

    /**
     * The challenge answer: AES-128-ECB over the 8 bytes twice, first 8 bytes out (xDrip+ Ob1G5StateMachine.calculateHash).
     * G5/G6 key: from the transmitter id ([G6Messages.key]).
     */
    fun challengeHash(key: ByteArray, data: ByteArray): ByteArray {
        require(key.size == 16 && data.size == 8)
        val aes = Cipher.getInstance("AES/ECB/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES")) }
        return aes.doFinal(data + data).copyOf(8)
    }

    /** The algorithm-state byte of every glucose message (LoopKit / xDrip+ agree on the numbers). */
    fun stateName(state: Int): String = when (state) {
        1 -> "stopped"
        2 -> "warming up"
        3 -> "excess noise"
        4, 5 -> "needs calibration"
        6 -> "ok"
        7 -> "needs calibration"
        8, 9, 10, 13, 14 -> "calibration problem"
        11, 12, 16, 17, 19, 20, 21, 22, 25 -> "sensor failed"
        15 -> "session expired"
        18 -> "temporary sensor issue"
        24 -> "expired"
        26 -> "session ended"
        else -> "state $state"
    }

    /**
     * LoopKit treats only state 6 as reliable; xDrip+ also stores values in state 7 ("needs calibration": the sensor
     * still gives its own numbers). This port follows xDrip+, which matches what the vendor app shows.
     */
    fun stateUsable(state: Int) = state == 6 || state == 7

    fun stateWarmingUp(state: Int) = state == 2

    /**
     * The session is over for good. "stopped" (1) is not: a G7 reports it before the vendor app starts the session
     * (LoopKit's lifecycle fixture opens with it), so it is a wait, not an end.
     */
    fun stateEnded(state: Int) = stateName(state) in setOf("session expired", "expired", "session ended", "sensor failed")

    /** Trend byte: signed tenths of mg/dL per minute, 0x7F = none. */
    fun trend(raw: Byte): Float? = if (raw.toInt() == 0x7F) null else raw / 10f
}
