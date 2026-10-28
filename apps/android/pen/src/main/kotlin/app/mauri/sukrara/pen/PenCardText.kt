// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.pen

import android.content.Context
import app.mauri.sukrara.pen.engine.Ledger
import app.mauri.sukrara.pen.engine.PenEngine
import app.mauri.sukrara.pen.engine.PenEntry
import app.mauri.sukrara.pen.engine.Question
import java.util.Locale
import app.mauri.sukrara.graph.Clock as ClockText

/** What the read card (and the notifications) offer. */
sealed interface PenAction {
    data object Undo : PenAction

    data object TurnOn : PenAction

    data object No : PenAction

    data object AddTreatment : PenAction

    /** *Check it*: an untested pen's page (its display check). */
    data class OpenPen(
        val serial: String,
    ) : PenAction

    /** *Log 1.5u*: the question's suggestion. [keys] = its doses as shown (a changed question isn't answered). */
    data class Suggest(
        val question: String,
        val keys: List<String>,
        val label: String,
    ) : PenAction

    data class AllPriming(
        val question: String,
        val keys: List<String>,
    ) : PenAction

    /** A same-injection question: [entry] = the hand-typed entry it is, null = different injections. */
    data class Same(
        val question: String,
        val keys: List<String>,
        val entry: String?,
    ) : PenAction
}

/** One card: title, lines, whether it reports an error, and its buttons. */
data class PenCard(
    val title: String,
    val lines: List<String>,
    val error: Boolean = false,
    val actions: List<PenAction> = emptyList(),
)

/**
 * Every pen sentence the read card and the notifications show (amounts `8.5u`: dot, lowercase `u`, no space; times in
 * the phone's clock format). The strings are resources (en + es); this only composes them.
 */
