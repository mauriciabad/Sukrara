// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.core.model

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * The one input of [GlucoseHub] for the phone's role: the wearer's sensor source (`:sensor` SourceManager) or the
 * follower engine. The monitoring service attaches exactly one at a time.
 */
interface ReadingSource {
    /** New readings as they arrive (a backfill comes as one list). Cold: collecting it runs the source. */
    val readings: Flow<List<Reading>>

    /** The sensor as the person needs to see it; [SensorState.None] = nothing set up to monitor. */
    val sensor: StateFlow<SensorState>

    val status: StateFlow<SourceStatus>

    /** Bluetooth came back on: reconnect with a fresh connection. */
    fun onBluetoothOn()

    /** Bluetooth went off: close links, don't retry until [onBluetoothOn]. */
    fun onBluetoothOff()
}
