// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.pen

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.nfc.NfcAdapter
import android.provider.Settings
import app.mauri.sukrara.core.model.PenCheck
import app.mauri.sukrara.core.model.PenDoseInfo
import app.mauri.sukrara.core.model.PenEntryInfo
import app.mauri.sukrara.core.model.PenHeldDose
import app.mauri.sukrara.core.model.PenInfo
import app.mauri.sukrara.core.model.PenNearEntry
import app.mauri.sukrara.core.model.PenSessionInfo
import app.mauri.sukrara.core.model.PenSessionStatus
import app.mauri.sukrara.core.model.PenTaps
import app.mauri.sukrara.core.model.PensPort
import app.mauri.sukrara.pen.engine.Ledger
import app.mauri.sukrara.pen.engine.PenEngine
import app.mauri.sukrara.pen.engine.PenEntry
import app.mauri.sukrara.pen.engine.Seen
import app.mauri.sukrara.pen.engine.Session
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Settings' view of the pens ([PensPort]) over the pen runtime; nothing here decides about doses. */
class PensPortImpl(
    private val context: Context,
    private val module: PensModule,
) : PensPort {
    private val pensRt get() = module.pens

    override val pens: Flow<List<PenInfo>> =
        pensRt.ledger.map { l ->
            val entries = if (l.pens.values.any { it.returning }) module.penEntries() else emptyList()
            l.pens.values
                .sortedBy { it.first }
                .map { info(it.serial, l, entries) }
        }

    private fun info(
        serial: String,
        l: Ledger,
        entries: List<PenEntry>,
    ): PenInfo {
        val p = l.pens.getValue(serial)
        val profile = PenProfiles.byId(p.model) ?: PenProfiles.ECHO_PLUS
        val held =
            if (!profile.verified && p.check == null) {
                pensRt.heldForCheck(serial)?.let { r ->
                    r.doses.maxByOrNull { it.time }?.let { PenHeldDose(it.units, it.time, r.scanTime) }
                }
            } else {
                null
            }
        return PenInfo(
            serial = serial,
            name = Pens.displayName(serial, l.pens),
            model = profile.name,
            kind = p.kind,
            since = p.since,
            first = p.first,
            lastScan = p.lastScan,
            older = p.older,
            returningSince =
                if (p.returning) {
                    entries.filter { it.pen == serial }.flatMap { it.pd.orEmpty() }.maxOfOrNull { it.t }
                } else {
                    null
                },
            untested = !profile.verified,
            check =
                when (p.check) {
                    Pens.CHECK_OK -> PenCheck.Ok
                    Pens.CHECK_FAILED -> PenCheck.Failed
                    else -> PenCheck.None
                },
            opened = p.opened,
            held = held,
        )
    }

    override fun sessions(serial: String): Flow<List<PenSessionInfo>> =
        pensRt.ledger.map { l ->
            val pen = l.pens[serial] ?: return@map emptyList()
            val (sessions, entries) = pensRt.history(serial)
            val byId = entries.associateBy { it.id }
            sessions.map { s -> session(s, pen.doses, byId[s.entry], pen.replaced[s.entry]?.a, l) }
        }

    private fun session(
        s: Session,
        doses: Map<String, Seen>,
        entry: PenEntry?,
        mergedFrom: Double?,
        l: Ledger,
    ) = PenSessionInfo(
        serial = s.serial,
        t = s.t,
        units = entry?.takeIf { !it.del }?.a ?: s.a,
        primed = s.pr,
        status = STATUS[s.status] ?: PenSessionStatus.NotLogged,
        entry = s.entry,
        question = s.question,
        sameQuestion = l.questions.firstOrNull { it.id == s.question }?.type == PenEngine.MATCH_Q,
        mergedFrom = mergedFrom,
        incomplete = s.incomplete,
        doses =
            s.keys
                .mapNotNull { k ->
                    doses[k]?.let { PenDoseInfo(k, it.t, it.a, k in s.primed, it.inc) }
                }.sortedBy { it.t },
    )

    override suspend fun entry(id: String): PenEntryInfo? {
        val e = module.penEntries().firstOrNull { it.id == id && !it.del && it.pen != null && !it.pd.isNullOrEmpty() } ?: return null
        val pen = pensRt.load().pens[e.pen]
        val inc = pen?.doses?.values.orEmpty()
        return PenEntryInfo(
            id = e.id,
            serial = e.pen!!,
            kind = e.k,
            units = e.a,
            primed = e.pr ?: 0.0,
            t = e.t,
            note = e.n,
            doses =
                e.pd!!.mapIndexed { i, d ->
                    PenDoseInfo(
                        "$i",
                        d.t,
                        d.a,
                        d.p,
                        inc.any { it.t == d.t && PenEngine.tenths(it.a) == PenEngine.tenths(d.a) && it.inc },
                    )
                },
            replaced = pen?.replaced?.get(e.id)?.let { it.a to it.t },
        )
    }

    override suspend fun rename(
        serial: String,
        name: String,
    ) = pensRt.update(serial) { it.copy(name = name.trim().take(40)) }

    override suspend fun setKind(
        serial: String,
        kind: String,
    ) = pensRt.update(serial) { it.copy(kind = if (kind == "long") "long" else "rapid") }

    override suspend fun opened(serial: String) = pensRt.opened(serial)

    override suspend fun forget(serial: String) = pensRt.forget(serial)

    override suspend fun logDoses(
        serial: String,
        doses: Map<String, Boolean>,
    ) = pensRt.logDoses(serial, doses).also { if (it) module.syncNotices() }

    override suspend fun sessionEntry(
        serial: String,
        t: Long,
    ) = pensRt.sessionEntry(serial, t)?.let { PenNearEntry(it.id, it.a, it.t) }

    override suspend fun joinEntry(
        serial: String,
        doses: Map<String, Boolean>,
        id: String,
    ) = pensRt.joinEntry(serial, doses, id)

    override suspend fun match(
        serial: String,
        t: Long,
        units: Double,
    ) = pensRt.match(serial, t, units)?.let { PenNearEntry(it.id, it.a, it.t) }

    override suspend fun correct(
        id: String,
        primed: List<Boolean>,
    ): PenEntryInfo? {
        val shown = entry(id) ?: return null
        val pd = module.penEntries().firstOrNull { it.id == id }?.pd ?: return null
        if (pd.size != shown.doses.size) return null
        return pensRt.correct(id, pd, primed)?.let { shown }
    }

    override suspend fun restore(was: PenEntryInfo) = pensRt.restoreLast(was.id)

    override suspend fun acceptSuggestion(question: String) = pensRt.acceptSuggestion(question, null).also { module.syncNotices() }

    override suspend fun allPriming(question: String) = pensRt.allPriming(question, null).also { module.syncNotices() }

    override suspend fun answerMatch(
        question: String,
        same: Boolean,
    ): Boolean {
        val q = pensRt.load().questions.firstOrNull { it.id == question } ?: return false
        // the page answers only a single-candidate question (the read card offers the others)
        val entry = if (same) q.cands.singleOrNull() ?: return false else null
        return pensRt.answerMatch(question, entry, null).also { module.syncNotices() }
    }

    override suspend fun checkPassed(serial: String): Boolean {
        val r = pensRt.heldForCheck(serial) ?: return false
        return pensRt.checkPassed(serial, r.scanTime)
    }

    override suspend fun checkFailed(serial: String) = pensRt.checkFailed(serial)

    override suspend fun checkAgain(serial: String) = pensRt.checkAgain(serial)

    override fun taps(): PenTaps = module.taps()

    override fun openTagSettings(): Boolean =
        start(Intent(NfcAdapter.ACTION_CHANGE_TAG_INTENT_PREFERENCE)) || start(Intent(Settings.ACTION_NFC_SETTINGS))

    override fun openNfcSettings() {
        start(Intent(Settings.ACTION_NFC_SETTINGS))
    }

    override fun openNotifySettings() {
        start(module.notices.readChannelSettings())
    }

    private fun start(i: Intent): Boolean =
        try {
            context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (_: ActivityNotFoundException) {
            false
        }

    companion object {
        private val STATUS =
            mapOf(
                Session.LOGGED to PenSessionStatus.Logged,
                Session.MERGED to PenSessionStatus.Merged,
                Session.CORRECTED to PenSessionStatus.Corrected,
                Session.PRIMING to PenSessionStatus.Priming,
                Session.WAITING to PenSessionStatus.Waiting,
                Session.REVIEW to PenSessionStatus.Review,
                Session.NOT_LOGGED to PenSessionStatus.NotLogged,
                Session.BEFORE to PenSessionStatus.Before,
                Session.IGNORED to PenSessionStatus.Ignored,
            )
    }
}
