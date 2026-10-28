// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.integrations.libreview

import app.mauri.sukrara.core.data.settings.SchemaSettings
import app.mauri.sukrara.core.model.Clock
import app.mauri.sukrara.core.model.Role
import app.mauri.sukrara.core.model.Treatment
import app.mauri.sukrara.core.model.storage.SecretUnreadableException
import app.mauri.sukrara.core.model.storage.SecureStore
import app.mauri.sukrara.core.model.storage.SettingsRepository
import app.mauri.sukrara.core.model.storage.StateStore
import app.mauri.sukrara.core.model.storage.TreatmentsRepository
import app.mauri.sukrara.integrations.CurrentSensor
import app.mauri.sukrara.integrations.upload.UploadBackoff
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
import java.time.Duration
import java.time.Instant
import java.util.TreeMap
import java.util.UUID
import kotlin.random.Random

/** The page's switches (schema `apps.libreview`, JSON in store `integrations`); never the token. */
@Serializable
data class LvSettings(
    val on: Boolean = false,
    val glucose: Boolean = true,
    val treatments: Boolean = true,
)

/** A batch of Libre 3 readings from the sensor link, tagged with the sensor they came from. */
data class LvReadings(
    val serial: String,
    val current: LvGlucose?,
    val history: List<LvGlucose>,
)

/** What the LibreView page shows. */
data class LvPageState(
    val settings: LvSettings,
    val signedIn: Boolean,
    val email: String,
    val status: UploadStatus,
    /** Sensor glucose goes up from here (null: it isn't uploading). */
    val glucoseFrom: Instant?,
)

/**
 * What LibreView has of the entries while the upload runs (the wearer, signed in, switch on), for home's list: [from] =
 * new entries go up when changed after it (epoch s; 0 = the entries switch is off: no marks); [stopped] = nothing goes
 * up until the person acts (sign in again, or LibreView refused the last upload).
 */
data class LvEntryMarks(
    val ledger: Map<String, LvSent>,
    val from: Long,
    val stopped: Boolean,
) {
    fun mark(
        t: Treatment,
        now: Instant,
    ): LvMark? = if (from == 0L) null else LibreViewQueue.mark(t, ledger, from, now.epochSecond)

    /** Deleting [t] takes it out of LibreView on the next upload (it has a live version and the upload isn't stopped). */
    fun removesOnDelete(t: Treatment): Boolean = !stopped && (ledger[t.id]?.v ?: 0L) != 0L
}

/** The result of the page's sign-in. */
sealed interface LvSignIn {
    data object Done : LvSignIn

    /** Another device uploads for this account: the person may take over (that device stops uploading). */
    data object OtherDevice : LvSignIn

    data object BadCredentials : LvSignIn

    data class Failed(val why: String) : LvSignIn
}

/**
 * Settings › Share with apps › LibreView: the wearer's entries and Libre 3 glucose to their LibreView account, the way
 * the FreeStyle Libre 3 app sends them, so their clinic sees them. Off by default; wearer only.
 *
 * Sign-in sends the password once and keeps only the token (SecureStore `libreview`). This phone becomes the account's
 * uploading device only when the person chooses *Take over*. When the token stops working (another device took over,
 * or it expired) nothing goes up until the person signs in again. Glucose and entries go up from the moment they were
 * switched on; what LibreView got is remembered so an edit or a delete corrects it.
 */
