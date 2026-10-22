// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.novopen.engine

import kotlin.math.abs
import kotlinx.serialization.Serializable

/*
 * The pen dose engine (spikes/novopen-android/DESIGN.md §3–§4, PRODUCT.md §3): a pen read + the pen ledger + the
 * treatment log → entry writes + questions. Pure Kotlin, no clock, no I/O: every time comes in as an argument, ids from
 * [PenEngine.newId]. Sukrara's `pen/PenEngine.kt` is this file with the package renamed (PenCopyTest keeps them equal);
 * spikes/novopen-android/scenarios.json drives both (PenScenarioTest).
 */

/** One dose inside a pen entry (`pd`, SPEC §1): epoch s, units, true if priming. */
@Serializable
data class PdDose(val t: Long, val a: Double, val p: Boolean = false)

/** The engine's view of a treatment: Sukrara's `Treatment` fields that matter here (SPEC §1). */
@Serializable
data class PenEntry(
    val id: String, val k: String, val a: Double, val t: Long, val n: String = "", val u: Long,
    val del: Boolean = false, val o: String = "phone",
    val pr: Double? = null, val pen: String? = null, val pd: List<PdDose>? = null,
)

/** A dose as read from the pen: the pen's raw relative time (s), its absolute time (epoch s), units, incomplete flag. */
data class ReadDose(val rel: Long, val t: Long, val a: Double, val incomplete: Boolean = false)

/** One NFC read: serial, scan time (epoch s), the pen's relative clock at that moment, every dose it holds. */
data class PenScan(val serial: String, val at: Long, val penNow: Long, val doses: List<ReadDose>, val model: String = "")

/** What the engine knows about one dose key ([PenEngine.key]). */
@Serializable
data class Seen(
    val t: Long, val a: Double, val role: String,
    val e: String? = null,      // the entry it became (injected / primed), or the deleted one
    val q: String? = null,      // the question it waits on
    val p: Boolean = false,     // priming (decided), or the suggestion while it waits
    val inc: Boolean = false,   // the pen flagged it incomplete (counted with the units it reports)
)

/** The ledger of one pen (`pens.json`, phone only, never synced). */
@Serializable
data class Pen(
    val serial: String, val model: String = "", val name: String = "", val kind: String = "rapid",
    val since: Long,                       // nothing older is imported on its own
    val first: Long,                       // first scan (epoch s)
    val lastScan: Long = 0, val lastPenNow: Long = 0,
    val older: Int = 0,                    // doses before `since` at the first scan
    val returning: Boolean = false,        // the first scan found this pen's entries in the log (new phone, reinstall)
    val check: String? = null,             // untested models: null = not checked, "ok" / "failed" (PRODUCT §6)
    val doses: Map<String, Seen> = emptyMap(),
    val wrote: Map<String, Long> = emptyMap(),          // entry id → the `u` the engine last wrote
    val replaced: Map<String, PenEntry> = emptyMap(),   // entry id → the hand-typed entry a merge replaced
)

/** A question: what some doses were (`doses`), or whether an injection is a hand-typed entry (`match`). */
@Serializable
data class Question(
    val id: String, val serial: String, val type: String, val keys: List<String>,
    val cands: List<String> = emptyList(), val at: Long, val answer: Answer? = null,
)

@Serializable
data class Answer(val at: Long, val entry: String? = null, val next: String? = null)

@Serializable
data class Ledger(val pens: Map<String, Pen> = emptyMap(), val questions: List<Question> = emptyList()) {
    fun open(): List<Question> = questions.filter { it.answer == null }
}

/** The first scan's one offer (PRODUCT §3.9): the pen's newest injection, if under 24 h old. */
data class Offer(val doses: Map<String, Boolean>, val t: Long, val a: Double, val pr: Double, val match: String?)

data class Report(
    val serial: String?, val first: Boolean = false, val returning: Boolean = false,
    val created: List<String> = emptyList(), val updated: List<String> = emptyList(),
    val merged: List<String> = emptyList(), val asked: List<String> = emptyList(),
    val pending: Int = 0, val older: Int = 0, val future: Int = 0, val clockChanged: Boolean = false,
    val clockMatched: Int = 0, val restored: Int = 0,
    val review: Int = 0, val reviewSince: Long? = null,
    val logged: Double = 0.0, val primed: Double = 0.0,
    val offer: Offer? = null,
    val bigSince: Long? = null,   // §3.11 the read logged an injection older than 72 h: the oldest one's time (Review)
)

/** What [PenEngine.undo] needs to take one read back: the doses it decided and the entries as they were before. */
data class UndoRecord(val serial: String, val keys: List<String>, val before: Map<String, PenEntry?>)

data class Outcome(val ledger: Ledger, val writes: List<PenEntry>, val report: Report, val undo: UndoRecord? = null)

