// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.integrations.nightscout

import app.mauri.sukrara.core.model.Reading
import app.mauri.sukrara.core.model.SourceId
import app.mauri.sukrara.core.model.storage.TreatmentRecord
import app.mauri.sukrara.integrations.CurrentSensor
import app.mauri.sukrara.integrations.upload.SentRanges
import app.mauri.sukrara.integrations.upload.Span
import app.mauri.sukrara.integrations.upload.UploadBackoff
import kotlinx.serialization.Serializable
import kotlin.random.Random

/**
 * What the site has, as this phone remembers it (typed store `nightscout-upload`). [site] + [device] tie it to one
 * configured site: a different site starts again from an empty state. Losing it only costs a re-check against the site.
 */
@Serializable
data class NsState(
    /** The site this state is about (its root); empty = nothing configured yet. */
    val site: String = "",
    /** This install's name on the site (`Sukrara-<6 hex>`), chosen when the site is set. */
    val device: String = "",
    /** Start of the upload window (epoch s), set on the first cycle. */
    val from: Long = 0,
    /** Reading minutes (epoch s / 60) the site has, as ranges. */
    val minutes: List<Span> = emptyList(),
    /** Treatment id → the version (`modifiedAt` ms) the site has. */
    val treatments: Map<String, Long> = emptyMap(),
    /** Sensor serials whose start went up. */
    val sensors: List<String> = emptyList(),
    val batteryPercent: Int = -1,
    val batteryMs: Long = 0,
    val reconciledMs: Long = 0,
    val failures: Int = 0,
    val nextAttemptMs: Long = 0,
    val failingSinceMs: Long = 0,
    /** The other device(s) writing readings to the site, while uploads are paused. */
    val conflict: String? = null,
    val conflictAccepted: Boolean = false,
    /** The site refused the token / secret: nothing goes up until it's changed. */
    val authFailed: Boolean = false,
    val lastOkMs: Long = 0,
    val lastCount: Int = 0,
    /** Why the last cycle failed (no secrets), null after a good one. */
    val lastError: String? = null,
) {
    val paused: Boolean get() = conflict != null && !conflictAccepted
}

/** What the person lets go up (Nightscout upload › What to send). */
data class NsOptions(
    val readings: Boolean = true,
    val treatments: Boolean = true,
    val sensor: Boolean = true,
    val battery: Boolean = true,
)

/**
 * The plan of one cycle, worked out from the stores and [NsState] (pure: JVM tests). The phone's readings and treatment
 * log are the queue; a periodic diff against the site ([reconciled]) forgets minutes the site lost and learns which
 * minutes another uploader already covers.
 */
object NightscoutQueue {
    const val WINDOW_S = 30 * 86_400L
    const val BATCH = 500
    const val V3_MAX_PER_CYCLE = 240
    const val RECONCILE_EVERY_MS = 6 * 3_600_000L
    const val RECONCILE_WINDOW_S = 86_400L
    const val CONFLICT_WINDOW_S = 30 * 60L
    const val BATTERY_EVERY_MS = 30 * 60_000L
    private const val BASE_BACKOFF_MS = 30_000L
    const val MAX_BACKOFF_MS = 15 * 60_000L

    /** Only readings this phone took from its own sensor go up (never a followed person's, never test data). */
    val UPLOADED_SOURCES = setOf(SourceId.Libre3, SourceId.Juggluco)

    data class Plan(
        /** Oldest first. */
        val entries: List<Reading>,
        val upserts: List<TreatmentRecord>,
        /** Deleted entries the site has. */
        val deletes: List<TreatmentRecord>,
        /** Nothing to send (deleted before it ever went up): just remember. */
        val settled: List<TreatmentRecord>,
        val sensor: CurrentSensor?,
    ) {
        val isEmpty get() = entries.isEmpty() && upserts.isEmpty() && deletes.isEmpty() && settled.isEmpty() && sensor == null

        companion object {
            val EMPTY = Plan(emptyList(), emptyList(), emptyList(), emptyList(), null)
        }
    }

    /** Paused (another uploader, not confirmed) = nothing at all goes up: readings, entries, sensor, battery. */
    fun plan(
        readings: List<Reading>,
        treatments: List<TreatmentRecord>,
        sensor: CurrentSensor?,
        state: NsState,
        o: NsOptions,
        nowS: Long,
    ): Plan {
        if (state.paused) return Plan.EMPTY
        val from = maxOf(state.from, nowS - WINDOW_S)
        val sent = SentRanges.expand(state.minutes)
        val entries =
            if (!o.readings) {
                emptyList()
            } else {
                readings
                    .filter { it.source in UPLOADED_SOURCES && it.t.epochSecond in from..(nowS + 60) && (it.t.epochSecond / 60) !in sent }
                    .distinctBy { it.t.epochSecond / 60 }
                    .sortedBy { it.t }
            }
        val upserts = ArrayList<TreatmentRecord>()
        val deletes = ArrayList<TreatmentRecord>()
        val settled = ArrayList<TreatmentRecord>()
        if (o.treatments) {
            for (r in treatments) {
                val t = r.treatment
                val had = state.treatments[t.id]
                if (had == t.modifiedAt.toEpochMilli()) continue
                when {
                    had == null && (t.deleted || t.at.epochSecond < from) -> if (t.deleted) settled += r
                    t.deleted -> deletes += r
                    else -> upserts += r
                }
            }
        }
        val s = sensor?.takeIf { o.sensor && it.serial.isNotEmpty() && it.serial !in state.sensors && it.start.epochSecond >= from }
        return Plan(entries, upserts, deletes, settled, s)
    }

