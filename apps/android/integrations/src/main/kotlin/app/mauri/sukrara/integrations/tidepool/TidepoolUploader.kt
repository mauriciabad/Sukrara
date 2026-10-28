// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.integrations.tidepool

import app.mauri.sukrara.core.data.settings.SchemaSettings
import app.mauri.sukrara.core.model.Clock
import app.mauri.sukrara.core.model.Role
import app.mauri.sukrara.core.model.SourceId
import app.mauri.sukrara.core.model.storage.ReadingsRepository
import app.mauri.sukrara.core.model.storage.SecretUnreadableException
import app.mauri.sukrara.core.model.storage.SecureStore
import app.mauri.sukrara.core.model.storage.SettingsRepository
import app.mauri.sukrara.core.model.storage.StateStore
import app.mauri.sukrara.core.model.storage.TreatmentsRepository
import app.mauri.sukrara.integrations.upload.UploadLoop
import app.mauri.sukrara.integrations.upload.UploadStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** The page's switches (schema `apps.tidepool`, JSON in store `integrations`); never the session. */
@Serializable
data class TpSettings(
    val on: Boolean = false,
    val glucose: Boolean = true,
    val treatments: Boolean = true,
    /** A follower's own, separate choice to upload the person they follow (off: a follower uploads nothing). */
    val uploadFollowed: Boolean = false,
)

/** What the Tidepool page shows. */
data class TpPageState(
    val settings: TpSettings,
    val signedIn: Boolean,
    val email: String,
    val status: UploadStatus,
    val role: Role?,
) {
    /** On and signed in on a follower's phone, waiting for *Upload the person you follow*. */
    val needsChoice: Boolean get() = settings.on && signedIn && role == Role.Follower && !settings.uploadFollowed
}

/** The result of the page's sign-in. */
sealed interface TpSignIn {
    data object Done : TpSignIn

    data object BadCredentials : TpSignIn

    data class Failed(val why: String) : TpSignIn
}

/**
 * Settings › Share with apps › Tidepool: glucose (one reading per 5 minutes, the last 30 days included) and insulin,
 * carbs and notes to the person's Tidepool account, so they and their clinic see them there. Off by default. The
 * wearer uploads their own data; a follower uploads the person they follow only after their own separate choice.
 *
 * Sign-in sends the password once and keeps only the session (SecureStore `tidepool`); a refused session is refreshed
 * once, and when Tidepool ends it the session is wiped and the page asks to sign in again. Each role has its own upload
 * record on the account (found again by device id, or created; one deleted on Tidepool is created again). What
 * Tidepool has is remembered, so an edit or a delete corrects it. A failing cycle backs off up to an hour.
 */
