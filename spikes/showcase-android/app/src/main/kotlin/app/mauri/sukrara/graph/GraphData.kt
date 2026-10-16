// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.graph

import android.content.Context
import android.util.Log
import app.mauri.sukrara.App
import app.mauri.sukrara.core.Proto
import app.mauri.sukrara.core.Reading

/**
 * Time in range over days, for the rings: read from the active role's store (the wearer keeps 15 days in `owner.db`,
 * a follower what it received) and cached for [TTL_S], so a widget redraw every minute doesn't query 20 000 rows.
 */
object GraphData {
    private const val TTL_S = 5 * 60L
    private data class Key(val windowS: Long, val bands: Bands)
    private val cache = HashMap<Key, Pair<Long, TimeInRange>>()

    fun readings(c: Context, fromS: Long): List<Reading> = runCatching {
        when (App.role(c)) {
            "owner" -> App.owner(c).history(fromS)
            "follower" -> App.follower(c).history(fromS).map { Reading(it.t, it.sgv, it.rate) }
            else -> emptyList()
        }
    }.getOrElse { Log.w("Sukrara.graph", "history: $it"); emptyList() }

    @Synchronized
    fun tir(c: Context, windowS: Long, bands: Bands, nowS: Long = Proto.nowS()): TimeInRange {
        val k = Key(windowS, bands)
        cache[k]?.let { (at, v) -> if (nowS - at < TTL_S) return v }
        val v = GlucoseStats.timeInRange(readings(c, nowS - windowS), nowS - windowS, nowS, bands)
        cache[k] = nowS to v
        return v
    }

    /** Role change / stop following: the cached numbers belong to someone else now. */
    @Synchronized fun reload() = cache.clear()
}
