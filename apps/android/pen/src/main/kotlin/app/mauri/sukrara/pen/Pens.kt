// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.pen

import app.mauri.sukrara.core.model.Clock
import app.mauri.sukrara.core.model.storage.StateStore
import app.mauri.sukrara.core.model.storage.TreatmentRecord
import app.mauri.sukrara.pen.engine.Ledger
import app.mauri.sukrara.pen.engine.Outcome
import app.mauri.sukrara.pen.engine.Pen
import app.mauri.sukrara.pen.engine.PenEngine
import app.mauri.sukrara.pen.engine.PenEntry
import app.mauri.sukrara.pen.engine.PenRules
import app.mauri.sukrara.pen.engine.PenScan
import app.mauri.sukrara.pen.engine.ReadDose
import app.mauri.sukrara.pen.engine.Report
import app.mauri.sukrara.pen.engine.UndoRecord
import app.mauri.sukrara.pen.opennov.PenRead
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The treatment log as the pens use it (`AppGraph` implements it over the repository and the share layer). */
interface PenLog {
    /** Every stored treatment, tombstones included (a deleted pen entry keeps its doses consumed). */
    suspend fun all(): List<TreatmentRecord>

    /** Whether this phone writes the log as the wearer now (checked before the engine runs, so nothing is half done). */
    fun canWrite(): Boolean

    /**
     * Stores [record] as this phone's (the wearer's) version and hands it to sharing: `modifiedAt` exactly as given,
     * the stored `creator` kept, `author` = the wearer.
     */
    suspend fun store(record: TreatmentRecord)
}

/** Whether reading is asked for at the first tap, on, or off (schema `pens.reading`). */
enum class PenReading(
    val value: String,
) {
    Ask("ask"),
    On("on"),
    Off("off"),
    ;

    companion object {
        fun of(value: String): PenReading = entries.firstOrNull { it.value == value } ?: Ask
    }
}

/** What the read card shows; [stage] first, the rest filled by the stage. */
data class PenReadState(
    val stage: Stage,
    val serial: String? = null,
    val report: Report? = null,
    val undo: UndoRecord? = null,
    val read: PenRead? = null,
    val undone: Boolean = false,
    /** A second failure within 2 min: the card adds the tips. */
    val tip: Boolean = false,
    /** No pen known yet: the first read takes a few seconds. */
    val firstPen: Boolean = false,
    /** The entries the read wrote (created, merged, updated), as stored after it. */
    val entries: List<PenEntry> = emptyList(),
    /** The ledger after the read (names, questions, held doses). */
    val ledger: Ledger = Ledger(),
    /** The treatment log after the read (question candidates, merged entries). */
    val log: List<PenEntry> = emptyList(),
    /** This pen's newest logged entry (*Nothing new · Last injection …*). */
    val last: PenEntry? = null,
) {
    enum class Stage { Reading, Done, Failed, Ask, Untested }
}

/**
 * Insulin pens on the wearer's phone (docs/29-novopen.md): the pen ledger (`pens.json`, phone only, never synced), the
 * [PenEngine] run over each read, and its writes into the treatment log.
 *
 * Every operation runs under one [Mutex], so two reads, a read and an *Undo*, or a read and the timer never interleave,
 * and every one checks the role first (only the wearer's phone logs pen doses). Order of a change: the ledger is saved
 * first, then the entries. A crash in between leaves a dose pointing at an entry that isn't there, which the engine
 * treats as deleted (it asks), never as new (it would log it twice).
 */
