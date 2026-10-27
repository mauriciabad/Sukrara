// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.sensor.newsensor

import android.nfc.Tag
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.mauri.sukrara.core.model.Clock
import app.mauri.sukrara.core.model.SensorState
import app.mauri.sukrara.sensor.pairing.PairOutcome
import app.mauri.sukrara.sensor.source.SensorStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.time.Instant

/** What the new-sensor flow needs from the sensor side (SensorUiModule implements it over chunk 12's SensorModule). */
interface NewSensorPort {
    /** Re-read every time the gates are checked (permissions and installed apps change while the screen is away). */
    suspend fun env(): SensorEnv

    suspend fun pair(tag: Tag): PairOutcome

    val detail: StateFlow<SensorStatus>
    val sensor: StateFlow<SensorState>

    /** Connect again with the saved pairing (BleFailed › Retry). */
    fun retryLink()

    /** Make Juggluco the glucose source (Sukrara stops reading the sensor directly). */
    suspend fun useJuggluco()
}

/** One-shot effects the screen plays (one haptic each). */
enum class NewSensorEffect { TapSucceeded, Ready }

/**
 * Runs [NewSensorMachine]. The NFC exchange runs in [appScope], never in the ViewModel's scope: closing the screen
 * mid-tap must not cancel a start whose pairing hasn't been saved yet (a started sensor without its PIN is lost).
 */
class NewSensorViewModel(
    private val port: NewSensorPort,
    private val clock: Clock,
    private val appScope: CoroutineScope,
) : ViewModel() {
    private val _state = MutableStateFlow(NewSensorState())
    val state: StateFlow<NewSensorState> = _state.asStateFlow()

    private val _effects = Channel<NewSensorEffect>(Channel.BUFFERED)
    val effects: Flow<NewSensorEffect> = _effects.receiveAsFlow()

    init {
        viewModelScope.launch {
            combine(port.detail, port.sensor, NewSensorEvent::Source).collect(::send)
        }
        viewModelScope.launch {
            // a 1 s clock only while something counts (Bluetooth timeout, warm-up countdown)
            state.map { it.step is NewSensorStep.ConnectingBle || it.step is NewSensorStep.WarmingUp }
                .distinctUntilChanged()
                .collect { counting ->
                    while (counting && (_state.value.step.let { it is NewSensorStep.ConnectingBle || it is NewSensorStep.WarmingUp })) {
                        send(NewSensorEvent.Tick(clock.now()))
                        delay(TICK_MS)
                    }
                }
        }
    }

    /** The time the countdown is drawn against (the same clock the machine uses). */
    fun now(): Instant = clock.now()

    /** (Re)check the gates: on first show and every time the screen resumes. */
    fun start() {
        viewModelScope.launch { send(NewSensorEvent.Start(port.env())) }
    }

    fun onContinue() = send(NewSensorEvent.Continue)

    fun onCancel() = send(NewSensorEvent.Cancel)

    fun onRetry() {
        val step = _state.value.step
        send(NewSensorEvent.Retry)
        if (step is NewSensorStep.BleFailed) port.retryLink()
    }

    fun onUseJuggluco() {
        if (_state.value.step == NewSensorStep.ReadingNfc) return
        appScope.launch { port.useJuggluco() }
        send(NewSensorEvent.UseJuggluco)
    }

    /** From the NFC reader callback (a binder thread). */
    fun onTag(tag: Tag) = onTagRead { port.pair(tag) }

    internal fun onTagRead(read: suspend () -> PairOutcome) {
        if (!_state.value.step.acceptsTap) return
        send(NewSensorEvent.TagFound)
        appScope.launch {
            val outcome = read()
            send(NewSensorEvent.Tapped(outcome))
        }
    }

    private fun send(event: NewSensorEvent) {
        while (true) {
            val before = _state.value
            val after = NewSensorMachine.reduce(before, event, clock.now())
            if (!_state.compareAndSet(before, after)) continue
            if (before.step == NewSensorStep.ReadingNfc && after.step is NewSensorStep.ConnectingBle) {
                _effects.trySend(NewSensorEffect.TapSucceeded)
            }
            if (before.step != NewSensorStep.Ready && after.step == NewSensorStep.Ready) _effects.trySend(NewSensorEffect.Ready)
            return
        }
    }

    private companion object {
        const val TICK_MS = 1_000L
    }
}
