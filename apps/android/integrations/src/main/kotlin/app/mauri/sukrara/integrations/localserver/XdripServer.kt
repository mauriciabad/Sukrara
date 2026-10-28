// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.integrations.localserver

import app.mauri.sukrara.core.data.settings.SchemaSettings
import app.mauri.sukrara.core.model.Clock
import app.mauri.sukrara.core.model.Reading
import app.mauri.sukrara.core.model.storage.ReadingsRepository
import app.mauri.sukrara.core.model.storage.SettingsRepository
import app.mauri.sukrara.integrations.CurrentSensor
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant

/** What the xDrip+ server is doing, for Share with apps. */
sealed interface XdripState {
    data object Off : XdripState

    /** Listening on 127.0.0.1:17580; [lastAsked] = the last request (a watch polling through Garmin Connect). */
    data class Listening(val lastAsked: Instant?) : XdripState

    /** Another app (Juggluco, xDrip+, an older install) holds the port: nothing bound, no retry loop. */
    data object PortBusy : XdripState

    /** Juggluco is the sensor source: the port is Juggluco's and it serves the watch itself. */
    data object LeftToJuggluco : XdripState
}

/**
 * The xDrip+-compatible server on 127.0.0.1:17580 for Garmin watches (schema `apps.xdrip`, off by default). It
 * follows its switch live; it never binds while Juggluco is the sensor source (that port is Juggluco's, and our
 * Juggluco source refuses entries that say `device: Sukrara`). Answers come from the readings store (last 24 h).
 * Requests are remembered only as "last asked" in memory: no log file.
 */
class XdripServer(
    private val settings: SettingsRepository,
    private val readings: ReadingsRepository,
    private val sensor: Flow<CurrentSensor?>,
    private val jugglucoIsSource: Flow<Boolean>,
    private val clock: Clock,
    private val io: CoroutineDispatcher,
    private val port: Int = XdripEndpoint.PORT,
) {
    private val _state = MutableStateFlow<XdripState>(XdripState.Off)
    val state: StateFlow<XdripState> = _state.asStateFlow()

    @Volatile private var recent: List<Reading> = emptyList()

    @Volatile private var currentSensor: CurrentSensor? = null
    private val server =
        LocalHttpServer(port, ::answer, onRequest = { _, _ ->
            _state.update { if (it is XdripState.Listening) XdripState.Listening(clock.now()) else it }
        })

    val on: Flow<Boolean> = settings.flow(SETTING)

    suspend fun setOn(on: Boolean) = settings.set(SETTING, on)

    /** Follows the switch and the sensor source for as long as [scope] lives. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun start(scope: CoroutineScope) {
        scope.launch {
            combine(on, jugglucoIsSource) { o, j -> o to j }.distinctUntilChanged().collect { (o, juggluco) -> apply(o, juggluco) }
        }
        scope.launch { sensor.collect { currentSensor = it } }
        // the last 24 h, the window moving forward every hour
        scope.launch {
            flow {
                while (true) {
                    emit(clock.now() - Duration.ofHours(25))
                    delay(Duration.ofHours(1).toMillis())
                }
            }.flatMapLatest { from -> readings.window(from) }.collect { recent = it.asReversed() }
        }
    }

    private suspend fun apply(
        on: Boolean,
        juggluco: Boolean,
    ) = withContext(io) {
        when {
            !on -> {
                server.stop()
                _state.value = XdripState.Off
            }

            juggluco -> {
                server.stop()
                _state.value = XdripState.LeftToJuggluco
            }

            server.running -> {}

            server.bind() -> {
                _state.value = XdripState.Listening(null)
            }

            else -> {
                _state.value = XdripState.PortBusy
            }
        }
    }

    /** Stops listening (the switch stays as it is). */
    fun stop() = server.stop()

    private fun answer(req: HttpRequest): HttpAnswer {
        val now = clock.now()
        val cutoff = now - Duration.ofHours(24)
        return XdripEndpoint.route(req, recent.filter { it.t >= cutoff }, currentSensor, now, clock.zone)
    }

    companion object {
        val SETTING = SchemaSettings.bool("apps.xdrip", default = false)
    }
}
