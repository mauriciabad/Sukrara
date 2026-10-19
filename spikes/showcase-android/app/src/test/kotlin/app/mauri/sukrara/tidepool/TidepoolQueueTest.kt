// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.tidepool

import app.mauri.sukrara.tidepool.TidepoolData.Glucose
import app.mauri.sukrara.treatments.Treatment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The upload queue (docs/27): what is still to send, backfill after an outage, backoff, role gating. */
class TidepoolQueueTest {
    private val now = 1_800_000_000L
    private val start = TidepoolQueue.State(from = now - TidepoolQueue.WINDOW_S)
    private fun minutes(from: Long, n: Int) = (0 until n).map { Glucose(from + it * 60L, 100 + it % 50) }
    private fun tr(id: String, k: String, a: Double, u: Long, del: Boolean = false, n: String = "", t: Long = now - 600) =
        Treatment(id = id, k = k, a = a, t = t, n = n, u = u, del = del, o = "phone")
    private fun plan(r: List<Glucose>, t: List<Treatment>, s: TidepoolQueue.State, at: Long = now, glucose: Boolean = true, treatments: Boolean = true) =
        TidepoolQueue.plan(r, t, s, glucose, treatments, at)

    @Test fun secondCycleSendsNothingAgain() {
        val r = minutes(now - 3600, 60)
        val p1 = plan(r, emptyList(), start)
        assertEquals(12, p1.cbg.size)
        val s1 = TidepoolQueue.applied(start, p1, emptyMap(), now)
        assertTrue(plan(r, emptyList(), s1).isEmpty)
        // the state survives being stored
        assertEquals(s1, TidepoolQueue.decode(TidepoolQueue.encode(s1)))
        assertEquals(1, s1.slots.size)   // one range, not 12 numbers
    }

    @Test fun gapFilledLaterIsBackfilled() {
        val before = minutes(now - 7200, 30)          // first half hour
        val after = minutes(now - 1800, 30)           // last half hour; the middle hour is missing (phone out of range)
        val s1 = TidepoolQueue.applied(start, plan(before + after, emptyList(), start), emptyMap(), now)
        val gap = minutes(now - 5400, 60)             // the sensor's history arrives after reconnecting
        val p2 = plan(before + gap + after, emptyList(), s1, now + 60)
        assertEquals(12, p2.cbg.size)
        assertTrue(p2.cbg.all { it.t >= now - 5400 && it.t < now - 1800 })
    }

    @Test fun backfillCoversTheStoreButNotOlder() {
        val old = Glucose(now - TidepoolQueue.WINDOW_S - 600, 100)
        val p = plan(listOf(old) + minutes(now - 600, 10), emptyList(), start)
        assertFalse(p.cbg.any { it.t == old.t })
        assertEquals(2, p.cbg.size)
    }

    @Test fun glucoseSwitchOff() {
        assertTrue(plan(minutes(now - 600, 10), emptyList(), start, glucose = false).cbg.isEmpty())
    }

    @Test fun treatmentEditsAndDeletes() {
        val carbs = tr("a", "carbs", 30.0, 1)
        val p1 = plan(emptyList(), listOf(carbs), start)
        assertEquals(listOf("a"), p1.upserts.map { it.id })
        val s1 = TidepoolQueue.applied(start, p1, emptyMap(), now)
        assertTrue(plan(emptyList(), listOf(carbs), s1).isEmpty)
        // edited: sent again (same origin id → Tidepool replaces it)
        val edited = carbs.copy(a = 35.0, u = 2)
        val p2 = plan(emptyList(), listOf(edited), s1)
        assertEquals(listOf(35.0), p2.upserts.map { it.a })
        val s2 = TidepoolQueue.applied(s1, p2, emptyMap(), now)
        // deleted: removed from Tidepool
        val p3 = plan(emptyList(), listOf(edited.copy(del = true, u = 3)), s2)
        assertEquals(listOf("a"), p3.deletes.map { it.id }); assertTrue(p3.upserts.isEmpty())
        val s3 = TidepoolQueue.applied(s2, p3, emptyMap(), now)
        assertTrue(plan(emptyList(), listOf(edited.copy(del = true, u = 3)), s3).isEmpty)
    }

