// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.integrations.healthconnect

import app.mauri.sukrara.core.data.settings.SchemaSettings
import app.mauri.sukrara.core.model.Clock
import app.mauri.sukrara.core.model.Reading
import app.mauri.sukrara.core.model.Role
import app.mauri.sukrara.core.model.SourceId
import app.mauri.sukrara.core.model.storage.ReadingsRepository
import app.mauri.sukrara.core.model.storage.SettingsRepository
import app.mauri.sukrara.core.model.storage.StateStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.time.Duration
import java.time.Instant

/** One blood glucose record for Health Connect: the minute's id makes writing it again an update, never a copy. */
data class HcGlucose(
    val t: Instant,
    val mgdl: Int,
    val clientId: String,
    /** Manufacturer and model of the sensor that measured it; null parts are left out. */
    val manufacturer: String?,
    val model: String?,
)

/** Health Connect refused a write: [code] is `HealthConnectException.errorCode` (rate limit, not allowed, …). */
class HealthWriteException(
    val code: Int,
) : Exception("health connect error $code")

/** Health Connect as the sync needs it; the platform one on the phone, a fake in tests. */
interface HealthStore {
    /** Health Connect exists on this phone (it can be disabled or missing on some builds). */
    fun available(): Boolean

    /** The write permission is granted now (it can be revoked at any time in Health Connect). */
    fun granted(): Boolean

    /** Inserts or updates (same clientId) [records]; throws [HealthWriteException] or SecurityException. */
    suspend fun insert(records: List<HcGlucose>)
}

/**
 * What the sync remembers: the minutes already written (as runs `[first, last]` of epoch minutes, the last
 * [HealthConnectSync.BACKFILL] only), when it last wrote and how many, and the last error with when to try again.
 */
@Serializable
data class HcState(
    val written: List<List<Long>> = emptyList(),
    /** The stored history (up to [HealthConnectSync.BACKFILL]) went in completely once since switching on. */
    val backfillDone: Boolean = false,
    val lastWriteMs: Long = 0,
    val lastCount: Int = 0,
    val errorCode: Int? = null,
    val errorAtMs: Long = 0,
    val failures: Int = 0,
    val retryAtMs: Long = 0,
)

/** Sorted, merged runs of epoch minutes: a month of minute-by-minute readings is a handful of pairs. */
object MinuteRuns {
    fun contains(
        runs: List<List<Long>>,
        m: Long,
    ): Boolean = runs.any { m >= it[0] && m <= it[1] }

    /** [runs] plus [minutes], merged, without anything before [keepFrom]. */
    fun add(
        runs: List<List<Long>>,
        minutes: Collection<Long>,
        keepFrom: Long,
    ): List<List<Long>> {
        val all = (runs.map { it[0] to it[1] } + minutes.map { it to it }).sortedBy { it.first }
        val out = ArrayList<LongArray>()
        for ((a, b) in all) {
            val last = out.lastOrNull()
            if (last != null && a <= last[1] + 1) last[1] = maxOf(last[1], b) else out += longArrayOf(a, b)
        }
        return out.filter { it[1] >= keepFrom }.map { listOf(maxOf(it[0], keepFrom), it[1]) }
    }
}

/** The Health Connect page's status line. */
sealed interface HcStatus {
    data object Off : HcStatus

    data object Unavailable : HcStatus

    /** On, but the permission isn't (or no longer) granted: nothing is written until it is. */
    data object NeedsPermission : HcStatus

    /** A follower's phone writes nothing (the followed person's glucose isn't this phone owner's health record). */
    data object Follower : HcStatus

    data object Waiting : HcStatus

    data class Written(val at: Instant, val count: Int) : HcStatus

    data class Failed(val code: Int, val at: Instant) : HcStatus
}

