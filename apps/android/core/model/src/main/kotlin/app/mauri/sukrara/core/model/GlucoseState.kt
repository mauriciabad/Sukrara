// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.core.model

import kotlinx.coroutines.flow.StateFlow
import java.time.Instant

/**
 * Everything the app knows about glucose right now, from the one source of truth ([GlucoseHub]). UI, widgets, the
 * live notification, alerts, sharing and uploads all read this; nothing keeps its own copy.
 */
data class GlucoseState(
    /** Null until first-run setup chose a role. */
    val role: Role?,
    val latest: Reading?,
    /** The recent window, oldest first. */
    val recent: List<Reading>,
    val sensor: SensorState,
    val source: SourceStatus,
    val alerts: AlertUiState,
    /** The follower's link to the wearer; null on a wearer phone. */
    val link: LinkState?,
) {
    companion object {
        /** Before anything is known: nothing is shown as a value and no data alarm can fire. */
        val Initial =
            GlucoseState(
                role = null,
                latest = null,
                recent = emptyList(),
                sensor = SensorState.None,
                source = SourceStatus.NotStarted,
                alerts = AlertUiState(),
                link = null,
            )
    }
}

/** The wearer's sensor as the person needs to see it. */
sealed interface SensorState {
    /** No sensor set up yet ("Not monitoring: no sensor yet"). */
    data object None : SensorState

    data class WarmingUp(val readyAt: Instant) : SensorState

    data class Active(val endsAt: Instant?) : SensorState

    data object Ended : SensorState

    /** This build has no Libre 3 native core (a debug build made without Juggluco). */
    data object CoreMissing : SensorState

    data object BluetoothOff : SensorState

    /** The sensor can't be read until the person does something (added by chunk 12; the UI words live in chunk 18). */
    data class ActionNeeded(val action: SensorAction) : SensorState
}

/** What the person has to do so the sensor can be read again. */
enum class SensorAction {
    /** Scan the sensor with the phone (NFC): a new sensor, a lost or unreadable pairing, or taking it over. */
    ScanSensor,

    /** Sign in to LibreView so V1 can start / take over the sensor with the account's receiver ID. */
    SignInLibreView,

    /** Another app (the Sukrara showcase) owns the sensor: stop it there or uninstall it, then scan again. */
    StopOtherApp,

    /** Grant the Nearby devices (Bluetooth) permission. */
    GrantBluetooth,
}

/** Whether readings are arriving from the current source. */
sealed interface SourceStatus {
    /** Monitoring hasn't started (no alarm about missing data in this state). */
    data object NotStarted : SourceStatus

    data object Connecting : SourceStatus

    data class Live(val lastReadingAt: Instant) : SourceStatus

    data class Interrupted(val since: Instant) : SourceStatus
}

/** What the alert runtime shows: which alert kinds are firing (contract kind ids) and the global mute. */
data class AlertUiState(
    val firingKinds: Set<String> = emptySet(),
    val mutedUntil: Instant? = null,
)

/** A follower's link to the wearer it follows. */
data class LinkState(
    val wearerName: String?,
    val lastHeardAt: Instant?,
    val revoked: Boolean = false,
)

/**
 * The single glucose state source. Exactly one input per role feeds it (wearer: the sensor source; follower: the
 * follower engine). Implemented in `:monitor`.
 */
interface GlucoseHub {
    val state: StateFlow<GlucoseState>

    suspend fun ingest(
        readings: List<Reading>,
        from: SourceId,
    )

    fun setSource(status: SourceStatus)
}