class PenCardText(
    private val ctx: Context,
) {
    fun u(a: Double) = number(a) + "u"

    fun time(t: Long): String = ClockText.time(t)

    private fun s(
        id: Int,
        vararg a: Any,
    ) = ctx.getString(id, *a)

    private fun p(
        id: Int,
        n: Int,
    ) = ctx.resources.getQuantityString(id, n, n)

    fun kind(k: String) = s(if (k == "long") R.string.pen_kind_long else R.string.pen_kind_rapid)

    private fun kindLower(k: String) = s(if (k == "long") R.string.pen_kind_long_lower else R.string.pen_kind_rapid_lower)

    /** "1u", "1u and 1.5u", "1u, 0.5u and 1.5u". */
    fun list(amounts: List<String>): String =
        when (amounts.size) {
            0 -> ""
            1 -> amounts[0]
            else -> s(R.string.pen_and, amounts.dropLast(1).joinToString(", "), amounts.last())
        }

    private fun doses(
        q: Question,
        l: Ledger,
    ) = q.keys.mapNotNull { l.pens[q.serial]?.doses?.get(it) }

    /** The question's line: "1u and 1.5u at 13:02" or "Pen 8.5u at 13:12 · yours 8u at 13:05". */
    fun questionLine(
        q: Question,
        l: Ledger,
        log: List<PenEntry>,
    ): String {
        val ds = doses(q, l)
        val at = ds.minOfOrNull { it.t }?.let { time(it) } ?: ""
        if (q.type == PenEngine.DOSES) return s(R.string.pen_doses_at, list(ds.map { u(it.a) }), at)
        val inj = ds.filter { !it.p }
        val a = u(PenEngine.r1(inj.sumOf { it.a }))
        val t = time(inj.minOfOrNull { it.t } ?: ds.minOfOrNull { it.t } ?: 0)
        val cands = q.cands.mapNotNull { id -> log.firstOrNull { it.id == id && !it.del } }
        return when {
            cands.size == 1 -> s(R.string.pen_q_same_line, a, t, u(cands[0].a), time(cands[0].t))
            cands.size > 1 -> s(R.string.pen_m_card, a, t) + " · " + p(R.plurals.pen_m_nearby, cands.size)
            else -> s(R.string.pen_m_card, a, t)
        }
    }

    /** The answers a question offers on the card and its notification (several candidates: only the app's list). */
    fun questionActions(
        q: Question,
        l: Ledger,
        log: List<PenEntry>,
    ): List<PenAction> {
        if (q.type == PenEngine.DOSES) {
            val inj = doses(q, l).filter { !it.p }
            return listOfNotNull(
                inj.takeIf { it.isNotEmpty() }?.let {
                    PenAction.Suggest(
                        q.id,
                        q.keys,
                        s(
                            R.string.pen_log_amount,
                            u(
                                PenEngine.r1(
                                    it.sumOf { d ->
                                        d.a
                                    },
                                ),
                            ),
                        ),
                    )
                },
                PenAction.AllPriming(q.id, q.keys),
            )
        }
        val cands = q.cands.filter { id -> log.any { it.id == id && !it.del } }
        return if (cands.size == 1) {
            listOf(PenAction.Same(q.id, q.keys, cands[0]), PenAction.Same(q.id, q.keys, null))
        } else {
            listOf(PenAction.Same(q.id, q.keys, null))
        }
    }

    fun questionTitle(q: Question) = s(if (q.type == PenEngine.DOSES) R.string.pen_q_doses_title else R.string.pen_q_same_title)

    /** "Rapid · 13:12 · 1.5u primed" (no priming part when 0). */
    private fun entryLine(e: PenEntry) =
        listOfNotNull(kind(e.k), time(e.t), e.pr?.takeIf { it > 0 }?.let { s(R.string.pen_primed, u(it)) }).joinToString(" · ")

    fun card(st: PenReadState): PenCard {
        val l = st.ledger
        val pen = st.serial?.let { l.pens[it] }
        val name = st.serial?.let { Pens.displayName(it, l.pens) }
        when (st.stage) {
            PenReadState.Stage.Reading -> {
                return PenCard(
                    s(R.string.pen_read_reading),
                    listOf(s(if (st.firstPen) R.string.pen_read_first_slow else R.string.pen_read_keep_still)),
                )
            }

            PenReadState.Stage.Failed -> {
                return PenCard(
                    s(R.string.pen_read_failed),
                    listOfNotNull(s(R.string.pen_read_failed_text), if (st.tip) s(R.string.pen_read_failed_tip) else null),
                    error = true,
                )
            }

            PenReadState.Stage.Ask -> {
                return PenCard(
                    s(R.string.pen_ask_title),
                    listOf(st.read?.let { PenProfiles.identify(it).name } ?: PenProfiles.ECHO_PLUS.name),
                    actions = listOf(PenAction.No, PenAction.TurnOn),
                )
            }

            PenReadState.Stage.Untested -> {
                val model =
                    pen?.model?.let { PenProfiles.byId(it)?.name } ?: st.read?.let { PenProfiles.identify(it).name }
                        ?: PenProfiles.NOVOPEN_6.name
                if (st.notUsable) return PenCard(s(R.string.pen_notusable_title), listOf(s(R.string.pen_notusable_text, model)))
                return PenCard(
                    s(R.string.pen_untested_title),
                    listOf(s(R.string.pen_untested_check_text, model)),
                    actions = listOfNotNull(st.serial?.let { PenAction.OpenPen(it) }),
                )
            }

            PenReadState.Stage.Done -> {
                Unit
            }
        }
        if (st.undone) return PenCard(s(R.string.pen_undone), listOf(s(R.string.pen_undone_text)))
        val r = st.report ?: return PenCard(s(R.string.pen_read_nothing), emptyList())
        val entries = st.entries
        val asked = r.asked.mapNotNull { id -> l.questions.firstOrNull { it.id == id && it.answer == null } }
        val q = asked.firstOrNull()
        val askActions = q?.let { questionActions(it, l, st.log) }.orEmpty()
        val askLine = q?.let { questionLine(it, l, st.log) }
        val undo = if (entries.isNotEmpty()) listOf(PenAction.Undo) else emptyList()
        if (r.first && !r.returning && entries.isEmpty()) {
            val parts =
                listOfNotNull(
                    name,
                    kindLower(pen?.kind ?: "rapid"),
                    r.older.takeIf { it > 0 }?.let { p(R.plurals.pen_older_left_out, it) },
                )
            return PenCard(s(R.string.pen_new_added), listOfNotNull(parts.joinToString(" · "), askLine), actions = askActions)
        }
        if (r.clockChanged && entries.isEmpty()) {
            val held =
                pen
                    ?.doses
                    ?.values
                    ?.filter { it.role == PenEngine.REVIEW }
                    ?.sortedBy { it.t }
                    .orEmpty()
            return PenCard(
                s(R.string.pen_read_clock),
                listOf(
                    if (held.isEmpty()) s(R.string.pen_read_clock_text) else s(R.string.pen_read_clock_held, list(held.map { u(it.a) })),
                ),
            )
        }
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
                    pen?.replaced?.get(e.id)?.takeIf { e.id in r.merged }?.let { was ->
                        lines +=
                            if (was.o == "follower") {
                                s(R.string.pen_read_replaced_other, u(was.a), time(was.t))
                            } else {
                                s(R.string.pen_read_replaced, u(was.a), time(was.t))
                            }
                    }
                }
            } else {
                title = ctx.resources.getQuantityString(R.plurals.pen_read_several, entries.size, entries.size)
                lines += s(R.string.pen_read_since, u(PenEngine.r1(entries.sumOf { it.a })), time(entries.first().t))
                if (r.merged.isNotEmpty()) lines += p(R.plurals.pen_read_replaced_several, r.merged.size)
            }
            askLine?.let { lines += it }
            return PenCard(title, lines, actions = undo + askActions + PenAction.AddTreatment)
        }
        if (q != null) return PenCard(questionTitle(q), listOfNotNull(askLine), actions = askActions)
        if (r.pending > 0) return PenCard(s(R.string.pen_read_pending), listOf(s(R.string.pen_read_pending_text)))
        return PenCard(s(R.string.pen_read_nothing), listOfNotNull(st.last?.let { s(R.string.pen_read_last, u(it.a), time(it.t)) }))
    }

    /** What TalkBack says for a card line: amounts as words ("8.5 units"), not "8.5u". */
    fun spoken(text: String): String =
        Regex("(\\d+(?:\\.\\d)?)u\\b").replace(text) { m ->
            val v = m.groupValues[1].toDouble()
            val shown = if (Locale.getDefault().language == "es") m.groupValues[1].replace('.', ',') else m.groupValues[1]
            ctx.resources.getQuantityString(R.plurals.pen_units_spoken, if (v == 1.0) 1 else 2, shown)
        }

    companion object {
        fun number(a: Double) = if (a % 1.0 == 0.0) "${a.toLong()}" else String.format(Locale.ROOT, "%.1f", a)
    }
}
