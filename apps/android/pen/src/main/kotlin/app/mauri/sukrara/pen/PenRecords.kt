// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.pen

import app.mauri.sukrara.core.model.Treatment
import app.mauri.sukrara.core.model.TreatmentKind
import app.mauri.sukrara.core.model.TreatmentOrigin
import app.mauri.sukrara.core.model.storage.TreatmentRecord
import app.mauri.sukrara.pen.engine.PdDose
import app.mauri.sukrara.pen.engine.PenEntry
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant

/**
 * The stored treatment ⇄ the engine's entry. The pen keys travel in [TreatmentRecord.penJson] as the treatments wire
 * keys (`pr`, `pen`, `pd`; contracts/treatments SPEC §1), which the sharing layer forwards as they are.
 */
object PenRecords {
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class Keys(
        val pr: Double? = null,
        val pen: String? = null,
        val pd: List<PdDose>? = null,
    )

    private val KINDS =
        mapOf(
            TreatmentKind.RapidInsulin to "rapid",
            TreatmentKind.LongInsulin to "long",
            TreatmentKind.Carbs to "carbs",
            TreatmentKind.Note to "note",
        )
    private val ORIGINS =
        mapOf(
            TreatmentOrigin.Phone to "phone",
            TreatmentOrigin.Mac to "mac",
            TreatmentOrigin.Follower to "follower",
        )

    fun kind(k: String): TreatmentKind = KINDS.entries.firstOrNull { it.value == k }?.key ?: TreatmentKind.RapidInsulin

    fun kindName(k: TreatmentKind): String = KINDS.getValue(k)

    /** A stored record as the engine sees it (tombstones included: a deleted pen entry keeps its doses consumed). */
    fun entry(r: TreatmentRecord): PenEntry {
        val t = r.treatment
        val keys = r.penJson?.let { runCatching { json.decodeFromString(Keys.serializer(), it) }.getOrNull() }
        return PenEntry(
            id = t.id,
            k = KINDS.getValue(t.kind),
            a = t.amount,
            t = t.at.epochSecond,
            n = t.note,
            u = t.modifiedAt.toEpochMilli(),
            del = t.deleted,
            o = ORIGINS.getValue(t.origin),
            pr = keys?.pr,
            pen = keys?.pen,
            pd = keys?.pd,
        )
    }

    /**
     * The record to store for an engine write: on top of the stored one ([stored], keeping its sharing fields) or new.
     * Hand-typed fields ([PenEntry.n]) come from the engine, which keeps them through a merge. `modifiedAt` is the
     * engine's `u` exactly: the engine tells its own writes from the user's edits by it.
     */
    fun record(
        w: PenEntry,
        stored: TreatmentRecord?,
    ): TreatmentRecord {
        val treatment =
            Treatment(
                id = w.id,
                kind = kind(w.k),
                amount = w.a,
                at = Instant.ofEpochSecond(w.t),
                note = w.n,
                modifiedAt = Instant.ofEpochMilli(w.u),
                deleted = w.del,
                origin = ORIGINS.entries.firstOrNull { it.value == w.o }?.key ?: TreatmentOrigin.Phone,
            )
        // an undo back to a hand-typed version (no `pen`) clears the keys
        val penJson = w.pen?.let { json.encodeToString(Keys.serializer(), Keys(pr = w.pr, pen = it, pd = w.pd)) }
        return (stored ?: TreatmentRecord(treatment)).copy(treatment = treatment, penJson = penJson)
    }
}
