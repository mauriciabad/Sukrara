// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.integrations.tidepool

import app.mauri.sukrara.core.model.Reading
import app.mauri.sukrara.core.model.Role
import app.mauri.sukrara.core.model.Treatment
import app.mauri.sukrara.core.model.TreatmentOrigin
import app.mauri.sukrara.core.model.storage.TreatmentRecord
import app.mauri.sukrara.integrations.upload.SentRanges
import app.mauri.sukrara.integrations.upload.Span
import app.mauri.sukrara.integrations.upload.UploadBackoff
import kotlinx.serialization.Serializable

/** The version of an entry Tidepool has ([u] = its `modifiedAt` ms) and as what ([kind]: data, note or none). */
@Serializable
data class TpSent(
    val u: Long,
    val kind: TpKind,
)

enum class TpKind { Datum, Note, None }

/**
 * What Tidepool has from one role of this install (a wearer's and a follower's uploads are apart on the account).
 * [from] = the start of the upload window (epoch s), set on the first cycle to the oldest day the phone keeps, so what
 * is stored goes up once.
 */
@Serializable
data class TpRoleState(
    /** This install's device id for the role (made once). */
    val deviceId: String = "",
    /** The upload record on the account; empty = find it again by [deviceId] or create it. */
    val dataSet: String = "",
    val from: Long = 0,
    /** Sent cbg slots. */
    val slots: List<Span> = emptyList(),
    /** Entry id → the version Tidepool has. */
    val treatments: Map<String, TpSent> = emptyMap(),
    /** Note entry id → Tidepool message id. */
    val notes: Map<String, String> = emptyMap(),
)

/**
 * The Tidepool upload's own state (typed store, no secret: the session is in the SecureStore). [account] = a hash of
 * the user id it belongs to: signing in to another account starts from nothing.
 */
@Serializable
data class TpState(
    val email: String = "",
    val account: String = "",
    val roles: Map<String, TpRoleState> = emptyMap(),
    /** Tidepool ended the session (the token was wiped): the page asks to sign in again. */
    val signedOutByTidepool: Boolean = false,
    val failures: Int = 0,
    val nextAttemptMs: Long = 0,
    val lastOkMs: Long = 0,
    val lastCount: Int = 0,
    val lastError: String? = null,
) {
    fun role(r: Role): TpRoleState = roles[r.name] ?: TpRoleState()

    fun withRole(
        r: Role,
        s: TpRoleState,
    ): TpState = copy(roles = roles + (r.name to s))
}

/**
 * Pure planning of one Tidepool cycle: `stores − state`. The readings the phone keeps and the entries are the queue;
 * [TpRoleState] remembers what Tidepool has, so an outage or a backfill is simply "not sent yet".
 */
object TidepoolQueue {
    /** How far back the first cycle goes (what the phone keeps). */
    const val WINDOW_S = 30 * 86_400L

    /** Data per POST. */
    const val BATCH = 500

    /** An entry another device made waits this long after the phone got it (that device may still change it). */
    const val GRACE_S = 120L

    private const val MINUTE_MS = 60_000L
    const val MAX_BACKOFF_MS = 60 * MINUTE_MS

    /** Who may upload: the wearer always; a follower only once the person chose to upload the one they follow. */
    fun allowed(
        role: Role?,
        uploadFollowed: Boolean,
    ): Boolean =
        when (role) {
            Role.Wearer -> true
            Role.Follower -> uploadFollowed
            null -> false
        }

    /** One cycle's work. */
    data class Plan(
        val cbg: List<Reading> = emptyList(),
        /** food / insulin to (re)send. */
        val upserts: List<Treatment> = emptyList(),
        /** data to delete (deleted, or now a note / no amount). */
        val deletes: List<Treatment> = emptyList(),
        val noteCreates: List<Treatment> = emptyList(),
        val noteEdits: List<Treatment> = emptyList(),
        /** deleted notes, emptied notes, or notes that became food / insulin. */
        val noteDeletes: List<Treatment> = emptyList(),
        /** nothing to send (deleted before it ever went up, no amount): just remembered. */
        val settled: List<Treatment> = emptyList(),
    ) {
        val isEmpty
            get() =
                cbg.isEmpty() && upserts.isEmpty() && deletes.isEmpty() && noteCreates.isEmpty() && noteEdits.isEmpty() &&
                    noteDeletes.isEmpty() && settled.isEmpty()

        val count get() = cbg.size + upserts.size + deletes.size + noteCreates.size + noteEdits.size + noteDeletes.size
    }

