// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.sensor

import app.mauri.sukrara.core.model.SensorState
import java.time.Instant

/** What is known about the current sensor (unix seconds): its start, end and when the first reading is due. */
data class SensorInfo(val serial: String, val startEpoch: Long, val endEpoch: Long, val warmupEndEpoch: Long)

/**
 * The sensor's life from its start, wear length and warm-up. The card's badge, ring and label helpers are drawn by the
 * graph renderer (`:core:graph`); this is only the state the hub needs.
 */
object SensorLife {
    val DEFAULT_WEAR_MIN: Int = SensorProfile.Libre3.wearMin
    val DEFAULT_WARMUP_MIN: Int = SensorProfile.Libre3.warmupMin

    /** The warm-up state lasts until the first reading, at most this long past the warm-up's end. */
    const val WARMUP_GRACE_S = 15 * 60L

    /** From the sensor's start, wear length and warm-up in minutes (0 or less = the Libre 3 defaults). */
    fun fromStart(serial: String, startEpoch: Long, wearMin: Int, warmupMin: Int = 0): SensorInfo =
        SensorInfo(
            serial,
            startEpoch,
            startEpoch + 60L * (if (wearMin > 0) wearMin else DEFAULT_WEAR_MIN),
            startEpoch + 60L * (if (warmupMin > 0) warmupMin else DEFAULT_WARMUP_MIN),
        )

    /**
     * Warming up: started, no reading since its start ([latestT] = newest reading's time, null = none) and not more than
     * [WARMUP_GRACE_S] past the warm-up's end.
     */
    fun warming(info: SensorInfo, latestT: Long?, now: Long): Boolean =
        now >= info.startEpoch && now < info.warmupEndEpoch + WARMUP_GRACE_S && (latestT == null || latestT < info.startEpoch)

    fun ended(info: SensorInfo, now: Long): Boolean = now >= info.endEpoch

    /** The hub's sensor state for a running sensor at [now]. */
    fun state(info: SensorInfo, latestT: Long?, now: Long): SensorState = when {
        ended(info, now) -> SensorState.Ended
        warming(info, latestT, now) -> SensorState.WarmingUp(Instant.ofEpochSecond(info.warmupEndEpoch))
        else -> SensorState.Active(Instant.ofEpochSecond(info.endEpoch))
    }
}
