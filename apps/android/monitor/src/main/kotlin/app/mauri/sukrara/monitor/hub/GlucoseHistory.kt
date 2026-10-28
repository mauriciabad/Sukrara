// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.monitor.hub

import app.mauri.sukrara.core.model.Clock
import app.mauri.sukrara.core.model.Reading
import app.mauri.sukrara.core.model.ReadingHistory
import app.mauri.sukrara.core.model.storage.ReadingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import java.time.Duration

/**
 * The stored history the person can browse and the multi-day slots count: the last [DAYS] of readings, oldest first.
 *
 * The hub's live window stays small ([GlucoseHubImpl.WINDOW]); this adds the older part and keeps it: [stored] reads the
 * readings older than [SPLIT] from storage once per [RELOAD] (one query of a few thousand rows), [days] joins them with
 * the hub's live [recent] readings, which every new reading updates. So a new reading never re-reads the month.
 */
class GlucoseHistory(
    private val clock: Clock,
    private val readings: ReadingsRepository,
    private val recent: Flow<List<Reading>>,
) {
    /** The stored readings of the last [DAYS] before the live window, re-read every [RELOAD] (and once at the start). */
    val stored: Flow<List<Reading>> =
        flow {
            while (true) {
                val now = clock.now()
                emit(read(now))
                delay(RELOAD.toMillis())
            }
        }

    /** The last [DAYS] of readings, oldest first; a new list for every new reading. */
    val days: Flow<List<Reading>> = combine(stored, recent.distinctUntilChanged(), ReadingHistory::join)

    /** A failed read draws the live window only (the slots then lack the older days) until the next reload. */
    private suspend fun read(now: java.time.Instant): List<Reading> =
        try {
            readings.range(now - DAYS, now - SPLIT)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }

    companion object {
        /** How far back home scrolls (31 d: the longest zoom and the 30-day slot). */
        val DAYS: Duration = Duration.ofDays(31)

        /**
         * Where the stored part ends: inside the hub's live window by more than [RELOAD], so the two always overlap until
         * the next reload (no reading falls between them).
         */
        val SPLIT: Duration = GlucoseHubImpl.WINDOW - Duration.ofHours(2)
        val RELOAD: Duration = Duration.ofHours(1)
    }
}
