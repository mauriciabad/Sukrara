// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.treatments

import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import app.mauri.sukrara.pen.PdDose

/**
 * One insulin / carbs / note entry. Same JSON on disk, over BLE and (later) on Nostr.
 * Field names and the merge rule are spikes/libreview/SPEC.md §1.
 */
@Serializable
data class Treatment(
    val id: String,
    val k: String,              // rapid | long | carbs | note
    val a: Double = 0.0,        // units or grams
    val t: Long,                // epoch s, when it happened
    val n: String = "",
    val u: Long,                // epoch ms, last modified (last-writer-wins version)
    val del: Boolean = false,
    val o: String,              // phone | mac
    val lv: Long = 0,           // the `u` last confirmed uploaded to LibreView
    // insulin pen entries only (inv. 29, SPEC §1), absent on hand-typed ones; `a` stays the injected units
    val pr: Double? = null,     // primed units: never insulin, never uploaded
    val pen: String? = null,    // the pen's serial
    val pd: List<PdDose>? = null, // its doses, time order
) {
    val needsUpload get() = lv < u

    /**
     * A pen entry's session may still grow (a top-up within [PEN_SETTLE_S] of its last dose): outputs that keep a copy
     * (LibreView, Nightscout, Tidepool) wait until then, so a top-up doesn't mean a retraction + re-upload. The log,
     * the graph, followers and the insulin mute have it at once. Null = ready.
     */
    fun heldUntil(nowS: Long): Long? = pd?.maxOfOrNull { it.t }?.plus(PEN_SETTLE_S)?.takeIf { it > nowS && !del }

    /** The Nostr/BLE v2 body (SPEC §5): `{"v":1,…}` without `lv`; the pen keys only on pen entries. */
    fun wire(): org.json.JSONObject = org.json.JSONObject().put("v", 1).put("id", id).put("k", k).put("a", a).put("t", t)
        .put("n", n).put("u", u).put("del", del).put("o", o).also { j ->
            pr?.let { j.put("pr", it) }
            pen?.let { j.put("pen", it) }
            pd?.let { l -> j.put("pd", org.json.JSONArray().apply { l.forEach { d -> put(org.json.JSONObject().put("t", d.t).put("a", d.a).put("p", d.p)) } }) }
        }

    companion object {
        /** Parse a v2 body (SPEC §5); null if it isn't a valid treatment. */
        fun fromWire(j: org.json.JSONObject): Treatment? = runCatching {
            val k = j.getString("k").takeIf { it in KINDS } ?: return null
            val (pr, pen, pd) = penKeys(j)
            Treatment(id = j.getString("id").takeIf { it.isNotEmpty() } ?: return null, k = k, a = j.optDouble("a", 0.0),
                t = j.getLong("t"), n = clip(j.optString("n")), u = j.getLong("u"), del = j.optBoolean("del", false),
                o = j.optString("o").ifEmpty { "phone" }.take(16), pr = pr, pen = pen, pd = pd)
        }.getOrNull()

        /** The optional pen keys of a v2 body (SPEC §1), bounded; malformed ones are dropped, never the entry. */
        fun penKeys(j: org.json.JSONObject): Triple<Double?, String?, List<PdDose>?> {
            val pr = if (j.has("pr")) j.optDouble("pr").takeIf { it.isFinite() && it >= 0 } else null
            val pen = j.optString("pen").takeIf { j.has("pen") && it.isNotEmpty() }?.take(32)
            val pd = j.optJSONArray("pd")?.let { a ->
                (0 until minOf(a.length(), 64)).mapNotNull { i -> a.optJSONObject(i)?.let { d ->
                    if (!d.has("t") || !d.has("a")) null else PdDose(d.getLong("t"), d.getDouble("a"), d.optBoolean("p", false)) } }
            }
            return Triple(pr, pen, pd)
        }

        /** A pen entry's session is open this long after its last dose (DESIGN §4.1 cluster gap). */
        const val PEN_SETTLE_S = 600L

        val KINDS = listOf("rapid", "long", "carbs", "note")

        /** Newer `u` wins, ties broken by origin; the upload watermark only ever moves forward. */
        fun merge(cur: Treatment?, inc: Treatment): Treatment {
            if (cur == null) return inc
            val incWins = inc.u > cur.u || (inc.u == cur.u && inc.o > cur.o)
            return (if (incWins) inc else cur).copy(lv = maxOf(cur.lv, inc.lv))
        }

        /** Truncate to [maxBytes] UTF-8 bytes without splitting a character. */
        fun clip(s: String, maxBytes: Int = 100): String {
            if (s.toByteArray().size <= maxBytes) return s
            var end = s.length
            while (s.substring(0, end).toByteArray().size > maxBytes) end--
            return s.substring(0, end)
        }
    }
}

