// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.settings.sensor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.mauri.sukrara.core.model.SensorState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant

/** Where the wearer's glucose comes from (schema `source.source`). */
enum class SourceChoice {
    /** Sukrara reads the Libre 3 / 3 Plus itself (NFC + Bluetooth). */
    Libre3Direct,

    /** Juggluco on this phone owns the sensor; Sukrara reads its local web server. */
    Juggluco,
}

/** The current sensor as the page shows it. */
data class SensorSummary(val serial: String, val startedAt: Instant, val endsAt: Instant)

/** The signed-in LibreView account: only the email and the receiver ID are kept (never the password). */
data class LibreViewSummary(val email: String, val receiverId: Long?)

/**
 * Whether glucose is actually arriving: the hub's [SensorState] says Active while the link is still connecting or
 * Juggluco doesn't answer, and the page must never say "Working" then.
 */
enum class SourceLink {
    /** Readings arrive (or the [SensorState] alone tells the story: warming up, ended, needs the person). */
    Receiving,

    /** Connecting / reconnecting to the sensor, or Juggluco is sending its history. Leaves by itself. */
    Connecting,

    /** Juggluco's web server doesn't answer: no readings until Juggluco runs with it on. */
    JugglucoUnreachable,

    /** Looking for the sensor (a scanned family): short scans until it shows up. Leaves by itself. */
    Searching,

    /** Connected, but the sensor asks for a calibration (Dexcom): done in the vendor's app or receiver. */
    NoValueCalibration,

    /** Connected, a temporary sensor issue: values come back by themselves. */
    NoValueTemporary,

    /** Connected, the sensor reports an error: if it lasts, the sensor needs replacing. */
    NoValueSensorError,

    /** Connected, the last message carried no value: waiting for the next one. */
    NoValueMissing,
}

/**
 * The paired sensor's family as the page names it ([label]: the brand name, not translated), whether Sukrara's support
 * for it was confirmed on a real sensor ([untested] = never) and how many older sensors of the family the scan skips
 * ([skippedOld]: an ended one still bonded or in range).
 */
data class SensorFamily(val label: String, val untested: Boolean, val skippedOld: Int = 0)

/**
 * Everything Settings › Sensor shows. [sensor] is null until a sensor is known; [account] null = not signed in.
 */
data class SensorSettingsState(
    val source: SourceChoice,
    val state: SensorState,
    val sensor: SensorSummary?,
    val account: LibreViewSummary?,
    val link: SourceLink = SourceLink.Receiving,
    val family: SensorFamily? = null,
)

/**
 * What Settings › Sensor needs from the sensor feature (features never depend on each other: the app implements it
 * over `SensorModule` and the source setting). Pairing and signing in are the sensor feature's screens.
 */
interface SensorSettingsPort {
    val state: Flow<SensorSettingsState>

    /**
     * Changes the glucose source (the source manager follows the setting) and makes sure monitoring runs. Runs to the
     * end in the app's scope even if the page closes right after.
     */
    fun setSource(source: SourceChoice)

    /** Forgets the LibreView account's email and receiver ID; a running sensor keeps working. */
    suspend fun signOut()
}

class SensorSettingsViewModel(private val port: SensorSettingsPort) : ViewModel() {
    val state: StateFlow<SensorSettingsState?> = port.state.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setSource(source: SourceChoice) {
        if (state.value?.source == source) return
        port.setSource(source)
    }

    fun signOut() {
        viewModelScope.launch { port.signOut() }
    }

    companion object {
        fun factory(
            port: SensorSettingsPort,
        ): ViewModelProvider.Factory = viewModelFactory { initializer { SensorSettingsViewModel(port) } }
    }
}
