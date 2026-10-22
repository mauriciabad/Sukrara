// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.novopen.engine

import kotlin.math.abs
import kotlinx.serialization.Serializable

/**
 * The pen dose engine (spikes/novopen-android/DESIGN.md §3–§4): a pen read + the pen ledger + the treatment log →
 * entry writes + questions. Pure Kotlin, no clock, no I/O: every time comes in as an argument, ids from [newId].
 * The same file is Sukrara's `pen/PenEngine.kt` (package renamed); `scenarios.json` drives both.
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

/** A dose as read from the pen: the pen's raw relative time (s), its absolute time (epoch s), units. */
data class ReadDose(val rel: Long, val t: Long, val a: Double)

/** One NFC read: serial, scan time (epoch s), the pen's relative clock at that moment, every dose it holds. */
data class PenScan(val serial: String, val at: Long, val penNow: Long, val doses: List<ReadDose>, val model: String = "")

/** What the engine knows about one dose key ([PenEngine.key]). */
@Serializable
data class Seen(
    val t: Long, val a: Double, val role: String,
    val e: String? = null,      // the entry it became (injected / primed)
    val q: String? = null,      // the question it waits on
    val p: Boolean = false,     // priming (final roles) or the current guess (question)
)

/** The ledger of one pen (`pens.json`, phone only, never synced). */
@Serializable
data class Pen(
    val serial: String, val model: String = "", val kind: String = "rapid",
    val since: Long,                       // nothing older is imported
    val first: Long,                       // first scan (epoch s)
    val lastScan: Long = 0, val lastPenNow: Long = 0,
    val setup: Boolean = false,            // the first-scan setup was seen
    val older: Int = 0,                    // doses before `since` at the first scan
    val doses: Map<String, Seen> = emptyMap(),
    val wrote: Map<String, Long> = emptyMap(),   // entry id → the `u` the engine last wrote
)

/** A question to the user: what some doses were (`doses`), or whether an injection is a hand-typed entry (`match`). */
@Serializable
data class Question(
    val id: String, val serial: String, val type: String, val keys: List<String>,
    val cands: List<String> = emptyList(), val at: Long, val answer: Answer? = null,
)

@Serializable
data class Answer(
    val at: Long,
    val entry: String? = null,      // the entry it made or merged into
    val before: PenEntry? = null,   // the hand-typed entry as it was before a merge (to reopen)
    val next: String? = null,       // the match question an answer led to
)

@Serializable
data class Ledger(val pens: Map<String, Pen> = emptyMap(), val questions: List<Question> = emptyList()) {
    fun open(): List<Question> = questions.filter { it.answer == null }
}

data class Report(
    val serial: String?, val first: Boolean = false,
    val created: List<String> = emptyList(), val updated: List<String> = emptyList(),
    val merged: List<String> = emptyList(), val asked: List<String> = emptyList(),
    val pending: Int = 0, val older: Int = 0, val future: Int = 0, val clockMatched: Int = 0,
    val logged: Double = 0.0, val primed: Double = 0.0,
    val restored: Int = 0,      // doses recognised from pen entries already in the log (the ledger was lost)
)

/** What [PenEngine.undo] needs to take one read back: the doses it decided and the entries as they were before. */
data class UndoRecord(val serial: String, val keys: List<String>, val before: Map<String, PenEntry?>)

data class Outcome(val ledger: Ledger, val writes: List<PenEntry>, val report: Report, val undo: UndoRecord? = null)

/**
 * Every threshold and window of DESIGN §3–§4 (seconds, units), in one place so the rules can be tuned without touching
 * the engine. [primeMax]: a dose up to this size can be priming (inclusive: Novo's flow check is 2 u, repeated after a
 * cartridge change); a cluster with a larger dose is an injection.
 */