val TreatmentJson = Json { encodeDefaults = true; ignoreUnknownKeys = true; explicitNulls = false }

/**
 * The phone's treatment log. Every accepted change gets the next [seq].
 */
class TreatmentStore(private val file: File, val origin: String = "phone") {
    @Serializable private data class Row(val s: Long, val tr: Treatment)
    @Serializable private data class Disk(val seq: Long = 0, val rows: List<Row> = emptyList())

    private val rows = LinkedHashMap<String, Row>()
    var seq = 0L
        private set

    /** Newest first, including tombstones (the UI filters). */
    val items = MutableStateFlow<List<Treatment>>(emptyList())

    /**
     * Called (outside the lock) for every accepted change, with its new seq: the owner engine's Nostr/BLE publisher
     * (SPEC §5) adds its listener and removes it on stop.
     */
    val onChange = java.util.concurrent.CopyOnWriteArrayList<(Treatment, Long) -> Unit>()

    init {
        runCatching { TreatmentJson.decodeFromString<Disk>(file.readText()) }.getOrNull()?.let { d ->
            seq = d.seq
            d.rows.forEach { rows[it.tr.id] = it }
        }
        publish()
    }

    fun all(): List<Treatment> = synchronized(this) { rows.values.map { it.tr } }

    /** The row for [id] with its seq, or null. */
    fun entry(id: String): Pair<Long, Treatment>? = synchronized(this) { rows[id]?.let { it.s to it.tr } }

    /** Merge a record from elsewhere (a follower or Mac over v2). Returns the stored result if anything changed. */
    fun merge(inc: Treatment): Treatment? = apply(inc.id) { cur -> Treatment.merge(cur, inc) }

    fun add(kind: String, amount: Double, at: Long, note: String = ""): Treatment {
        require(kind in Treatment.KINDS)
        val tr = Treatment(
            id = UUID.randomUUID().toString(), k = kind, t = at, n = Treatment.clip(note),
            a = when (kind) { "carbs" -> Math.round(amount).toDouble(); "note" -> 0.0; else -> Math.round(amount * 10) / 10.0 },
            u = System.currentTimeMillis(), o = origin,
        )
        apply(tr.id) { tr }
        return tr
    }

    /**
     * A pen entry written by the pen engine (inv. 29): stored as given (its `u` is the engine's), keeping the
     * LibreView watermark of the row it replaces.
     */
    fun put(tr: Treatment): Treatment? = apply(tr.id) { cur -> tr.copy(lv = maxOf(cur?.lv ?: 0, tr.lv)) }

    fun delete(id: String) = apply(id) { cur ->
        if (cur == null || cur.del) cur else cur.copy(del = true, u = maxOf(System.currentTimeMillis(), cur.u + 1))
    }

    /** Rows confirmed by LibreView at the version that was uploaded. */
    fun markUploaded(uploaded: List<Treatment>) = uploaded.forEach { up ->
        apply(up.id) { cur -> if (cur != null && cur.lv < up.u) cur.copy(lv = up.u) else cur }
    }

    private fun apply(id: String, f: (Treatment?) -> Treatment?): Treatment? {
        val changed = synchronized(this) {
            val cur = rows[id]?.tr
            val next = f(cur) ?: return null
            if (next == cur) return null
            rows[id] = Row(++seq, next)
            save()
            next to seq
        }
        publish()
        onChange.forEach { l -> runCatching { l(changed.first, changed.second) } }
        return changed.first
    }

    private fun save() {
        val tmp = File(file.path + ".tmp")
        tmp.writeText(TreatmentJson.encodeToString(Disk(seq, rows.values.toList())))
        tmp.renameTo(file)
    }

    private fun publish() {
        items.value = synchronized(this) { rows.values.map { it.tr } }.sortedByDescending { it.t }
    }
}
