// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.tidepool

import app.mauri.sukrara.treatments.Treatment
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What still has to go to Tidepool, worked out from the phone's own stores every cycle (no separate outbox): the
 * readings the role keeps (31 days) and the treatment log are the queue, and [TidepoolQueue.State] remembers what
 * Tidepool already has. So an outage, a reconnect backfill or a sensor history packet that fills a gap is simply
 * "slots not sent yet" on the next cycle. The same design as Nightscout's (`upload/Uploads.kt`). Pure Kotlin (JVM tests).
 */
object TidepoolQueue {
    const val WINDOW_S = 31 * 86_400L   // what the wearer's store keeps (OwnerDb)
    const val BATCH = 500               // data per POST
    private const val MINUTE_MS = 60_000L
    const val MAX_BACKOFF_MS = 60 * MINUTE_MS
    private val DATUM_KINDS = setOf("carbs", "rapid", "long")

    @Serializable
    data class Sent(val u: Long, val kind: String)

    /**
     * What Tidepool has from this install. [from] is the start of the upload window (epoch s): set when the upload is
     * switched on to the oldest reading the phone keeps, so everything stored is backfilled once.
     */
    @Serializable
    data class State(
        val from: Long = 0,
        val slots: List<LongArray> = emptyList(),          // sent cbg slots as [first, last] ranges
        val treatments: Map<String, Sent> = emptyMap(),     // treatment id → the version Tidepool has
        val notes: Map<String, String> = emptyMap(),        // note treatment id → Tidepool message id
        val failures: Int = 0,
        val nextAttemptMs: Long = 0,
    ) {
        fun sentSlots(): java.util.TreeSet<Long> = app.mauri.sukrara.upload.SentRanges.expand(slots)

        override fun equals(other: Any?) = other is State && from == other.from && treatments == other.treatments &&
            notes == other.notes && failures == other.failures && nextAttemptMs == other.nextAttemptMs &&
            slots.size == other.slots.size && slots.indices.all { slots[it].contentEquals(other.slots[it]) }
        override fun hashCode() = from.hashCode() * 31 + treatments.hashCode()
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    fun encode(s: State): String = json.encodeToString(State.serializer(), s)
    fun decode(s: String?): State = s?.let { runCatching { json.decodeFromString(State.serializer(), it) }.getOrNull() } ?: State()

    /** Sorted slots → [first, last] ranges (a day of readings is one pair, not 288 numbers). */
    fun ranges(slots: Collection<Long>): List<LongArray> = app.mauri.sukrara.upload.SentRanges.ranges(slots)

    /** Who may upload: the wearer always; a follower only once the user picked "upload the person I follow". */
    fun allowed(role: String?, uploadFollowed: Boolean): Boolean = when (role) {
        "owner" -> true
        "follower" -> uploadFollowed
        else -> false
    }

    /** One cycle's work. */
    data class Plan(
        val cbg: List<TidepoolData.Glucose>,
        val upserts: List<Treatment>,         // food / insulin to (re)send
        val deletes: List<Treatment>,         // data to delete (deleted, or now a note)
        val noteCreates: List<Treatment>,
        val noteEdits: List<Treatment>,
        val noteDeletes: List<Treatment>,     // deleted notes, emptied notes, or notes that became food / insulin
        val settled: List<Treatment>,         // nothing to send (deleted before it was ever sent, no amount): just remember
    ) {
        val isEmpty get() = cbg.isEmpty() && upserts.isEmpty() && deletes.isEmpty() && noteCreates.isEmpty() &&
            noteEdits.isEmpty() && noteDeletes.isEmpty() && settled.isEmpty()
        val count get() = cbg.size + upserts.size + deletes.size + noteCreates.size + noteEdits.size + noteDeletes.size
    }

    fun plan(readings: List<TidepoolData.Glucose>, treatments: List<Treatment>, state: State, glucose: Boolean,
             withTreatments: Boolean, nowS: Long): Plan {
        val from = maxOf(state.from, nowS - WINDOW_S)
        val sent = state.sentSlots()
        val cbg = if (!glucose) emptyList() else
            TidepoolData.perSlot(readings.filter { it.t >= from && it.t <= nowS + 60 }).filterKeys { it !in sent }.values.toList()

        val upserts = ArrayList<Treatment>(); val deletes = ArrayList<Treatment>()
        val noteCreates = ArrayList<Treatment>(); val noteEdits = ArrayList<Treatment>(); val noteDeletes = ArrayList<Treatment>()
        val settled = ArrayList<Treatment>()
        if (withTreatments) for (tr in treatments) {
            val had = state.treatments[tr.id]
            if (had != null && had.u == tr.u) continue
            if (tr.heldUntil(nowS) != null) continue    // a pen session that may still grow (Treatment.heldUntil)
            if (had == null && (tr.del || tr.t < from)) { if (tr.del) settled += tr; continue }   // never sent: nothing to undo
            val wasDatum = had != null && had.kind in DATUM_KINDS
            val wasNote = tr.id in state.notes
            val isNote = !TidepoolData.isDatum(tr)
            val wantDatum = !tr.del && !isNote && tr.a > 0 && tr.a <= 1000
            val wantNote = !tr.del && isNote && tr.n.isNotBlank()
            when {
                wantDatum -> upserts += tr
                wasDatum -> deletes += tr
            }
            when {
                wantNote && wasNote -> noteEdits += tr
                wantNote -> noteCreates += tr
                wasNote -> noteDeletes += tr
            }
            if (!wantDatum && !wantNote && !wasDatum && !wasNote) settled += tr
        }
        return Plan(cbg.sortedBy { it.t }, upserts, deletes, noteCreates, noteEdits, noteDeletes, settled)
    }

    /** After a successful cycle: remember what Tidepool has now, drop slots that left the window, reset the backoff. */
    fun applied(state: State, plan: Plan, createdNotes: Map<String, String>, nowS: Long): State {
        val keep = TidepoolData.slot(nowS - WINDOW_S - 86_400)
        val slots = state.sentSlots().apply { addAll(plan.cbg.map { TidepoolData.slot(it.t) }) }.tailSet(keep)
        val tr = HashMap(state.treatments)
        val notes = HashMap(state.notes)
        // later lines win: an entry that went from a note to carbs is both a note delete and an upsert
        for (t in plan.settled) tr[t.id] = Sent(t.u, "none")
        for (t in plan.deletes + plan.noteDeletes) tr[t.id] = Sent(t.u, "none")
        for (t in plan.noteCreates + plan.noteEdits) tr[t.id] = Sent(t.u, "note")
        for (t in plan.upserts) tr[t.id] = Sent(t.u, t.k)
        for (t in plan.noteDeletes) notes.remove(t.id)
        notes.putAll(createdNotes)
        return state.copy(slots = ranges(slots), treatments = tr, notes = notes, failures = 0, nextAttemptMs = 0)
    }

    /** Exponential backoff after a failed cycle: 1, 2, 4 … minutes, at most an hour. */
    fun backoffMs(failures: Int): Long = app.mauri.sukrara.upload.UploadBackoff.ms(failures, MINUTE_MS, MAX_BACKOFF_MS)

    fun failed(state: State, nowMs: Long): State {
        val f = state.failures + 1
        return state.copy(failures = f, nextAttemptMs = nowMs + backoffMs(f))
    }
}