class TidepoolUploader(
    private val settings: SettingsRepository,
    private val secure: SecureStore,
    private val store: StateStore<TpState>,
    private val readings: ReadingsRepository,
    private val treatments: TreatmentsRepository,
    private val role: Flow<Role?>,
    http: OkHttpClient,
    /** The app's version, as Tidepool shows the uploader. */
    private val version: String,
    private val clock: Clock,
    private val io: CoroutineDispatcher,
    private val log: (String) -> Unit = {},
    /** Tidepool's API; tests point it at a MockWebServer on loopback. */
    baseUrl: String = TidepoolClient.API,
) {
    private val client = TidepoolClient(http, version, baseUrl)

    /** Null until [load] read the secure store. */
    private val signedIn = MutableStateFlow<Boolean?>(null)
    private val loop = UploadLoop(INTERVAL_MS, io, { e -> log("tidepool cycle: ${e.javaClass.simpleName}") }) { cycle() }
    private val mutex = Mutex()

    /** When the last cycle read the whole window (in between, only the last hours are read). */
    private var fullScanMs = 0L

    val settingsFlow: Flow<TpSettings> = settings.flow(SETTING).map(::decode)

    val page: Flow<TpPageState> =
        combine(settingsFlow, signedIn, store.data, role) { s, known, st, r ->
            val signed = known == true
            TpPageState(s, signed, st.email, status(s, signed, st, r), r)
        }

    /** The upload's status, for the Share with apps row ([tidepoolStatusText] words it). */
    val status: Flow<UploadStatus> = page.map { it.status }.distinctUntilChanged()

    /** Reads whether a session is saved; call once from the app's load step. */
    suspend fun load() {
        signedIn.value =
            try {
                readSession().also { client.session = it } != null
            } catch (e: SecretUnreadableException) {
                // it exists: never shown as "signed out"
                true
            }
    }

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

    // MARK: page actions

    /** Signs in with the Tidepool account; the password is used for this request only. Another account starts afresh. */
    suspend fun signIn(
        email: String,
        password: String,
    ): TpSignIn =
        mutex.withLock {
            when (val answer = withContext(io) { client.login(email, password) }) {
                is TpLogin.SignedIn -> {
                    withContext(NonCancellable) {
                        val account = sha256(answer.session.userId)
                        store.update {
                            val base = if (it.account == account) it else TpState(account = account)
                            base.copy(
                                email = email.trim(),
                                signedOutByTidepool = false,
                                failures = 0,
                                nextAttemptMs = 0,
                                lastError = null,
                            )
                        }
                        writeSession(answer.session)
                        signedIn.value = true
                        log("tidepool: signed in")
                        loop.kick()
                        TpSignIn.Done
                    }
                }

                TpLogin.BadCredentials -> {
                    TpSignIn.BadCredentials
                }

                is TpLogin.Failed -> {
                    TpSignIn.Failed(answer.why)
                }
            }
        }

    /**
     * Ends the session (on Tidepool too, best effort) and forgets it. What Tidepool got stays remembered, so signing in
     * to the same account again never posts a note twice.
     */
    suspend fun signOut() =
        mutex.withLock {
            client.session?.let { s -> withContext(io) { client.logout(s) } }
            client.session = null
            secure.delete(NAME)
            signedIn.value = false
            store.update {
                it.copy(
                    signedOutByTidepool = false,
                    failures = 0,
                    nextAttemptMs = 0,
                    lastError = null,
                    lastOkMs = 0,
                    lastCount = 0,
                )
            }
        }

    suspend fun change(transform: (TpSettings) -> TpSettings) {
        var turnedOn = false
        settings.update(SETTING) {
            val old = decode(it)
            val new = transform(old)
            turnedOn = (new.on && !old.on) || (new.uploadFollowed && !old.uploadFollowed)
            encode(new)
        }
        // switching on tries at once, whatever an earlier backoff said
        if (turnedOn) mutex.withLock { store.update { it.copy(failures = 0, nextAttemptMs = 0, lastError = null) } }
        loop.kick()
    }

    fun kick() = loop.kick()

    // MARK: the cycle

    internal suspend fun cycle() =
        mutex.withLock {
            try {
                cycleLocked()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("tidepool cycle: ${e.javaClass.simpleName}")
                store.update { TidepoolQueue.failed(it, clock.now().toEpochMilli(), e.javaClass.simpleName) }
            }
        }

    private suspend fun cycleLocked() {
        val s = settingsFlow.first()
        val r = role.first()
        if (!s.on || r == null || !TidepoolQueue.allowed(r, s.uploadFollowed)) return
        var state = store.data.first()
        val nowMs = clock.now().toEpochMilli()
        val nowS = nowMs / 1000
        if (nowMs < state.nextAttemptMs) return
        val session =
            try {
                client.session ?: readSession()?.also { client.session = it }
            } catch (e: SecretUnreadableException) {
                store.update { TidepoolQueue.failed(it, nowMs, "secure storage") }
                return
            } ?: return

        var rs = state.role(r)
        if (rs.deviceId.isEmpty()) {
            val who = if (r == Role.Wearer) "wearer" else "following"
            rs = rs.copy(deviceId = "Sukrara-$who-" + UUID.randomUUID().toString().take(8))
        }
        // first cycle: what the phone keeps goes up once
        if (rs.from == 0L) rs = rs.copy(from = nowS - TidepoolQueue.WINDOW_S)
        state = state.withRole(r, rs)
        store.update { state }

        val windowStart = Instant.ofEpochSecond(maxOf(rs.from, nowS - TidepoolQueue.WINDOW_S))
        val full = nowMs - fullScanMs >= FULL_SCAN_EVERY_MS
        val start = if (full) windowStart else maxOf(windowStart, clock.now() - RECENT)
        // only this role's readings: never the person followed earlier (or one's own readings while following)
        val own = if (r == Role.Wearer) SourceId.Wearer else setOf(SourceId.Relay, SourceId.Nearby)
        val rd =
            if (s.glucose) readings.range(start, clock.now() + Duration.ofMinutes(1)).filter { it.source in own } else emptyList()
        if (full) fullScanMs = nowMs
        val records = if (s.treatments) treatments.changedSince(windowStart - Duration.ofDays(1)) else emptyList()
        val plan = TidepoolQueue.plan(rd, records, rs, s.glucose, s.treatments, notes = true, nowS = nowS)
        if (plan.count == 0) {
            if (!plan.isEmpty) rs = TidepoolQueue.applied(rs, plan, emptyMap(), nowS)
            store.update { it.withRole(r, rs).copy(failures = 0, nextAttemptMs = 0, lastError = null) }
            return
        }
        try {
            rs = withContext(io) { send(r, rs, plan, nowS) }
            store.update {
                it.withRole(r, rs).copy(failures = 0, nextAttemptMs = 0, lastError = null, lastOkMs = nowMs, lastCount = plan.count)
            }
            log("tidepool: ${plan.cbg.size} readings, ${plan.upserts.size + plan.deletes.size} entries, notes ${plan.noteCreates.size}")
        } catch (e: TpSignedOut) {
            log("tidepool: signed out by Tidepool")
            client.session = null
            secure.delete(NAME)
            signedIn.value = false
            store.update { it.copy(signedOutByTidepool = true, lastError = null, failures = 0, nextAttemptMs = 0) }
        } catch (e: TpFailure) {
            log("tidepool: ${e.message}")
            store.update {
                // the upload record was deleted on Tidepool: a new one is made next time
                val cur = it.role(r)
                val kept = if (e.dataSetGone) cur.copy(dataSet = "") else cur
                TidepoolQueue.failed(it.withRole(r, kept), nowMs, e.message ?: "HTTP ${e.code}")
            }
        } finally {
            // a refreshed token is kept
            val now = client.session
            if (now != null && now != session) withContext(NonCancellable) { writeSession(now) }
        }
    }

    /**
     * Sends [plan] to [r]'s upload record. Readings go in batches and each is remembered at once (a long backfill keeps
     * its progress through a failure); entries and notes follow (a created note is remembered at once: a retry never
     * posts it twice).
     */
    private suspend fun send(
        r: Role,
        state: TpRoleState,
        plan: TidepoolQueue.Plan,
        nowS: Long,
    ): TpRoleState {
        val zone = clock.zone
        var st = state

        suspend fun keep(next: TpRoleState) {
            st = next
            store.update { it.withRole(r, next) }
        }
        if (st.dataSet.isEmpty()) keep(st.copy(dataSet = dataSet(r, st.deviceId)))
        val ds = st.dataSet
        for (chunk in plan.cbg.chunked(TidepoolQueue.BATCH)) {
            client.addData(ds, chunk.map { TidepoolData.cbg(it, zone, st.deviceId, version) })
            keep(TidepoolQueue.applied(st, TidepoolQueue.Plan(cbg = chunk), emptyMap(), nowS))
        }
        client.deleteData(ds, plan.deletes.map { TidepoolData.treatmentOrigin(it.id) })
        for (chunk in plan.upserts.chunked(TidepoolQueue.BATCH)) {
            client.addData(ds, chunk.mapNotNull { TidepoolData.treatment(it, zone, st.deviceId, version) })
        }
        for (t in plan.noteDeletes) st.notes[t.id]?.let { client.removeNote(it) }
        val userId = client.session?.userId ?: throw TpSignedOut()
        for (t in plan.noteEdits) {
            val id = st.notes[t.id] ?: continue
            // removed on Tidepool's web page: it goes up again as a new note (never a retry loop on the edit)
            if (!client.editNote(id, TidepoolData.noteEdit(t))) {
                val made = client.sendNote(TidepoolData.note(t, userId))
                keep(TidepoolQueue.applied(st, TidepoolQueue.Plan(noteCreates = listOf(t)), mapOf(t.id to made), nowS))
            }
        }
        for (t in plan.noteCreates) {
            val id = client.sendNote(TidepoolData.note(t, userId))
            keep(TidepoolQueue.applied(st, TidepoolQueue.Plan(noteCreates = listOf(t)), mapOf(t.id to id), nowS))
        }
        keep(TidepoolQueue.applied(st, plan.copy(cbg = emptyList(), noteCreates = emptyList()), emptyMap(), nowS))
        return st
    }

    /** This role's upload record on the account: found again by device id, or created. */
    private fun dataSet(
        r: Role,
        deviceId: String,
    ): String {
        val found =
            client.dataSets().firstNotNullOfOrNull { e ->
                val o = e as? JsonObject ?: return@firstNotNullOfOrNull null
                if (o["deviceId"]?.jsonPrimitive?.contentOrNull != deviceId) return@firstNotNullOfOrNull null
                (o["uploadId"] ?: o["id"])?.jsonPrimitive?.contentOrNull
            }
        if (found != null) return found
        val model = if (r == Role.Wearer) "Sukrara" else "Sukrara (following)"
        return client.createDataSet(TidepoolData.dataSet(deviceId, model, version, clock.zone, clock.now()))
    }

    private suspend fun readSession(): TpSession? =
        secure.read(NAME)?.let { runCatching { json.decodeFromString(TpSession.serializer(), it.decodeToString()) }.getOrNull() }

    private suspend fun writeSession(s: TpSession) = secure.write(NAME, json.encodeToString(TpSession.serializer(), s).toByteArray())

    private fun status(
        s: TpSettings,
        signed: Boolean,
        st: TpState,
        r: Role?,
    ): UploadStatus =
        when {
            !s.on -> UploadStatus.Off

            !signed && st.signedOutByTidepool -> UploadStatus.AuthFailed

            !signed -> UploadStatus.Setup

            // a follower who hasn't chosen to upload the person they follow: nothing goes up (the page asks)
            !TidepoolQueue.allowed(r, s.uploadFollowed) -> UploadStatus.Off

            st.lastError != null -> UploadStatus.Failing(st.lastError, st.lastOkMs.takeIf { it > 0 }?.let(Instant::ofEpochMilli))

            st.lastOkMs > 0 -> UploadStatus.Ok(Instant.ofEpochMilli(st.lastOkMs), st.lastCount)

            else -> UploadStatus.Waiting
        }

    companion object {
        const val INTERVAL_MS = 5 * 60_000L
        const val NAME = "tidepool"
        private const val FULL_SCAN_EVERY_MS = 30 * 60_000L
        private val RECENT: Duration = Duration.ofHours(6)
        val SETTING = SchemaSettings.text("apps.tidepool", default = "")
        private val json = Json { ignoreUnknownKeys = true }

        fun decode(text: String): TpSettings =
            text.takeIf { it.isNotEmpty() }?.let { runCatching { json.decodeFromString(TpSettings.serializer(), it) }.getOrNull() }
                ?: TpSettings()

        fun encode(s: TpSettings): String = json.encodeToString(TpSettings.serializer(), s)

        private fun sha256(x: String) = MessageDigest.getInstance("SHA-256").digest(x.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
