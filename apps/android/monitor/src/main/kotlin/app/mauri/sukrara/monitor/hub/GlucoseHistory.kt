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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import java.time.Duration
import java.time.Instant

/**
 * The stored history the person can browse and the multi-day slots count: the last [DAYS] of readings, oldest first.
 *
 * The hub's live window stays small ([GlucoseHubImpl.WINDOW]); this adds the older part and keeps it: [stored] reads the
 * readings older than [SPLIT] from storage at most once per [RELOAD] of wall-clock time (one query of a few thousand
 * rows), [days] joins them with the hub's live [recent] readings, which every new reading updates. So a new reading never
 * re-reads the month.
 */
class GlucoseHistory(
    private val clock: Clock,
    private val readings: ReadingsRepository,
    private val recent: Flow<List<Reading>>,
) {
    /**
     * The stored readings of the last [DAYS] before the live window. Checked on every live update and every [RELOAD] of
     * uptime, re-read once [RELOAD] of wall-clock time has passed (a sleeping phone's uptime runs slower than the clock:
     * timing by `delay` alone would let the live window move past the stored part's end).
     */
    val stored: Flow<List<Reading>> =
        flow {
            var readAt: Instant? = null
            merge(recent.map { }, ticks()).collect {
                val now = clock.now()
                val last = readAt
                if (last == null || Duration.between(last, now) >= RELOAD || now.isBefore(last)) {
                    readAt = now
                    emit(read(now))
                }
            }
        }.distinctUntilChanged()

    /** The last [DAYS] of readings, oldest first; a new list for every new reading. */
    val days: Flow<List<Reading>> = combine(stored, recent.distinctUntilChanged(), ReadingHistory::join)

    private fun ticks(): Flow<Unit> =
        flow {
            while (true) {
                emit(Unit)
                delay(RELOAD.toMillis())
            }
        }

    /** A failed read draws the live window only (the slots then lack the older days) until the next reload. */
    private suspend fun read(now: Instant): List<Reading> =
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
         * Where the stored part ends: inside the hub's live window by more than [RELOAD], so the two overlap until the
         * next reload (no reading falls between them).
         */
        val SPLIT: Duration = GlucoseHubImpl.WINDOW - Duration.ofHours(2)
        val RELOAD: Duration = Duration.ofHours(1)
    }
}
