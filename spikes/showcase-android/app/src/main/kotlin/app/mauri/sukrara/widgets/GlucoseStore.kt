// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.widgets

import app.mauri.sukrara.R

import android.content.Context
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The widgets' whole data layer: the last [KEEP_SEC] of readings, oldest first, kept in memory and mirrored to a
 * tiny CSV so a widget re-render after process death (launcher restart, 30-min system update, resize) still has
 * the graph without waiting for the source. In the showcase app this is replaced by its own reading store.
 */
object GlucoseStore {
    const val KEEP_SEC = 8 * 3600L

    private val _history = MutableStateFlow<List<Reading>>(emptyList())
    val history: StateFlow<List<Reading>> = _history
    @Volatile private var loaded = false

    private fun file(c: Context) = File(c.filesDir, "history.csv")

    @Synchronized
    fun load(c: Context): List<Reading> {
        if (!loaded) {
            loaded = true
            _history.value = runCatching {
                file(c).readLines().mapNotNull { line ->
                    val p = line.split(',')
                    if (p.size < 3) null
                    else Reading(p[0].toLong(), p[1].toInt(), p[2].takeIf { it.isNotEmpty() }?.toDouble())
                }
            }.getOrDefault(emptyList())
        }
        return _history.value
    }

    /** Merge readings (any order), dedupe by timestamp (±30 s), trim, persist. Returns true if anything changed. */
    @Synchronized
    fun add(c: Context, readings: List<Reading>): Boolean {
        val old = load(c)
        val cutoff = nowSec() - KEEP_SEC
        val merged = (old + readings).filter { it.epochSec >= cutoff }.sortedBy { it.epochSec }
            .fold(ArrayList<Reading>()) { acc, r ->
                if (acc.isNotEmpty() && r.epochSec - acc.last().epochSec < 30) acc[acc.size - 1] = r else acc += r
                acc
            }
        if (merged == old) return false
        _history.value = merged
        file(c).writeText(merged.joinToString("\n") { "${it.epochSec},${it.mgdl},${it.rate ?: ""}" })
        return true
    }

    @Synchronized
    fun replace(c: Context, readings: List<Reading>) {
        loaded = true
        _history.value = emptyList()
        file(c).delete()
        add(c, readings)
    }
}
