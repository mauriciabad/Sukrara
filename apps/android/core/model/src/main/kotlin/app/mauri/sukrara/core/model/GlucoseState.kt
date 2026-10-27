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
    /**
     * When monitoring started: the first reading from the current source (persisted; cleared only when the person
     * removes the sensor or changes the role). Null = not monitoring yet, so no "no data" alarm can be armed (CHANGES P3).
     */
    val monitoringStartedAt: Instant? = null,
    /** Whether the monitoring service runs. */
    val service: ServiceState = ServiceState.NotRunning,
) {
    /** Why nothing is monitored yet ("Not monitoring: no sensor yet"), or null while monitoring. */
    val notMonitoring: NotMonitoringReason?
        get() =
            when {
                role == null -> NotMonitoringReason.SetupIncomplete
                monitoringStartedAt == null -> NotMonitoringReason.NoSensorYet
                else -> null
            }

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
}

/** Why the app isn't monitoring yet. Shown as "Not monitoring: no sensor yet" with the action "Set up sensor". */
enum class NotMonitoringReason {
    /** First-run setup hasn't chosen a role. */
    SetupIncomplete,

    /** A role is set but no reading has come from a source yet (no sensor paired / not joined, or waiting). */
    NoSensorYet,
}

/** The monitoring foreground service, as the person needs to see it. */
sealed interface ServiceState {
    data object Running : ServiceState

    /** Not started (yet), or nothing to monitor. */
    data object NotRunning : ServiceState

    /** Android refused or ended it; shown as "Monitoring stopped — tap to resume". */
    data class Stopped(val reason: StopReason) : ServiceState
}

enum class StopReason {
    /** Android didn't allow starting it from the background (ForegroundServiceStartNotAllowedException). */
    StartDenied,
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

    /** The sensor as shown (a follower mirrors the wearer's). */
    fun setSensor(sensor: SensorState)

    /** The alert runtime's summary (firing kinds, mute) for every surface. */
    fun setAlerts(alerts: AlertUiState)

    /** The follower's link to the wearer. */
    fun setLink(link: LinkState?)
}
