// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.integrations.healthconnect

import app.mauri.sukrara.core.model.Reading
import app.mauri.sukrara.core.model.Role
import app.mauri.sukrara.core.model.SourceId
import app.mauri.sukrara.core.testing.FakeClock
import app.mauri.sukrara.core.testing.InMemoryReadingsRepository
import app.mauri.sukrara.core.testing.InMemorySettingsRepository
import app.mauri.sukrara.core.testing.InMemoryStateStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

/** What goes into Health Connect, that nothing goes twice, and that every failure becomes a state. */
class HealthConnectSyncTest {
    private val now = Instant.parse("2026-10-28T12:00:30Z")
    private val clock = FakeClock(now)
    private val readings = InMemoryReadingsRepository()
    private val store = InMemoryStateStore(HcState())
    private val role = MutableStateFlow<Role?>(Role.Wearer)
    private val health = FakeHealth()
    private val sync = HealthConnectSync(InMemorySettingsRepository(), store, readings, role, health, clock, Dispatchers.Unconfined) {}

    private fun r(
        minAgo: Long,
        mgdl: Int = 100,
        source: SourceId = SourceId.Libre3,
    ) = Reading(now - Duration.ofMinutes(minAgo), mgdl, null, source)

    @Test fun `only this phone's own sensor, never LO HI or the future, one per minute`() {
        val list =
            listOf(
                r(5),
                r(4, source = SourceId.Relay),
                r(3, source = SourceId.Nearby),
                r(2, mgdl = 39),
                r(1, mgdl = 401),
                r(6, source = SourceId.Juggluco),
                Reading(now - Duration.ofSeconds(10), 130, null, SourceId.Libre3),
                r(-5),
            )
        val q = HealthConnectSync.qualifying(list, now)
        assertEquals(listOf(now - Duration.ofMinutes(6), now - Duration.ofMinutes(5), now - Duration.ofSeconds(10)), q.map { it.t })
        // 12:00:30 and 12:00:20 share a minute: the newest one stands for it
        assertEquals(130, q.last().mgdl)
        assertEquals("sukrara-glucose-${(now - Duration.ofMinutes(5)).epochSecond / 60}", q[1].clientId)
        assertEquals("Abbott" to "FreeStyle Libre 3", q[1].manufacturer to q[1].model)
        assertEquals(null to "Juggluco", HealthConnectSync.device(SourceId.Juggluco))
        assertTrue(HealthConnectSync.device(SourceId.DexcomG7).second!!.contains("untested"))
    }

    @Test fun `the history goes in once, then only new minutes and backfilled gaps`() =
        runBlocking {
            readings.upsert((10L downTo 1L).filter { it != 5L }.map { r(it) })
            sync.setOn(true)
            sync.pass()
            assertEquals(9, health.written.size)
            sync.pass()
            assertEquals(9, health.written.size)
            // a reconnect fills minute 5 and a new reading arrives
            readings.upsert(listOf(r(5), r(0)))
            sync.pass()
            assertEquals(11, health.written.size)
            assertEquals(
                setOf(r(5).t, r(0).t),
                health.written
                    .takeLast(2)
                    .map { it.t }
                    .toSet(),
            )
            val st = store.data.first()
            assertEquals(1, st.written.size)
            assertTrue(sync.status.first() is HcStatus.Written)
        }

    @Test fun `a rate limit backs off and shows, then clears`() =
        runBlocking {
            readings.upsert(listOf(r(1)))
            sync.setOn(true)
            health.fail = HealthWriteException(HealthConnectSync.ERROR_RATE_LIMIT)
            sync.pass()
            val failed = sync.status.first()
            assertTrue(failed is HcStatus.Failed && failed.code == HealthConnectSync.ERROR_RATE_LIMIT)
            health.fail = null
            clock.advance(Duration.ofMinutes(10))
            sync.pass()
            assertEquals(0, health.written.size)
            clock.advance(Duration.ofMinutes(6))
            sync.pass()
            assertEquals(1, health.written.size)
            assertTrue(sync.status.first() is HcStatus.Written)
        }

    @Test fun `revoked permission or a crash in Health Connect never escapes`() =
        runBlocking {
            readings.upsert(listOf(r(1)))
            sync.setOn(true)
            health.fail = SecurityException("revoked")
            sync.pass()
            assertEquals(HcStatus.NeedsPermission, sync.status.first())
            health.fail = IllegalStateException("boom")
            sync.recheck()
            sync.pass()
            assertTrue(sync.status.first() is HcStatus.Failed)
            health.granted = false
            sync.recheck()
            assertEquals(HcStatus.NeedsPermission, sync.status.first())
        }

    @Test fun `a follower's phone writes nothing and says so`() =
        runBlocking {
            role.value = Role.Follower
            assertEquals(HcStatus.Follower, sync.status.first())
            health.available = false
            role.value = Role.Wearer
            assertEquals(HcStatus.Unavailable, sync.status.first())
        }

    @Test fun `minute runs merge and forget what is older than the backfill`() {
        val runs = MinuteRuns.add(emptyList(), listOf(1, 2, 3, 7, 8), keepFrom = 0)
        assertEquals(listOf(listOf(1L, 3L), listOf(7L, 8L)), runs)
        assertEquals(listOf(listOf(1L, 8L)), MinuteRuns.add(runs, listOf(4, 5, 6), keepFrom = 0))
        assertEquals(listOf(listOf(2L, 3L), listOf(7L, 8L)), MinuteRuns.add(runs, emptyList(), keepFrom = 2))
        assertTrue(MinuteRuns.contains(runs, 2))
        assertTrue(!MinuteRuns.contains(runs, 5))
    }

    private class FakeHealth : HealthStore {
        var available = true
        var granted = true
        var fail: Exception? = null
        val written = ArrayList<HcGlucose>()

        override fun available() = available

        override fun granted() = granted

        override suspend fun insert(records: List<HcGlucose>) {
            fail?.let { throw it }
            written += records
        }
    }
}
