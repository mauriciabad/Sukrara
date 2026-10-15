// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.spike.glucobridge

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * BLE protocol between the phone (GATT server, advertises) and the Mac (central).
 * Must match spikes/menubar/Sources/GlucoseBar/BLEBridgeSource.swift.
 *
 * READING  (read, notify): u32 epoch s ‖ u16 mg/dL ‖ i16 rate×100 (0x8000 = unknown)            — 8 bytes, LE
 * HISTORY  (read, long):   u32 epoch s of newest ‖ u16 mg/dL per minute, newest first (0 = gap) — ≤ 4+2·180 bytes
 */
object Bridge {
    val SERVICE: UUID = UUID.fromString("6c1f0001-5b8a-4d8e-9a3c-676c75636f42")
    val READING: UUID = UUID.fromString("6c1f0002-5b8a-4d8e-9a3c-676c75636f42")
    val HISTORY: UUID = UUID.fromString("6c1f0003-5b8a-4d8e-9a3c-676c75636f42")
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    const val HISTORY_MINUTES = 180

    data class Reading(val epochSec: Long, val mgdl: Int, val rate: Double?)

    fun encode(r: Reading): ByteArray = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        .putInt(r.epochSec.toInt())
        .putShort(r.mgdl.toShort())
        .putShort(r.rate?.let { (it * 100).toInt().coerceIn(-32767, 32767).toShort() } ?: Short.MIN_VALUE)
        .array()

    fun encodeHistory(history: List<Reading>): ByteArray {
        val newest = history.maxByOrNull { it.epochSec } ?: return ByteArray(0)
        val slots = ShortArray(HISTORY_MINUTES)
        for (r in history) {
            val i = Math.round((newest.epochSec - r.epochSec) / 60.0).toInt()
            if (i in 0 until HISTORY_MINUTES) slots[i] = r.mgdl.toShort()
        }
        val buf = ByteBuffer.allocate(4 + 2 * HISTORY_MINUTES).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(newest.epochSec.toInt())
        slots.forEach { buf.putShort(it) }
        return buf.array()
    }

    /** UI-visible state. */
    data class Status(
        val source: String = "not started",
        val sourceDetail: String? = null,
        val latest: Reading? = null,
        val history: List<Reading> = emptyList(),
        val advertising: Boolean = false,
        val subscribed: Int = 0,
        val error: String? = null,
    )

    val status = MutableStateFlow(Status())
}
