// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.pen

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import app.mauri.sukrara.App
import app.mauri.sukrara.features.Features
import app.mauri.sukrara.pen.opennov.PenRead
import app.mauri.sukrara.treatments.Treat
import app.mauri.sukrara.treatments.Treatment
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json

/**
 * Insulin pens on the wearer's phone (investigation 29, spikes/novopen-android/DESIGN.md + PRODUCT.md): the pen
 * ledger (`pens.json`, phone only, never synced), the [PenEngine] run over each read, and its writes into the treatment
 * log (Treat.store → graph, followers, the Mac, LibreView / Nightscout / Tidepool, the insulin mute: all of them read `a`).
 *
 * Order of a change: the ledger is saved first, then the entries. A crash in between leaves a dose pointing at an entry
 * that isn't there, which the engine treats as deleted (it asks), never as new (it would log it twice).
 */
object Pens {
    private const val TAG = "SukraraPen"

    /**
     * Every change runs here, never on the main thread (it writes pens.json and the treatment log): a call from the UI
     * returns at once and the flows ([ledger], [readState], Treat.store.items) update when it's done.
     */
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "pens") }
    private fun offMain(block: () -> Unit) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) worker.execute { runCatching(block).onFailure { Log.e(TAG, "pen change failed", it) } }
        else block()
    }
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true; explicitNulls = false }
    private var file: File? = null

    /** The ledger as the UI sees it (pens, open questions). */
    val ledger = MutableStateFlow(Ledger())

    /**
     * The read sheet's state (PRODUCT §4.1): [stage] reading | done | failed | notusable; [report] and [undo] of the
     * read; [read] is the raw read (newest dose for the display check).
     */
    data class ReadState(val stage: String, val serial: String? = null, val report: Report? = null, val undo: UndoRecord? = null,
                         val read: PenRead? = null, val at: Long = System.currentTimeMillis() / 1000, val undone: Boolean = false,
                         val tip: Boolean = false, val firstPen: Boolean = false)
    val readState = MutableStateFlow<ReadState?>(null)

    /** The running read's share of the pen's records (null = not known yet: the ring spins), PRODUCT §4.11. */
    val readProgress = MutableStateFlow<Float?>(null)

    /** A read of an untested pen waiting for the display check (PRODUCT §6), applied on *It matches*. */
    @Volatile var held: PenRead? = null

    @Synchronized fun init(ctx: Context) {
        if (file != null) return
        Treat.init(ctx)
        val f = File(ctx.applicationContext.filesDir, "pens.json")
        file = f
        ledger.value = runCatching { json.decodeFromString<Ledger>(f.readText()) }.getOrNull() ?: Ledger()
    }

    // ------------------------------------------------------------------------------------------- availability

    /** Pen reading exists only on the wearer's own phone with NFC (never on a follower: it would log into someone else's log). */
    fun available(ctx: Context): Boolean =
        App.role(ctx) == "owner" && (ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_NFC) || fakeNfc)

    /** Debug builds only (DebugFakes `penRead` on an emulator without NFC): show the pen surfaces anyway. */
    @Volatile var fakeNfc = false

    /** The background reader is on in "ask" (the first tap asks) and "on"; only "on" logs. */
    fun enabled(ctx: Context): Boolean { Features.init(ctx); return available(ctx) && Features.value.penReading != "off" }

    private fun component(ctx: Context) = ComponentName(ctx, "app.mauri.sukrara.pen.PenReadActivity")

    /** The background reader (manifest TECH_DISCOVERED) is enabled exactly while pen reading is on. */
    fun syncComponent(ctx: Context) = runCatching {
        ctx.packageManager.setComponentEnabledSetting(component(ctx),
            if (enabled(ctx)) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP)
    }

    fun setReading(ctx: Context, on: Boolean) { Features.update { it.copy(penReading = if (on) "on" else "off") }; syncComponent(ctx) }

    /** *Turn on* on the first tap's question (PRODUCT §1b): reading on, and the held read goes in as a first scan. */
    fun turnOn(ctx: Context) = offMain { run {
        setReading(ctx, true)
        val r = held ?: return@offMain
        held = null
        onRead(ctx, r).let { PenReadActivity.notify(ctx, it) }
    } }

    /** *No*: reading off and the reader disabled, never asked again (Settings turns it on). */
    fun decline(ctx: Context) = offMain { run { held = null; setReading(ctx, false); readState.value = null } }

    fun rules() = PenRules(primeBelow = Features.value.penPrimeBelow)
    private fun engine() = PenEngine(rules(), origin = "phone", log = { Log.i(TAG, it) })

    // ------------------------------------------------------------------------------------------- treatments ↔ engine

    fun toEntry(t: Treatment) = PenEntry(t.id, t.k, t.a, t.t, t.n, t.u, t.del, t.o, t.pr, t.pen, t.pd)
    private fun entries(): List<PenEntry> = Treat.store.all().map(::toEntry)

    /** Apply an engine outcome: ledger first, then the entries (keeping each row's LibreView watermark), then wake-ups. */
    @Synchronized
    fun apply(ctx: Context, o: Outcome): Outcome {
        save(o.ledger)
        for (w in o.writes) {
            val cur = Treat.store.entry(w.id)?.second
            val tr = (cur ?: Treatment(id = w.id, k = w.k, t = w.t, u = w.u, o = w.o)).copy(
                k = w.k, a = w.a, t = w.t, n = w.n, u = w.u, del = w.del, o = w.o, pr = w.pr, pen = w.pen, pd = w.pd)
            Treat.store.put(tr)
        }
        if (o.writes.isNotEmpty()) Treat.uploader.kick()
        // whose entry a merge replaced (a writer's: tombstoned + recreated), for the card's merge line
        o.report.merged.forEach { id ->
            val was = ledger.value.pens.values.firstNotNullOfOrNull { it.replaced[id] } ?: return@forEach
            if (was.o != "phone" && was.o != "mac") runCatching { App.owner(ctx).writerName(was.id) }.getOrNull()?.let { n -> writerNames.value = writerNames.value + (id to n) }
        }
        PenWork.schedule(ctx)
        PenNotifier.sync(ctx)
        return o
    }

    private fun save(l: Ledger) {
        ledger.value = l
        val f = file ?: return
        val tmp = File(f.path + ".tmp")
        tmp.writeText(json.encodeToString(Ledger.serializer(), l))
        tmp.renameTo(f)
    }

    private fun now() = System.currentTimeMillis()

    // ------------------------------------------------------------------------------------------- operations

    /**
     * A finished NFC read → the engine. Untested models (PRODUCT §6) log nothing before the display check: a new one is
     * only noted (a placeholder pen without doses) and its read held; *It matches* ([checkPassed]) applies it.
     */
    @Synchronized
    fun onRead(ctx: Context, r: PenRead, checked: Boolean = false): ReadState {
        init(ctx)
        val pen = ledger.value.pens[r.serial]
        // a pen keeps the model it was first identified as (a later read without a half-unit dose is still an Echo Plus)
        val profile = pen?.model?.let { PenProfiles.byId(it) } ?: PenProfiles.identify(r)
        // a fresh install asks once (PRODUCT §1b): nothing is logged before *Turn on*
        if (Features.value.penReading == "ask") {
            held = r
            return ReadState("ask", r.serial, read = r).also { readState.value = it }
        }
        if (!profile.verified && !checked && pen?.check != "ok") {
            if (pen?.check == "failed") return ReadState("notusable", r.serial, read = r).also { readState.value = it }
            held = r
            if (pen == null) save(Ledger(ledger.value.pens + (r.serial to Pen(r.serial, model = profile.id,
                since = r.scanTime, first = r.scanTime, lastScan = r.scanTime)), ledger.value.questions))
            return ReadState("check", r.serial, read = r).also { readState.value = it }
        }
        val o = apply(ctx, engine().scan(ledger.value, scanOf(r), entries(), now()))
        ledger.value.pens[r.serial]?.takeIf { it.model.isEmpty() }?.let { save(engine().update(ledger.value, r.serial) { it.copy(model = profile.id) }) }
        return ReadState("done", r.serial, o.report, o.undo, r).also { readState.value = it }
    }

    /**
     * The treatment dialog read the pen (PRODUCT §4.6b): its newest not-yet-logged injection fills the dialog and stays
     * unread until *Save* ([saveFill]); every other new injection of the read is logged as on a background read.
     */
    data class Fill(val read: PenRead, val serial: String, val kind: String, val a: Double, val pr: Double, val t: Long,
                    val primed: Map<String, Boolean>, val unclear: Boolean, val also: Int, val offer: Boolean = false)

    /** null = nothing new to fill (the rest of the read, if anything, is applied). */
    @Synchronized
    fun readForDialog(ctx: Context, r: PenRead): Fill? {
        init(ctx)
        val pen = ledger.value.pens[r.serial]
        // a pen keeps the model it was first identified as (a later read without a half-unit dose is still an Echo Plus)
        val profile = pen?.model?.let { PenProfiles.byId(it) } ?: PenProfiles.identify(r)
        if (Features.value.penReading != "on" || (!profile.verified && pen?.check != "ok")) { onRead(ctx, r); return null }
        val dry = engine().scan(ledger.value, scanOf(r), entries(), now())
        val dl = dry.ledger.pens.getValue(r.serial)
        if (dry.report.first && dry.report.offer != null && !dry.report.returning) {
            apply(ctx, dry)
            ledger.value.pens[r.serial]?.takeIf { it.model.isEmpty() }?.let { save(engine().update(ledger.value, r.serial) { it.copy(model = profile.id) }) }
            val o = dry.report.offer
            return Fill(r, r.serial, dl.kind, o.a, o.pr, o.t, o.doses, unclear = false, also = 0, offer = true)
        }
        val made = (dry.report.created + dry.report.merged).mapNotNull { id -> dry.writes.firstOrNull { it.id == id } }.maxByOrNull { it.t }
        val asked = dry.report.asked.mapNotNull { id -> dry.ledger.questions.firstOrNull { it.id == id && it.type == PenEngine.DOSES } }
            .maxByOrNull { q -> q.keys.maxOf { dl.doses.getValue(it).t } }
        val askedT = asked?.keys?.maxOf { dl.doses.getValue(it).t }
        val pick: Pair<Set<String>, Boolean>? = when {
            made != null && (askedT == null || made.t >= askedT) -> dl.doses.filter { it.value.e == made.id }.keys to false
            asked != null -> asked.keys.toSet() to true
            else -> null
        }
        if (pick == null) { apply(ctx, dry); return null }
        val (keys, unclear) = pick
        val rest = r.copy(doses = r.doses.filterNot { PenEngine.key(it.relTime, it.units) in keys })
        val o = apply(ctx, engine().scan(ledger.value, scanOf(rest), entries(), now()))
        val ds = keys.map { dl.doses.getValue(it) }
        val inj = ds.filter { !it.p }
        return Fill(r, r.serial, dl.kind, PenEngine.r1(inj.sumOf { it.a }), PenEngine.r1(ds.filter { it.p }.sumOf { it.a }),
            inj.minOfOrNull { it.t } ?: ds.minOf { it.t }, keys.associateWith { dl.doses.getValue(it).p }, unclear,
            also = o.report.created.size + o.report.merged.size)
    }

    /** *Save* in the dialog with the pen's amount: the whole read goes in now; [primed] are the roles the dialog shows. */
    fun saveFill(ctx: Context, f: Fill, primed: Map<String, Boolean>) = offMain { synchronized(this) {
        if (f.offer) { logDoses(ctx, f.serial, primed); return@offMain }
        val o = apply(ctx, engine().scan(ledger.value, scanOf(f.read), entries(), now()))
        readState.value = ReadState("done", f.serial, o.report, o.undo, f.read)
        val q = ledger.value.open().firstOrNull { it.type == PenEngine.DOSES && it.keys.any { k -> k in primed } }
        if (q != null) { answerDoses(ctx, q.id, q.keys.filter { primed[it] == true }.toSet()); return@offMain }
        val id = ledger.value.pens[f.serial]?.doses?.get(primed.keys.first())?.e ?: return@offMain
        val tr = Treat.store.entry(id)?.second ?: return@offMain
        val roles = tr.pd.orEmpty().map { d -> primed.entries.firstOrNull { (k, _) -> ledger.value.pens[f.serial]?.doses?.get(k)?.let { it.t == d.t && PenEngine.tenths(it.a) == PenEngine.tenths(d.a) } == true }?.value ?: d.p }
        if (roles != tr.pd.orEmpty().map { it.p }) correct(ctx, tr, roles)
    } }

    /**
     * The name a pen is shown with (QA review): the user's name if they renamed it; else its model alone when it is the
     * only pen of that model, or the model and the serial's last 3 for every pen of a shared model.
     */
    fun displayName(serial: String, pens: Map<String, Pen> = ledger.value.pens): String {
        val p = pens[serial] ?: return serial
        val model = PenProfiles.byId(p.model)?.name ?: PenProfiles.ECHO_PLUS.name
        if (p.name.isNotEmpty() && p.name != model && !p.name.startsWith("$model ·")) return p.name
        return if (pens.values.count { it.model == p.model } > 1) "$model ·${serial.takeLast(3)}" else model
    }

    /** A writer's name for an entry a pen read replaced (the card: *Replaced Ana's 8u entry*), in memory for the read. */
    val writerNames = MutableStateFlow<Map<String, String>>(emptyMap())

    fun scanOf(r: PenRead) = PenScan(r.serial, r.scanTime, r.penRelNow,
        r.doses.map { ReadDose(it.relTime, it.time, it.units, it.incomplete) }, r.model)


    private var lastFail = 0L

    /** A failed read; a second one within 2 min adds the tips line (PRODUCT §4.1). */
    fun failed(serial: String?) {
        val now = now()
        readState.value = ReadState("failed", serial, tip = now - lastFail < 120_000)
        lastFail = now
    }

    /** Reading; with no pen known yet the card says the first read takes a few seconds (the whole memory comes over). */
    fun reading() { readProgress.value = null; readState.value = ReadState("reading", firstPen = ledger.value.pens.isEmpty()) }

    fun undoRead(ctx: Context, r: UndoRecord) = offMain { synchronized(this) {
        apply(ctx, engine().undo(ledger.value, r, entries(), now()))
        readState.value = readState.value?.copy(undone = true)
    } }

    fun refresh(ctx: Context) = synchronized(this) {
        init(ctx)
        if (ledger.value.pens.isEmpty()) return@synchronized
        apply(ctx, engine().refresh(ledger.value, entries(), now() / 1000, now()))
    }

    fun answerDoses(ctx: Context, qid: String, primed: Set<String>) = offMain { synchronized(this) {
        apply(ctx, engine().answerDoses(ledger.value, qid, primed, entries(), now() / 1000, now()))
    } }

    fun answerMatch(ctx: Context, qid: String, same: String?) = offMain { synchronized(this) {
        apply(ctx, engine().answerMatch(ledger.value, qid, same, entries(), now() / 1000, now()))
    } }

    /** The suggestion (notification / card *Log 1.5u*): the question's pre-set priming doses. */
    fun acceptSuggestion(ctx: Context, qid: String) = offMain { run {
        val q = ledger.value.questions.firstOrNull { it.id == qid } ?: return@offMain
        val doses = ledger.value.pens[q.serial]?.doses ?: return@offMain
        answerDoses(ctx, qid, q.keys.filter { doses[it]?.p == true }.toSet())
    } }

    fun allPriming(ctx: Context, qid: String) = offMain { run {
        val q = ledger.value.questions.firstOrNull { it.id == qid } ?: return@offMain
        answerDoses(ctx, qid, q.keys.toSet())
    } }

    fun logDoses(ctx: Context, serial: String, doses: Map<String, Boolean>, onDone: (Outcome) -> Unit = {}) = offMain { synchronized(this) {
        onDone(apply(ctx, engine().logDoses(ledger.value, serial, doses, entries(), now() / 1000, now())))
    } }

    fun logReview(ctx: Context, serial: String) = offMain { synchronized(this) {
        apply(ctx, engine().logReview(ledger.value, serial, entries(), now() / 1000, now()))
    } }

    fun skipReview(ctx: Context, serial: String) = offMain { synchronized(this) { apply(ctx, engine().skipReview(ledger.value, serial, now())) } }

    /** §4.3 *Save* of a correction: the entry with its doses' roles as the user set them (a user edit, new `u`). */
    fun correct(ctx: Context, tr: Treatment, primed: List<Boolean>) = offMain { run {
        val next = PenEngine.correct(toEntry(tr), primed) ?: return@offMain
        val saved = tr.copy(t = next.t, a = next.a, pr = next.pr, pd = next.pd, u = maxOf(now(), tr.u + 1))
        Treat.store.put(saved)
        Treat.uploader.kick()
        Unit
    } }

    /** §4.3 *Remove from log* (every dose priming). */
    fun remove(ctx: Context, tr: Treatment) = offMain { synchronized(this) {
        val serial = tr.pen ?: return@synchronized
        apply(ctx, engine().removeEntry(ledger.value, serial, tr.id, entries(), now()))
    } }

    /** The snackbar's *Undo* after a correction or a removal: [was] back as it was. */
    fun restore(ctx: Context, was: Treatment) = offMain { synchronized(this) {
        val serial = was.pen ?: return@synchronized
        apply(ctx, engine().restore(ledger.value, serial, toEntry(was), entries(), now()))
    } }

    fun history(serial: String): List<Session> = engine().history(ledger.value, serial, entries())

    fun offerMatch(o: Offer): Treatment? = o.match?.let { Treat.store.entry(it)?.second }

    /** The hand-typed entry an injection of [a] units at [t] would replace (§4.5's one candidate within tolerance), if any. */
    fun match(serial: String, t: Long, a: Double): Treatment? {
        val kind = ledger.value.pens[serial]?.kind ?: "rapid"
        return engine().candidates(entries(), kind, t, a).second?.let { Treat.store.entry(it.id)?.second }
    }

    fun update(serial: String, f: (Pen) -> Pen) = offMain { synchronized(this) { save(engine().update(ledger.value, serial, f)) } }

    /** Debug builds only (DebugFakes `penRead --ei knownDays`): a pen read before, since [since]. */
    fun debugKnown(serial: String, since: Long, penNow: Long) = offMain { synchronized(this) {
        save(Ledger(ledger.value.pens + (serial to Pen(serial, model = PenProfiles.ECHO_PLUS.id,
            since = since, first = since, lastScan = since, lastPenNow = penNow)), ledger.value.questions))
    } }

    fun forget(serial: String) = offMain { synchronized(this) { save(engine().forget(ledger.value, serial)) } }

    /** The display check passed (*It matches*): the pen is used like any other; a held read is applied now. */
    fun checkPassed(ctx: Context, serial: String) = offMain { synchronized(this) {
        val r = held?.takeIf { it.serial == serial }
        held = null
        val was = ledger.value.pens[serial]
        if (r == null || was == null) { update(serial) { it.copy(check = "ok") }; return@offMain }
        // a new pen's placeholder makes way for its real first scan (start date, earlier doses, a returning pen)
        if (was.doses.isEmpty()) save(engine().forget(ledger.value, serial))
        val st = onRead(ctx, r, checked = true)
        update(serial) { it.copy(check = "ok", name = was.name, kind = if (was.doses.isEmpty()) was.kind else it.kind) }
        st.let { }
    } }

    fun checkFailed(serial: String) = update(serial) { it.copy(check = "failed") }

    /** The pen tapped first as untested and cancelled: it isn't added (PRODUCT §6). */
    fun cancelNew(serial: String) = offMain { run { held = null; forget(serial) } }

    /** Stop the read early once it reaches what the ledger already holds (minus 2 h, for sessions still open). */
    fun stopBefore(serial: String): Long? = ledger.value.pens[serial]?.let { p ->
        val open = p.doses.values.filter { it.role in setOf(PenEngine.PENDING_ROLE, PenEngine.QUESTION, PenEngine.REVIEW) }.minOfOrNull { it.t }
        minOf(p.lastScan, open ?: Long.MAX_VALUE) - 2 * 3600
    }
}
