// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.pen

import android.content.Context
import android.content.Intent
import app.mauri.sukrara.R
import app.mauri.sukrara.graph.Clock
import app.mauri.sukrara.treatments.Treat
import app.mauri.sukrara.treatments.Treatment
import java.util.Locale

/**
 * Every pen sentence the read card, the notifications and the sheets show (PRODUCT §2/§4: amounts `8.5u`, dot, lowercase
 * `u`, no space; times in the user's clock format). The strings are resources (en + es); this only composes them.
 */
class PenWords(private val ctx: Context) {
    fun u(a: Double) = number(a) + "u"
    fun time(t: Long) = Clock.time(t)
    fun s(id: Int, vararg a: Any) = ctx.getString(id, *a)
    fun kind(k: String) = s(if (k == "long") R.string.pen_kind_long else R.string.pen_kind_rapid)
    fun kindLower(k: String) = s(if (k == "long") R.string.pen_kind_long_lower else R.string.pen_kind_rapid_lower)

    private fun dose(q: Question, k: String) = Pens.ledger.value.pens[q.serial]?.doses?.get(k)

    /** "1u", "1u and 1.5u", "1u, 0.5u and 1.5u". */
    fun list(amounts: List<String>): String = when (amounts.size) {
        0 -> ""; 1 -> amounts[0]
        else -> s(R.string.pen_and, amounts.dropLast(1).joinToString(", "), amounts.last())
    }

    fun doses(q: Question) = list(q.keys.mapNotNull { dose(q, it)?.let { d -> u(d.a) } })
    fun time(q: Question) = q.keys.mapNotNull { dose(q, it)?.t }.minOrNull()?.let { time(it) } ?: ""

    /** The suggested injected units of a doses question ("1.5u"), null when the suggestion is all priming. */
    fun suggestion(q: Question): String? {
        val inj = q.keys.mapNotNull { dose(q, it) }.filter { !it.p }
        return if (inj.isEmpty()) null else u(PenEngine.r1(inj.sumOf { it.a }))
    }

    /** "Pen 8.5u at 13:12 · yours 5u at 13:05" (the nearest candidate). */
    fun matchLine(q: Question): String {
        val ds = q.keys.mapNotNull { dose(q, it) }
        val inj = ds.filter { !it.p }
        val c = q.cands.firstNotNullOfOrNull { Treat.store.entry(it)?.second }
        val t = inj.minOfOrNull { it.t } ?: 0
        val n = q.cands.count { Treat.store.entry(it)?.second?.let { e -> !e.del && e.pen == null } == true }
        return when {
            c == null -> s(R.string.pen_m_card, u(PenEngine.r1(inj.sumOf { it.a })), time(t))
            // several candidates: say how many, not one of them (QA review 10)
            n > 1 -> s(R.string.pen_m_card, u(PenEngine.r1(inj.sumOf { it.a })), time(t)) + " · " + ctx.resources.getQuantityString(R.plurals.pen_m_nearby, n, n)
            else -> s(R.string.pen_q_same_line, u(PenEngine.r1(inj.sumOf { it.a })), time(t), u(c.a), time(c.t))
        }
    }

    /** "Rapid · 13:12 · 1.5u primed" (no priming part when 0). */
    fun entryLine(e: Treatment) = listOfNotNull(kind(e.k), time(e.t), e.pr?.takeIf { it > 0 }?.let { s(R.string.pen_primed, u(it)) }).joinToString(" · ")

    /** The card of one read (PRODUCT §4.1): title, lines, whether it is an error. Pills are the caller's. */
    data class Card(val title: String, val lines: List<String>, val error: Boolean = false)

