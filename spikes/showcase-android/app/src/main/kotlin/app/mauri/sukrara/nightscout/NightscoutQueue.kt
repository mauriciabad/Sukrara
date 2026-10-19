// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.nightscout

import app.mauri.sukrara.core.Reading
import app.mauri.sukrara.core.SensorInfo
import app.mauri.sukrara.treatments.Treatment
import app.mauri.sukrara.upload.SentRanges
import app.mauri.sukrara.upload.UploadBackoff
import kotlin.random.Random
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What still has to go to Nightscout, worked out from the wearer's stores every cycle (the shared upload design,
 * `upload/Uploads.kt`): the readings the phone keeps and the treatment log are the queue; [State] remembers what the
 * site has. A periodic diff against the site ([NightscoutSync.reconcile]) forgets minutes the site lost, so a wiped or
 * restored database heals the same way as an outage. Pure Kotlin (JVM tests).
 */
object NightscoutQueue {
    const val WINDOW_S = 30 * 86_400L           // backfill: 30 days (the phone keeps 31)
    const val BATCH = 500                       // v1 entries per POST
    const val V3_MAX_PER_CYCLE = 240            // v3 has no bulk insert: one request per entry
    const val RECONCILE_EVERY_MS = 6 * 3_600_000L
    const val RECONCILE_WINDOW_S = 86_400L
    const val CONFLICT_WINDOW_S = 30 * 60L      // another device's entries this recent pause ours
    const val BATTERY_EVERY_MS = 30 * 60_000L
    private const val BASE_BACKOFF_MS = 30_000L
    const val MAX_BACKOFF_MS = 15 * 60_000L