    /** The site has these now (minutes older than the window are forgotten). */
    fun sentEntries(
        state: NsState,
        entries: List<Reading>,
        nowS: Long,
    ): NsState {
        val keep = (nowS - WINDOW_S - 86_400) / 60
        val m = SentRanges.expand(state.minutes).apply { addAll(entries.map { it.t.epochSecond / 60 }) }.tailSet(keep)
        return state.copy(minutes = SentRanges.ranges(m))
    }

    fun sentTreatments(
        state: NsState,
        ts: List<TreatmentRecord>,
    ): NsState = state.copy(treatments = state.treatments + ts.associate { it.treatment.id to it.treatment.modifiedAt.toEpochMilli() })

    /** Forgets treatment versions no longer in the stores' window (bounded memory). */
    fun prune(
        state: NsState,
        known: Set<String>,
    ): NsState = state.copy(treatments = state.treatments.filterKeys { it in known })

    fun sentSensor(
        state: NsState,
        serial: String,
    ): NsState = state.copy(sensors = (state.sensors + serial).takeLast(10))

    /** After a good cycle: backoff reset. */
    fun ok(state: NsState): NsState = state.copy(failures = 0, nextAttemptMs = 0, failingSinceMs = 0, lastError = null)

    /** 30 s doubling to 15 min, ±20 %; the site's `Retry-After` wins when longer. */
    fun failed(
        state: NsState,
        nowMs: Long,
        why: String,
        retryAfterMs: Long? = null,
        random: Random = Random.Default,
    ): NsState {
        val f = state.failures + 1
        val wait = maxOf(UploadBackoff.ms(f, BASE_BACKOFF_MS, MAX_BACKOFF_MS, random), retryAfterMs ?: 0)
        return state.copy(
            failures = f,
            nextAttemptMs = nowMs + wait,
            failingSinceMs = state.failingSinceMs.takeIf { it > 0 } ?: nowMs,
            lastError = why,
        )
    }

    /** First run, after failures and every 6 h: how far back to diff against the site, or null for not now. */
    fun reconcileWindowS(
        state: NsState,
        nowMs: Long,
    ): Long? =
        when {
            state.reconciledMs == 0L -> {
                WINDOW_S
            }

            state.failingSinceMs > 0 -> {
                minOf(
                    WINDOW_S,
                    maxOf(RECONCILE_WINDOW_S, (nowMs - state.failingSinceMs) / 1000 + RECONCILE_WINDOW_S),
                )
            }

            nowMs - state.reconciledMs >= RECONCILE_EVERY_MS -> {
                RECONCILE_WINDOW_S
            }

            else -> {
                null
            }
        }

    /** A site entry is ours when its `device` is exactly this install's name. */
    fun ours(
        dev: String,
        device: String,
    ) = dev == device

    /**
     * Apply a diff: [server] = the site's sgv entries in [fromS, toS] (date ms → device). Minutes we sent that the site
     * no longer has are forgotten (sent again next); minutes within 2 min of another uploader's entry count as covered
     * (a backfill never doubles someone else's readings); another device's entries in the last 30 min pause everything.
     */
    fun reconciled(
        state: NsState,
        server: Map<Long, String>,
        fromS: Long,
        toS: Long,
        nowMs: Long,
    ): NsState {
        val have = server.keys.map { it / 60_000 }.toHashSet()
        val lo = fromS / 60
        val hi = toS / 60
        val kept = SentRanges.expand(state.minutes).filter { it < lo || it > hi || it in have }.toMutableSet()
        val theirs = server.filter { (_, dev) -> !ours(dev, state.device) }
        for (ms in theirs.keys) for (d in -2L..2L) kept += ms / 60_000 + d
        val others =
            theirs
                .filter { (ms, _) -> ms / 1000 >= toS - CONFLICT_WINDOW_S }
                .values
                .map { it.ifEmpty { "?" } }
                .toSortedSet()
        val conflict = others.takeIf { it.isNotEmpty() }?.joinToString()
        return state.copy(
            minutes = SentRanges.ranges(kept),
            reconciledMs = nowMs,
            conflict = conflict,
            conflictAccepted = state.conflictAccepted && conflict != null,
        )
    }
}
