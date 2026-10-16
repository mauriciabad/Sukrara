// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.widgets

import android.content.Context
import android.util.Log
import app.mauri.sukrara.App
import app.mauri.sukrara.core.Proto
import app.mauri.sukrara.core.Reading
import app.mauri.sukrara.treatments.Treat
import app.mauri.sukrara.treatments.Treatment

/**
 * The last [KEEP_S] the widgets and the notification graph draw. It keeps no file of its own: the first read in a
 * process (e.g. a system-initiated widget render after process death) loads from the active role's store
 * (`owner.db` + `treatments.json` for the wearer, `follower.db` for a follower), then [add] / [setTreatments] keep
 * the in-memory copy current. [add] matters for the wearer: its fan-out reaches the widgets before the owner engine
 * has written the reading to `owner.db`.
 */
object RecentHistory {
    const val KEEP_S = 8 * 3600L

    private var readings: List<Reading>? = null
    private var treatments: List<Treatment>? = null

    @Synchronized
    fun readings(c: Context): List<Reading> = readings ?: load(c).also { readings = it }

    @Synchronized
    fun treatments(c: Context): List<Treatment> = treatments ?: recent(loadTreatments(c)).also { treatments = it }

    /** Merge readings (any order), dedupe by timestamp (±30 s), trim. Returns true if anything changed. */
    @Synchronized
    fun add(c: Context, list: List<Reading>): Boolean {
        val old = readings(c)
        val cutoff = Proto.nowS() - KEEP_S
        val merged = (old + list).filter { it.t >= cutoff }.sortedBy { it.t }
            .fold(ArrayList<Reading>()) { acc, r ->
                if (acc.isNotEmpty() && r.t - acc.last().t < 30) acc[acc.size - 1] = r else acc += r
                acc
            }
        if (merged == old) return false
        readings = merged
        return true
    }

    /** Keep the live entries of the last [KEEP_S] of [list]. Returns true if anything changed. */
    @Synchronized
    fun setTreatments(c: Context, list: List<Treatment>): Boolean {
        val next = recent(list)
        if (next == treatments(c)) return false
        treatments = next
        return true
    }

    /** Forget the copy: the next read goes to the role's store again (follower start, stop following, role change). */
    @Synchronized
    fun reload() {
        readings = null
        treatments = null
    }

    private fun recent(list: List<Treatment>): List<Treatment> {
        val cutoff = Proto.nowS() - KEEP_S
        return list.filter { !it.del && it.t >= cutoff }.sortedBy { it.t }.map { it.copy(lv = 0) }
    }

    private fun load(c: Context): List<Reading> = runCatching {
        val from = Proto.nowS() - KEEP_S
        when (App.role(c)) {
            "owner" -> App.owner(c).history(from)
            "follower" -> App.follower(c).history(from).map { Reading(it.t, it.sgv, it.rate) }
            else -> emptyList()
        }
    }.getOrElse { Log.w("Sukrara.widgets", "history: $it"); emptyList() }

    private fun loadTreatments(c: Context): List<Treatment> = runCatching {
        when (App.role(c)) {
            "owner" -> { Treat.init(c); Treat.store.all() }
            "follower" -> App.follower(c).treatments()
            else -> emptyList()
        }
    }.getOrElse { Log.w("Sukrara.widgets", "treatments: $it"); emptyList() }
}
