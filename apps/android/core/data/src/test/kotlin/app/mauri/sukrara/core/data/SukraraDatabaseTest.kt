// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.core.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.mauri.sukrara.core.data.db.OutboxEntity
import app.mauri.sukrara.core.data.db.RejectEntity
import app.mauri.sukrara.core.data.db.RoomReadingsRepository
import app.mauri.sukrara.core.data.db.RoomTreatmentsRepository
import app.mauri.sukrara.core.data.db.SukraraDatabase
import app.mauri.sukrara.core.model.Reading
import app.mauri.sukrara.core.model.SourceId
import app.mauri.sukrara.core.model.Treatment
import app.mauri.sukrara.core.model.TreatmentKind
import app.mauri.sukrara.core.model.TreatmentOrigin
import app.mauri.sukrara.core.model.storage.TreatmentDelivery
import app.mauri.sukrara.core.model.storage.TreatmentRecord
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Duration
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
class SukraraDatabaseTest {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), SukraraDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val readings = RoomReadingsRepository(db.readings())
    private val treatments = RoomTreatmentsRepository(db.treatments())
    private val t0 = Instant.parse("2026-01-15T12:00:00Z")

    @After
    fun close() = db.close()

    private fun reading(
        minutes: Long,
        mgdl: Int,
        source: SourceId = SourceId.Libre3,
    ) = Reading(t0.plus(Duration.ofMinutes(minutes)), mgdl, 0.5, source)

    @Test
    fun `window returns readings from a time, oldest first`() = runTest {
        readings.upsert(listOf(reading(10, 120), reading(0, 100), reading(5, 110)))
        assertEquals(listOf(110, 120), readings.window(t0.plusSeconds(60)).first().map { it.mgdl })
        assertEquals(120, readings.latest().first()?.mgdl)
    }

    @Test
    fun `the same time and source is replaced, another source is kept`() = runTest {
        readings.upsert(listOf(reading(0, 100)))
        readings.upsert(listOf(reading(0, 101), reading(0, 99, SourceId.Relay)))
        assertEquals(setOf(101 to SourceId.Libre3, 99 to SourceId.Relay), readings.window(t0).first().map { it.mgdl to it.source }.toSet())
    }

    @Test
    fun `range is half-open and retention deletes older readings`() = runTest {
        readings.upsert((0L..4L).map { reading(it * 5, 100 + it.toInt()) })
        assertEquals(listOf(101, 102), readings.range(t0.plusSeconds(300), t0.plusSeconds(900)).map { it.mgdl })
        assertEquals(2, readings.deleteBefore(t0.plusSeconds(600)))
        assertEquals(102, readings.window(Instant.EPOCH).first().first().mgdl)
    }

    private fun record(
        modifiedMinutes: Long,
        amount: Double,
        deleted: Boolean = false,
    ) = TreatmentRecord(
        treatment = Treatment(
            id = "tr-1",
            kind = TreatmentKind.RapidInsulin,
            amount = amount,
            at = t0,
            modifiedAt = t0.plus(Duration.ofMinutes(modifiedMinutes)),
            deleted = deleted,
            origin = TreatmentOrigin.Phone,
        ),
        creator = "creator-key",
        delivery = TreatmentDelivery.Pending,
        penJson = "{\"pen\":\"x\"}",
    )

    @Test
    fun `treatments - last writer wins and round-trips every field`() = runTest {
        assertTrue(treatments.upsertIfNewer(record(1, 4.0)))
        assertFalse("older loses", treatments.upsertIfNewer(record(0, 9.0)))
        assertFalse("same version loses", treatments.upsertIfNewer(record(1, 9.0)))
        assertTrue(treatments.upsertIfNewer(record(2, 5.0)))
        assertEquals(record(2, 5.0), treatments.get("tr-1"))
    }

    @Test
    fun `deleted treatments are hidden but stay as tombstones for sync`() = runTest {
        treatments.upsertIfNewer(record(1, 4.0))
        treatments.upsertIfNewer(record(2, 4.0, deleted = true))
        assertTrue(treatments.between(t0.minusSeconds(60), t0.plusSeconds(60)).first().isEmpty())
        assertEquals(1, treatments.changedSince(t0).size)
        assertNull(treatments.get("missing"))
    }

    @Test
    fun `seen wraps dedupe, rejects and outbox`() = runTest {
        assertTrue(db.seenWraps().markSeen("w1", 1))
        assertFalse(db.seenWraps().markSeen("w1", 2))
        db.rejects().insert(RejectEntity(at = 5, why = "bad signature", author = null, via = "relay.example"))
        assertEquals("bad signature", db.rejects().recent(10).first().single().why)

        db.outbox().put(OutboxEntity("e1", version = 1, event = "{}", createdAt = 1, sentAt = null))
        db.outbox().put(OutboxEntity("e1", version = 2, event = "{\"v\":2}", createdAt = 1, sentAt = null))
        assertEquals(0, db.outbox().markSent("e1", version = 1, at = 3))
        assertEquals(1, db.outbox().markSent("e1", version = 2, at = 3))
        assertTrue(db.outbox().pending().first().isEmpty())
    }
}
