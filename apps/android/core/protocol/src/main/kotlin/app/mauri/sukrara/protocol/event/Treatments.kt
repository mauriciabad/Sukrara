// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.protocol.event

import app.mauri.sukrara.protocol.Wire
import app.mauri.sukrara.protocol.event.Payload.bool
import app.mauri.sukrara.protocol.event.Payload.long
import app.mauri.sukrara.protocol.event.Payload.numberOrNull
import app.mauri.sukrara.protocol.event.Payload.requireV
import app.mauri.sukrara.protocol.event.Payload.string
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A treatment entry (contracts/treatments/SPEC.md §1 without `lv`; PROTOCOL-v2 §Treatments). [u] = merge version (ms),
 * [origin] = `phone` | `mac` | `follower`. Pen entries carry [primed], [pen] and [penDoses] exactly as received: every
 * publisher and receiver keeps them unchanged, so the doses stay raw JSON.
 */
data class TreatmentPayload(
    val id: String,
    val kind: String,
    val amount: Double,
    val t: Long,
    val note: String,
    val u: Long,
    val deleted: Boolean,
    val origin: String,
    val primed: Double? = null,
    val pen: String? = null,
    val penDoses: JsonArray? = null,
) {
    /** Health data: never in logs. */
    override fun toString() = "TreatmentPayload($id, $kind${if (deleted) ", deleted" else ""})"

    /** SPEC §1 merge: `(u, o)` compared lexicographically; the greater wins. */
    fun newerThan(other: TreatmentPayload): Boolean = compareValuesBy(this, other, { it.u }, { it.origin }) > 0
}

object TreatmentCodec {
    val KINDS = setOf("rapid", "long", "carbs", "note")
    const val ORIGIN_FOLLOWER = "follower"
    private val UUID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    private const val NOTE_MAX_BYTES = 100
    private const val PEN_MAX_BYTES = 32
    private const val DOSES_MAX = 20

    fun encode(tr: TreatmentPayload): String =
        buildJsonObject {
            put("v", Wire.TREATMENT_V)
            put("id", tr.id)
            put("k", tr.kind)
            put("a", amount(tr.amount))
            put("t", tr.t)
            put("n", tr.note)
            put("u", tr.u)
            put("del", tr.deleted)
            put("o", tr.origin)
            tr.primed?.let { put("pr", amount(it)) }
            tr.pen?.let { put("pen", it) }
            tr.penDoses?.let { put("pd", it) }
        }.toString()

    /** Whole amounts go out as integers (`"a":8`), fractional ones as decimals (`"a":8.5`). */
    private fun amount(a: Double): JsonElement = if (a == Math.floor(a) && !a.isInfinite()) JsonPrimitive(a.toLong()) else JsonPrimitive(a)

    fun decode(json: String): TreatmentPayload = decode(Payload.parse(json))

    fun decode(o: JsonObject): TreatmentPayload {
        o.requireV(Wire.TREATMENT_V)
        val id = o.string("id")?.takeIf { UUID.matches(it) } ?: throw InvalidPayload("id must be a lowercase UUID")
        val kind = o.string("k")?.takeIf { it in KINDS } ?: throw InvalidPayload("unknown treatment kind")
        val note = if ("n" in o) o.string("n") ?: throw InvalidPayload("n must be a string") else ""
        if (note.toByteArray().size > NOTE_MAX_BYTES) throw InvalidPayload("n longer than 100 bytes")
        val amount = o.numberOrNull("a") ?: 0.0
        if (amount < 0) throw InvalidPayload("negative amount")
        val primed = o.numberOrNull("pr")
        if (primed != null && primed < 0) throw InvalidPayload("negative pr")
        val pen = if ("pen" in o) o.string("pen") ?: throw InvalidPayload("pen must be a string") else null
        if (pen != null && pen.toByteArray().size > PEN_MAX_BYTES) throw InvalidPayload("pen longer than 32 bytes")
        return TreatmentPayload(
            id = id,
            kind = kind,
            amount = amount,
            t = o.long("t"),
            note = note,
            u = o.long("u"),
            deleted = o.bool("del") ?: false,
            origin = o.string("o").orEmpty(),
            primed = primed,
            pen = pen,
            penDoses = o["pd"]?.let(::doses),
        )
    }

    /** `pd`: ≤ 20 `{t:int, a:number ≥ 0, p?:bool}` in time order, kept as sent. */
    private fun doses(e: JsonElement): JsonArray {
        val list = e as? JsonArray ?: throw InvalidPayload("pd must be a list")
        if (list.size > DOSES_MAX) throw InvalidPayload("pd has more than 20 doses")
        var last = Long.MIN_VALUE
        list.forEach { d ->
            val o = d as? JsonObject ?: throw InvalidPayload("pd dose is not an object")
            val t = o.long("t")
            val a = o.numberOrNull("a") ?: throw InvalidPayload("pd dose without a")
            if (a < 0) throw InvalidPayload("pd dose: negative a")
            if ("p" in o && o.bool("p") == null) throw InvalidPayload("pd dose: p must be a boolean")
            if (t < last) throw InvalidPayload("pd not in time order")
            last = t
        }
        return list
    }
}

/**
 * Who may change which treatment entry (PROTOCOL-v2 §Treatments "Writer scope"): a writer creates entries and changes
 * or deletes only the ones it created; the wearer changes anything. Receivers remember each id's creator.
 */
object WriterScope {
    enum class Verdict { Accept, Ignore, Reject, Override }

    /**
     * The owner, for an entry signed by writer [author]: [creator] = the writer recorded for this id (null = none),
     * [inOwnLog] = the id is in the wearer's log. An id in the log with no recorded writer is the wearer's own.
     */
    fun ownerAllows(author: String, creator: String?, inOwnLog: Boolean): Boolean =
        if (creator != null) creator == author else !inOwnLog

    /**
     * A follower, for a version signed by [author] ([fromDevice] = a roster device, i.e. the wearer's merged log).
     * A writer: only a new id or one it created; when the wearer's copy of a writer entry came first the writer's
     * own copy is [Verdict.Ignore]d. The wearer's own entry (`o` ≠ follower) beats a version a writer forged first
     * ([Verdict.Override], whatever its `u`).
     */
    fun follower(
        author: String,
        fromDevice: Boolean,
        incomingOrigin: String,
        creator: String?,
        heldOrigin: String?,
        devices: Collection<String>,
    ): Verdict = when {
        !fromDevice -> when {
            creator == null || creator == author -> Verdict.Accept
            creator in devices && heldOrigin == TreatmentCodec.ORIGIN_FOLLOWER -> Verdict.Ignore
            else -> Verdict.Reject
        }
        incomingOrigin != TreatmentCodec.ORIGIN_FOLLOWER && creator != null && creator !in devices -> Verdict.Override
        else -> Verdict.Accept
    }
}
