// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.pen

import app.mauri.sukrara.core.model.Treatment
import app.mauri.sukrara.core.model.TreatmentKind
import app.mauri.sukrara.core.model.TreatmentOrigin
import app.mauri.sukrara.core.model.storage.TreatmentRecord
import app.mauri.sukrara.core.testing.FakeClock
import app.mauri.sukrara.core.testing.InMemoryStateStore
import app.mauri.sukrara.pen.engine.Ledger
import app.mauri.sukrara.pen.engine.PenEngine
import app.mauri.sukrara.pen.engine.PenRules
import app.mauri.sukrara.pen.opennov.PenDose
import app.mauri.sukrara.pen.opennov.PenRead
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** The runtime around the engine: storage round trip, Ask, untested pens, the role gate, undo validity, stale answers. */
class PensTest {
    private val clock = FakeClock(Instant.parse("2026-10-20T13:30:00Z"))
    private val start = clock.instant.epochSecond

    /** The log as the repository behaves: last writer wins on `modifiedAt`, creator kept. */
    private class Log : PenLog {
        val records = linkedMapOf<String, TreatmentRecord>()
        var writer = true

        override suspend fun all() = records.values.toList()

        override fun canWrite() = writer

        override suspend fun store(record: TreatmentRecord) {
            val old = records[record.treatment.id]
            if (old != null && old.treatment.modifiedAt >= record.treatment.modifiedAt) return
            records[record.treatment.id] = record.copy(creator = old?.creator ?: "wearer", author = "wearer")
        }

        fun live() = records.values.filter { !it.treatment.deleted }
    }

    private val log = Log()
    private var reading = PenReading.On
    private var wearer = true

    private val pens =
        Pens(
            store = InMemoryStateStore(Ledger()),
            log = log,
            clock = clock,
            rules = { PenRules() },
            available = { wearer },
            reading = { reading },
            setReading = { reading = it },
        )

    /** A pen whose clock started 400 days ago; doses at [minutes] before now with [units]. */
    private fun read(vararg doses: Pair<Int, Double>): PenRead {
        val penNow = 400 * 86400L
        return PenRead(
            serial = "AB1234567",
            model = "NovoPen",
            scanTime = clock.instant.epochSecond,
            penRelNow = penNow + (clock.instant.epochSecond - start),
            doses =
                doses.map { (min, u) ->
                    val t = start - min * 60L
                    PenDose(relTime = penNow - (start - t), time = t, units = u, flags = 0x08000000)
                },
        )
    }

    @Test fun aRescanKeepsOneEntryAndAGrowingSessionUpdatesIt() =
        runTest {
            // the first scan logs nothing older than itself: read once, then inject
            pens.onRead(read(600 to 4.5))
            clock.instant = clock.instant.plusSeconds(600)
            val first = pens.onRead(read(600 to 4.5, -8 to 1.0, -9 to 8.5))!!
            assertEquals(1, first.report!!.created.size)
            val entry = log.live().single()
            assertEquals(8.5, entry.treatment.amount, 0.0)
            assertTrue(entry.penJson!!.contains("\"pr\":1.0"))
            // the same doses again: nothing changes (the engine's own `u` came back from storage unchanged)
            clock.instant = clock.instant.plusSeconds(60)
            val again = pens.onRead(read(600 to 4.5, -8 to 1.0, -9 to 8.5))!!
            assertTrue(again.report!!.created.isEmpty() && again.report!!.updated.isEmpty())
            // a top-up in the same session updates the same entry, never a second one
            clock.instant = clock.instant.plusSeconds(60)
            pens.onRead(read(600 to 4.5, -8 to 1.0, -9 to 8.5, -11 to 1.0))
            assertEquals(
                9.5,
                log
                    .live()
                    .single()
                    .treatment.amount,
                0.0,
            )
        }

    @Test fun askHoldsTheReadAndLogsNothingUntilTurnOn() =
        runTest {
            reading = PenReading.Ask
            pens.onRead(read(600 to 4.5))
            assertEquals(PenReadState.Stage.Ask, pens.read.value!!.stage)
            assertTrue(log.records.isEmpty())
            pens.turnOn()
            assertEquals(PenReading.On, reading)
            assertEquals(PenReadState.Stage.Done, pens.read.value!!.stage)
            assertTrue(
                pens.ledger.value.pens
                    .containsKey("AB1234567"),
            )
        }

