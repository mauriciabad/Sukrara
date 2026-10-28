// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.integrations.localserver

import app.mauri.sukrara.core.data.settings.SchemaSettings
import app.mauri.sukrara.core.model.Clock
import app.mauri.sukrara.core.model.Reading
import app.mauri.sukrara.core.model.Treatment
import app.mauri.sukrara.core.model.storage.ReadingsRepository
import app.mauri.sukrara.core.model.storage.SecretUnreadableException
import app.mauri.sukrara.core.model.storage.SecureStore
import app.mauri.sukrara.core.model.storage.SettingsRepository
import app.mauri.sukrara.core.model.storage.TreatmentsRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant

/** What the Nightscout API on this phone is doing, for its page and the Share with apps row. */
sealed interface NsApiState {
    data object Off : NsApiState

    /** Listening on 127.0.0.1:17581; [lastAsked] = the last request with the right secret. */
    data class Listening(val lastAsked: Instant?) : NsApiState

    /** Another app holds the port: nothing bound, no retry loop. */
    data object PortBusy : NsApiState

    /** The API secret is stored but can't be read now: nothing is served until it can, or a new one is made. */
    data object SecretUnreadable : NsApiState
}

/**
 * The Nightscout read API on `127.0.0.1:17581` (schema `apps.nightscout`, off by default), for both roles: the
 * wearer's readings or the followed person's, labelled per entry ([NightscoutApi.device]). Every data request needs
 * the API secret: any app on the phone can open a loopback port, and this answers doses and notes too. The secret is a
 * random 128-bit value made when the API is first switched on, kept in the secure store (`nightscout-api`) and kept
 * when the API is switched off; *New secret* replaces it. Requests are never logged: only "last asked" in memory.
 */
class NightscoutApiServer(
    private val settings: SettingsRepository,
    private val secure: SecureStore,
    private val readings: ReadingsRepository,
    private val treatments: TreatmentsRepository,
    private val clock: Clock,
    private val io: CoroutineDispatcher,
    private val port: Int = NightscoutApi.PORT,
) {
    private val _state = MutableStateFlow<NsApiState>(NsApiState.Off)
    val state: StateFlow<NsApiState> = _state.asStateFlow()

    @Volatile private var secret: String = ""

    @Volatile private var recent: List<Reading> = emptyList()

    @Volatile private var recentTreatments: List<Treatment> = emptyList()
    private val lock = Mutex()

    private val server =
        LocalHttpServer(port, ::answer) { req, answer ->
            if (answer.status.startsWith("200") && NightscoutApi.authorized(secret, req)) {
                _state.update { if (it is NsApiState.Listening) NsApiState.Listening(clock.now()) else it }
            }
        }

    val on: Flow<Boolean> = settings.flow(SETTING)

    suspend fun setOn(on: Boolean) = settings.set(SETTING, on)

    /** Follows the switch for as long as [scope] lives; the data windows move forward every hour. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun start(scope: CoroutineScope) {
        scope.launch { on.distinctUntilChanged().collect { apply(it) } }
        scope.launch {
            hourly(WINDOW).flatMapLatest { from -> readings.window(from) }.collect { recent = it.asReversed() }
        }
        scope.launch {
            hourly(TREATMENTS_WINDOW)
                .flatMapLatest { from -> treatments.between(from, from + Duration.ofDays(3650)) }
                .collect { recentTreatments = it }
        }
    }

    private fun hourly(back: Duration) =
        flow {
            while (true) {
                emit(clock.now() - back - Duration.ofHours(1))
                delay(Duration.ofHours(1).toMillis())
            }
        }

    private suspend fun apply(on: Boolean) =
        lock.withLock {
            withContext(io) {
                if (!on) {
                    server.stop()
                    secret = ""
                    _state.value = NsApiState.Off
                    return@withContext
                }
                val s =
                    try {
                        readOrMakeSecret()
                    } catch (e: SecretUnreadableException) {
                        null
                    }
                if (s == null) {
                    server.stop()
                    secret = ""
                    _state.value = NsApiState.SecretUnreadable
                    return@withContext
                }
                secret = s
                _state.value =
                    when {
                        server.running -> _state.value.takeIf { it is NsApiState.Listening } ?: NsApiState.Listening(null)
                        server.bind() -> NsApiState.Listening(null)
                        else -> NsApiState.PortBusy
                    }
            }
        }

    private suspend fun readOrMakeSecret(): String {
        secure.read(SECRET)?.let { return String(it, Charsets.UTF_8) }
        val made = newSecret()
        secure.write(SECRET, made.toByteArray(Charsets.UTF_8))
        return made
    }

    /** The API secret for the page (null when none was made yet); throws [SecretUnreadableException]. */
    suspend fun secret(): String? = withContext(io) { secure.read(SECRET)?.let { String(it, Charsets.UTF_8) } }

    /** *New secret*: apps that use the old one stop reading. Also the way out of [NsApiState.SecretUnreadable]. */
    suspend fun renewSecret(): String {
        val made = newSecret()
        withContext(io) {
            secure.write(SECRET, made.toByteArray(Charsets.UTF_8))
        }
        lock.withLock {
            if (_state.value != NsApiState.Off) secret = made
        }
        // re-apply: a server that couldn't read its secret starts now
        apply(on.first())
        return made
    }

    /** Stops listening (the switch stays as it is). */
    fun stop() = server.stop()

    private fun answer(req: HttpRequest): HttpAnswer =
        NightscoutApi.route(req, secret) {
            val now = clock.now()
            NightscoutApi.Snapshot(recent.filter { it.t >= now - WINDOW }, recentTreatments, now, clock.zone)
        }

    companion object {
        val SETTING = SchemaSettings.bool("apps.nightscout", default = false)
        const val SECRET = "nightscout-api"
        val WINDOW: Duration = Duration.ofDays(7)
        val TREATMENTS_WINDOW: Duration = Duration.ofDays(30)

        /** 128 random bits as 32 hex digits (typed into a client by hand at worst). */
        fun newSecret(): String {
            val b = ByteArray(16)
            SecureRandom().nextBytes(b)
            return b.joinToString("") { "%02x".format(it) }
        }
    }
}