    fun plan(
        readings: List<Reading>,
        records: List<TreatmentRecord>,
        state: TpRoleState,
        glucose: Boolean,
        withTreatments: Boolean,
        notes: Boolean,
        nowS: Long,
    ): Plan {
        val from = maxOf(state.from, nowS - WINDOW_S)
        val sent = SentRanges.expand(state.slots)
        val cbg =
            if (!glucose) {
                emptyList()
            } else {
                TidepoolData
                    .perSlot(readings.filter { it.t.epochSecond in from..(nowS + 60) })
                    .filterKeys { it !in sent }
                    .values
                    .sortedBy { it.t }
            }
        val upserts = ArrayList<Treatment>()
        val deletes = ArrayList<Treatment>()
        val noteCreates = ArrayList<Treatment>()
        val noteEdits = ArrayList<Treatment>()
        val noteDeletes = ArrayList<Treatment>()
        val settled = ArrayList<Treatment>()
        if (withTreatments) {
            for (rec in records) {
                val t = rec.treatment
                val u = t.modifiedAt.toEpochMilli()
                val had = state.treatments[t.id]
                if (had != null && had.u == u) continue
                // another device's entry: give it a moment to settle
                if (t.origin != TreatmentOrigin.Phone && nowS - (rec.receivedAt ?: t.modifiedAt).epochSecond < GRACE_S) continue
                // never sent: nothing to undo
                if (had == null && (t.deleted || t.at.epochSecond < from)) {
                    if (t.deleted) settled += t
                    continue
                }
                val wasDatum = had?.kind == TpKind.Datum
                val wasNote = t.id in state.notes
                val wantDatum = !t.deleted && TidepoolData.validDatum(t)
                val wantNote = notes && !t.deleted && !TidepoolData.isDatum(t) && t.note.isNotBlank()
                when {
                    wantDatum -> upserts += t
                    wasDatum -> deletes += t
                }
                when {
                    wantNote && wasNote -> noteEdits += t
                    wantNote -> noteCreates += t
                    wasNote -> noteDeletes += t
                }
                if (!wantDatum && !wantNote && !wasDatum && !wasNote) settled += t
            }
        }
        return Plan(cbg, upserts, deletes, noteCreates, noteEdits, noteDeletes, settled)
    }

    /** After a part of a plan went up: remember what Tidepool has now, drop slots that left the window. */
    fun applied(
        state: TpRoleState,
        plan: Plan,
        createdNotes: Map<String, String>,
        nowS: Long,
    ): TpRoleState {
        val keep = Math.floorDiv(nowS - WINDOW_S - 86_400, TidepoolData.SLOT_S)
        val slots = SentRanges.expand(state.slots).apply { addAll(plan.cbg.map { TidepoolData.slot(it.t) }) }.tailSet(keep)
        val tr = HashMap(state.treatments)
        val notes = HashMap(state.notes)

        fun u(t: Treatment) = t.modifiedAt.toEpochMilli()
        // later lines win: an entry that went from a note to carbs is both a note delete and an upsert
        for (t in plan.settled + plan.deletes + plan.noteDeletes) tr[t.id] = TpSent(u(t), TpKind.None)
        for (t in plan.noteCreates + plan.noteEdits) tr[t.id] = TpSent(u(t), TpKind.Note)
        for (t in plan.upserts) tr[t.id] = TpSent(u(t), TpKind.Datum)
        for (t in plan.noteDeletes) notes.remove(t.id)
        notes.putAll(createdNotes)
        return state.copy(slots = SentRanges.ranges(slots), treatments = tr, notes = notes)
    }

    /** Exponential backoff after a failed cycle: 1, 2, 4 … minutes, at most an hour. */
    fun backoffMs(failures: Int): Long = UploadBackoff.ms(failures, MINUTE_MS, MAX_BACKOFF_MS)

    fun failed(
        state: TpState,
        nowMs: Long,
        why: String,
    ): TpState {
        val f = state.failures + 1
        return state.copy(failures = f, nextAttemptMs = nowMs + backoffMs(f), lastError = why)
    }
}
