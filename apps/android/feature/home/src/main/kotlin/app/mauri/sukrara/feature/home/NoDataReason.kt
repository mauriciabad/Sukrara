// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.home

import app.mauri.sukrara.core.model.GlucoseState
import app.mauri.sukrara.core.model.NotMonitoringReason
import app.mauri.sukrara.core.model.Role
import app.mauri.sukrara.core.model.SensorState
import app.mauri.sukrara.core.model.ServiceState
import app.mauri.sukrara.core.model.SourceStatus
import app.mauri.sukrara.graph.GlucoseLook
import java.time.Instant

/**
 * Why the home screen has no value to show, and what the person can do about it. Never a bare "---": every reason has
 * a title, an explanation and (when something can be done) an [action]. The first matching rule wins, in the order
 * of [of]: things the person must fix before things that are just slow.
 */
sealed interface NoDataReason {
    val action: HomeAction?

    /** First-run setup hasn't chosen a role yet. */
    data object SetupIncomplete : NoDataReason {
        override val action = HomeAction.OpenSetup
    }

    /**
     * A role is set but no reading has come yet ("Not monitoring: no sensor yet"). No "no data" alarm is armed in this
     * state (CHANGES P3), so the wording is never an alarm's.
     */
    data class NoSensorYet(val follower: Boolean) : NoDataReason {
        override val action = if (follower) HomeAction.OpenSetup else HomeAction.PairSensor
    }

    data object CoreMissing : NoDataReason {
        override val action: HomeAction? = null
    }

    data object BluetoothOff : NoDataReason {
        override val action = HomeAction.EnableBluetooth
    }

    /** Android refused to start monitoring in the background. */
    data object MonitoringStopped : NoDataReason {
        override val action = HomeAction.ResumeMonitoring
    }

    data object SensorEnded : NoDataReason {
        override val action = HomeAction.PairSensor
    }

    data class WarmingUp(val readyAt: Instant) : NoDataReason {
        override val action: HomeAction? = null
    }

    /** The wearer removed this follower. */
    data object LinkRevoked : NoDataReason {
        override val action = HomeAction.OpenSetup
    }

    /** Follower: nothing heard from the wearer's phone since [since] (null = never). */
    data class WearerSilent(val since: Instant?) : NoDataReason {
        override val action: HomeAction? = null
    }

    /** Wearer: monitoring runs but the sensor sent nothing for 20 min (the card's "no readings" step). */
    data class NoReadings(val since: Instant?) : NoDataReason {
        override val action = HomeAction.OpenSensor
    }

    companion object {
        /**
         * The reason for [state], or null when the card has a value to show. The card's own decision [look]
         * ([homeLook]) decides "no value": a reason is only given when it hides the number (≥ 20 min or none), or when a
         * state makes any number wrong (setup, ended, warming up), so the panel and the card never disagree.
         */
        fun of(
            state: GlucoseState,
            look: GlucoseLook,
        ): NoDataReason? {
            val follower = state.role == Role.Follower
            val latest = state.latest
            val valueShown = look.shown
            val sensor = state.sensor
            return when {
                state.notMonitoring == NotMonitoringReason.SetupIncomplete -> SetupIncomplete
                state.link?.revoked == true -> LinkRevoked
                state.sensor == SensorState.CoreMissing -> CoreMissing
                state.sensor == SensorState.Ended -> SensorEnded
                sensor is SensorState.WarmingUp -> WarmingUp(sensor.readyAt)
                valueShown -> null
                state.service is ServiceState.Stopped -> MonitoringStopped
                state.sensor == SensorState.BluetoothOff && !follower -> BluetoothOff
                latest == null && state.notMonitoring == NotMonitoringReason.NoSensorYet -> NoSensorYet(follower)
                follower -> WearerSilent(state.link?.lastHeardAt ?: latest?.t)
                else -> NoReadings((state.source as? SourceStatus.Interrupted)?.since ?: latest?.t)
            }
        }
    }
}

/** Something the home screen asks the app to do (routed by `:app`; home never navigates by itself). */
sealed interface HomeAction {
    data object OpenSetup : HomeAction

    data object PairSensor : HomeAction

    data object OpenSensor : HomeAction

    data object EnableBluetooth : HomeAction

    data object ResumeMonitoring : HomeAction

    data object AddTreatment : HomeAction

    data object AddWidget : HomeAction

    data object OpenBackup : HomeAction

    data object OpenClinicUpload : HomeAction

    /** Opens the Sukrara Showcase's app info page so the person can turn it off or uninstall it. */
    data object OpenShowcaseInfo : HomeAction
}
