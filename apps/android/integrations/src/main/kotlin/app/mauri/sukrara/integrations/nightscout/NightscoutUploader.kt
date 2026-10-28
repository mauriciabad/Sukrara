// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.integrations.nightscout

import app.mauri.sukrara.core.data.settings.SchemaSettings
import app.mauri.sukrara.core.model.Clock
import app.mauri.sukrara.core.model.Reading
import app.mauri.sukrara.core.model.Role
import app.mauri.sukrara.core.model.storage.ReadingsRepository
import app.mauri.sukrara.core.model.storage.SecureStore
import app.mauri.sukrara.core.model.storage.SettingsRepository
import app.mauri.sukrara.core.model.storage.StateStore
import app.mauri.sukrara.core.model.storage.TreatmentsRepository
import app.mauri.sukrara.integrations.CurrentSensor
import app.mauri.sukrara.integrations.upload.UploadLoop
import app.mauri.sukrara.integrations.upload.UploadStatus
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant

/** The page's switches (schema `apps.nightscoutUpload`, JSON in store `integrations`); never the credential. */
@Serializable
data class NsSettings(
    val on: Boolean = false,
    val url: String = "",
    val api: NsApi = NsApi.V1,
    val readings: Boolean = true,
    val treatments: Boolean = true,
    val sensor: Boolean = true,
    val battery: Boolean = true,
) {
    val options get() = NsOptions(readings, treatments, sensor, battery)
}

/** Which kind of credential is saved (its value never leaves the uploader). */
enum class NsCredentialKind { Token, Secret }

/** What the Nightscout page shows. */
data class NsPageState(
    val settings: NsSettings,
    val credential: NsCredentialKind?,
    val status: UploadStatus,
) {
    val configured: Boolean get() = settings.url.isNotEmpty() && credential != null
}

/** The result of *Test connection*. */
sealed interface NsTestResult {
    data class Ok(val version: String?) : NsTestResult

    data object AuthFailed : NsTestResult

    data class Failed(val why: String) : NsTestResult
}

/**
 * Settings › Share with apps › Nightscout upload: the wearer's readings (30 days back), treatments, sensor starts and
 * the phone's battery to the person's own Nightscout site. Off by default; wearer only; upload only (nothing is read
 * back into Sukrara except the site's entry dates for the duplicate check).
 *
 * The token / API secret lives only in the [SecureStore] (`nightscout`). One cycle after every reading, every changed
 * entry and every 5 minutes plans what the site lacks ([NightscoutQueue]) and sends it. A refused credential stops
 * uploading until it's changed; another uploader writing the same site pauses everything until *Upload anyway*.
 */
