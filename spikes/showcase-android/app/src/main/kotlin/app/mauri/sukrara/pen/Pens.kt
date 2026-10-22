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
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true; explicitNulls = false }
    private var file: File? = null

    /** The ledger as the UI sees it (pens, open questions). */
    val ledger = MutableStateFlow(Ledger())

    /**
     * The read sheet's state (PRODUCT §4.1): [stage] reading | done | failed | notusable; [report] and [undo] of the
     * read; [read] is the raw read (newest dose for the display check).
     */
    data class ReadState(val stage: String, val serial: String? = null, val report: Report? = null, val undo: UndoRecord? = null,
                         val read: PenRead? = null, val at: Long = System.currentTimeMillis() / 1000, val undone: Boolean = false)
    val readState = MutableStateFlow<ReadState?>(null)

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
        App.role(ctx) == "owner" && ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_NFC)

    fun enabled(ctx: Context): Boolean { Features.init(ctx); return available(ctx) && Features.value.penReading }

    private fun component(ctx: Context) = ComponentName(ctx, "app.mauri.sukrara.pen.PenReadActivity")

    /** The background reader (manifest TECH_DISCOVERED) is enabled exactly while pen reading is on. */
    fun syncComponent(ctx: Context) = runCatching {
        ctx.packageManager.setComponentEnabledSetting(component(ctx),
            if (enabled(ctx)) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP)
    }

    fun setReading(ctx: Context, on: Boolean) { Features.update { it.copy(penReading = on) }; syncComponent(ctx) }

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
        val profile = PenProfiles.identify(r)
        val pen = ledger.value.pens[r.serial]
        if (!profile.verified && !checked && pen?.check != "ok") {
            if (pen?.check == "failed") return ReadState("notusable", r.serial, read = r).also { readState.value = it }
            held = r
            if (pen == null) save(Ledger(ledger.value.pens + (r.serial to Pen(r.serial, model = profile.id,
                name = defaultName(r.serial, profile), since = r.scanTime, first = r.scanTime, lastScan = r.scanTime)), ledger.value.questions))
            return ReadState("check", r.serial, read = r).also { readState.value = it }
        }
        val o = apply(ctx, engine().scan(ledger.value, scanOf(r), entries(), now()))
        ledger.value.pens[r.serial]?.takeIf { it.name.isEmpty() }?.let { p ->
            save(engine().update(ledger.value, r.serial) { it.copy(name = defaultName(p.serial, profile), model = profile.id) })
        }
        return ReadState("done", r.serial, o.report, o.undo, r).also { readState.value = it }
    }

    fun scanOf(r: PenRead) = PenScan(r.serial, r.scanTime, r.penRelNow,
        r.doses.map { ReadDose(it.relTime, it.time, it.units, it.incomplete) }, r.model)

    /** The pen's default name: its model, plus the serial's last 3 when another pen has the same model (PRODUCT §4.7). */
    private fun defaultName(serial: String, profile: PenProfile): String {
        val same = ledger.value.pens.values.count { it.serial != serial && it.model == profile.id }
        return if (same > 0) "${profile.name} ·${serial.takeLast(3)}" else profile.name
    }

    fun failed(serial: String?) { readState.value = ReadState("failed", serial) }
    fun reading() { readState.value = ReadState("reading") }

    fun undoRead(ctx: Context, r: UndoRecord) = synchronized(this) {
        apply(ctx, engine().undo(ledger.value, r, entries(), now()))
        readState.value = readState.value?.copy(undone = true)
    }

    fun refresh(ctx: Context) = synchronized(this) {
        init(ctx)
        if (ledger.value.pens.isEmpty()) return@synchronized
        apply(ctx, engine().refresh(ledger.value, entries(), now() / 1000, now()))
    }

    fun answerDoses(ctx: Context, qid: String, primed: Set<String>) = synchronized(this) {
        apply(ctx, engine().answerDoses(ledger.value, qid, primed, entries(), now() / 1000, now()))
    }

    fun answerMatch(ctx: Context, qid: String, same: String?) = synchronized(this) {
        apply(ctx, engine().answerMatch(ledger.value, qid, same, entries(), now() / 1000, now()))
    }

    /** The suggestion (notification / card *Log 1.5u*): the question's pre-set priming doses. */
    fun acceptSuggestion(ctx: Context, qid: String) {
        val q = ledger.value.questions.firstOrNull { it.id == qid } ?: return
        val doses = ledger.value.pens[q.serial]?.doses ?: return
        answerDoses(ctx, qid, q.keys.filter { doses[it]?.p == true }.toSet())
    }

    fun allPriming(ctx: Context, qid: String) {
        val q = ledger.value.questions.firstOrNull { it.id == qid } ?: return
        answerDoses(ctx, qid, q.keys.toSet())
    }

    fun logDoses(ctx: Context, serial: String, doses: Map<String, Boolean>) = synchronized(this) {
        apply(ctx, engine().logDoses(ledger.value, serial, doses, entries(), now() / 1000, now()))
    }

    fun logReview(ctx: Context, serial: String) = synchronized(this) {
        apply(ctx, engine().logReview(ledger.value, serial, entries(), now() / 1000, now()))
    }

    fun skipReview(ctx: Context, serial: String) = synchronized(this) { apply(ctx, engine().skipReview(ledger.value, serial, now())) }

    /** §4.3 *Save* of a correction: the entry with its doses' roles as the user set them (a user edit, new `u`). */
    fun correct(ctx: Context, tr: Treatment, primed: List<Boolean>): Treatment? {
        val next = PenEngine.correct(toEntry(tr), primed) ?: return null
        val saved = tr.copy(t = next.t, a = next.a, pr = next.pr, pd = next.pd, u = maxOf(now(), tr.u + 1))
        Treat.store.put(saved)
        Treat.uploader.kick()
        return saved
    }

    /** §4.3 *Remove from log* (every dose priming). */
    fun remove(ctx: Context, tr: Treatment) = synchronized(this) {
        val serial = tr.pen ?: return@synchronized
        apply(ctx, engine().removeEntry(ledger.value, serial, tr.id, entries(), now()))
    }

    /** The snackbar's *Undo* after a correction or a removal: [was] back as it was. */
    fun restore(ctx: Context, was: Treatment) = synchronized(this) {
        val serial = was.pen ?: return@synchronized
        apply(ctx, engine().restore(ledger.value, serial, toEntry(was), entries(), now()))
    }

    fun history(serial: String): List<Session> = engine().history(ledger.value, serial, entries())

    fun offerMatch(o: Offer): Treatment? = o.match?.let { Treat.store.entry(it)?.second }

    /** The hand-typed entry an injection of [a] units at [t] would replace (§4.5's one candidate within tolerance), if any. */
    fun match(serial: String, t: Long, a: Double): Treatment? {
        val kind = ledger.value.pens[serial]?.kind ?: "rapid"
        return engine().candidates(entries(), kind, t, a).second?.let { Treat.store.entry(it.id)?.second }
    }

    fun update(serial: String, f: (Pen) -> Pen) = synchronized(this) { save(engine().update(ledger.value, serial, f)) }

    fun forget(serial: String) = synchronized(this) { save(engine().forget(ledger.value, serial)) }

    /** The display check passed (*It matches*): the pen is used like any other; a held read is applied now. */
    fun checkPassed(ctx: Context, serial: String): ReadState? = synchronized(this) {
        val r = held?.takeIf { it.serial == serial }
        held = null
        val was = ledger.value.pens[serial]
        if (r == null || was == null) { update(serial) { it.copy(check = "ok") }; return null }
        // a new pen's placeholder makes way for its real first scan (start date, earlier doses, a returning pen)
        if (was.doses.isEmpty()) save(engine().forget(ledger.value, serial))
        val st = onRead(ctx, r, checked = true)
        update(serial) { it.copy(check = "ok", name = was.name, kind = if (was.doses.isEmpty()) was.kind else it.kind) }
        return st
    }

    fun checkFailed(serial: String) = update(serial) { it.copy(check = "failed") }

    /** The pen tapped first as untested and cancelled: it isn't added (PRODUCT §6). */
    fun cancelNew(serial: String) { held = null; forget(serial) }

    /** Stop the read early once it reaches what the ledger already holds (minus 2 h, for sessions still open). */
    fun stopBefore(serial: String): Long? = ledger.value.pens[serial]?.let { p ->
        val open = p.doses.values.filter { it.role in setOf(PenEngine.PENDING_ROLE, PenEngine.QUESTION, PenEngine.REVIEW) }.minOfOrNull { it.t }
        minOf(p.lastScan, open ?: Long.MAX_VALUE) - 2 * 3600
    }
}
