// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.core.model

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * One role's glucose input as the hub consumes it (wearer: the sensor's `SourceManager`, chunk 12; follower: chunk 25).
 * Readings are only ever what the sensor or the followed wearer sent: a source never invents, repeats as new or
 * extrapolates a value.
 */
interface ReadingSource {
    /** Batches of readings: one live value or a backfill (may repeat values the hub already has; it upserts). */
    val readings: Flow<List<Reading>>

    val sensor: StateFlow<SensorState>

    val status: StateFlow<SourceStatus>

    /** Bluetooth came back on (`BluetoothAdapter.ACTION_STATE_CHANGED`): reopen links with new connections. */
    fun onBluetoothOn()

    /** Bluetooth went off: close links now (no retries until [onBluetoothOn]). */
    fun onBluetoothOff()
}
