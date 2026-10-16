// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.bridge

import kotlinx.coroutines.flow.MutableStateFlow

/** Shared reading type and UI state of the wearer's local servers. */
object Bridge {
    data class Reading(val epochSec: Long, val mgdl: Int, val rate: Double?)

    /** UI-visible state. */
    data class Status(
        val source: String = "not started",
        val sourceDetail: String? = null,
        val latest: Reading? = null,
        val history: List<Reading> = emptyList(),
        val error: String? = null,
        /** Last request to the xDrip web service (epoch ms) — i.e. the Garmin watch polling through Garmin Connect. */
        val watchPolledAt: Long? = null,
    )

    val status = MutableStateFlow(Status())
}
