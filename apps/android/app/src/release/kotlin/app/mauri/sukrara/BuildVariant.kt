// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara

import app.mauri.sukrara.core.model.ReadingSource

/** Release builds: readings only ever come from the real sensor (the debug variant of this file adds test sources). */
internal object BuildVariant {
    @Suppress("UNUSED_PARAMETER", "RedundantSuspendModifier")
    suspend fun wearerSource(graph: AppGraph): ReadingSource? = null
}
