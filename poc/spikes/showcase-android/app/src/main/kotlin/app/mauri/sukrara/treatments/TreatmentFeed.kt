// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.treatments

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The treatments every chart draws (app home chart, widgets, Live Update graph), for whichever role is active:
 * the wearer feeds its [Treat.store], a follower feeds the treatments it received from the wearer. Includes
 * tombstones (`del`); readers skip them. Newest first.
 */
object TreatmentFeed {
    private val _items = MutableStateFlow<List<Treatment>>(emptyList())
    val items: StateFlow<List<Treatment>> = _items

    fun update(list: List<Treatment>) {
        _items.value = list.sortedByDescending { it.t }
    }

    /** Live (non-deleted) entries with `t` (epoch s) in [fromS, toS]. */
    fun between(fromS: Long, toS: Long): List<Treatment> = _items.value.filter { !it.del && it.t in fromS..toS }
}