    @Serializable
    data class State(
        val from: Long = 0,                                 // start of the upload window (epoch s), set when switched on
        val minutes: List<LongArray> = emptyList(),         // sent reading minutes (epoch s / 60) as [first, last] ranges
        val treatments: Map<String, Long> = emptyMap(),     // treatment id → the version (`u`) the site has
        val sensors: List<String> = emptyList(),            // sensor serials whose start went up
        val batteryPercent: Int = -1,
        val batteryMs: Long = 0,
        val reconciledMs: Long = 0,
        val failures: Int = 0,
        val nextAttemptMs: Long = 0,
        val failingSinceMs: Long = 0,
        val conflict: String? = null,                       // the other device(s) writing entries, while paused
        val conflictAccepted: Boolean = false,
    ) {
        fun sentMinutes(): java.util.TreeSet<Long> = SentRanges.expand(minutes)

        override fun equals(other: Any?) = other is State && from == other.from && treatments == other.treatments &&
            sensors == other.sensors && batteryPercent == other.batteryPercent && batteryMs == other.batteryMs &&
            reconciledMs == other.reconciledMs && failures == other.failures && nextAttemptMs == other.nextAttemptMs &&
            failingSinceMs == other.failingSinceMs && conflict == other.conflict && conflictAccepted == other.conflictAccepted &&
            minutes.size == other.minutes.size && minutes.indices.all { minutes[it].contentEquals(other.minutes[it]) }
        override fun hashCode() = from.hashCode() * 31 + treatments.hashCode()
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    fun encode(s: State): String = json.encodeToString(State.serializer(), s)
    fun decode(s: String?): State = s?.let { runCatching { json.decodeFromString(State.serializer(), it) }.getOrNull() } ?: State()

    /** What the user lets go up (Settings → Share with apps → Nightscout upload → What to send). */
    data class Options(val readings: Boolean = true, val treatments: Boolean = true, val sensor: Boolean = true, val battery: Boolean = true)

    data class Plan(
        val entries: List<Reading>,          // oldest first
        val upserts: List<Treatment>,
        val deletes: List<Treatment>,        // deleted entries the site has
        val settled: List<Treatment>,        // nothing to send (deleted before it ever went up): just remember
        val sensor: SensorInfo?,
    ) {
        val isEmpty get() = entries.isEmpty() && upserts.isEmpty() && deletes.isEmpty() && settled.isEmpty() && sensor == null
        val count get() = entries.size + upserts.size + deletes.size + (if (sensor != null) 2 else 0)
    }

    fun plan(readings: List<Reading>, treatments: List<Treatment>, sensor: SensorInfo?, state: State, o: Options, nowS: Long): Plan {
        val from = maxOf(state.from, nowS - WINDOW_S)
        val sent = state.sentMinutes()
        val paused = state.conflict != null && !state.conflictAccepted
        val entries = if (!o.readings || paused) emptyList() else
            readings.filter { it.t in from..(nowS + 60) && (it.t / 60) !in sent }.distinctBy { it.t / 60 }.sortedBy { it.t }
        val upserts = ArrayList<Treatment>(); val deletes = ArrayList<Treatment>(); val settled = ArrayList<Treatment>()
        if (o.treatments) for (t in treatments) {
            val had = state.treatments[t.id]
            if (had == t.u) continue
            when {
                had == null && (t.del || t.t < from) -> if (t.del) settled += t
                t.del -> deletes += t
                else -> upserts += t
            }
        }
        val s = sensor?.takeIf { o.sensor && it.serial.isNotEmpty() && it.serial !in state.sensors && it.startEpoch >= from }
        return Plan(entries, upserts, deletes, settled, s)
    }

    /** The site has these now. */
    fun sentEntries(state: State, entries: List<Reading>, nowS: Long): State {
        val keep = (nowS - WINDOW_S - 86_400) / 60
        val m = state.sentMinutes().apply { addAll(entries.map { it.t / 60 }) }.tailSet(keep)
        return state.copy(minutes = SentRanges.ranges(m))
    }

    fun sentTreatments(state: State, ts: List<Treatment>): State =
        state.copy(treatments = state.treatments + ts.associate { it.id to it.u })

    fun sentSensor(state: State, serial: String): State = state.copy(sensors = (state.sensors + serial).takeLast(10))

    /** After a good cycle: backoff reset. */
    fun ok(state: State): State = state.copy(failures = 0, nextAttemptMs = 0, failingSinceMs = 0)

    /** 30 s doubling to 15 min, ±20 %; the site's `Retry-After` wins when longer. */
    fun failed(state: State, nowMs: Long, retryAfterMs: Long? = null, random: Random = Random.Default): State {
        val f = state.failures + 1
        val wait = maxOf(UploadBackoff.ms(f, BASE_BACKOFF_MS, MAX_BACKOFF_MS, random), retryAfterMs ?: 0)
        return state.copy(failures = f, nextAttemptMs = nowMs + wait, failingSinceMs = state.failingSinceMs.takeIf { it > 0 } ?: nowMs)
    }

    /** First run, after failures and every 6 h: how far back to diff against the site, or null for not now. */
    fun reconcileWindowS(state: State, nowMs: Long): Long? = when {
        state.reconciledMs == 0L -> WINDOW_S
        state.failingSinceMs > 0 -> minOf(WINDOW_S, maxOf(RECONCILE_WINDOW_S, (nowMs - state.failingSinceMs) / 1000 + RECONCILE_WINDOW_S))
        nowMs - state.reconciledMs >= RECONCILE_EVERY_MS -> RECONCILE_WINDOW_S
        else -> null
    }

    /**
     * Apply a diff: [server] = the site's sgv entries in [fromS, toS] (date ms → device). Minutes we sent that the site
     * no longer has are forgotten (sent again next); another device's entries in the last 30 min pause ours.
     */
    fun reconciled(state: State, server: Map<Long, String>, fromS: Long, toS: Long, device: String, nowMs: Long): State {
        val have = server.keys.map { it / 60_000 }.toHashSet()
        val lo = fromS / 60; val hi = toS / 60
        val kept = state.sentMinutes().filter { it < lo || it > hi || it in have }
        val others = server.filter { (ms, dev) -> ms / 1000 >= toS - CONFLICT_WINDOW_S && !NsDocs.ours(dev, device) }.values.toSortedSet()
        val conflict = others.takeIf { it.isNotEmpty() }?.joinToString()
        return state.copy(minutes = SentRanges.ranges(kept), reconciledMs = nowMs, conflict = conflict,
            conflictAccepted = state.conflictAccepted && conflict != null)
    }
}
