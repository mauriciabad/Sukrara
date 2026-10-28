// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.treatments

import app.mauri.sukrara.core.model.Clock
import app.mauri.sukrara.core.model.Role
import app.mauri.sukrara.core.model.Treatment
import app.mauri.sukrara.core.model.TreatmentOrigin
import app.mauri.sukrara.core.model.storage.TreatmentDelivery
import app.mauri.sukrara.core.model.storage.TreatmentRecord
import app.mauri.sukrara.core.model.storage.TreatmentsRepository
import java.time.Instant
import java.util.UUID

/**
 * The phone's writes to the treatment log: add, edit, delete and undo, each stored last-writer-wins in [repository]
 * and handed to [sync] so followers, the Mac and the wearer get the same version.
 *
 * Every write gets a `modifiedAt` strictly after the stored version's, so it always wins locally and on the other
 * devices; an undo is a new version too (a raw restore of the old one would lose against the deletion elsewhere).
 */
class TreatmentLog(
    private val repository: TreatmentsRepository,
    private val sync: TreatmentSync,
    private val clock: Clock,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    /** Thrown when this phone may not write (see [WriterScope]); the UI only offers writing when it may. */
    class NotAllowed : IllegalStateException("this phone can't add treatments")

    /** Stores [entries] at [at] (null = now) as new treatments; returns them. */
    suspend fun add(
        entries: List<NewEntry>,
        at: Instant?,
    ): List<Treatment> {
        val writer = sync.writer.value ?: throw NotAllowed()
        val now = clock.now()
        return entries.map { entry ->
            val treatment =
                Treatment(
                    id = newId(),
                    kind = entry.kind,
                    amount = entry.amount,
                    at = at ?: now,
                    note = entry.note,
                    modifiedAt = now,
                    origin = TreatmentOrigin.Phone,
                )
            write(TreatmentRecord(treatment, creator = writer.key, author = writer.key, delivery = writer.delivery))
            treatment
        }
    }

    /**
     * Replaces the stored treatment with [changed] (same id: amount, time or note edited); returns the version it
     * replaced, for undo, or null when it isn't stored or this phone may not change it.
     */
    suspend fun edit(changed: Treatment): Treatment? = rewrite(changed.id) { changed.copy(deleted = false) }

    /** Deletes [id] (kept as a tombstone for sync); returns the deleted version, for undo. */
    suspend fun delete(id: String): Treatment? = rewrite(id) { it.copy(deleted = true) }

    /** Whether this phone may edit or delete [id] (see [WriterScope.mayChange]); false when it isn't stored. */
    suspend fun mayChange(id: String): Boolean {
        val writer = sync.writer.value ?: return false
        val stored = repository.get(id) ?: return false
        return WriterScope.mayChange(writer, stored.creator)
    }

    /** Brings [previous] (what [edit] or [delete] returned) back as the newest version. */
    suspend fun undo(previous: Treatment): Boolean = rewrite(previous.id) { previous } != null

    private suspend fun rewrite(
        id: String,
        change: (Treatment) -> Treatment,
    ): Treatment? {
        val writer = sync.writer.value ?: return null
        val stored = repository.get(id) ?: return null
        if (!WriterScope.mayChange(writer, stored.creator)) return null
        val modifiedAt = maxOf(clock.now(), stored.treatment.modifiedAt.plusMillis(1))
        val next = change(stored.treatment).copy(id = id, modifiedAt = modifiedAt)
        write(stored.copy(treatment = next, author = writer.key, delivery = writer.delivery))
        return stored.treatment
    }

    private suspend fun write(record: TreatmentRecord) {
        if (repository.upsertIfNewer(record)) sync.publish(record)
    }

    private val TreatmentWriter.delivery get() = if (throughOutbox) TreatmentDelivery.Pending else TreatmentDelivery.Local
}

/**
 * Who may write what (followers the wearer allowed to add entries, contracts/protocol treatments): the wearer adds
 * and changes everything; a follower adds only through the outbox and changes only the entries it created.
 */
object WriterScope {
    /** Whether a phone with [role] and [writer] (null = not allowed) offers Add treatment. */
    fun canAdd(
        role: Role?,
        writer: TreatmentWriter?,
    ): Boolean =
        when (role) {
            Role.Wearer -> writer != null && !writer.throughOutbox
            Role.Follower -> writer != null && writer.throughOutbox
            null -> false
        }

    /** Whether [writer] may edit or delete an entry created by [creator] (null = the wearer's own, unshared). */
    fun mayChange(
        writer: TreatmentWriter,
        creator: String?,
    ): Boolean = !writer.throughOutbox || (writer.key != null && writer.key == creator)
}