    fun card(st: Pens.ReadState): Card {
        val r = st.report
        val pen = st.serial?.let { Pens.ledger.value.pens[it] }
        when {
            st.stage == "reading" -> return Card(s(R.string.pen_read_reading), listOf(s(if (st.firstPen) R.string.pen_read_first_slow else R.string.pen_read_keep_still)))
            st.stage == "failed" -> return Card(s(R.string.pen_read_failed),
                listOfNotNull(s(R.string.pen_read_failed_text), if (st.tip) s(R.string.pen_read_failed_tip) else null), error = true)
            st.stage == "notusable" -> return Card(s(R.string.pen_read_notusable), listOf(s(R.string.pen_read_notusable_text)))
            st.stage == "ask" -> return Card(s(R.string.pen_ask_title), listOf(st.read?.let { PenProfiles.identify(it).name } ?: PenProfiles.ECHO_PLUS.name))
            st.stage == "check" -> return Card(s(R.string.pen_untested_added),
                listOf(s(R.string.pen_untested_added_line, st.serial?.let { Pens.displayName(it) } ?: PenProfiles.NOVOPEN_6.name)))
            st.undone -> return Card(s(R.string.pen_undone), listOf(s(R.string.pen_undone_text)))
        }
        r ?: return Card(s(R.string.pen_read_nothing), emptyList())
        val ids = (r.created + r.merged + r.updated).distinct()
        val entries = ids.mapNotNull { Treat.store.entry(it)?.second }.filter { !it.del }.sortedBy { it.t }
        val asked = r.asked.mapNotNull { id -> Pens.ledger.value.questions.firstOrNull { it.id == id && it.answer == null } }
        val askLine = asked.firstOrNull()?.let { q -> if (q.type == PenEngine.DOSES) s(R.string.pen_doses_at, doses(q), time(q)) else matchLine(q) }
        if (r.first && !r.returning && entries.isEmpty()) {
            val name = st.serial?.let { Pens.displayName(it) } ?: PenProfiles.ECHO_PLUS.name
            val parts = listOfNotNull(name, kindLower(pen?.kind ?: "rapid"),
                r.older.takeIf { it > 0 }?.let { ctx.resources.getQuantityString(R.plurals.pen_older_left_out, it, it) })
            val matches = r.offer?.let { Pens.offerMatch(it) }?.let { s(R.string.pen_matches_short, u(it.a)) }
            return Card(s(R.string.pen_new_added), listOfNotNull(parts.joinToString(" · "), matches))
        }
        if (r.clockChanged && entries.isEmpty()) return Card(s(R.string.pen_read_clock), listOf(s(R.string.pen_read_clock_text)))
        val merged = r.merged.size
        if (entries.isNotEmpty()) {
            val lines = mutableListOf<String>()
            val title: String
            if (entries.size == 1) {
                val e = entries[0]
                if (e.id in r.updated && e.id !in r.created && e.id !in r.merged) {
                    title = s(R.string.pen_read_grew, u(e.a))
                    lines += listOf(kind(e.k), time(e.t), s(R.string.pen_added, u(r.logged))).joinToString(" · ")
                } else {
                    title = s(R.string.pen_read_logged, u(e.a))
                    lines += entryLine(e)
                    pen?.replaced?.get(e.id)?.takeIf { e.id in r.merged }?.let { lines += replacedLine(e.id, it, now = true) }
                }
            } else {
                title = ctx.resources.getQuantityString(R.plurals.pen_read_several, entries.size, entries.size)
                val replaced = if (merged > 0) ctx.resources.getQuantityString(R.plurals.pen_read_replaced_several, merged, merged) else null
                if (r.bigSince != null) lines += listOfNotNull(s(R.string.pen_read_big_line, Clock.dayLabel(r.bigSince), replaced ?: "")
                    .removeSuffix(" · "))
                else {
                    lines += s(R.string.pen_read_since, u(PenEngine.r1(entries.sumOf { it.a })), Clock.dayTime(entries.first().t))
                    replaced?.let { lines += it }
                }
            }
            askLine?.let { lines += it }
            return Card(title, lines)
        }
        asked.firstOrNull()?.let { q ->
            return Card(s(if (q.type == PenEngine.DOSES) R.string.pen_q_doses_title else R.string.pen_q_same_title), listOfNotNull(askLine))
        }
        if (r.pending > 0) return Card(s(R.string.pen_read_pending), listOf(s(R.string.pen_read_pending_text)))
        val last = st.serial?.let { s -> Treat.store.all().filter { !it.del && it.pen == s }.maxByOrNull { it.t } }
        return Card(s(R.string.pen_read_nothing), listOfNotNull(last?.let { s(R.string.pen_read_last, u(it.a), time(it.t)) }))
    }

    /**
     * The line saying which hand-typed entry a pen injection replaced: *your* entry (the wearer's), a follower's by name,
     * or "the" entry. [now]: the read card (*Replaced …at 13:05*); else the history wording (*Replaced your 8u entry*).
     */
    fun replacedLine(id: String, was: PenEntry, now: Boolean): String {
        val writer = was.o != "phone" && was.o != "mac"
        val name = Pens.writerNames.value[id]
        return when {
            writer && name != null -> s(R.string.pen_read_replaced_by, name, u(was.a), time(was.t))
            writer -> s(R.string.pen_read_replaced_other, u(was.a), time(was.t))
            now -> s(R.string.pen_read_replaced, u(was.a), time(was.t))
            else -> s(R.string.pen_h_merged, u(was.a))
        }
    }

    /** What TalkBack says for a card: title and lines, amounts as words ("8,5 unidades", not "8.5u"). */
    fun spoken(text: String): String = Regex("(\\d+(?:\\.\\d)?)u\\b").replace(text) { m ->
        val v = m.groupValues[1].toDouble()
        val shown = if (java.util.Locale.getDefault().language == "es") m.groupValues[1].replace('.', ',') else m.groupValues[1]
        ctx.resources.getQuantityString(R.plurals.pen_units_spoken, if (v == 1.0) 1 else 2, shown)
    }

    companion object {
        fun number(a: Double) = if (a % 1.0 == 0.0) "${a.toLong()}" else String.format(Locale.ROOT, "%.1f", a)

        /**
         * *Add treatment* (PRODUCT §4.1): the quick treatment dialog over the current app at the pen injection's time, the
         * cursor on the other insulin (Long after a rapid read, Rapid after a long one).
         */
        fun addTreatment(ctx: Context, tr: Treatment): Intent = Intent(ctx, app.mauri.sukrara.ui.QuickTreatmentActivity::class.java)
            .putExtra("at", tr.t).putExtra("focus", if (tr.k == "long") "rapid" else "long")
    }
}
