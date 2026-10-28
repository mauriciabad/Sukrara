// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.share.owner.usecase

import app.mauri.sukrara.core.model.Treatment
import app.mauri.sukrara.core.model.TreatmentKind
import app.mauri.sukrara.core.model.TreatmentOrigin
import app.mauri.sukrara.core.model.storage.TreatmentDelivery
import app.mauri.sukrara.core.model.storage.TreatmentRecord
import app.mauri.sukrara.core.model.storage.TreatmentsRepository
import app.mauri.sukrara.protocol.PublicKeyHex
import app.mauri.sukrara.protocol.SignedEvent
import app.mauri.sukrara.protocol.Wire
import app.mauri.sukrara.protocol.event.TreatmentCodec
import app.mauri.sukrara.protocol.event.TreatmentPayload
import app.mauri.sukrara.protocol.event.WriterScope
import app.mauri.sukrara.share.owner.OwnerState
import app.mauri.sukrara.share.transport.RelayFilter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant

/**
 * The wearer's treatment log to followers (PROTOCOL-v2 §Treatments): every version of the merged log goes out signed by
 * the device key D under the current epoch — a change at once, the last 30 days again on start, a new epoch, a new
 * follower and weekly — and writer entries (followers allowed to add entries) are accepted under the writer scope,
 * merged into [repository] and echoed. Not thread-safe: the engine calls it on its serial dispatcher.
 */
class SyncTreatments(private val ctx: OwnerContext, private val repository: TreatmentsRepository) {
    /** Why a writer event wasn't merged (tests and diagnostics; never logged with its content). */
    enum class Verdict { Accepted, Older, NotWriter, UnknownEpoch, Undecryptable, Invalid, NotCreator }

    data class Received(val verdict: Verdict, val echo: SignedEvent? = null)

    private val createdAt = HashMap<String, Long>()

    /** The event for a version stored locally (the phone's add / edit / delete / undo, the pens). */
    fun publish(s: OwnerState, record: TreatmentRecord, now: Long): SignedEvent = event(s, TreatmentWire.toPayload(record), now)

    /** The whole log of the last 30 days, tombstones included, under the current epoch; stamps [OwnerState.treatmentsPublishedAt]. */
    suspend fun republish(s: OwnerState, now: Long): Step {
        val since = Instant.ofEpochSecond(now - REPUBLISH_WINDOW)
        val events = repository.changedSince(since).sortedBy { it.treatment.at }.map { event(s, TreatmentWire.toPayload(it), now) }
        return Step(s.copy(treatmentsPublishedAt = now), events)
    }

    fun republishDue(s: OwnerState, now: Long): Boolean = now - s.treatmentsPublishedAt >= Wire.DAY * 7

    /** The writers' entries of the last 30 days (null without writers: no subscription). */
    fun writerFilter(s: OwnerState, now: Long): RelayFilter? =
        s.activeWriters().takeIf { it.isNotEmpty() }?.let {
            RelayFilter(
                kinds = listOf(Wire.KIND_DATA),
                authors = it.sorted(),
                c = listOf(Wire.treatmentsC(s.sid)),
                since =
                    now - REPUBLISH_WINDOW,
            )
        }

    /** A writer's event: accepted only from an active roster writer, for a new id or one it created, then echoed by D. */
    suspend fun receive(s: OwnerState, ev: SignedEvent, now: Long, via: String?): Received {
        val author = ev.pubkey
        if (ev.kind != Wire.KIND_DATA || author !in s.activeWriters() || !ctx.crypto.verify(ev)) return Received(Verdict.NotWriter)
        val epoch =
            s.epochs.firstOrNull { Wire.gk(s.readKey(ctx.crypto, it).public) == ev.tag("gk") } ?: return Received(Verdict.UnknownEpoch)
        val plain =
            ctx.crypto.decrypt(s.readKey(ctx.crypto, epoch).secret, PublicKeyHex(author), ev.content)
                ?: return Received(Verdict.Undecryptable)
        val tr = runCatching { TreatmentCodec.decode(plain) }.getOrNull() ?: return Received(Verdict.Invalid)
        if (ev.tag("d") != Wire.treatmentD(s.sid, tr.id)) return Received(Verdict.Invalid)
        val stored = repository.get(tr.id)
        if (!WriterScope.ownerAllows(author, stored?.creator, inOwnLog = stored != null)) return Received(Verdict.NotCreator)
        // a writer always sends `follower` (and never pen keys); the log keeps the first writer as the creator
        val wire = tr.copy(origin = TreatmentCodec.ORIGIN_FOLLOWER, primed = null, pen = null, penDoses = null)
        val record =
            TreatmentRecord(
                treatment = TreatmentWire.toTreatment(wire),
                creator = stored?.creator ?: author,
                author = author,
                via = via,
                receivedAt = Instant.ofEpochSecond(now),
                delivery = TreatmentDelivery.Delivered,
            )
        if (!repository.upsertIfNewer(record)) return Received(Verdict.Older)
        return Received(Verdict.Accepted, event(s, wire, now))
    }

    /** `created_at` strictly increases per entry, or relays keep the older version. */
    private fun event(s: OwnerState, tr: TreatmentPayload, now: Long): SignedEvent {
        val at = maxOf(now, (createdAt[tr.id] ?: 0) + 1)
        createdAt[tr.id] = at
        return ctx.events.treatment(s.device(ctx.crypto), s.readKey(ctx.crypto).public, s.sid, tr, at)
    }

    companion object {
        const val REPUBLISH_WINDOW = 30 * Wire.DAY
    }
}

/** [TreatmentRecord] ⇄ the wire entry (contracts/treatments SPEC §1: `t` seconds, `u` milliseconds, pen keys as stored). */
object TreatmentWire {
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
            TreatmentOrigin.Follower to TreatmentCodec.ORIGIN_FOLLOWER,
        )

    fun toPayload(r: TreatmentRecord): TreatmentPayload {
        val t = r.treatment
        val pen = r.penJson?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
        return TreatmentPayload(
            id = t.id,
            kind = KINDS.getValue(t.kind),
            amount = t.amount,
            t = t.at.epochSecond,
            note = t.note,
            u = t.modifiedAt.toEpochMilli(),
            deleted = t.deleted,
            origin = ORIGINS.getValue(t.origin),
            primed = pen?.get("pr")?.jsonPrimitive?.doubleOrNull,
            pen = pen?.get("pen")?.jsonPrimitive?.contentOrNull,
            penDoses = pen?.get("pd") as? JsonArray,
        )
    }

    fun toTreatment(p: TreatmentPayload): Treatment =
        Treatment(
            id = p.id,
            kind = KINDS.entries.first { it.value == p.kind }.key,
            amount = p.amount,
            at = Instant.ofEpochSecond(p.t),
            note = p.note,
            modifiedAt = Instant.ofEpochMilli(p.u),
            deleted = p.deleted,
            origin = ORIGINS.entries.firstOrNull { it.value == p.origin }?.key ?: TreatmentOrigin.Follower,
        )
}