/**
 * Writes the wearer's readings to Health Connect as blood glucose (interstitial fluid), opt-in (`healthConnect.on`,
 * off by default; turning it on asks Health Connect's own permission sheet first). Switched on, the stored history (up
 * to [BACKFILL]) goes in once, then each new reading. Only this phone's own sensor readings ([SourceId.Wearer], by the
 * reading's stored source, so a follower feed kept from another role never goes in) and never LO / HI (no number).
 *
 * Idempotent: each record's clientRecordId is its minute ([clientId]), so writing a minute again updates it. The
 * written minutes are remembered ([MinuteRuns]), so each pass writes only new minutes: a new reading, or readings a
 * reconnect backfilled in the last [RECHECK]. A rate limit backs off from 15 minutes. Every failure (rate limit,
 * permission revoked, Health Connect missing) becomes a state on the page: nothing here can crash the process the
 * alerts run in.
 */
class HealthConnectSync(
    private val settings: SettingsRepository,
    private val store: StateStore<HcState>,
    private val readings: ReadingsRepository,
    private val role: Flow<Role?>,
    private val health: HealthStore,
    private val clock: Clock,
    private val io: CoroutineDispatcher,
    private val log: (String) -> Unit,
) {
    val on: Flow<Boolean> = settings.flow(SETTING)

    /** Bumped when the page comes back (the permission may have changed in Health Connect meanwhile). */
    private val recheck = MutableStateFlow(0)
    private val granted = MutableStateFlow(false)
    private val lock = Mutex()

    val status: Flow<HcStatus> =
        combine(on, role, store.data, granted) { o, r, st, g ->
            when {
                r == Role.Follower -> HcStatus.Follower
                !health.available() -> HcStatus.Unavailable
                !o -> HcStatus.Off
                !g -> HcStatus.NeedsPermission
                st.errorCode != null -> HcStatus.Failed(st.errorCode, Instant.ofEpochMilli(st.errorAtMs))
                st.lastWriteMs > 0 -> HcStatus.Written(Instant.ofEpochMilli(st.lastWriteMs), st.lastCount)
                else -> HcStatus.Waiting
            }
        }.distinctUntilChanged()

    /** On after the permission was granted (the page asks first); off stops writing, the records stay. */
    suspend fun setOn(value: Boolean) {
        // switched on again: the whole stored history goes in again (idempotent), as the first time
        if (value) store.update { HcState() }
        settings.set(SETTING, value)
        recheck()
    }

    fun recheck() {
        granted.value = runCatching { health.available() && health.granted() }.getOrDefault(false)
        recheck.value++
    }

    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    fun start(scope: CoroutineScope) {
        recheck()
        scope.launch {
            combine(on, role, granted, recheck) { o, r, g, _ -> o && g && r == Role.Wearer }
                .flatMapLatest { active ->
                    if (!active) {
                        emptyFlow()
                    } else {
                        readings
                            .latest()
                            .map { it?.t }
                            .distinctUntilChanged()
                            .onStart { emit(null) }
                            // a failing store skips this round; the switch or the next recheck starts it again
                            .catch { e -> log("health connect paused: ${e.javaClass.simpleName}") }
                    }
                }
                // a driver backfill lands as many upserts at once: one pass after it settles
                .debounce(DEBOUNCE_MS)
                // a running pass (the 30-day copy can take minutes) is never cancelled by a new reading: it finishes,
                // then one more pass covers what arrived meanwhile
                .conflate()
                .collect { pass() }
        }
    }

    /** Writes what isn't in Health Connect yet; every error becomes state. */
    internal suspend fun pass() =
        lock.withLock {
            try {
                withContext(io) { write() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: HealthWriteException) {
                fail(e.code)
            } catch (e: SecurityException) {
                // revoked in Health Connect: the page shows Allow again / turn off
                granted.value = false
            } catch (e: Throwable) {
                fail(ERROR_OTHER)
            }
        }

    /** Records the failure and when to try again; a failing store here too is only logged (never a crash). */
    private suspend fun fail(code: Int) {
        log("health connect write failed: $code")
        val now = clock.now().toEpochMilli()
        try {
            backOff(code, now)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            log("health connect state not saved: ${e.javaClass.simpleName}")
        }
    }

    private suspend fun backOff(
        code: Int,
        now: Long,
    ) {
        store.update {
            val n = it.failures + 1
            // Health Connect's quota refills slowly: back off from 15 min (doubling, ≤ 4 h); other errors retry sooner
            val base = if (code == ERROR_RATE_LIMIT) RATE_LIMIT_BACKOFF else Duration.ofMinutes(5)
            val wait = minOf(base.multipliedBy(1L shl minOf(n - 1, 4)), Duration.ofHours(4))
            it.copy(errorCode = code, errorAtMs = now, failures = n, retryAtMs = now + wait.toMillis())
        }
    }

    private suspend fun write() {
        if (!health.available() || !health.granted()) {
            granted.value = false
            return
        }
        val now = clock.now()
        val st = store.data.first()
        if (now.toEpochMilli() < st.retryAtMs) return
        val keepFrom = (now - BACKFILL).epochSecond / 60
        // until the whole stored history went in once (a rate limit or a stop can cut the first copy short), each pass
        // looks at all of it; then at the last day, where a reconnect's backfill lands: only minutes not written yet
        // go in (a new reading, or a gap the sensor filled)
        val from = if (!st.backfillDone) now - BACKFILL else now - RECHECK
        val rows =
            qualifying(readings.range(from, now + MAX_AHEAD), now)
                .filter { !MinuteRuns.contains(st.written, it.t.epochSecond / 60) }
        if (rows.isEmpty()) {
            if (!st.backfillDone) store.update { it.copy(backfillDone = true) }
            return
        }
        // oldest first, and the written minutes saved after each batch: a failure mid-way keeps what went in
        for (batch in rows.chunked(BATCH)) {
            health.insert(batch)
            store.update { it.copy(written = MinuteRuns.add(it.written, batch.map { b -> b.t.epochSecond / 60 }, keepFrom)) }
        }
        store.update {
            it.copy(
                lastWriteMs = now.toEpochMilli(),
                lastCount = rows.size,
                backfillDone = true,
                errorCode = null,
                failures = 0,
                retryAtMs = 0,
            )
        }
    }

    companion object {
        val SETTING = SchemaSettings.bool("healthConnect.on", default = false)
        val BACKFILL: Duration = Duration.ofDays(30)
        val RECHECK: Duration = Duration.ofDays(1)
        val RATE_LIMIT_BACKOFF: Duration = Duration.ofMinutes(15)

        /** `HealthConnectException.ERROR_RATE_LIMIT_EXCEEDED`. */
        const val ERROR_RATE_LIMIT = 7
        val MAX_AHEAD: Duration = Duration.ofMinutes(1)
        const val BATCH = 500
        const val DEBOUNCE_MS = 2_000L
        const val ERROR_OTHER = -1

        /** The record id Health Connect dedupes on: one per minute of glucose. */
        fun clientId(t: Instant) = "sukrara-glucose-${t.epochSecond / 60}"

        /**
         * The readings that go in: this phone's own sensor, a number (not LO / HI), not in the future, one per minute
         * (the newest of a minute: the same id would overwrite it anyway).
         */
        fun qualifying(
            list: List<Reading>,
            now: Instant,
        ): List<HcGlucose> =
            list
                .asSequence()
                .filter { it.source in SourceId.Wearer && !it.isLo && !it.isHi && it.t <= now + MAX_AHEAD }
                .groupBy { it.t.epochSecond / 60 }
                .values
                .map { m -> m.maxBy { it.t } }
                .sortedBy { it.t }
                .map { r ->
                    val (maker, model) = device(r.source)
                    HcGlucose(r.t, r.mgdl, clientId(r.t), maker, model)
                }

        /** The measuring device as Health Connect shows it; families not tested on real sensors say so. */
        fun device(source: SourceId): Pair<String?, String?> =
            when (source) {
                SourceId.Libre3 -> "Abbott" to "FreeStyle Libre 3"
                SourceId.Juggluco -> null to "Juggluco"
                SourceId.DexcomG7 -> "Dexcom" to "G7 (untested)"
                SourceId.DexcomG6 -> "Dexcom" to "G6 (untested)"
                SourceId.SmartGuide -> "Roche" to "Accu-Chek SmartGuide (untested)"
                SourceId.AidexX -> "LinX" to "AiDEX X (untested)"
                else -> null to null
            }
    }
}