class LibreViewUploader(
    private val settings: SettingsRepository,
    private val secure: SecureStore,
    private val store: StateStore<LvState>,
    private val treatments: TreatmentsRepository,
    private val readings: Flow<LvReadings>,
    private val sensor: Flow<CurrentSensor?>,
    private val role: Flow<Role?>,
    private val client: LibreViewClient,
    private val clock: Clock,
    private val io: CoroutineDispatcher,
    private val log: (String) -> Unit = {},
) {
    /** Null until [load] read the secure store: nothing claims "signed out" before that (no clinic banner flash). */
    private val signedIn = MutableStateFlow<Boolean?>(null)
    private val loop = UploadLoop(INTERVAL_MS, io, { e -> log("libreview cycle: ${e.javaClass.simpleName}") }) { cycle() }
    private val mutex = Mutex()

    /** Libre 3 readings of the current sensor, kept in memory (3 days). */
    private var bufferSerial: String? = null
    private var current: LvGlucose? = null
    private val history = TreeMap<Int, LvGlucose>()

    val settingsFlow: Flow<LvSettings> = settings.flow(SETTING).map(::decode)

    val page: Flow<LvPageState> =
        combine(settingsFlow, signedIn, store.data, role) { s, known, st, r ->
            val signed = known == true
            LvPageState(s, signed, st.email, status(s, signed, st, r), st.glucoseFrom.takeIf { it > 0 }?.let(Instant::ofEpochSecond))
        }

    /** What LibreView has of the entries while the upload runs (home's cloud marks); null while it's off. */
    val entryMarks: Flow<LvEntryMarks?> =
        combine(settingsFlow, signedIn, store.data, role) { s, signed, st, r ->
            if (s.on && signed == true && r == Role.Wearer) {
                LvEntryMarks(st.ledger, if (s.treatments) st.entriesFrom else 0L, st.authFailed || st.refused)
            } else {
                null
            }
        }.distinctUntilChanged()

    /** Whether a token is saved; null until [load] ran. */
    val signedInKnown: Flow<Boolean?> = signedIn

    /** Reads whether a token is saved; call once from the app's load step. */
    suspend fun load() {
        signedIn.value =
            try {
                secure.read(NAME) != null
            } catch (e: SecretUnreadableException) {
                // it exists: never shown as "signed out"
                true
            }
    }

    fun start(scope: CoroutineScope) {
        if (!loop.start(scope)) return
        scope.launch {
            readings.collect { r ->
                buffer(r)
                if (settingsFlow.first().glucose) loop.kick()
            }
        }
        scope.launch {
            val now = clock.now()
            treatments.between(now - Duration.ofDays(31), now + Duration.ofDays(3650)).drop(1).collect { changed() }
        }
        scope.launch { settingsFlow.distinctUntilChanged().drop(1).collect { changed() } }
        scope.launch { role.distinctUntilChanged().drop(1).collect { loop.kick() } }
    }

    /** Keeps the newest batch's readings for the next cycle; another sensor's batch replaces them. */
    internal fun buffer(r: LvReadings) =
        synchronized(history) {
            if (r.serial != bufferSerial) {
                bufferSerial = r.serial
                current = null
                history.clear()
            }
            r.current?.let { c -> if ((current?.lifeCount ?: -1) < c.lifeCount) current = c }
            r.history.forEach { history[it.lifeCount] = it }
            if (history.isNotEmpty()) history.headMap(history.lastKey() - LibreViewQueue.HISTORY_MIN).clear()
        }

    /** An entry or a setting changed: a refused upload may go now. */
    private suspend fun changed() {
        mutex.withLock { store.update { if (it.refused) it.copy(refused = false, lastError = null) else it } }
        loop.kick()
    }

    // MARK: page actions

    /**
     * Signs in with the LibreView app login; the password is used for this request only. [takeOver] = the person chose
     * to make this phone the account's uploading device. Another account than the last one starts from nothing.
     */
    suspend fun signIn(
        email: String,
        password: String,
        takeOver: Boolean,
    ): LvSignIn =
        mutex.withLock {
            val st = ensureDevice()
            val answer = withContext(io) { client.signIn(email, password, st.deviceId, takeOver) }
            when (answer) {
                is LvAuth.SignedIn -> {
                    withContext(NonCancellable) {
                        // state first, then the token, both or neither: a token never sits under another account's ledger
                        val e = email.trim()
                        val nowS = clock.now().epochSecond
                        val s = settingsFlow.first()
                        store.update {
                            val same = it.email.equals(e, ignoreCase = true)
                            val base = if (same) it else LvState(deviceId = it.deviceId)
                            base.copy(
                                email = e,
                                // a new sign-in may bring the profile the sensor registration needs
                                sensors = base.sensors.map { st -> st.copy(refusedRegistration = false) },
                                authFailed = false,
                                refused = false,
                                failures = 0,
                                nextAttemptMs = 0,
                                lastError = null,
                                glucoseFrom =
                                    if (same) {
                                        it.glucoseFrom
                                    } else if (s.on && s.glucose) {
                                        nowS
                                    } else {
                                        0
                                    },
                                entriesFrom =
                                    if (same) {
                                        it.entriesFrom
                                    } else if (s.on && s.treatments) {
                                        nowS
                                    } else {
                                        0
                                    },
                            )
                        }
                        secure.write(NAME, json.encodeToString(LvSession.serializer(), answer.session).toByteArray())
                        signedIn.value = true
                        log("libreview: signed in${if (takeOver) " (took over)" else ""}")
                        loop.kick()
                        LvSignIn.Done
                    }
                }

                LvAuth.OtherDevice -> {
                    LvSignIn.OtherDevice
                }

                LvAuth.BadCredentials -> {
                    LvSignIn.BadCredentials
                }

                is LvAuth.Failed -> {
                    LvSignIn.Failed(answer.why)
                }
            }
        }

    /** Forgets the token. What LibreView got stays remembered (signing in again still corrects it). */
    suspend fun signOut() =
        mutex.withLock {
            secure.delete(NAME)
            signedIn.value = false
            store.update { it.copy(authFailed = false, refused = false, lastError = null, failures = 0, nextAttemptMs = 0) }
        }

    suspend fun change(transform: (LvSettings) -> LvSettings) {
        var old = LvSettings()
        var new = LvSettings()
        settings.update(SETTING) {
            old = decode(it)
            new = transform(old)
            encode(new)
        }
        val nowS = clock.now().epochSecond
        mutex.withLock {
            store.update {
                it.copy(
                    // each part goes up from the moment it (or the upload) is switched on
                    glucoseFrom = if (new.on && new.glucose) it.glucoseFrom.takeIf { f -> f > 0 } ?: nowS else 0,
                    entriesFrom = if (new.on && new.treatments) it.entriesFrom.takeIf { f -> f > 0 } ?: nowS else 0,
                    failures = if (new.on && !old.on) 0 else it.failures,
                    nextAttemptMs = if (new.on && !old.on) 0 else it.nextAttemptMs,
                    refused = false,
                )
            }
        }
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
                // an answer we can't read: retried later with backoff, never a tight loop
                log("libreview cycle: ${e.javaClass.simpleName}")
                save(failed(store.data.first(), clock.now().toEpochMilli(), e.javaClass.simpleName))
            }
        }

    private suspend fun cycleLocked() {
        val s = settingsFlow.first()
        if (!s.on || role.first() != Role.Wearer) return
        var state = ensureDevice()
        if (state.authFailed) return
        val nowMs = clock.now().toEpochMilli()
        val nowS = nowMs / 1000
        if (nowMs < state.nextAttemptMs) return
        if ((s.glucose && state.glucoseFrom == 0L) || (s.treatments && state.entriesFrom == 0L)) {
            // never a backfill of what was there before the switch: a lost moment starts now
            state =
                state.copy(
                    glucoseFrom = if (s.glucose && state.glucoseFrom == 0L) nowS else state.glucoseFrom,
                    entriesFrom = if (s.treatments && state.entriesFrom == 0L) nowS else state.entriesFrom,
                )
            save(state)
        }
        val session =
            try {
                secure.read(NAME)?.let { json.decodeFromString(LvSession.serializer(), it.decodeToString()) }
            } catch (e: SecretUnreadableException) {
                save(failed(state, nowMs, "secure storage"))
                return
            } ?: return

        val entries =
            if (!state.refused && (s.treatments || state.ledger.isNotEmpty())) {
                // a switched-off part still corrects what LibreView already has
                val records = treatments.changedSince(clock.now() - Duration.ofDays(31))
                LibreViewQueue
                    .entries(records, state.ledger, if (s.treatments) state.entriesFrom else Long.MAX_VALUE, nowS)
            } else {
                emptyList()
            }
        val batch = if (s.glucose) glucoseBatch(state, nowS) else null
        if (entries.isEmpty() && batch == null) return

        val refusedSensor = batch != null && state.sensors.any { it.serial == batch.serial && it.refusedRegistration }
        var sensorPart = batch.takeIf { !refusedSensor }
        if (batch != null && !refusedSensor && state.sensors.none { it.serial == batch.serial && it.registered }) {
            when (val r = withContext(io) { client.putSensor(session, batch.serial) }) {
                LvPost.Ok -> {
                    state = state.copy(sensors = LibreViewQueue.registered(state.sensors, batch.serial)).also { save(it) }
                }

                LvPost.SignInAgain -> {
                    return save(state.copy(authFailed = true, lastError = null))
                }

                is LvPost.Retry -> {
                    return save(failed(state, nowMs, r.why))
                }

                // glucose waits for a sensor LibreView knows; entries still go
                is LvPost.Rejected -> {
                    log("libreview: sensor not registered (${r.why})")
                    state =
                        state
                            .copy(sensors = LibreViewQueue.registrationRefused(state.sensors, batch.serial), lastError = r.why)
                            .also { save(it) }
                    sensorPart = null
                }
            }
        }
        if (entries.isEmpty() && sensorPart == null) return
        val body = LibreViewPayload.payload(entries, state.deviceId, session.token, nowS, clock.zone, sensorPart)
        when (val r = withContext(io) { client.postMeasurements(session, body) }) {
            LvPost.Ok -> {
                val count = entries.size + (sensorPart?.history?.size ?: 0) + (if (sensorPart?.current != null) 1 else 0)
                state =
                    state.copy(
                        ledger = LibreViewQueue.sent(state.ledger, entries, nowS),
                        sensors = sensorPart?.let { LibreViewQueue.sentSensor(state.sensors, it) } ?: state.sensors,
                        failures = 0,
                        nextAttemptMs = 0,
                        lastError = null,
                        lastOkMs = nowMs,
                        lastCount = count,
                    )
                save(state)
                // more than one upload's worth waiting (a long backfill): go on at once
                if (entries.size == LibreViewQueue.MAX_ENTRIES ||
                    (sensorPart?.history?.size ?: 0) == LibreViewQueue.MAX_HISTORY
                ) {
                    loop.kick()
                }
            }

            LvPost.SignInAgain -> {
                log("libreview: token refused")
                save(state.copy(authFailed = true, lastError = null))
            }

            is LvPost.Retry -> {
                save(failed(state, nowMs, r.why))
            }

            is LvPost.Rejected -> {
                log("libreview: upload refused (${r.why})")
                // refused entries wait for a change (glucose goes on without them); refused glucose backs off
                save(if (entries.isNotEmpty()) state.copy(refused = true, lastError = r.why) else failed(state, nowMs, r.why))
                if (entries.isNotEmpty() && sensorPart != null) loop.kick()
            }
        }
    }

    private suspend fun glucoseBatch(
        state: LvState,
        nowS: Long,
    ): LvSensor? {
        val (serial, cur, hist) =
            synchronized(history) { Triple(bufferSerial, current, history.values.toList()) }
        if (serial == null) return null
        val start =
            sensor
                .first()
                ?.takeIf { it.serial == serial }
                ?.start
                ?.epochSecond
        return LibreViewQueue.sensor(serial, start, cur, hist, state.sensors.firstOrNull { it.serial == serial }, state.glucoseFrom, nowS)
    }

    private suspend fun ensureDevice(): LvState {
        val st = store.data.first()
        if (st.deviceId.isNotEmpty()) return st
        store.update { if (it.deviceId.isEmpty()) it.copy(deviceId = UUID.randomUUID().toString()) else it }
        return store.data.first()
    }

    private fun failed(
        st: LvState,
        nowMs: Long,
        why: String,
    ): LvState {
        val n = st.failures + 1
        return st.copy(failures = n, nextAttemptMs = nowMs + UploadBackoff.ms(n, 30_000, 15 * 60_000L, Random), lastError = why)
    }

    private suspend fun save(st: LvState) = store.update { st }

    private fun status(
        s: LvSettings,
        signed: Boolean,
        st: LvState,
        r: Role?,
    ): UploadStatus =
        when {
            !s.on || r != Role.Wearer -> UploadStatus.Off
            !signed -> UploadStatus.Setup
            st.authFailed -> UploadStatus.AuthFailed
            st.lastError != null -> UploadStatus.Failing(st.lastError, st.lastOkMs.takeIf { it > 0 }?.let(Instant::ofEpochMilli))
            st.lastOkMs > 0 -> UploadStatus.Ok(Instant.ofEpochMilli(st.lastOkMs), st.lastCount)
            else -> UploadStatus.Waiting
        }

    companion object {
        const val INTERVAL_MS = 5 * 60_000L
        const val NAME = "libreview"
        val SETTING = SchemaSettings.text("apps.libreview", default = "")
        private val json = Json { ignoreUnknownKeys = true }

        fun decode(text: String): LvSettings =
            text.takeIf { it.isNotEmpty() }?.let { runCatching { json.decodeFromString(LvSettings.serializer(), it) }.getOrNull() }
                ?: LvSettings()

        fun encode(s: LvSettings): String = json.encodeToString(LvSettings.serializer(), s)
    }
}