class NightscoutUploader(
    private val settings: SettingsRepository,
    private val secure: SecureStore,
    private val store: StateStore<NsState>,
    private val readings: ReadingsRepository,
    private val treatments: TreatmentsRepository,
    private val sensor: Flow<CurrentSensor?>,
    private val role: Flow<Role?>,
    /** The phone's battery (percent, charging), null when unknown. */
    private val battery: () -> Pair<Int, Boolean>?,
    private val http: OkHttpClient,
    private val clock: Clock,
    private val io: CoroutineDispatcher,
    private val log: (String) -> Unit = {},
) {
    private val credential = MutableStateFlow<NsCredentialKind?>(null)
    private val loop = UploadLoop(INTERVAL_MS, io, { e -> log("nightscout cycle: ${e.javaClass.simpleName}") }) { cycle() }
    private var client: Pair<NsConfig, NsClient>? = null

    /** One writer of [store] at a time: a cycle's network calls never overwrite a newer *Upload anyway*, token or removal. */
    private val mutex = Mutex()

    /** When the last cycle planned over the whole 30-day window (in between, only the last hours are read). */
    private var fullScanMs = 0L

    val settingsFlow: Flow<NsSettings> = settings.flow(SETTING).map(::decode)

    val page: Flow<NsPageState> =
        combine(settingsFlow, credential, store.data, role) { s, c, st, r ->
            NsPageState(s, c, status(s, c, st, r))
        }

    /** Reads which credential is saved (the page and the loop need it); call once from the app's load step. */
    suspend fun load() {
        credential.value = readAuth()?.let { if (it is NsAuth.AccessToken) NsCredentialKind.Token else NsCredentialKind.Secret }
    }

    /** Starts the loop: a cycle now, on every new reading / entry change / setting change, and every 5 minutes. */
    fun start(scope: CoroutineScope) {
        if (!loop.start(scope)) return
        scope.launch { readings.latest().drop(1).collect { loop.kick() } }
        scope.launch {
            val now = clock.now()
            treatments.between(now - Duration.ofDays(31), now + Duration.ofDays(3650)).drop(1).collect { loop.kick() }
        }
        scope.launch { settingsFlow.distinctUntilChanged().drop(1).collect { loop.kick() } }
        scope.launch { role.distinctUntilChanged().drop(1).collect { loop.kick() } }
    }

    // MARK: settings

    /**
     * Saves the site and, when [credential] isn't null, the token / secret (only in the secure store). A different
     * site starts from an empty state (its own 30-day backfill, a new device name); the same site keeps what it has.
     * Clears a refused-credential stop.
     */
    suspend fun configure(
        url: String,
        kind: NsCredentialKind,
        credential: String?,
        api: NsApi,
    ) = mutex.withLock {
        val site = NsSite.parse(url)
        require(site.problem == null) { "unusable address" }
        // a saved token is only ever sent to the site it was saved for
        require(credential != null || site.root == settingsFlow.first().url) { "a new site needs its token" }
        if (credential != null) {
            secure.write(NAME, Json.encodeToString(StoredCredential.serializer(), StoredCredential(kind, credential.trim())).toByteArray())
        }
        load()
        val savedKind = this.credential.value
        // API v3 needs an access token: a secret keeps v1
        val chosenApi = if (savedKind == NsCredentialKind.Token) api else NsApi.V1
        settings.update(SETTING) { encode(decode(it).copy(url = site.root, api = chosenApi)) }
        store.update { st ->
            if (st.site ==
                site.root
            ) {
                st.copy(authFailed = false, failures = 0, nextAttemptMs = 0)
            } else {
                NsState(site = site.root, device = newDevice())
            }
        }
        client = null
        fullScanMs = 0
        loop.kick()
    }

    suspend fun change(transform: (NsSettings) -> NsSettings) {
        var turnedOn = false
        settings.update(SETTING) {
            val old = decode(it)
            val new = transform(old)
            turnedOn = new.on && !old.on
            encode(new)
        }
        // switching on tries at once, whatever an earlier backoff said
        if (turnedOn) mutex.withLock { store.update { it.copy(failures = 0, nextAttemptMs = 0) } }
        loop.kick()
    }

    /** *Upload anyway*: the person confirmed this phone uploads next to the other device. */
    suspend fun acceptConflict() {
        mutex.withLock { store.update { it.copy(conflictAccepted = true) } }
        loop.kick()
    }

    /** *Remove this site*: forgets the site, the credential and what was sent (what's on the site stays there). */
    suspend fun remove() =
        mutex.withLock {
            secure.delete(NAME)
            credential.value = null
            settings.set(SETTING, encode(NsSettings()))
            store.update { NsState() }
            client = null
        }

    /** *Test connection*: the site's status with this credential (read-only, nothing is written). */
    suspend fun test(
        url: String,
        kind: NsCredentialKind,
        credential: String?,
        api: NsApi,
    ): NsTestResult =
        withContext(io) {
            val site = NsSite.parse(url)
            if (site.problem != null) return@withContext NsTestResult.Failed("address")
            // the saved token only for the site it was saved for
            val saved = if (site.root == settingsFlow.first().url) readAuth() else null
            val auth = credential?.trim()?.takeIf { it.isNotEmpty() }?.let { authOf(kind, it) } ?: saved
            if (auth == null) return@withContext NsTestResult.Failed("no token")
            val chosenApi = if (auth is NsAuth.AccessToken) api else NsApi.V1
            val (version, o) = NsClient(NsConfig(site, auth, chosenApi, "test"), http) { clock.now().toEpochMilli() }.status()
            when (o) {
                is Outcome.Ok -> NsTestResult.Ok(version)
                is Outcome.AuthFailed -> NsTestResult.AuthFailed
                is Outcome.Retry -> NsTestResult.Failed(o.reason)
                is Outcome.Rejected -> NsTestResult.Failed(o.reason)
            }
        }

    fun kick() = loop.kick()

    // MARK: the cycle

    /** One cycle (internal for tests). */
    internal suspend fun cycle() = mutex.withLock { cycleLocked() }

    private suspend fun cycleLocked() {
        val s = settingsFlow.first()
        if (!s.on || role.first() != Role.Wearer) return
        val auth = readAuth() ?: return
        val site = NsSite.parse(s.url)
        if (site.problem != null) return
        var state = store.data.first()
        if (state.site != site.root) {
            state = NsState(site = site.root, device = newDevice())
            save(state)
        }
        if (state.authFailed) return
        val nowMs = clock.now().toEpochMilli()
        val nowS = nowMs / 1000
        if (nowMs < state.nextAttemptMs) return
        if (state.from == 0L) state = state.copy(from = nowS - NightscoutQueue.WINDOW_S).also { save(it) }
        val cfg = NsConfig(site, auth, if (auth is NsAuth.AccessToken) s.api else NsApi.V1, state.device)
        val c = client?.takeIf { it.first == cfg }?.second ?: NsClient(cfg, http) { clock.now().toEpochMilli() }.also { client = cfg to it }
        try {
            val before = state.reconciledMs
            state = withContext(io) { reconcile(c, state, nowMs) }.also { save(it) }
            val since = clock.now() - Duration.ofSeconds(NightscoutQueue.WINDOW_S)
            // the whole window after a comparison with the site and every 30 min; in between only the last hours
            val full = state.reconciledMs != before || nowMs - fullScanMs >= FULL_SCAN_EVERY_MS
            val start = if (full) maxOf(Instant.ofEpochSecond(state.from), since) else clock.now() - RECENT
            val rs = readings.range(start - Duration.ofMinutes(15), clock.now() + Duration.ofMinutes(1))
            if (full) fullScanMs = nowMs
            val ts = treatments.changedSince(since - Duration.ofDays(1))
            state = NightscoutQueue.prune(state, ts.map { it.treatment.id }.toSet())
            val plan = NightscoutQueue.plan(rs, ts, sensor.first(), state, s.options, nowS)
            var sent = 0
            if (!plan.isEmpty) {
                val r = withContext(io) { NightscoutSync.send(c, cfg, plan, state, rs, clock.zone, nowS) { st -> state = st } }
                save(state)
                sent = r.sent
            }
            if (s.battery && !state.paused) {
                battery()?.let { (p, charging) ->
                    withContext(io) { NightscoutSync.battery(c, cfg, state, p, charging, clock.now(), clock.zone) }?.let { state = it }
                }
            }
            state = NightscoutQueue.ok(state)
            if (sent > 0) state = state.copy(lastOkMs = nowMs, lastCount = sent)
            save(state)
        } catch (e: NsFailure) {
            val o = e.outcome
            state =
                if (o is Outcome.AuthFailed) {
                    log("nightscout: credential refused")
                    state.copy(authFailed = true, lastError = null)
                } else {
                    NightscoutQueue.failed(state, nowMs, e.message.orEmpty(), (o as? Outcome.Retry)?.afterMs)
                }
            save(state)
        }
    }

    private suspend fun save(st: NsState) = store.update { st }

    private fun reconcile(
        c: NsClient,
        state: NsState,
        nowMs: Long,
    ): NsState {
        val windowS = NightscoutQueue.reconcileWindowS(state, nowMs) ?: return state
        val toS = nowMs / 1000
        val fromS = toS - windowS
        val (server, o) = c.entriesBetween(fromS * 1000, toS * 1000, max = (windowS / 30).toInt().coerceIn(100, 100_000))
        if (server == null) throw NsFailure(o)
        return NightscoutQueue.reconciled(state, server, fromS, toS, nowMs)
    }

    private suspend fun readAuth(): NsAuth? {
        val bytes = runCatching { secure.read(NAME) }.getOrNull() ?: return null
        val c = runCatching { Json.decodeFromString(StoredCredential.serializer(), bytes.decodeToString()) }.getOrNull() ?: return null
        return authOf(c.kind, c.value)
    }

    private fun authOf(
        kind: NsCredentialKind,
        value: String,
    ): NsAuth = if (kind == NsCredentialKind.Secret) NsAuth.ApiSecret(value) else NsAuth.AccessToken(value)

    private fun status(
        s: NsSettings,
        c: NsCredentialKind?,
        st: NsState,
        r: Role?,
    ): UploadStatus =
        when {
            !s.on || r != Role.Wearer -> UploadStatus.Off
            s.url.isEmpty() || c == null -> UploadStatus.Setup
            st.authFailed -> UploadStatus.AuthFailed
            st.paused -> UploadStatus.Paused(st.conflict.orEmpty())
            st.lastError != null -> UploadStatus.Failing(st.lastError, st.lastOkMs.takeIf { it > 0 }?.let(Instant::ofEpochMilli))
            st.lastOkMs > 0 -> UploadStatus.Ok(Instant.ofEpochMilli(st.lastOkMs), st.lastCount)
            else -> UploadStatus.Waiting
        }

    @Serializable
    private data class StoredCredential(
        val kind: NsCredentialKind,
        val value: String,
    ) {
        override fun toString() = "StoredCredential($kind, ***)"
    }

    companion object {
        const val INTERVAL_MS = 5 * 60_000L
        const val FULL_SCAN_EVERY_MS = 30 * 60_000L
        private val RECENT: Duration = Duration.ofHours(3)
        const val NAME = "nightscout"
        val SETTING = SchemaSettings.text("apps.nightscoutUpload", default = "")
        private val json = Json { ignoreUnknownKeys = true }
        private val random = SecureRandom()

        fun decode(text: String): NsSettings =
            text.takeIf { it.isNotEmpty() }?.let { runCatching { json.decodeFromString(NsSettings.serializer(), it) }.getOrNull() }
                ?: NsSettings()

        fun encode(s: NsSettings): String = json.encodeToString(NsSettings.serializer(), s)

        /** This install's name on a site: `Sukrara-` + 6 hex, so another uploader (or another install) is never "us". */
        fun newDevice(): String = "Sukrara-" + ByteArray(3).also(random::nextBytes).joinToString("") { "%02x".format(it) }
    }
}