/** One row of the pen's dose history (PRODUCT §4.9): a session and its status. */
data class Session(
    val serial: String, val keys: List<String>, val primed: Set<String>, val t: Long, val a: Double, val pr: Double,
    val status: String, val entry: String? = null, val question: String? = null, val incomplete: Boolean = false,
) {
    companion object {
        const val LOGGED = "logged"; const val MERGED = "merged"; const val CORRECTED = "corrected"
        const val PRIMING = "priming"; const val WAITING = "waiting"; const val REVIEW = "review"
        const val NOT_LOGGED = "not-logged"; const val BEFORE = "before"; const val IGNORED = "ignored"
    }
}

/**
 * Every threshold and window (seconds, units), in one place so the rules can be tuned without touching the engine.
 * [primeBelow] = setting `penPrimeBelow`: a dose smaller than this can be priming.
 */
data class PenRules(
    val primeBelow: Double = 2.0,
    val cluster: Long = 600, val primeBefore: Long = 1800, val pending: Long = 1800,
    val match: Long = 2700, val matchUnits: Double = 1.0, val matchShare: Double = 0.25,
    val future: Long = 120, val resetMatch: Long = 120, val review: Long = 72 * 3600L, val offer: Long = 24 * 3600L,
    val maxDoses: Int = 20,   // SPEC §1: a `pd` holds at most 20 doses; a longer session is split
)

