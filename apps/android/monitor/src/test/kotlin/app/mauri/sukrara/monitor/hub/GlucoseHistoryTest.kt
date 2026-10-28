// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.monitor.hub

import app.mauri.sukrara.core.model.Reading
import app.mauri.sukrara.core.model.ReadingHistory
import app.mauri.sukrara.core.model.SourceId
import app.mauri.sukrara.core.testing.FakeClock
import app.mauri.sukrara.core.testing.InMemoryReadingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

class GlucoseHistoryTest {
    private val clock = FakeClock()

    private fun reading(t: Instant) = Reading(t, 120, null, SourceId.Libre3)

    /** A reading every 5 min for [days] days up to now. */
    private fun month(days: Long): List<Reading> {
        val now = clock.now()
        return (0 until days * 288).map { i -> reading(now - Duration.ofMinutes(5 * i)) }.reversed()
    }

    @Test fun joinsTheStoredDaysWithTheLiveWindowOnceEach() =
        runTest {
            val store = InMemoryReadingsRepository()
            val all = month(35)
            store.upsert(all)
            val live = all.filter { it.t >= clock.now() - GlucoseHubImpl.WINDOW }
            val history = GlucoseHistory(clock, store, MutableStateFlow(live))
            val days = history.days.first()
            // 31 days back, nothing older, nothing twice, oldest first
            assertTrue(days.first().t >= clock.now() - GlucoseHistory.DAYS)
            assertEquals(days.map { it.t }.distinct(), days.map { it.t })
            assertEquals(days.sortedBy { it.t }, days)
            assertEquals(all.count { it.t >= clock.now() - GlucoseHistory.DAYS }, days.size)
            assertEquals(live.last(), days.last())
        }

    @Test fun storedPartEndsInsideTheLiveWindow() =
        runTest {
            val store = InMemoryReadingsRepository()
            store.upsert(month(3))
            val stored = GlucoseHistory(clock, store, MutableStateFlow(emptyList())).stored.first()
            assertTrue(stored.last().t < clock.now() - GlucoseHistory.SPLIT)
            assertTrue(GlucoseHistory.SPLIT + GlucoseHistory.RELOAD < GlucoseHubImpl.WINDOW)
        }

    @Test fun joinKeepsOnlyNewerLiveReadings() {
        val now = clock.now()
        val stored = listOf(reading(now - Duration.ofHours(30)), reading(now - Duration.ofHours(23)))
        val live = listOf(reading(now - Duration.ofHours(24)), reading(now - Duration.ofHours(23)), reading(now))
        assertEquals(stored + reading(now), ReadingHistory.join(stored, live))
        assertEquals(live, ReadingHistory.join(emptyList(), live))
    }
}