    @Test fun declineTurnsReadingOffAndDropsTheRead() =
        runTest {
            reading = PenReading.Ask
            pens.onRead(read(600 to 4.5))
            pens.decline()
            assertEquals(PenReading.Off, reading)
            assertNull(pens.onRead(read(600 to 4.5)))
            assertTrue(
                pens.ledger.value.pens
                    .isEmpty(),
            )
        }

    @Test fun anUntestedPenLogsNothing() =
        runTest {
            // whole units only: not an Echo Plus
            val r = read(-5 to 6.0).copy(model = "NovoPen")
            assertEquals(PenReadState.Stage.Untested, pens.onRead(r)!!.stage)
            assertTrue(log.records.isEmpty())
            assertTrue(
                pens.ledger.value.pens
                    .isEmpty(),
            )
        }

    @Test fun aFollowerOrAPhoneThatCantWriteLogsNothing() =
        runTest {
            wearer = false
            assertNull(pens.onRead(read(600 to 4.5)))
            wearer = true
            log.writer = false
            assertNull(pens.onRead(read(600 to 4.5)))
            assertTrue(
                pens.ledger.value.pens
                    .isEmpty(),
            )
        }

    @Test fun undoWorksOnlyUntilTheNextChange() =
        runTest {
            pens.onRead(read(600 to 4.5))
            clock.instant = clock.instant.plusSeconds(600)
            val st = pens.onRead(read(600 to 4.5, -9 to 8.5))!!
            val undo = st.undo!!
            clock.instant = clock.instant.plusSeconds(60)
            pens.onRead(read(600 to 4.5, -9 to 8.5, -11 to 1.0))
            assertFalse(pens.undo(undo))
            assertEquals(
                9.5,
                log
                    .live()
                    .single()
                    .treatment.amount,
                0.0,
            )
            // the newest read's undo still works
            val latest = pens.read.value!!.undo
            assertTrue(pens.undo(latest!!))
            assertTrue(pens.read.value!!.undone)
        }

    @Test fun aHandTypedEntryIsReplacedNotDuplicated() =
        runTest {
            pens.onRead(read(600 to 4.5))
            val typed =
                Treatment(
                    "typed",
                    TreatmentKind.RapidInsulin,
                    8.0,
                    Instant.ofEpochSecond(start + 300),
                    "lunch",
                    clock.instant,
                    origin = TreatmentOrigin.Phone,
                )
            log.records["typed"] = TreatmentRecord(typed, creator = "wearer")
            clock.instant = clock.instant.plusSeconds(600)
            pens.onRead(read(600 to 4.5, -6 to 1.0, -7 to 8.5))
            val live = log.live().single()
            assertEquals("typed", live.treatment.id)
            assertEquals(8.5, live.treatment.amount, 0.0)
            assertEquals("lunch", live.treatment.note)
            assertEquals("wearer", live.creator)
        }

    @Test fun aChangedQuestionIsNotAnsweredFromAStaleAction() =
        runTest {
            pens.onRead(read(600 to 4.5))
            // a lone small dose 39 min ago (after the first scan): a question
            clock.instant = clock.instant.plusSeconds(600)
            clock.instant = clock.instant.plusSeconds(1800)
            pens.onRead(read(600 to 4.5, -1 to 1.5))
            val q =
                pens.ledger.value
                    .open()
                    .single { it.type == PenEngine.DOSES }
            assertFalse(pens.allPriming(q.id, keys = listOf("made-up")))
            assertTrue(
                pens.ledger.value
                    .open()
                    .any { it.id == q.id },
            )
            assertTrue(pens.acceptSuggestion(q.id, q.keys))
            assertTrue(
                pens.ledger.value
                    .open()
                    .none { it.id == q.id },
            )
        }

    @Test fun recordsRoundTripThePenKeys() {
        val w =
            app.mauri.sukrara.pen.engine.PenEntry(
                "id",
                "rapid",
                8.5,
                start,
                u = 5,
                pr = 1.0,
                pen = "AB1",
                pd =
                    listOf(
                        app.mauri.sukrara.pen.engine
                            .PdDose(start, 1.0, true),
                        app.mauri.sukrara.pen.engine
                            .PdDose(start + 60, 8.5),
                    ),
            )
        val back = PenRecords.entry(PenRecords.record(w, null))
        assertEquals(w, back)
        // an undo back to a hand-typed version clears the keys
        assertNull(PenRecords.record(w.copy(pen = null, pr = null, pd = null), PenRecords.record(w, null)).penJson)
    }
}