    @Test fun deletedBeforeEverSentIsNeverSent() {
        val p = plan(emptyList(), listOf(tr("b", "rapid", 2.0, 5, del = true)), start)
        assertEquals(0, p.count); assertEquals(listOf("b"), p.settled.map { it.id })
        assertTrue(plan(emptyList(), listOf(tr("b", "rapid", 2.0, 5, del = true)), TidepoolQueue.applied(start, p, emptyMap(), now)).isEmpty)
    }

    @Test fun notesAreTidepoolNotes() {
        val note = tr("n", "note", 0.0, 1, n = "walk")
        val p1 = plan(emptyList(), listOf(note), start)
        assertEquals(listOf("n"), p1.noteCreates.map { it.id }); assertTrue(p1.upserts.isEmpty())
        val s1 = TidepoolQueue.applied(start, p1, mapOf("n" to "msg1"), now)
        assertEquals("msg1", s1.notes["n"])
        val p2 = plan(emptyList(), listOf(note.copy(n = "long walk", u = 2)), s1)
        assertEquals(listOf("n"), p2.noteEdits.map { it.id })
        val p3 = plan(emptyList(), listOf(note.copy(del = true, u = 3)), s1)
        assertEquals(listOf("n"), p3.noteDeletes.map { it.id })
        assertFalse("n" in TidepoolQueue.applied(s1, p3, emptyMap(), now).notes)
    }

    @Test fun kindChangeMovesBetweenDataAndNotes() {
        val note = tr("x", "note", 0.0, 1, n = "snack")
        val s1 = TidepoolQueue.applied(start, plan(emptyList(), listOf(note), start), mapOf("x" to "m"), now)
        val p = plan(emptyList(), listOf(tr("x", "carbs", 20.0, 2, n = "snack")), s1)
        assertEquals(listOf("x"), p.upserts.map { it.id }); assertEquals(listOf("x"), p.noteDeletes.map { it.id })
        val s2 = TidepoolQueue.applied(s1, p, emptyMap(), now)
        assertEquals("carbs", s2.treatments["x"]!!.kind)
        val back = plan(emptyList(), listOf(tr("x", "note", 0.0, 3, n = "snack")), s2)
        assertEquals(listOf("x"), back.deletes.map { it.id }); assertEquals(listOf("x"), back.noteCreates.map { it.id })
    }

    @Test fun treatmentsSwitchOff() {
        assertTrue(plan(emptyList(), listOf(tr("a", "carbs", 30.0, 1)), start, treatments = false).isEmpty)
    }

    @Test fun backoffDoublesUpToAnHour() {
        assertEquals(0, TidepoolQueue.backoffMs(0))
        assertEquals(listOf(1L, 2, 4, 8, 16, 32, 60, 60), (1..8).map { TidepoolQueue.backoffMs(it) / 60_000 })
        var s = start
        repeat(3) { s = TidepoolQueue.failed(s, 1000) }
        assertEquals(3, s.failures); assertEquals(1000 + 4 * 60_000L, s.nextAttemptMs)
        val ok = TidepoolQueue.applied(s, plan(emptyList(), emptyList(), s), emptyMap(), now)
        assertEquals(0, ok.failures); assertEquals(0, ok.nextAttemptMs)
    }

    @Test fun roleGating() {
        assertTrue(TidepoolQueue.allowed("owner", uploadFollowed = false))
        assertFalse(TidepoolQueue.allowed("follower", uploadFollowed = false))
        assertTrue(TidepoolQueue.allowed("follower", uploadFollowed = true))
        assertFalse(TidepoolQueue.allowed(null, uploadFollowed = true))
    }

    @Test fun rangesMerge() {
        assertEquals(listOf(listOf(1L, 3L), listOf(5L, 5L)), TidepoolQueue.ranges(listOf(3, 1, 2, 5, 2)).map { it.toList() })
    }

    @Test fun oldSlotsArePruned() {
        val s = TidepoolQueue.applied(start, plan(minutes(now - 600, 10), emptyList(), start), emptyMap(), now)
        val later = now + 40 * 86_400L
        val s2 = TidepoolQueue.applied(s, plan(minutes(later - 300, 5), emptyList(), s, later), emptyMap(), later)
        assertEquals(1, s2.sentSlots().size)
    }
}
