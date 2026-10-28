// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.core.model

import kotlinx.coroutines.flow.Flow

/** Whether an untested pen model's display check was done (it logs nothing until [Ok]). */
enum class PenCheck { None, Ok, Failed }

/** One insulin pen as Settings › Insulin pens shows it (times epoch s). */
data class PenInfo(
    val serial: String,
    /** The name it is shown with (the user's, else its model). */
    val name: String,
    val model: String,
    /** `rapid` or `long`. */
    val kind: String,
    val since: Long,
    val first: Long,
    val lastScan: Long,
    /** Doses before Sukrara at its first read (left out). */
    val older: Int,
    /** The first read found this pen's entries in the log: its newest logged dose (else null). */
    val returningSince: Long?,
    /** A model nobody has confirmed yet: nothing is logged before its display check. */
    val untested: Boolean,
    val check: PenCheck,
    /** The pen's page was opened once (the list says *Review its settings* until then). */
    val opened: Boolean,
    /** Untested and unchecked: the newest dose of the read waiting for the check (units, epoch s, read at), or null. */
    val held: PenHeldDose?,
)

/** The newest dose of an untested pen's held read, as the pen's display shows it. */
data class PenHeldDose(
    val units: Double,
    val t: Long,
    val readAt: Long,
)

/** One dose of a session or entry: [key] identifies it to the pen runtime. */
data class PenDoseInfo(
    val key: String,
    val t: Long,
    val units: Double,
    val primed: Boolean,
    val incomplete: Boolean,
)

/** What happened to one session of a pen's doses (*Doses read*). */
enum class PenSessionStatus { Logged, Merged, Corrected, Priming, Waiting, Review, NotLogged, Before, Ignored }

/** One session of *Doses read*, newest first. */
data class PenSessionInfo(
    val serial: String,
    val t: Long,
    /** Injected units (the entry's amount when it is logged). */
    val units: Double,
    val primed: Double,
    val status: PenSessionStatus,
    /** The treatment entry it is (logged, merged, corrected). */
    val entry: String?,
    /** The question it waits on, and whether that asks *Same injection?* (else *Priming or injected?*). */
    val question: String?,
    val sameQuestion: Boolean,
    /** The hand-typed amount a merge replaced. */
    val mergedFrom: Double?,
    val incomplete: Boolean,
    val doses: List<PenDoseInfo>,
)

/** A pen entry of the treatment log with its doses (the entry sheet). */
data class PenEntryInfo(
    val id: String,
    val serial: String,
    /** `rapid` or `long`. */
    val kind: String,
    val units: Double,
    val primed: Double,
    val t: Long,
    val note: String,
    val doses: List<PenDoseInfo>,
    /** The hand-typed entry (units, epoch s) the pen's read replaced. */
    val replaced: Pair<Double, Long>?,
)

/** A hand-typed or pen entry near a pen injection (units, epoch s). */
data class PenNearEntry(
    val id: String,
    val units: Double,
    val t: Long,
)

/** The state Android puts the pen taps in (the pens page's rows, home's banner). */
data class PenTaps(
    /** Android blocks this app's tag intents (Android 15+): no tap reaches it until the user allows it. */
    val blocked: Boolean,
    val nfcOff: Boolean,
    /** *Notification after each read* is on but its channel or the app's notifications are off. */
    val notifyBlocked: Boolean,
)

/**
 * Insulin pens as screens use them (the wearer's phone with NFC; `AppGraph` implements it over `:pen`). Every action
 * runs under the pen runtime's lock and returns false (nothing changed) when this phone may not write or the data
 * moved on since the screen read it.
 */
interface PensPort {
    val pens: Flow<List<PenInfo>>

    /** The pen's sessions, newest first; follows the ledger and the log. */
    fun sessions(serial: String): Flow<List<PenSessionInfo>>

    suspend fun entry(id: String): PenEntryInfo?

    suspend fun rename(
        serial: String,
        name: String,
    )

    suspend fun setKind(
        serial: String,
        kind: String,
    )

    suspend fun opened(serial: String)

    /** *Forget this pen*: its record goes, its entries stay. */
    suspend fun forget(serial: String)

    /** *Log this injection* / *Log it*: these doses, each priming (true) or injected. */
    suspend fun logDoses(
        serial: String,
        doses: Map<String, Boolean>,
    ): Boolean

    /** The live pen entry whose session doses at [t] belong to (*Add to the 13:10 entry*). */
    suspend fun sessionEntry(
        serial: String,
        t: Long,
    ): PenNearEntry?

    suspend fun joinEntry(
        serial: String,
        doses: Map<String, Boolean>,
        id: String,
    ): Boolean

    /** The hand-typed entry logging [units] at [t] would replace (*Matches your 8u entry at 13:10*). */
    suspend fun match(
        serial: String,
        t: Long,
        units: Double,
    ): PenNearEntry?

    /** Each dose of entry [id] set to priming or injected (all priming removes it). Returns the entry as it was. */
    suspend fun correct(
        id: String,
        primed: List<Boolean>,
    ): PenEntryInfo?

    /** The snackbar's *Undo*: [was] back as it was, only while nothing changed the entry since. */
    suspend fun restore(was: PenEntryInfo): Boolean

    /** The answers a waiting session's question offers. */
    suspend fun acceptSuggestion(question: String): Boolean

    suspend fun allPriming(question: String): Boolean

    suspend fun answerMatch(
        question: String,
        same: Boolean,
    ): Boolean

    /** The display check of an untested pen: *It matches* (the held read is applied) / *It doesn't* / *Check again*. */
    suspend fun checkPassed(serial: String): Boolean

    suspend fun checkFailed(serial: String)

    suspend fun checkAgain(serial: String)

    /** Android's state for the taps, read again whenever a screen comes back. */
    fun taps(): PenTaps

    /** Android's screens that fix [taps]: tag intents, NFC, the *Pen reads* channel. */
    fun openTagSettings(): Boolean

    fun openNfcSettings()

    fun openNotifySettings()
}

/** The treatment dialog's *Already logged by your pen?*: a live pen entry of [kind] within the match window of [at]. */
fun interface PenDuplicates {
    suspend fun near(
        kind: String,
        at: Long,
    ): PenNearEntry?
}