class PenEngine(
    val rules: PenRules = PenRules(),
    val origin: String = "phone",
    val newId: () -> String = { java.util.UUID.randomUUID().toString() },
    val log: (String) -> Unit = {},
) {
    companion object {
        /** Dose roles in the ledger. */
        const val BEFORE = "before-since"      // older than the pen's start: only by hand (Doses read)
        const val NEW = "new"                  // just read, not decided (transient)
        const val PENDING_ROLE = "pending"     // newest small doses: an injection may still follow
        const val QUESTION = "question"        // waits on a question
        const val REVIEW = "review"            // read after a pen clock reset: waits in Doses read
        const val INJECTED = "injected"; const val PRIMED = "primed"
        const val IGNORED = "ignored"          // not logged: undone, "Not now"
        const val FUTURE = "future"            // later than the scan: never used
        const val REPLACED = "replaced"        // superseded by its new key after a pen clock reset
        const val DOSES = "doses"; const val MATCH_Q = "match"

        /** Dose key: the pen's raw relative time + units (tenths). Stable across scans; scoped by serial in the ledger. */
        fun key(rel: Long, a: Double) = "$rel:${tenths(a)}"
        fun tenths(a: Double) = Math.round(a * 10)
        fun r1(x: Double) = Math.round(x * 10) / 10.0

        /** §4.6: the live pen entry of kind [k] within 45 min of [t] (nearest), for the hand-typed duplicate warning. */
        fun near(entries: List<PenEntry>, k: String, t: Long, except: String? = null, window: Long = PenRules().match): PenEntry? =
            entries.filter { !it.del && it.pen != null && it.k == k && it.id != except && abs(it.t - t) <= window }
                .minByOrNull { abs(it.t - t) }

        /**
         * §4.8: the entry with each dose's role set by the user ([primed] per `pd` index). Null when every dose is
         * priming (priming alone never makes an entry: the caller removes it instead). `u` is the caller's.
         */
        fun correct(e: PenEntry, primed: List<Boolean>): PenEntry? {
            val pd = e.pd ?: return null
            require(primed.size == pd.size)
            val next = pd.mapIndexed { i, d -> d.copy(p = primed[i]) }
            val inj = next.filter { !it.p }
            if (inj.isEmpty()) return null
            return e.copy(t = inj.minOf { it.t }, a = r1(inj.sumOf { it.a }), pr = r1(next.filter { it.p }.sumOf { it.a }), pd = next)
        }

        /** When the engine wants to run again (a pending cluster turns into a decision), epoch s; null if never. */
        fun nextWake(l: Ledger, rules: PenRules = PenRules()): Long? = l.pens.values.mapNotNull { p ->
            p.doses.values.filter { it.role == PENDING_ROLE }.maxOfOrNull { it.t }?.plus(rules.pending)
        }.minOrNull()
    }

    private data class D(val key: String, val t: Long, val a: Double)
    private data class Item(val d: D, val p: Boolean)

    /** §4.5 candidates for an injection at [t] of [a] units: hand-typed entries of [kind] within 45 min, nearest first. */
    fun candidates(entries: Collection<PenEntry>, kind: String, t: Long, a: Double, claimed: Set<String> = emptySet()): Pair<List<PenEntry>, PenEntry?> {
        val c = entries.filter { !it.del && it.k == kind && it.pen == null && it.id !in claimed && abs(it.t - t) <= rules.match }
            .sortedBy { abs(it.t - t) }
        val unique = c.singleOrNull()?.takeIf { abs(it.a - a) <= maxOf(rules.matchUnits, rules.matchShare * a) + 1e-9 }
        return c to unique
    }

    private inner class Work(l: Ledger, entries: List<PenEntry>, val nowMs: Long) {
        val pens = l.pens.toMutableMap()
        val qs = l.questions.toMutableList()
        val entries = LinkedHashMap<String, PenEntry>().apply { entries.forEach { put(it.id, it) } }
        val writes = LinkedHashMap<String, PenEntry>()
        val before = LinkedHashMap<String, PenEntry?>()
        val claimed = mutableSetOf<String>()
        val created = mutableListOf<String>(); val updated = mutableListOf<String>()
        val merged = mutableListOf<String>(); val asked = mutableListOf<String>()
        var pending = 0; var older = 0; var future = 0; var clockMatched = 0; var restored = 0
        var review = 0; var reviewSince: Long? = null; var clockChanged = false; var bigSince: Long? = null
        var logged = 0.0; var primed = 0.0
        var offer: Offer? = null

        fun pen(s: String) = pens.getValue(s)
        fun seen(s: String, k: String) = pen(s).doses.getValue(k)
        fun q(id: String?) = qs.firstOrNull { it.id == id }
        fun setDose(s: String, k: String, v: Seen) { pens[s] = pen(s).let { it.copy(doses = it.doses + (k to v)) } }
        fun putQ(q: Question) { val i = qs.indexOfFirst { it.id == q.id }; if (i < 0) qs += q else qs[i] = q }

        fun final(s: String, k: String, p: Boolean, e: String?) {
            val cur = seen(s, k)
            if (cur.role != INJECTED && cur.role != PRIMED) { if (p) primed += cur.a else logged += cur.a }
            setDose(s, k, cur.copy(role = if (p) PRIMED else INJECTED, e = e, q = null, p = p))
        }

        /** [user]: a change the user asked for (a correction): it doesn't count as the engine's write (§4.7). */
        fun write(s: String, e: PenEntry, user: Boolean = false) {
            val u = maxOf(nowMs, (entries[e.id]?.u ?: 0) + 1)
            val w = e.copy(u = u)
            if (!before.containsKey(e.id)) before[e.id] = entries[e.id]
            entries[e.id] = w; writes[e.id] = w
            if (!user) pens[s] = pen(s).let { it.copy(wrote = it.wrote + (e.id to u)) }
        }

        /** The entry [id] rebuilt from the ledger's doses for it, on top of [base] (or new). */
        fun build(s: String, id: String, base: PenEntry?): PenEntry {
            val ds = pen(s).doses.values.filter { it.e == id && (it.role == INJECTED || it.role == PRIMED) }.sortedBy { it.t }
            val inj = ds.filter { !it.p }
            val pd = ds.map { PdDose(it.t, r1(it.a), it.p) }
            val t = inj.minOf { it.t }; val a = r1(inj.sumOf { it.a }); val pr = r1(ds.filter { it.p }.sumOf { it.a })
            return base?.copy(t = t, a = a, pr = pr, pen = s, pd = pd, del = false)
                ?: PenEntry(id = id, k = pen(s).kind, a = a, t = t, u = 0, o = origin, pr = pr, pen = s, pd = pd)
        }

        /**
         * §4.7/4.8: the user changed [e] after the engine wrote it: its `u` isn't the one written last, or its `pd`
         * roles aren't the ledger's (a dose-role correction, maybe made on the other device).
         */
        fun edited(s: String, e: PenEntry): Boolean {
            if (pen(s).wrote[e.id] != e.u) return true
            val mine = pen(s).doses.values.filter { it.e == e.id && (it.role == INJECTED || it.role == PRIMED) }
                .map { Triple(it.t, tenths(it.a), it.p) }.sortedWith(compareBy({ it.first }, { it.second }, { it.third }))
            val theirs = (e.pd ?: emptyList()).map { Triple(it.t, tenths(it.a), it.p) }
                .sortedWith(compareBy({ it.first }, { it.second }, { it.third }))
            return mine != theirs
        }

        fun outcome(serial: String?, first: Boolean = false, start: Ledger? = null): Outcome {
            val undo = if (start != null && serial != null) {
                val was = start.pens[serial]?.doses ?: emptyMap()
                val keys = pens[serial]?.doses?.filter { (k, v) ->
                    v.role !in setOf(BEFORE, REPLACED, FUTURE) && was[k]?.let { it.role != v.role || it.e != v.e } ?: true
                }?.keys
                UndoRecord(serial, keys?.toList() ?: emptyList(), before.toMap())
            } else null
            return Outcome(
                Ledger(pens.toMap(), qs.toList()), writes.values.toList(),
                Report(serial, first, serial?.let { pens[it]?.returning } == true && first, created.toList(), updated.toList(),
                    merged.toList(), asked.toList(), pending, older, future, clockChanged, clockMatched, restored,
                    review, reviewSince, r1(logged), r1(primed), offer, bigSince),
                undo,
            )
        }
    }

    // ---------------------------------------------------------------- entry points

    /** §3–§4: one NFC read of a pen. */
    fun scan(l: Ledger, s: PenScan, entries: List<PenEntry>, nowMs: Long): Outcome {
        val w = Work(l, entries, nowMs)
        val old = w.pens[s.serial]
        val first = old == null
        // A lost ledger (reinstall, new phone, a forgotten pen): the pen entries already in the synced log say what was
        // read before; the start becomes the newest known dose, not now.
        val known = if (first) entries.filter { it.pen == s.serial && !it.pd.isNullOrEmpty() } else emptyList()
        val start = known.flatMap { it.pd!! }.maxOfOrNull { it.t } ?: s.at
        var pen = old ?: Pen(serial = s.serial, model = s.model, since = start, first = s.at, returning = known.isNotEmpty(),
            kind = known.maxByOrNull { it.t }?.k ?: "rapid", wrote = known.associate { it.id to it.u })
        val logged = known.flatMap { e -> e.pd!!.map { e to it } }.toMutableList()
        val reset = old != null && s.penNow < old.lastPenNow
        if (reset) { w.clockChanged = true; log("pen ${s.serial}: clock went back (${old!!.lastPenNow} → ${s.penNow})") }
        val doses = pen.doses.toMutableMap()
        val read = s.doses.filter { tenths(it.a) > 0 }.distinctBy { key(it.rel, it.a) }.sortedBy { it.t }
        val readKeys = read.map { key(it.rel, it.a) }.toSet()
        val used = mutableSetOf<String>()
        val renamed = mutableMapOf<String, String>()
        for (d in read) {
            val k = key(d.rel, d.a)
            if (k in doses) continue
            if (d.t > s.at + rules.future) {
                w.future++; log("pen ${s.serial}: dose ${d.a} u at ${d.t} is after the scan (${s.at}); ignored")
                doses[k] = Seen(d.t, r1(d.a), FUTURE, inc = d.incomplete); continue
            }
            if (reset) {
                val m = doses.entries.filter {
                    it.key !in readKeys && it.key !in used && it.value.role != REPLACED &&
                        tenths(it.value.a) == tenths(d.a) && abs(it.value.t - d.t) <= rules.resetMatch
                }.minByOrNull { abs(it.value.t - d.t) }
                if (m != null) {
                    used += m.key; renamed[m.key] = k
                    doses[k] = m.value
                    doses[m.key] = m.value.copy(role = REPLACED, e = null, q = null)
                    w.clockMatched++
                    continue
                }
            }
            val m = logged.filter { (_, pd) -> tenths(pd.a) == tenths(d.a) && abs(pd.t - d.t) <= rules.resetMatch }.minByOrNull { abs(it.second.t - d.t) }
            if (m != null) {
                logged -= m
                doses[k] = Seen(d.t, r1(d.a), if (m.second.p) PRIMED else INJECTED, e = m.first.id, p = m.second.p, inc = d.incomplete)
                w.restored++
                continue
            }
            val before = d.t < pen.since
            if (before) w.older++
            doses[k] = Seen(d.t, r1(d.a), if (before) BEFORE else NEW, inc = d.incomplete)
        }
        pen = pen.copy(
            model = s.model.ifEmpty { pen.model }, lastScan = s.at, lastPenNow = s.penNow, doses = doses,
            older = if (first) w.older else pen.older,
        )
        w.pens[s.serial] = pen
        if (renamed.isNotEmpty()) w.qs.replaceAll { q -> q.copy(keys = q.keys.map { renamed[it] ?: it }) }
        if (first && !pen.returning) w.offer = offer(w, s.serial, s.at)
        settle(w, s.serial, s.at, hold = reset)
        return w.outcome(s.serial, first, l)
    }

    /** Re-run the rules without a read (pending doses that timed out): at app start and at [nextWake]. */
    fun refresh(l: Ledger, entries: List<PenEntry>, now: Long, nowMs: Long): Outcome {
        val w = Work(l, entries, nowMs)
        l.pens.keys.forEach { settle(w, it, now) }
        return w.outcome(null)
    }

    /** Answer a `doses` question: [primed] keys were priming, the rest injected. */
    fun answerDoses(l: Ledger, qid: String, primed: Set<String>, entries: List<PenEntry>, now: Long, nowMs: Long): Outcome {
        val w = Work(l, entries, nowMs)
        val q = w.q(qid)?.takeIf { it.type == DOSES && it.answer == null } ?: return w.outcome(null)
        val (entry, next) = place(w, q.serial, q.keys.map { Item(D(it, w.seen(q.serial, it).t, w.seen(q.serial, it).a), it in primed) }, now)
        w.putQ(q.copy(answer = Answer(now, entry, next)))
        return w.outcome(q.serial)
    }

    /** Answer a `match` question: [same] = the hand-typed entry it is, or null for a different injection (logged apart). */
    fun answerMatch(l: Ledger, qid: String, same: String?, entries: List<PenEntry>, now: Long, nowMs: Long): Outcome {
        val w = Work(l, entries, nowMs)
        val q = w.q(qid)?.takeIf { it.type == MATCH_Q && it.answer == null } ?: return w.outcome(null)
        val items = q.keys.map { k -> w.seen(q.serial, k).let { Item(D(k, it.t, it.a), it.p) } }
        val c = same?.let { w.entries[it] }?.takeIf { !it.del && it.pen == null }
        val entry = if (c != null) merge(w, q.serial, items, c) else create(w, q.serial, items)
        w.putQ(q.copy(answer = Answer(now, entry)))
        return w.outcome(q.serial)
    }

    /**
     * *Log this injection* / *Log it* / *Log as injection* (Doses read, the first-scan offer): these doses, each
     * priming or not, go through §4.5 (merge, ask, or a new entry). All priming = *Only priming*, no entry.
     */
    fun logDoses(l: Ledger, serial: String, doses: Map<String, Boolean>, entries: List<PenEntry>, now: Long, nowMs: Long): Outcome {
        val w = Work(l, entries, nowMs)
        val pen = w.pens[serial] ?: return w.outcome(serial)
        val items = doses.keys.mapNotNull { k -> pen.doses[k]?.let { Item(D(k, it.t, it.a), doses.getValue(k)) } }.sortedBy { it.d.t }
        // a waiting question about them is answered by this
        w.qs.filter { it.answer == null && it.keys.any { k -> k in doses } }.forEach { q -> w.putQ(q.copy(keys = q.keys - doses.keys)) }
        place(w, serial, items, now)
        cleanQuestions(w, serial)
        return w.outcome(serial)
    }

    /** After a pen clock reset (PRODUCT §3.14): *Log all* the injections that wait in Doses read. */
    fun logReview(l: Ledger, serial: String, entries: List<PenEntry>, now: Long, nowMs: Long): Outcome {
        val w = Work(l, entries, nowMs)
        if (w.pens[serial] == null) return w.outcome(serial)
        reviewGroups(w.pen(serial)).forEach { g -> place(w, serial, g.map { (k, v) -> Item(D(k, v.t, v.a), v.p) }, now) }
        return w.outcome(serial)
    }

    /** After a pen clock reset: the waiting injections become *Not logged* (one at a time later). */
    fun skipReview(l: Ledger, serial: String, nowMs: Long): Outcome {
        val w = Work(l, emptyList(), nowMs)
        val pen = w.pens[serial] ?: return w.outcome(serial)
        pen.doses.filter { it.value.role == REVIEW }.forEach { (k, v) -> w.setDose(serial, k, v.copy(role = IGNORED)) }
        return w.outcome(serial)
    }

    /**
     * *Remove from log* (§4.3, every dose set to priming): the entry is deleted, its doses are *Only priming*. A user
     * edit; *Undo* = [restore].
     */
    fun removeEntry(l: Ledger, serial: String, id: String, entries: List<PenEntry>, nowMs: Long): Outcome {
        val w = Work(l, entries, nowMs)
        val e = w.entries[id]
        if (w.pens[serial] == null || e == null) return w.outcome(serial)
        w.pen(serial).doses.filter { it.value.e == id }.forEach { (k, v) -> w.setDose(serial, k, v.copy(role = PRIMED, e = null, p = true)) }
        if (!e.del) w.write(serial, e.copy(del = true), user = true)
        return w.outcome(serial)
    }

    /** Put [was] back (the snackbar's *Undo* after a correction or a removal): the entry and its doses as they were. */
    fun restore(l: Ledger, serial: String, was: PenEntry, entries: List<PenEntry>, nowMs: Long): Outcome {
        val w = Work(l, entries, nowMs)
        if (w.pens[serial] == null) return w.outcome(serial)
        val pd = was.pd.orEmpty().toMutableList()
        w.pen(serial).doses.filter { (_, v) -> v.e == was.id || (v.e == null && pd.any { it.t == v.t && tenths(it.a) == tenths(v.a) }) }.forEach { (k, v) ->
            val d = pd.firstOrNull { it.t == v.t && tenths(it.a) == tenths(v.a) }
            if (d != null) { pd -= d; w.setDose(serial, k, v.copy(role = if (d.p) PRIMED else INJECTED, e = was.id, p = d.p, q = null)) }
        }
        w.write(serial, was.copy(del = false), user = true)
        return w.outcome(serial)
    }

    /**
     * Take one read back (*Undo* on the read sheet): the entries it wrote return to how they were (new ones are
     * deleted, replaced hand-typed entries come back), its doses become *Not logged*; questions it asked are withdrawn.
     */
    fun undo(l: Ledger, r: UndoRecord, entries: List<PenEntry>, nowMs: Long): Outcome {
        val w = Work(l, entries, nowMs)
        if (w.pens[r.serial] == null) return w.outcome(r.serial)
        r.before.forEach { (id, was) ->
            val cur = w.entries[id] ?: return@forEach
            if (was == null) { if (!cur.del) w.write(r.serial, cur.copy(del = true)) }
            else w.write(r.serial, cur.copy(k = was.k, a = was.a, t = was.t, n = was.n, del = was.del, pr = was.pr, pen = was.pen, pd = was.pd), user = was.pen == null)
            if (was?.pen == null) w.pens[r.serial] = w.pen(r.serial).let { it.copy(replaced = it.replaced - id) }
        }
        r.keys.forEach { k -> w.pen(r.serial).doses[k]?.let { w.setDose(r.serial, k, it.copy(role = IGNORED, e = null, q = null)) } }
        cleanQuestions(w, r.serial)
        return w.outcome(r.serial)
    }

    fun forget(l: Ledger, serial: String) = Ledger(l.pens - serial, l.questions.filter { it.serial != serial })
    fun update(l: Ledger, serial: String, f: (Pen) -> Pen) = l.pens[serial]?.let { Ledger(l.pens + (serial to f(it)), l.questions) } ?: l

    /** The pen's dose history, newest first (PRODUCT §4.9). */
    fun history(l: Ledger, serial: String, entries: List<PenEntry>): List<Session> {
        val pen = l.pens[serial] ?: return emptyList()
        val byId = entries.associateBy { it.id }
        val w = Work(l, entries, 0)
        val out = mutableListOf<Session>()
        fun session(keys: List<String>, p: (String, Seen) -> Boolean, status: String, entry: String? = null, q: String? = null) {
            val ds = keys.map { it to pen.doses.getValue(it) }.sortedBy { it.second.t }
            val primed = ds.filter { (k, v) -> p(k, v) }.map { it.first }.toSet()
            val inj = ds.filter { it.first !in primed }
            out += Session(serial, ds.map { it.first }, primed, (inj.firstOrNull() ?: ds.first()).second.t,
                r1(inj.sumOf { it.second.a }), r1(ds.filter { it.first in primed }.sumOf { it.second.a }), status, entry, q,
                ds.any { it.second.inc })
        }
        val live = pen.doses.filter { it.value.role != REPLACED }
        live.filter { it.value.e != null }.entries.groupBy { it.value.e!! }.forEach { (id, l0) ->
            val e = byId[id]
            val pd = e?.pd.orEmpty()
            val p = { _: String, v: Seen -> pd.firstOrNull { it.t == v.t && tenths(it.a) == tenths(v.a) }?.p ?: v.p }
            val status = when {
                e == null || e.del -> Session.NOT_LOGGED
                id in pen.replaced -> Session.MERGED
                w.edited(serial, e) -> Session.CORRECTED
                else -> Session.LOGGED
            }
            session(l0.map { it.key }, p, status, entry = id)
        }
        live.filter { it.value.e == null && it.value.role == QUESTION }.entries.groupBy { it.value.q }.forEach { (q, l0) ->
            session(l0.map { it.key }, { _, v -> v.p }, Session.WAITING, q = q)
        }
        for ((role, status) in listOf(PENDING_ROLE to Session.WAITING, REVIEW to Session.REVIEW, BEFORE to Session.BEFORE,
                IGNORED to Session.NOT_LOGGED, PRIMED to Session.PRIMING, FUTURE to Session.IGNORED, NEW to Session.WAITING)) {
            val ds = live.filter { it.value.e == null && it.value.role == role }.map { D(it.key, it.value.t, it.value.a) }.sortedBy { it.t }
            for (c in clusters(ds)) {
                val split = split(c)
                session(c.map { it.key }, { k, v -> if (role == PRIMED) true else if (role == REVIEW) v.p else split[k] == true }, status)
            }
        }
        return out.sortedByDescending { it.t }
    }

    // ---------------------------------------------------------------- the rules

    private fun clusters(ds: List<D>): List<List<D>> {
        val cl = mutableListOf<MutableList<D>>()
        for (d in ds) if (cl.isEmpty() || d.t - cl.last().last().t > rules.cluster) cl += mutableListOf(d) else cl.last() += d
        return cl
    }

    private fun isBig(a: Double) = tenths(a) >= tenths(rules.primeBelow)

    /**
     * PRODUCT §3.2/§3.5: in a session with a large dose, small doses before the last large one are priming and small
     * doses after it are injected (a top-up). Without a large dose (the suggestion while it's asked): the last dose
     * injected, the ones before it priming. Key → priming.
     */
    private fun split(c: List<D>): Map<String, Boolean> {
        val lastBig = c.indexOfLast { isBig(it.a) }
        return if (lastBig >= 0) c.mapIndexed { j, d -> d.key to (!isBig(d.a) && j < lastBig) }.toMap()
        else c.mapIndexed { j, d -> d.key to (j < c.lastIndex) }.toMap()
    }

    /** §3.9: the pen's newest injection among the doses before the start, if it is under 24 h old. */
    private fun offer(w: Work, s: String, now: Long): Offer? {
        val ds = w.pen(s).doses.filter { it.value.role == BEFORE }.map { D(it.key, it.value.t, it.value.a) }.sortedBy { it.t }
        val c = clusters(ds).lastOrNull { c -> c.any { isBig(it.a) } } ?: return null
        val sp = split(c)
        val inj = c.filter { sp[it.key] == false }
        val t = inj.minOf { it.t }
        if (now - t > rules.offer) return null
        val a = r1(inj.sumOf { it.a })
        return Offer(sp, t, a, r1(c.filter { sp[it.key] == true }.sumOf { it.a }), candidates(w.entries.values, w.pen(s).kind, t, a).second?.id)
    }

    private fun settle(w: Work, s: String, now: Long, allowPending: Boolean = true, hold: Boolean = false) {
        val pen = w.pen(s)
        val ds = pen.doses.filter { it.value.role !in setOf(BEFORE, REPLACED, IGNORED, FUTURE) && it.value.t >= pen.since }
            .map { D(it.key, it.value.t, it.value.a) }.sortedWith(compareBy({ it.t }, { it.key }))
        if (ds.isEmpty()) return
        val cl = clusters(ds)
        val big = cl.map { c -> c.any { isBig(it.a) } }
        val sessions = sortedMapOf<Int, MutableList<Item>>()
        cl.forEachIndexed { i, c -> if (big[i]) { val sp = split(c); sessions[i] = c.map { Item(it, sp.getValue(it.key)) }.toMutableList() } }
        val pendingCl = mutableListOf<Int>(); val questionCl = mutableListOf<Int>()
        cl.forEachIndexed { i, c ->
            if (big[i]) return@forEachIndexed
            val nx = (i + 1 until cl.size).firstOrNull { big[it] }
            when {
                // §3.3 priming, then a distraction, then the injection
                nx != null && cl[nx].first().t - c.last().t <= rules.primeBefore -> sessions.getValue(nx) += c.map { Item(it, true) }
                // the newest small doses may still be followed by their injection: wait
                allowPending && i == cl.lastIndex && now - c.last().t < rules.pending -> pendingCl += i
                // §3.4 no top-up across sessions: ask
                else -> questionCl += i
            }
        }
        // PRODUCT §3.14: after a pen clock reset nothing new is logged on its own (it waits in Doses read); §3.17 every
        // other read logs at once, a big one (an injection older than 72 h) is only flagged (Report.bigSince)
        val holdAll = hold
        for ((_, items0) in sessions) {
            val items = items0.sortedWith(compareBy({ it.d.t }, { it.d.key }))
            val fresh = items.filter { fresh(w, s, it) }
            if (fresh.isEmpty()) continue
            val openMatch = items.mapNotNull { w.q(w.seen(s, it.d.key).q) }.firstOrNull { it.type == MATCH_Q && it.answer == null }
            if (openMatch != null) { ask(w, s, openMatch, fresh); continue }
            val target = items.map { w.seen(s, it.d.key) }.firstOrNull { (it.role == INJECTED || it.role == PRIMED) && it.e != null }?.e
            if (target != null) {
                val e = w.entries[target]
                val inEntry = items.count { w.seen(s, it.d.key).e == target }
                if (e == null || e.del || w.edited(s, e) || inEntry + fresh.size > rules.maxDoses) { dosesQuestion(w, s, items, fresh, now); continue }
                fresh.forEach { w.final(s, it.d.key, it.p, target) }
                w.write(s, w.build(s, target, e)); w.updated += target
                continue
            }
            if (holdAll) {
                fresh.forEach { w.setDose(s, it.d.key, w.seen(s, it.d.key).copy(role = REVIEW, p = it.p, q = null)) }
                w.review++
                val t = fresh.filter { !it.p }.minOfOrNull { it.d.t } ?: fresh.first().d.t
                w.reviewSince = minOf(w.reviewSince ?: t, t)
                continue
            }
            place(w, s, fresh, now)
            val t = fresh.filter { !it.p }.minOfOrNull { it.d.t }
            if (t != null && t < now - rules.review) w.bigSince = minOf(w.bigSince ?: t, t)
        }
        for (i in pendingCl) cl[i].filter { w.seen(s, it.key).role == NEW || w.seen(s, it.key).role == PENDING_ROLE }.forEach {
            w.setDose(s, it.key, w.seen(s, it.key).copy(role = PENDING_ROLE)); w.pending++
        }
        for (i in questionCl) {
            val sp = split(cl[i])
            val items = cl[i].map { Item(it, sp.getValue(it.key)) }
            val fresh = items.filter { w.seen(s, it.d.key).role == NEW || w.seen(s, it.d.key).role == PENDING_ROLE }
            if (fresh.isNotEmpty()) dosesQuestion(w, s, items, fresh, now)
        }
        cleanQuestions(w, s)
    }

    /** Questions whose doses all went elsewhere are withdrawn. */
    private fun cleanQuestions(w: Work, s: String) {
        val p = w.pen(s)
        w.qs.toList().filter { it.serial == s && it.answer == null }.forEach { q ->
            val keys = q.keys.filter { p.doses[it]?.let { d -> d.role == QUESTION && d.q == q.id } == true }
            if (keys.isEmpty()) w.qs.removeAll { it.id == q.id } else if (keys != q.keys) w.putQ(q.copy(keys = keys))
        }
    }

    /** Not decided yet: new, pending, or (folded into an injection as its priming) an unanswered doses question. */
    private fun fresh(w: Work, s: String, it: Item): Boolean {
        val v = w.seen(s, it.d.key)
        return v.role == NEW || v.role == PENDING_ROLE ||
            (it.p && v.role == QUESTION && w.q(v.q)?.let { q -> q.type == DOSES && q.answer == null } == true)
    }

    private fun reviewGroups(pen: Pen): List<List<Pair<String, Seen>>> {
        val ds = pen.doses.filter { it.value.role == REVIEW }.map { D(it.key, it.value.t, it.value.a) }.sortedBy { it.t }
        return clusters(ds).map { c -> c.map { it.key to pen.doses.getValue(it.key) } }
    }

    /** §4.5: an injection's doses → merged into the one hand-typed entry, a match question, or a new entry. (entry, question) */
    private fun place(w: Work, s: String, items: List<Item>, now: Long): Pair<String?, String?> {
        // SPEC §1 caps `pd` at 20 doses: a longer session (a cartridge of primes, a stuck button) is split in time order
        if (items.size > rules.maxDoses) return items.sortedBy { it.d.t }.chunked(rules.maxDoses).map { place(w, s, it, now) }.last()
        val inj = items.filter { !it.p }
        if (inj.isEmpty()) { items.forEach { w.final(s, it.d.key, true, null) }; return null to null }
        val t = inj.minOf { it.d.t }; val a = r1(inj.sumOf { it.d.a })
        val (cands, unique) = candidates(w.entries.values, w.pen(s).kind, t, a, w.claimed)
        return when {
            unique != null -> merge(w, s, items, unique) to null
            cands.isEmpty() -> create(w, s, items) to null
            else -> {
                cands.forEach { w.claimed += it.id }
                val q = Question(newId(), s, MATCH_Q, emptyList(), cands.map { it.id }, now)
                w.asked += q.id
                ask(w, s, q, items)
                null to q.id
            }
        }
    }

    /**
     * The pen's doses replace hand-typed entry [c]. The wearer's own entry (`o` phone / mac) keeps its `id`, note and
     * origin; a writer's (a caregiver's, `o` follower) is tombstoned and a new pen entry of the wearer's takes its note
     * (PROTOCOL review 2026-10-23: a writer may edit its own entry later and could drop the pen keys).
     */
    private fun merge(w: Work, s: String, items: List<Item>, c: PenEntry): String {
        w.claimed += c.id
        if (c.o != origin && c.o != "mac") {
            w.write(s, c.copy(del = true), user = true)
            val id = newId()
            items.forEach { w.final(s, it.d.key, it.p, id) }
            w.pens[s] = w.pen(s).let { it.copy(replaced = it.replaced + (id to c)) }
            w.write(s, w.build(s, id, null).copy(n = c.n)); w.merged += id
            return id
        }
        items.forEach { w.final(s, it.d.key, it.p, c.id) }
        w.pens[s] = w.pen(s).let { it.copy(replaced = it.replaced + (c.id to c)) }
        w.write(s, w.build(s, c.id, c)); w.merged += c.id
        return c.id
    }

    private fun create(w: Work, s: String, items: List<Item>): String {
        val id = newId()
        items.forEach { w.final(s, it.d.key, it.p, id) }
        w.write(s, w.build(s, id, null)); w.created += id
        return id
    }

    /** Add [items] to question [q] (stored or new) and park them on it. */
    private fun ask(w: Work, s: String, q: Question, items: List<Item>) {
        items.forEach { w.setDose(s, it.d.key, w.seen(s, it.d.key).copy(role = QUESTION, e = null, q = q.id, p = it.p)) }
        w.putQ(q.copy(keys = (q.keys + items.map { it.d.key }).distinct().sortedBy { w.seen(s, it).t }))
    }

    /** A `doses` question for [fresh] (extending an open one about the same cluster). */
    private fun dosesQuestion(w: Work, s: String, cluster: List<Item>, fresh: List<Item>, now: Long) {
        val keys = cluster.map { it.d.key }.toSet()
        val open = w.qs.firstOrNull { it.serial == s && it.type == DOSES && it.answer == null && it.keys.any { k -> k in keys } }
        val q = open ?: Question(newId(), s, DOSES, emptyList(), emptyList(), now).also { w.asked += it.id }
        ask(w, s, q, fresh)
    }
}