/** A cycle that stopped: [outcome] says whether to retry later or stop until the credential is fixed. */
class NsFailure(
    val outcome: Outcome,
) : Exception(
        when (outcome) {
            is Outcome.Retry -> outcome.reason
            is Outcome.AuthFailed -> outcome.reason
            is Outcome.Rejected -> outcome.reason
            is Outcome.Ok -> "ok"
        },
    )

/**
 * Sends one [NightscoutQueue.Plan] (blocking: IO). Entries go oldest first in batches, each batch remembered at once
 * through [save] so a 30-day backfill keeps its progress through a failure; then treatments, then the sensor start. A
 * document the site refuses (a 4xx other than 401 / 403) counts as sent: one bad document must not block the rest.
 */
object NightscoutSync {
    data class Result(
        val sent: Int,
        val dropped: Int,
    )

    fun send(
        client: NsClient,
        cfg: NsConfig,
        plan: NightscoutQueue.Plan,
        state: NsState,
        readings: List<Reading>,
        zone: java.time.ZoneId,
        nowS: Long,
        save: (NsState) -> Unit,
    ): Result {
        var st = state
        var sent = 0
        var dropped = 0
        val prevOf = HashMap<Instant, Reading>()
        readings.sortedBy { it.t }.zipWithNext { a, b -> prevOf[b.t] = a }
        val entries = if (cfg.api == NsApi.V3) plan.entries.takeLast(NightscoutQueue.V3_MAX_PER_CYCLE) else plan.entries
        for (chunk in entries.chunked(NightscoutQueue.BATCH)) {
            val docs = chunk.map { NsDocs.entry(it, prevOf[it.t], cfg.device, zone, cfg.api) }
            when (val o = client.postEntries(docs)) {
                is Outcome.Ok -> {
                    val bad = if (cfg.api == NsApi.V3) client.lastRejected else 0
                    sent += chunk.size - bad
                    dropped += bad
                }

                is Outcome.Rejected -> {
                    dropped += chunk.size
                }

                else -> {
                    throw NsFailure(o)
                }
            }
            st = NightscoutQueue.sentEntries(st, chunk, nowS).also(save)
        }
        for (r in plan.upserts + plan.deletes) {
            val t = r.treatment
            val o = if (t.deleted) client.deleteTreatment(t.id) else client.upsertTreatment(NsDocs.treatment(t, cfg.device, zone))
            when (o) {
                is Outcome.Ok -> sent++
                is Outcome.Rejected -> dropped++
                else -> throw NsFailure(o)
            }
            st = NightscoutQueue.sentTreatments(st, listOf(r)).also(save)
        }
        if (plan.settled.isNotEmpty()) st = NightscoutQueue.sentTreatments(st, plan.settled).also(save)
        plan.sensor?.let { s ->
            when (val o = client.postTreatments(NsDocs.sensorEvents(s, cfg.device, zone))) {
                is Outcome.Ok -> sent += 2
                is Outcome.Rejected -> dropped += 2
                else -> throw NsFailure(o)
            }
            st = NightscoutQueue.sentSensor(st, s.serial).also(save)
        }
        return Result(sent, dropped)
    }

    /** The phone's battery: when it changed, or every 30 min at most otherwise. */
    fun battery(
        client: NsClient,
        cfg: NsConfig,
        state: NsState,
        percent: Int,
        charging: Boolean,
        now: Instant,
        zone: java.time.ZoneId,
    ): NsState? {
        val nowMs = now.toEpochMilli()
        if (percent < 0 || (percent == state.batteryPercent && nowMs - state.batteryMs < NightscoutQueue.BATTERY_EVERY_MS)) return null
        when (val o = client.postDeviceStatus(NsDocs.deviceStatus(percent, charging, now, cfg.device, zone))) {
            is Outcome.Ok, is Outcome.Rejected -> {}

            else -> {
                throw NsFailure(o)
            }
        }
        return state.copy(batteryPercent = percent, batteryMs = nowMs)
    }
}
