// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara

import app.mauri.sukrara.core.model.Reading
import app.mauri.sukrara.core.model.ReadingSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/**
 * [source] runs exactly while the hub collects its readings, i.e. while the monitoring service hosts it: the sensor's
 * Bluetooth link never runs without the foreground service, and stops with it (or when the role changes).
 */
class RunWhileAttached(
    private val source: ReadingSource,
    private val start: () -> Unit,
    private val stop: () -> Unit,
) : ReadingSource by source {
    override val readings: Flow<List<Reading>> =
        flow {
            start()
            try {
                emitAll(source.readings)
            } finally {
                stop()
            }
        }
}