data class PenRules(
    val primeMax: Double = 2.0,
    val cluster: Long = 600, val primeBefore: Long = 1800, val topUpAfter: Long = 3600, val pending: Long = 1800,
    val match: Long = 2700, val matchUnits: Double = 1.0, val matchShare: Double = 0.25,
    val future: Long = 120, val resetMatch: Long = 120,
)

class PenEngine(
    val rules: PenRules = PenRules(),
    val origin: String = "phone",
    val newId: () -> String = { java.util.UUID.randomUUID().toString() },
    val log: (String) -> Unit = {},
) {
    companion object {
        /** Dose roles in the ledger. [IGNORED] = the user chose not to log it; [REPLACED] = superseded after a clock reset. */
        const val BEFORE = "before-since"; const val NEW = "new"; const val PENDING_ROLE = "pending"
        const val QUESTION = "question"; const val INJECTED = "injected"; const val PRIMED = "primed"
        const val IGNORED = "ignored"; const val REPLACED = "replaced"
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
         * priming (priming alone never makes an entry: the caller offers to delete it instead). `u` is the caller's.
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
    private val CLUSTER get() = rules.cluster
    private val PRIME_BEFORE get() = rules.primeBefore
    private val TOPUP_AFTER get() = rules.topUpAfter
    private val PENDING get() = rules.pending
    private val MATCH get() = rules.match

    private data class D(val key: String, val t: Long, val a: Double)
    private data class Item(val d: D, val p: Boolean)
    private data class Placed(val entry: String? = null, val before: PenEntry? = null, val question: String? = null)

    private inner class Work(l: Ledger, entries: List<PenEntry>, val nowMs: Long) {
        val pens = l.pens.toMutableMap()
        val qs = l.questions.toMutableList()
        val entries = LinkedHashMap<String, PenEntry>().apply { entries.forEach { put(it.id, it) } }
        val writes = LinkedHashMap<String, PenEntry>()
        val claimed = mutableSetOf<String>()
        val created = mutableListOf<String>(); val updated = mutableListOf<String>()
        val merged = mutableListOf<String>(); val asked = mutableListOf<String>()
        var pending = 0; var older = 0; var future = 0; var clockMatched = 0
        var logged = 0.0; var primed = 0.0

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

        /** [user]: a change the user asked for (a dose-role correction) — it doesn't count as the engine's write (§4.8). */
        fun write(s: String, e: PenEntry, user: Boolean = false) {
            val u = maxOf(nowMs, (entries[e.id]?.u ?: 0) + 1)
            val w = e.copy(u = u)
            if (!before.containsKey(e.id)) before[e.id] = entries[e.id]
            entries[e.id] = w; writes[e.id] = w
            if (!user) pens[s] = pen(s).let { it.copy(wrote = it.wrote + (e.id to u)) }
        }
        val before = LinkedHashMap<String, PenEntry?>()

        /** The entry [id] rebuilt from the ledger's doses for it, on top of [base] (or new). */
        fun build(s: String, id: String, base: PenEntry?): PenEntry {
            val ds = pen(s).doses.values.filter { it.e == id && (it.role == INJECTED || it.role == PRIMED) }.sortedBy { it.t }
            val inj = ds.filter { !it.p }
            val pd = ds.map { PdDose(it.t, r1(it.a), it.p) }
            val t = inj.minOf { it.t }; val a = r1(inj.sumOf { it.a }); val pr = r1(ds.filter { it.p }.sumOf { it.a })
            return base?.copy(t = t, a = a, pr = pr, pen = s, pd = pd)
                ?: PenEntry(id = id, k = pen(s).kind, a = a, t = t, u = 0, o = origin, pr = pr, pen = s, pd = pd)
        }

        /**
         * §4.7/4.8: the user changed [e] after the engine wrote it — its `u` isn't the one written last, or its `pd`
         * roles aren't the ledger's (a dose-role correction, maybe on the other device).
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
                val keys = pens[serial]?.doses?.filter { (k, v) -> v.role != BEFORE && v.role != REPLACED && was[k]?.let { it.role != v.role || it.e != v.e } ?: true }?.keys
                UndoRecord(serial, keys?.toList() ?: emptyList(), before.toMap())
            } else null
            return Outcome(
                Ledger(pens.toMap(), qs.toList()), writes.values.toList(),
                Report(serial, first, created.toList(), updated.toList(), merged.toList(), asked.toList(),
                    pending, older, future, clockMatched, r1(logged), r1(primed), restored),
                undo,
            )
        }
        var restored = 0
    }

    // ---------------------------------------------------------------- entry points

    /** §3–§4: one NFC read of a pen. */
    fun scan(l: Ledger, s: PenScan, entries: List<PenEntry>, nowMs: Long): Outcome {
        val w = Work(l, entries, nowMs)
        val old = w.pens[s.serial]
        val first = old == null
        // A lost ledger (reinstall, new phone): the pen entries already in the synced log say what was read before.
        val known = if (first) entries.filter { it.pen == s.serial && !it.pd.isNullOrEmpty() } else emptyList()
        val start = known.flatMap { it.pd!! }.maxOfOrNull { it.t } ?: s.at
        var pen = old ?: Pen(serial = s.serial, model = s.model, since = start, first = s.at,
            kind = known.maxByOrNull { it.t }?.k ?: "rapid", wrote = known.associate { it.id to it.u })
        val logged = known.flatMap { e -> e.pd!!.map { e to it } }.toMutableList()
        val reset = old != null && s.penNow < old.lastPenNow
        if (reset) log("pen ${s.serial}: clock went back (${old!!.lastPenNow} → ${s.penNow}); matching known doses by time + units")
        val doses = pen.doses.toMutableMap()
        val read = s.doses.filter { tenths(it.a) > 0 }.distinctBy { key(it.rel, it.a) }.sortedBy { it.t }
        val readKeys = read.map { key(it.rel, it.a) }.toSet()
        val used = mutableSetOf<String>()
        val renamed = mutableMapOf<String, String>()
        for (d in read) {
            val k = key(d.rel, d.a)
            if (k in doses) continue
            if (d.t > s.at + rules.future) { w.future++; log("pen ${s.serial}: dose ${d.a} u at ${d.t} is after the scan (${s.at}); dropped"); continue }
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
                doses[k] = Seen(d.t, r1(d.a), if (m.second.p) PRIMED else INJECTED, e = m.first.id, p = m.second.p)
                w.restored++
                continue
            }
            val before = d.t < pen.since
            if (before) w.older++
            doses[k] = Seen(d.t, r1(d.a), if (before) BEFORE else NEW)
        }
        pen = pen.copy(
            model = s.model.ifEmpty { pen.model }, lastScan = s.at, lastPenNow = s.penNow, doses = doses,
            older = if (first) w.older else pen.older,
        )
        w.pens[s.serial] = pen
        if (renamed.isNotEmpty()) w.qs.replaceAll { q -> q.copy(keys = q.keys.map { renamed[it] ?: it }) }
        settle(w, s.serial, s.at)
        return w.outcome(s.serial, first, l)
    }

    /** Re-run the rules without a read (pending clusters that timed out): at app start and at [nextWake]. */
    fun refresh(l: Ledger, entries: List<PenEntry>, now: Long, nowMs: Long): Outcome {
        val w = Work(l, entries, nowMs)
        l.pens.keys.forEach { settle(w, it, now) }
        return w.outcome(null)
    }

    /** Answer a `doses` question: [primed] keys were priming, the rest injected. */
    fun answerDoses(l: Ledger, qid: String, primed: Set<String>, entries: List<PenEntry>, now: Long, nowMs: Long): Outcome {
        val w = Work(l, entries, nowMs)
        val q = w.q(qid)?.takeIf { it.type == DOSES && it.answer == null } ?: return w.outcome(null)
        val placed = place(w, q.serial, q.keys.map { Item(D(it, w.seen(q.serial, it).t, w.seen(q.serial, it).a), it in primed) }, now)
        w.putQ(q.copy(answer = Answer(now, placed.entry, placed.before, placed.question)))
        return w.outcome(q.serial)
    }

    /** Answer a `match` question: [same] = the hand-typed entry it is, or null for a different dose (logged apart). */
    fun answerMatch(l: Ledger, qid: String, same: String?, entries: List<PenEntry>, now: Long, nowMs: Long): Outcome {
        val w = Work(l, entries, nowMs)
        val q = w.q(qid)?.takeIf { it.type == MATCH_Q && it.answer == null } ?: return w.outcome(null)
        val items = q.keys.map { k -> w.seen(q.serial, k).let { Item(D(k, it.t, it.a), it.p) } }
        val c = same?.let { w.entries[it] }?.takeIf { !it.del && it.pen == null }
        val placed = if (c != null) merge(w, q.serial, items, c) else create(w, q.serial, items)
        w.putQ(q.copy(answer = Answer(now, placed.entry, placed.before)))
        return w.outcome(q.serial)
    }

    /**
     * One dose's role set by the user, from the pen's dose history or a pen entry's *Edit doses* (§4.8): [role] is
     * [INJECTED], [PRIMED] or [IGNORED] (don't log). A dose in an entry changes that entry (a user edit: later doses
     * that would join it become a question); an entry left with no injected dose is deleted. A dose outside any entry
     * (before the start, priming, not logged, waiting on a question) logged as injected goes through §4.5 on its own.
     */
    fun setDose(l: Ledger, serial: String, key: String, role: String, entries: List<PenEntry>, now: Long, nowMs: Long): Outcome {
        require(role == INJECTED || role == PRIMED || role == IGNORED)
        val w = Work(l, entries, nowMs)
        val cur = w.pens[serial]?.doses?.get(key) ?: return w.outcome(serial)
        val e = cur.e?.let { w.entries[it] }
        if (e != null && !e.del) {
            // adopt a correction made on the other device before changing one more dose
            e.pd?.forEach { d -> w.pen(serial).doses.filter { (_, v) -> v.e == e.id && v.t == d.t && tenths(v.a) == tenths(d.a) }
                .forEach { (k, v) -> w.setDose(serial, k, v.copy(role = if (d.p) PRIMED else INJECTED, p = d.p)) } }
            if (role == IGNORED) w.setDose(serial, key, cur.copy(role = IGNORED, e = null, q = null, p = false))
            else w.setDose(serial, key, cur.copy(role = role, p = role == PRIMED))
            val left = w.pen(serial).doses.filter { it.value.e == e.id }
            if (left.values.none { it.role == INJECTED }) {
                left.forEach { (k, v) -> w.setDose(serial, k, v.copy(role = PRIMED, e = null, p = true)) }
                w.write(serial, e.copy(del = true), user = true)
            } else w.write(serial, w.build(serial, e.id, e), user = true)
        } else {
            w.setDose(serial, key, cur.copy(role = NEW, e = null, q = null))
            when (role) {
                INJECTED -> place(w, serial, listOf(Item(D(key, cur.t, cur.a), false)), now)
                PRIMED -> w.final(serial, key, true, null)
                else -> w.setDose(serial, key, cur.copy(role = IGNORED, e = null, q = null, p = false))
            }
            cleanQuestions(w, serial)
        }
        return w.outcome(serial)
    }

    /** §4.3: "Don't log" for a whole question; reversible per dose through [setDose]. */
    fun dismiss(l: Ledger, qid: String, nowMs: Long): Outcome {
        val w = Work(l, emptyList(), nowMs)
        val q = w.q(qid)?.takeIf { it.answer == null } ?: return w.outcome(null)
        q.keys.forEach { k -> w.setDose(q.serial, k, w.seen(q.serial, k).copy(role = IGNORED, e = null, q = null, p = false)) }
        w.qs.removeAll { it.id == qid }
        return w.outcome(q.serial)
    }

    /**
     * Take one read back (*Undo* on the read's notification): the entries it wrote return to how they were (new ones
     * are deleted, merged hand-typed entries come back), its doses become "not logged" — changeable in the history.
     */
    fun undo(l: Ledger, r: UndoRecord, entries: List<PenEntry>, nowMs: Long): Outcome {
        val w = Work(l, entries, nowMs)
        if (w.pens[r.serial] == null) return w.outcome(r.serial)
        r.before.forEach { (id, was) ->
            val cur = w.entries[id] ?: return@forEach
            if (was == null) { if (!cur.del) w.write(r.serial, cur.copy(del = true)) }
            else w.write(r.serial, cur.copy(k = was.k, a = was.a, t = was.t, n = was.n, del = was.del, pr = was.pr, pen = was.pen, pd = was.pd), user = was.pen == null)
        }
        r.keys.forEach { k -> w.pen(r.serial).doses[k]?.let { w.setDose(r.serial, k, it.copy(role = IGNORED, e = null, q = null, p = false)) } }
        cleanQuestions(w, r.serial)
        return w.outcome(r.serial)
    }

    fun forget(l: Ledger, serial: String) = Ledger(l.pens - serial, l.questions.filter { it.serial != serial })
    fun setKind(l: Ledger, serial: String, kind: String) = l.pens[serial]?.let { Ledger(l.pens + (serial to it.copy(kind = kind)), l.questions) } ?: l
    fun setupSeen(l: Ledger, serial: String) = l.pens[serial]?.let { Ledger(l.pens + (serial to it.copy(setup = true)), l.questions) } ?: l

    // ---------------------------------------------------------------- the rules

    private fun settle(w: Work, s: String, now: Long, allowPending: Boolean = true) {
        val pen = w.pen(s)
        val ds = pen.doses.filter { it.value.role != BEFORE && it.value.role != REPLACED && it.value.role != IGNORED && it.value.t >= pen.since }
            .map { D(it.key, it.value.t, it.value.a) }.sortedWith(compareBy({ it.t }, { it.key }))
        if (ds.isEmpty()) return
        // §4.1 clusters
        val cl = mutableListOf<MutableList<D>>()
        for (d in ds) if (cl.isEmpty() || d.t - cl.last().last().t > CLUSTER) cl += mutableListOf(d) else cl.last() += d
        val big = cl.map { c -> c.any { isBig(it.a) } }
        // §4.2 injection clusters: before the first big dose priming, from it on injected
        val sessions = sortedMapOf<Int, MutableList<Item>>()
        cl.forEachIndexed { i, c ->
            if (big[i]) { val f = c.indexOfFirst { isBig(it.a) }; sessions[i] = c.mapIndexed { j, d -> Item(d, j < f) }.toMutableList() }
        }
        // §4.3 small clusters
        val pendingCl = mutableListOf<Int>(); val questionCl = mutableListOf<Int>()
        cl.forEachIndexed { i, c ->
            if (big[i]) return@forEachIndexed
            val nx = (i + 1 until cl.size).firstOrNull { big[it] }
            val pv = (i - 1 downTo 0).firstOrNull { big[it] }
            when {
                nx != null && cl[nx].first().t - c.last().t <= PRIME_BEFORE -> sessions.getValue(nx) += c.map { Item(it, true) }
                // the newest cluster may still be followed by an injection (priming wins over a top-up): wait
                allowPending && i == cl.lastIndex && now - c.last().t < PENDING -> pendingCl += i
                pv != null && c.first().t - cl[pv].last().t <= TOPUP_AFTER -> sessions.getValue(pv) += c.map { Item(it, false) }
                else -> questionCl += i
            }
        }
        for ((_, items0) in sessions) {
            val items = items0.sortedWith(compareBy({ it.d.t }, { it.d.key }))
            val fresh = items.filter { fresh(w, s, it) }
            if (fresh.isEmpty()) continue
            // an open match question for this session: the new doses wait with it
            val openMatch = items.mapNotNull { w.q(w.seen(s, it.d.key).q) }.firstOrNull { it.type == MATCH_Q && it.answer == null }
            if (openMatch != null) { ask(w, s, openMatch, fresh); continue }
            val target = items.map { w.seen(s, it.d.key) }.firstOrNull { (it.role == INJECTED || it.role == PRIMED) && it.e != null }?.e
            if (target != null) {
                val e = w.entries[target]
                if (e == null || e.del || w.edited(s, e)) { dosesQuestion(w, s, items, fresh, now); continue }
                fresh.forEach { w.final(s, it.d.key, it.p, target) }
                w.write(s, w.build(s, target, e)); w.updated += target
                continue
            }
            place(w, s, fresh, now)
        }
        for (i in pendingCl) cl[i].filter { w.seen(s, it.key).role == NEW || w.seen(s, it.key).role == PENDING_ROLE }.forEach {
            w.setDose(s, it.key, w.seen(s, it.key).copy(role = PENDING_ROLE)); w.pending++
        }
        for (i in questionCl) {
            val items = cl[i].map { Item(it, true) }
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

    private fun isBig(a: Double) = tenths(a) > tenths(rules.primeMax)

    /** Not decided yet: new, pending, or (folded into an injection as its priming) an unanswered doses question. */
    private fun fresh(w: Work, s: String, it: Item): Boolean {
        val v = w.seen(s, it.d.key)
        return v.role == NEW || v.role == PENDING_ROLE ||
            (it.p && v.role == QUESTION && w.q(v.q)?.let { q -> q.type == DOSES && q.answer == null } == true)
    }

    /** §4.5: a new injection (its new doses) → merge into the one hand-typed entry, a match question, or a new entry. */
    private fun place(w: Work, s: String, items: List<Item>, now: Long): Placed {
        val inj = items.filter { !it.p }
        if (inj.isEmpty()) { items.forEach { w.final(s, it.d.key, true, null) }; return Placed() }
        val t = inj.minOf { it.d.t }; val a = r1(inj.sumOf { it.d.a })
        val kind = w.pen(s).kind
        val cands = w.entries.values.filter { !it.del && it.k == kind && it.pen == null && it.id !in w.claimed && abs(it.t - t) <= MATCH }
            .sortedBy { abs(it.t - t) }
        return when {
            cands.size == 1 && abs(cands[0].a - a) <= maxOf(rules.matchUnits, rules.matchShare * a) + 1e-9 -> merge(w, s, items, cands[0])
            cands.isEmpty() -> create(w, s, items)
            else -> {
                cands.forEach { w.claimed += it.id }
                val q = Question(w.newQ(), s, MATCH_Q, emptyList(), cands.map { it.id }, now)
                w.asked += q.id
                ask(w, s, q, items)
                Placed(question = q.id)
            }
        }
    }

    private fun Work.newQ() = newId()

    private fun merge(w: Work, s: String, items: List<Item>, c: PenEntry): Placed {
        w.claimed += c.id
        items.forEach { w.final(s, it.d.key, it.p, c.id) }
        w.write(s, w.build(s, c.id, c)); w.merged += c.id
        return Placed(entry = c.id, before = c)
    }

    private fun create(w: Work, s: String, items: List<Item>): Placed {
        val id = newId()
        items.forEach { w.final(s, it.d.key, it.p, id) }
        w.write(s, w.build(s, id, null)); w.created += id
        return Placed(entry = id)
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
        val q = open ?: Question(w.newQ(), s, DOSES, emptyList(), emptyList(), now).also { w.asked += it.id }
        ask(w, s, q, fresh)
    }
}
