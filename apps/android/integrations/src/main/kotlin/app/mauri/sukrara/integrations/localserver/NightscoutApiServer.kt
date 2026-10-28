// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.integrations.localserver

import app.mauri.sukrara.core.data.settings.SchemaSettings
import app.mauri.sukrara.core.model.Clock
import app.mauri.sukrara.core.model.Reading
import app.mauri.sukrara.core.model.Role
import app.mauri.sukrara.core.model.Treatment
import app.mauri.sukrara.core.model.storage.ReadingsRepository
import app.mauri.sukrara.core.model.storage.SecureStore
import app.mauri.sukrara.core.model.storage.SettingsRepository
import app.mauri.sukrara.core.model.storage.StateStore
import app.mauri.sukrara.core.model.storage.TreatmentsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
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

    /**
     * Listening on 127.0.0.1:17581; [lastAsked] = the last request with the right secret; [lan] = the Wi-Fi address
     * it also listens on (the home-network option), null when it doesn't.
     */
    data class Listening(
        val lastAsked: Instant?,
        val lan: String? = null,
    ) : NsApiState

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
    /** The phone's role: only that role's readings are served (never another person's left from an earlier role). */
    private val role: Flow<Role?>,
    private val clock: Clock,
    private val io: CoroutineDispatcher,
    /** The network the home-network option was turned on in. */
    private val lanPin: StateStore<LanPin>,
    /** The phone's Wi-Fi ([LanWatcher.wifi]): collected only while the home-network option is on. */
    private val wifi: Flow<WifiNet?>,
    /** Android 17's local network access is allowed. */
    private val localNetworkAllowed: () -> Boolean,
    private val port: Int = NightscoutApi.PORT,
) {
    private val _state = MutableStateFlow<NsApiState>(NsApiState.Off)
    val state: StateFlow<NsApiState> = _state.asStateFlow()

    @Volatile private var secret: String = ""

    @Volatile private var recent: List<Reading> = emptyList()

    @Volatile private var recentTreatments: List<Treatment> = emptyList()
    private val lock = Mutex()

    private val _lan = MutableStateFlow<LanState>(LanState.Off)

    /** The home-network listener's state, for the page. */
    val lan: StateFlow<LanState> = _lan.asStateFlow()
    private val lanCheck = MutableStateFlow(0)

    @Volatile private var lanServer: LocalHttpServer? = null
    private var lanAddress: String? = null

    private val asked: (HttpRequest, HttpAnswer) -> Unit = { req, answer ->
        if (answer.status.startsWith("200") && NightscoutApi.authorized(secret, req)) {
            _state.update { if (it is NsApiState.Listening) it.copy(lastAsked = clock.now()) else it }
        }
    }

    private val server = LocalHttpServer(port, ::answer, asked)

    val on: Flow<Boolean> = settings.flow(SETTING)

    /** The home-network option (`apps.nightscoutLan`, off by default; only while the API itself is on). */
    val lanOn: Flow<Boolean> = settings.flow(LAN_SETTING)

    suspend fun setOn(on: Boolean) {
        settings.set(SETTING, on)
        // turning the API back on months later never also opens it to the home network again on its own
        if (!on) settings.set(LAN_SETTING, false)
    }

    /** On: listens on the current Wi-Fi too (pinned to it); the page has asked for confirmation and the permission. */
    suspend fun setLan(on: Boolean) {
        if (on) lanPin.update { LanPin() }
        settings.set(LAN_SETTING, on)
        recheckLan()
    }

    /** *Allow on this Wi-Fi*: the option moves to the network the phone is on now. */
    suspend fun allowHere() {
        lanPin.update { LanPin() }
        recheckLan()
    }

    /** Looks again (the page came back: the local network permission may have changed). */
    fun recheckLan() {
        lanCheck.value++
    }

    /** Follows the switch for as long as [scope] lives; the data windows move forward every hour. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun start(scope: CoroutineScope) {
        scope.launch { on.distinctUntilChanged().collect { apply(it) } }
        // the Wi-Fi is watched only while both switches are on
        scope.launch {
            combine(on, lanOn) { a, b -> a && b }
                .distinctUntilChanged()
                .flatMapLatest { active ->
                    if (!active) {
                        flowOf(null)
                    } else {
                        combine(wifi, lanPin.data, lanCheck) { w, p, _ -> LanInput(w, p.fingerprint) }
                    }
                }.catch { emit(null) }
                .collect { applyLan(it) }
        }
        // the stores are read only while the API is on (off by default: nobody pays for it)
        scope.launch {
            combine(on.distinctUntilChanged(), role) { o, r -> if (o) r else null }
                .distinctUntilChanged()
                .flatMapLatest { r ->
                    if (r == null) {
                        flowOf(emptyList())
                    } else {
                        hourly(WINDOW).flatMapLatest { from -> readings.window(from) }.map { list ->
                            list.filter { it.source in NightscoutApi.sources(r) }.asReversed()
                        }
                    }
                }.catch { emit(emptyList()) }
                .collect { recent = it }
        }
        scope.launch {
            on
                .distinctUntilChanged()
                .flatMapLatest { o ->
                    if (!o) {
                        flowOf(emptyList())
                    } else {
                        hourly(TREATMENTS_WINDOW).flatMapLatest { from -> treatments.between(from, from + Duration.ofDays(3650)) }
                    }
                }.catch { emit(emptyList()) }
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
                    stopLan(LanState.Off)
                    secret = ""
                    _state.value = NsApiState.Off
                    return@withContext
                }
                val s =
                    try {
                        readOrMakeSecret()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // unreadable, or the Keystore refused the write: never a crash (alerts run in this process)
                        null
                    }
                if (s == null) {
                    server.stop()
                    stopLan(LanState.Off)
                    secret = ""
                    _state.value = NsApiState.SecretUnreadable
                    return@withContext
                }
                secret = s
                _state.value =
                    when {
                        server.running -> _state.value.takeIf { it is NsApiState.Listening } ?: NsApiState.Listening(null, lanAddress)
                        server.bind() -> NsApiState.Listening(null, lanAddress)
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
    suspend fun renewSecret(): String =
        withContext(NonCancellable + io) {
            // leaving the page mid-way never leaves the store and the server with different secrets
            val made = newSecret()
            secure.write(SECRET, made.toByteArray(Charsets.UTF_8))
            lock.withLock {
                if (_state.value != NsApiState.Off) secret = made
            }
            // re-apply: a server that couldn't read its secret starts now
            apply(on.first())
            recheckLan()
            made
        }

    private data class LanInput(
        val wifi: WifiNet?,
        val pin: String?,
    )

    private suspend fun applyLan(input: LanInput?) =
        lock.withLock {
            withContext(io) {
                when {
                    input == null || secret.isEmpty() -> {
                        stopLan(LanState.Off)
                    }

                    !runCatching(localNetworkAllowed).getOrDefault(false) -> {
                        stopLan(LanState.NoPermission)
                    }

                    else -> {
                        when (val pick = LanPolicy.pick(input.wifi, input.pin)) {
                            is LanPolicy.Pick.Blocked -> {
                                stopLan(pick.state)
                            }

                            is LanPolicy.Pick.Ok -> {
                                val t = pick.target
                                // the first eligible network after switching on is the one it stays on
                                if (input.pin == null) lanPin.update { LanPin(t.fingerprint) }
                                listenLan(t)
                            }
                        }
                    }
                }
            }
        }

    private fun listenLan(t: LanTarget) {
        val ip = t.address.hostAddress ?: return stopLan(LanState.NotPrivate)
        if (lanServer?.running == true && lanServer?.boundAddress == t.address && lanAddress == ip) return
        stopLan(LanState.Off)
        val s =
            LocalHttpServer(
                port,
                ::answer,
                asked,
                bindAddress = t.address,
                allowCaller = { it.isLoopbackAddress || LanPolicy.sameSubnet(it, t.address, t.prefix) },
                hostOk = { LanPolicy.hostOk(it, t.address, port) },
            )
        if (s.bind()) {
            lanServer = s
            setLanAddress(ip)
            _lan.value = LanState.Listening(ip)
        } else {
            _lan.value = LanState.PortBusy
        }
    }

    private fun stopLan(state: LanState) {
        lanServer?.stop()
        lanServer = null
        setLanAddress(null)
        _lan.value = state
    }

    private fun setLanAddress(ip: String?) {
        lanAddress = ip
        _state.update { if (it is NsApiState.Listening) it.copy(lan = ip) else it }
    }

    /** Stops listening (the switch stays as it is). */
    fun stop() {
        server.stop()
        lanServer?.stop()
    }

    private fun answer(req: HttpRequest): HttpAnswer =
        NightscoutApi.route(req, secret) {
            val now = clock.now()
            NightscoutApi.Snapshot(recent.filter { it.t >= now - WINDOW }, recentTreatments, now, clock.zone)
        }

    companion object {
        val SETTING = SchemaSettings.bool("apps.nightscout", default = false)
        val LAN_SETTING = SchemaSettings.bool("apps.nightscoutLan", default = false)
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