class Pens(
    private val store: StateStore<Ledger>,
    private val log: PenLog,
    private val clock: Clock,
    private val rules: suspend () -> PenRules,
    /** The wearer's own phone with NFC: every path checks it (a follower must never log into the wearer's log). */
    private val available: suspend () -> Boolean,
    private val reading: suspend () -> PenReading,
    private val setReading: suspend (PenReading) -> Unit,
    /** After every change: the pen timer and the *Pen doses to check* notification follow the ledger. */
    private val changed: suspend (Ledger) -> Unit = {},
    private val diagnostic: (String) -> Unit = {},
) {
    private val mutex = Mutex()
    private var loaded: Ledger? = null
    private val _ledger = MutableStateFlow(Ledger())
    private val _read = MutableStateFlow<PenReadState?>(null)
    private var held: PenRead? = null
    private var lastFail = 0L

    /** The last read's undo, valid only until any later change (a later read or answer may have grown its entries). */
    private var lastUndo: UndoRecord? = null

    /** The ledger as the screens see it (pens, open questions). */
    val ledger: StateFlow<Ledger> = _ledger.asStateFlow()

    /** The read card's state (null = no card). */
    val read: StateFlow<PenReadState?> = _read.asStateFlow()

    /** Loads the ledger (idempotent). */
    suspend fun load(): Ledger = mutex.withLock { current() }

    private suspend fun current(): Ledger =
        loaded ?: store.data.first().also {
            loaded = it
            _ledger.value = it
        }

    private suspend fun save(l: Ledger) {
        store.update { l }
        loaded = l
        _ledger.value = l
    }

    private suspend fun engine() = PenEngine(rules(), origin = "phone", log = diagnostic)

    private suspend fun entries(): Pair<List<PenEntry>, Map<String, TreatmentRecord>> {
        val records = log.all()
        return records.map(PenRecords::entry) to records.associateBy { it.treatment.id }
    }

    private fun nowMs() = clock.now().toEpochMilli()

    private suspend fun allowed() = available() && log.canWrite()

    /** Ledger first, then the entries (each on top of what is stored, keeping its sharing fields). */
    private suspend fun apply(
        o: Outcome,
        stored: Map<String, TreatmentRecord>,
    ): Outcome {
        lastUndo = o.undo
        save(o.ledger)
        for (w in o.writes) log.store(PenRecords.record(w, stored[w.id]))
        changed(o.ledger)
        return o
    }

    /** The tag answered the pen's selection: the card says *Reading*. */
    fun startedReading() {
        _read.value = PenReadState(PenReadState.Stage.Reading, firstPen = _ledger.value.pens.isEmpty())
    }

    /** A pen that answered and then failed: nothing of a partial read is used. */
    fun failed() {
        val now = nowMs()
        _read.value = PenReadState(PenReadState.Stage.Failed, tip = now - lastFail < 120_000)
        lastFail = now
    }

    fun close() {
        _read.value = null
    }

    /** Where the reader may stop early: what the ledger already holds (minus 2 h for sessions still open). */
    suspend fun stopBefore(serial: String): Long? =
        load().pens[serial]?.let { p ->
            val open =
                p.doses.values
                    .filter { it.role in setOf(PenEngine.PENDING_ROLE, PenEngine.QUESTION, PenEngine.REVIEW) }
                    .minOfOrNull { it.t }
            minOf(p.lastScan, open ?: Long.MAX_VALUE) - 2 * 3600
        }

    /**
     * A finished NFC read → the engine. *Ask* holds the read and logs nothing until *Turn on*; an untested model logs
     * nothing at all. Null = nothing to show (reading off, not the wearer).
     */
    suspend fun onRead(r: PenRead): PenReadState? =
        mutex.withLock {
            if (!available()) return@withLock null
            val l = current()
            // a pen keeps the model it was first identified as (a later read without a half-unit dose is still an Echo Plus)
            val profile = profileOf(r, l)
            val state =
                when (reading()) {
                    PenReading.Off -> {
                        null
                    }

                    PenReading.Ask -> {
                        held = r
                        PenReadState(PenReadState.Stage.Ask, r.serial, read = r, ledger = l)
                    }

                    PenReading.On -> {
                        when {
                            !profile.verified -> PenReadState(PenReadState.Stage.Untested, r.serial, read = r, ledger = l)
                            !log.canWrite() -> null
                            else -> scanLocked(r, profile)
                        }
                    }
                }
            _read.value = state
            state
        }

    private fun profileOf(
        r: PenRead,
        l: Ledger,
    ) = l.pens[r.serial]?.model?.let { PenProfiles.byId(it) } ?: PenProfiles.identify(r)

    private suspend fun scanLocked(
        r: PenRead,
        profile: PenProfile,
    ): PenReadState {
        val (entries, stored) = entries()
        val o = apply(engine().scan(current(), scanOf(r), entries, nowMs()), stored)
        if (current().pens[r.serial]?.model.isNullOrEmpty()) save(engine().update(current(), r.serial) { it.copy(model = profile.id) })
        return done(r.serial, o.report, o.undo, r)
    }

    private suspend fun done(
        serial: String,
        report: Report,
        undo: UndoRecord?,
        r: PenRead?,
    ): PenReadState {
        val after = log.all().map(PenRecords::entry)
        val ids = (report.created + report.merged + report.updated).distinct()
        val written = ids.mapNotNull { id -> after.firstOrNull { it.id == id } }.filter { !it.del }.sortedBy { it.t }
        val last = after.filter { !it.del && it.pen == serial }.maxByOrNull { it.t }
        return PenReadState(
            PenReadState.Stage.Done,
            serial,
            report,
            undo,
            r,
            entries = written,
            ledger = current(),
            log = after,
            last = last,
        )
    }

    /** *Turn on* on the first tap's question: reading on, and the held read goes in as a first scan. */
    suspend fun turnOn() {
        setReading(PenReading.On)
        mutex.withLock {
            val r = held ?: return
            held = null
            if (!allowed()) return
            val profile = profileOf(r, current())
            _read.value =
                if (profile.verified) {
                    scanLocked(r, profile)
                } else {
                    PenReadState(PenReadState.Stage.Untested, r.serial, read = r, ledger = current())
                }
        }
    }

    /** *No*: reading off (Settings turns it on again); the held read is dropped. */
    suspend fun decline() {
        mutex.withLock { held = null }
        setReading(PenReading.Off)
        _read.value = null
    }

    /** The notification's *Undo*: the last read, while nothing changed since (false = too late). */
    suspend fun undoLast(): Boolean = lastUndo?.let { undo(it) } ?: false

    /** Whether [r] can still be undone (no later change since: a later read or answer may have grown its entries). */
    fun canUndo(r: UndoRecord?): Boolean = r != null && r == lastUndo

    /** *Undo* on the card or the notification: the read's entries back as they were, its doses *Not logged*. */
    suspend fun undo(r: UndoRecord): Boolean =
        mutex.withLock {
            if (r != lastUndo || !allowed()) return@withLock false
            val (entries, stored) = entries()
            apply(engine().undo(current(), r, entries, nowMs()), stored)
            lastUndo = null
            _read.value = _read.value?.let { if (it.undo == r) it.copy(undone = true) else it }
            true
        }

    /** The timer: pending small doses whose wait ended become a question. */
    suspend fun refresh() =
        mutex.withLock {
            if (current().pens.isEmpty() || !allowed()) return@withLock
            val (entries, stored) = entries()
            val o = engine().refresh(current(), entries, nowMs() / 1000, nowMs())
            if (o.writes.isEmpty() && o.ledger == current()) return@withLock
            apply(o, stored)
        }

    /**
     * A doses question answered: [primed] = the keys that were priming (the rest was injected). [keys] = the
     * question's doses as the user saw them: a question that changed since (a later read added doses) isn't answered.
     */
    suspend fun answerDoses(
        qid: String,
        primed: Set<String>,
        keys: List<String>?,
    ): Boolean =
        mutex.withLock {
            val q = current().questions.firstOrNull { it.id == qid && it.answer == null } ?: return@withLock false
            if ((keys != null && keys != q.keys) || !allowed()) return@withLock false
            val (entries, stored) = entries()
            apply(engine().answerDoses(current(), qid, primed, entries, nowMs() / 1000, nowMs()), stored)
            refreshCard()
            true
        }

    /** *Log 1.5u*: the question's suggestion (the doses it pre-set as priming stay priming, the rest is injected). */
    suspend fun acceptSuggestion(
        qid: String,
        keys: List<String>?,
    ): Boolean {
        val l = load()
        val q = l.questions.firstOrNull { it.id == qid } ?: return false
        val doses = l.pens[q.serial]?.doses ?: return false
        return answerDoses(qid, q.keys.filter { doses[it]?.p == true }.toSet(), keys)
    }

    /** *All priming*: nothing of the question is logged. */
    suspend fun allPriming(
        qid: String,
        keys: List<String>?,
    ): Boolean {
        val q = load().questions.firstOrNull { it.id == qid } ?: return false
        return answerDoses(qid, q.keys.toSet(), keys)
    }

    /** A same-injection question answered: [same] = the hand-typed entry it is, or null for different injections. */
    suspend fun answerMatch(
        qid: String,
        same: String?,
        keys: List<String>?,
    ): Boolean =
        mutex.withLock {
            val q = current().questions.firstOrNull { it.id == qid && it.answer == null } ?: return@withLock false
            if ((keys != null && keys != q.keys) || (same != null && same !in q.cands) || !allowed()) return@withLock false
            val (entries, stored) = entries()
            apply(engine().answerMatch(current(), qid, same, entries, nowMs() / 1000, nowMs()), stored)
            refreshCard()
            true
        }

    /** A question on the card was answered: the card follows (it reads the ledger it carries). */
    private suspend fun refreshCard() {
        val card = _read.value ?: return
        _read.value = card.copy(ledger = current(), log = log.all().map(PenRecords::entry))
    }

    /** The pen's name, insulin or other per-pen fields. */
    suspend fun update(
        serial: String,
        f: (Pen) -> Pen,
    ) = mutex.withLock { save(engine().update(current(), serial, f)) }

    /** *Forget this pen*: its ledger goes; its entries stay (a later tap continues after its newest logged dose). */
    suspend fun forget(serial: String) =
        mutex.withLock {
            lastUndo = null
            save(engine().forget(current(), serial))
            changed(current())
        }

    /** The treatment log as the engine sees it (question sheets: the candidates' amounts and times). */
    suspend fun entries(ids: Collection<String>): List<PenEntry> = log.all().map(PenRecords::entry).filter { it.id in ids }

    /** A pen entry of [kind] (`rapid` / `long`) within the match window of [at] (epoch s), if any. */
    suspend fun penEntryNear(
        kind: String,
        at: Long,
    ): PenEntry? = PenEngine.near(log.all().map(PenRecords::entry), kind, at, window = rules().match)

    companion object {
        fun scanOf(r: PenRead) =
            PenScan(r.serial, r.scanTime, r.penRelNow, r.doses.map { ReadDose(it.relTime, it.time, it.units, it.incomplete) }, "")

        /** The name a pen is shown with: the user's; else its model, with the serial's last 3 when two share a model. */
        fun displayName(
            serial: String,
            pens: Map<String, Pen>,
        ): String {
            val p = pens[serial] ?: return PenProfiles.ECHO_PLUS.name
            val model = PenProfiles.byId(p.model)?.name ?: PenProfiles.ECHO_PLUS.name
            if (p.name.isNotEmpty() && p.name != model) return p.name
            return if (pens.values.count { it.model == p.model } > 1) "$model ·${serial.takeLast(3)}" else model
        }
    }
}
