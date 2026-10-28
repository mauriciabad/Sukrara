// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.treatments

import app.mauri.sukrara.core.model.Role
import app.mauri.sukrara.core.model.TreatmentKind
import app.mauri.sukrara.core.model.storage.TreatmentDelivery
import app.mauri.sukrara.core.model.storage.TreatmentRecord
import app.mauri.sukrara.core.testing.FakeClock
import app.mauri.sukrara.core.testing.InMemoryTreatmentsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

class TreatmentLogTest {
    private class FakeSync(
        writer: TreatmentWriter?,
    ) : TreatmentSync {
        override val writer = MutableStateFlow(writer)
        val published = mutableListOf<TreatmentRecord>()

        override suspend fun publish(record: TreatmentRecord) {
            published += record
        }
    }

    private val t0 = Instant.parse("2026-06-15T12:00:00Z")
    private val clock = FakeClock(t0)
    private val repo = InMemoryTreatmentsRepository()
    private var ids = 0

    private fun log(sync: TreatmentSync) = TreatmentLog(repo, sync, clock) { "id${++ids}" }

    private suspend fun visible() = repo.between(Instant.EPOCH, t0.plus(Duration.ofDays(1))).first()

    @Test fun addStoresEachEntryAndPublishesIt() =
        runTest {
            val sync = FakeSync(TreatmentWriter("wearer", throughOutbox = false))
            val earlier = t0.minusSeconds(600)
            val added = log(sync).add(listOf(NewEntry(TreatmentKind.RapidInsulin, 4.5), NewEntry(TreatmentKind.Carbs, 30.0)), earlier)
            assertEquals(listOf("id1", "id2"), added.map { it.id })
            assertEquals(listOf(earlier, earlier), visible().map { it.at })
            assertEquals(listOf(t0, t0), added.map { it.modifiedAt })
            assertEquals(2, sync.published.size)
            assertTrue(sync.published.all { it.creator == "wearer" && it.delivery == TreatmentDelivery.Local })
        }

    @Test fun nullTimeMeansNow() =
        runTest {
            val added = log(FakeSync(TreatmentWriter(null, false))).add(listOf(NewEntry(TreatmentKind.Note, 0.0, "walk")), null)
            assertEquals(t0, added.single().at)
        }

    @Test fun followerEntriesArePendingUntilTheWearerHasThem() =
        runTest {
            val sync = FakeSync(TreatmentWriter("ana", throughOutbox = true))
            log(sync).add(listOf(NewEntry(TreatmentKind.Carbs, 20.0)), null)
            assertEquals(TreatmentDelivery.Pending, sync.published.single().delivery)
            assertEquals("ana", sync.published.single().creator)
        }

    @Test(expected = TreatmentLog.NotAllowed::class)
    fun addWithoutWriterIsRefused() =
        runTest {
            log(FakeSync(null)).add(listOf(NewEntry(TreatmentKind.Carbs, 20.0)), null)
        }

    @Test fun deleteThenUndoBringsItBackAsANewerVersion() =
        runTest {
            val sync = FakeSync(TreatmentWriter("wearer", false))
            val log = log(sync)
            val added = log.add(listOf(NewEntry(TreatmentKind.Carbs, 30.0)), null).single()
            // same clock instant: each version must still be strictly newer
            val deleted = log.delete(added.id)
            assertEquals(added, deleted)
            assertTrue(visible().isEmpty())
            assertTrue(repo.get(added.id)!!.treatment.deleted)
            assertTrue(log.undo(checkNotNull(deleted)))
            assertEquals(listOf(30.0), visible().map { it.amount })
            val versions = sync.published.map { it.treatment.modifiedAt }
            assertEquals(versions.sorted().distinct(), versions)
        }

    @Test fun editThenUndoRestoresTheOldAmount() =
        runTest {
            val log = log(FakeSync(TreatmentWriter("wearer", false)))
            val added = log.add(listOf(NewEntry(TreatmentKind.RapidInsulin, 4.0)), null).single()
            clock.advance(Duration.ofMinutes(1))
            val previous = log.edit(added.copy(amount = 5.0))
            assertEquals(5.0, visible().single().amount, 0.0)
            log.undo(checkNotNull(previous))
            assertEquals(4.0, visible().single().amount, 0.0)
        }

    @Test fun aFollowerChangesOnlyItsOwnEntries() =
        runTest {
            val wearer = log(FakeSync(TreatmentWriter("wearer", false)))
            val own = wearer.add(listOf(NewEntry(TreatmentKind.Carbs, 10.0)), null).single()
            val follower = log(FakeSync(TreatmentWriter("ana", true)))
            assertNull(follower.delete(own.id))
            val mine = follower.add(listOf(NewEntry(TreatmentKind.Carbs, 15.0)), null).single()
            assertEquals(mine, follower.delete(mine.id))
            // the wearer may change everything
            assertEquals(own, wearer.delete(own.id))
        }

    @Test fun writerScope() {
        val local = TreatmentWriter(null, false)
        val outbox = TreatmentWriter("ana", true)
        assertTrue(WriterScope.canAdd(Role.Wearer, local))
        assertFalse(WriterScope.canAdd(Role.Wearer, outbox))
        assertTrue(WriterScope.canAdd(Role.Follower, outbox))
        assertFalse(WriterScope.canAdd(Role.Follower, local))
        assertFalse(WriterScope.canAdd(Role.Follower, null))
        assertFalse(WriterScope.canAdd(null, local))
    }
}
