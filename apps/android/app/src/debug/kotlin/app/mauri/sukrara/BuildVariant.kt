// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara

import app.mauri.sukrara.core.model.ReadingSource
import app.mauri.sukrara.debug.SyntheticSource
import kotlinx.coroutines.withContext

/** Debug builds: the DebugFakes `SYNTHETIC` source replaces the sensor while it's switched on (release: never). */
internal object BuildVariant {
    suspend fun wearerSource(graph: AppGraph): ReadingSource? =
        withContext(graph.dispatchers.io) {
            if (SyntheticSource.isOn(graph.app)) SyntheticSource(graph.clock) { SyntheticSource.isFrozen(graph.app) } else null
        }
}
