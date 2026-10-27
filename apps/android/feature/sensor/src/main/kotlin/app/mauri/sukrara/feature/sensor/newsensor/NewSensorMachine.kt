// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.sensor.newsensor

import app.mauri.sukrara.core.model.SensorState
import app.mauri.sukrara.sensor.ble.LinkStatus
import app.mauri.sukrara.sensor.pairing.PairOutcome
import app.mauri.sukrara.sensor.source.SensorStatus
import java.time.Duration
import java.time.Instant

/** What the phone knows before anything is sent to a sensor (re-read at every gate). */
data class SensorEnv(
    /** This build can read a Libre 3 (release builds always can). */
    val coreAvailable: Boolean,
    val nfcAvailable: Boolean,
    val nfcOn: Boolean,
    /** A LibreView account receiver ID is known (CHANGES P1: never start a sensor with a random ID). */
    val signedIn: Boolean,
    /** Sukrara Showcase (the PoC) is installed and may still own the sensor. */
    val showcaseInstalled: Boolean,
    /** LibreLink / Libre by Abbott is installed: running both loses the sensor. */
    val officialAppInstalled: Boolean,
    /** Nearby devices (BLUETOOTH_CONNECT) is granted. */
    val bluetoothPermission: Boolean,
    val bluetoothOn: Boolean,
    /** Notifications are allowed (the "Sensor ready" notification needs them). */
    val notificationsAllowed: Boolean,
)

enum class NfcFailure {
    /** The tap didn't complete (moved away too soon, no answer). Scanning again is safe. */
    MovedTooSoon,

    /** Not a Libre 3 / 3 Plus (a Libre 2, Lingo, another security version). */
    NotLibre3,

    /** The sensor has ended. */
    SensorEnded,
}

enum class BleFailure { BluetoothOff, PermissionMissing, Timeout }

/** One screen of the new-sensor flow. Every step is visible and has a way out (`Cancel` or `Done`). */
sealed interface NewSensorStep {
    data object Idle : NewSensorStep

    /** This build has no Libre 3 core: start and takeover are blocked (use Juggluco). */
    data object CoreMissing : NewSensorStep

    /** This phone has no NFC: a Libre 3 can't be started or taken over here (use Juggluco). */
    data object NoNfc : NewSensorStep

    data object NfcOff : NewSensorStep

    /** No LibreView receiver ID: nothing is sent to the sensor until the person signs in. */
    data object NeedsLibreViewSignIn : NewSensorStep

    data object ShowcaseWarning : NewSensorStep

    data object OfficialAppWarning : NewSensorStep

    data object NeedsBluetoothPermission : NewSensorStep

    data object BluetoothOff : NewSensorStep

    /** Notifications are off: the person can allow them or go on without the "ready" notification. */
    data object NeedsNotifications : NewSensorStep

    data object WaitingForTap : NewSensorStep

    data object ReadingNfc : NewSensorStep

    data class NfcFailed(val cause: NfcFailure) : NewSensorStep

    /** Another receiver (the showcase, another account) owns the sensor; nothing changed on it. */
    data class TakeoverFailed(val showcaseInstalled: Boolean) : NewSensorStep

    data class ConnectingBle(val serial: String, val since: Instant, val activated: Boolean) : NewSensorStep

    data class BleFailed(val serial: String, val cause: BleFailure) : NewSensorStep

    data class WarmingUp(val serial: String, val readyAt: Instant) : NewSensorStep

    data object Ready : NewSensorStep

    /** The person closed the flow after the tap: Sukrara keeps connecting / warming up in the background. */
    data object LeftRunning : NewSensorStep

    /** The person chose Juggluco as the source instead. */
    data object UsingJuggluco : NewSensorStep

    data object Cancelled : NewSensorStep
}

/** Only these steps take an NFC tap (no double dispatch while one is being read). */
val NewSensorStep.acceptsTap: Boolean
    get() = this == NewSensorStep.WaitingForTap || this is NewSensorStep.NfcFailed

/** The flow is over; leaving the screen is the only thing left. */
val NewSensorStep.isFinal: Boolean
    get() = this == NewSensorStep.Ready || this == NewSensorStep.UsingJuggluco || this == NewSensorStep.Cancelled ||
        this == NewSensorStep.LeftRunning

/** After a successful tap the sensor is paired: closing leaves it connecting instead of aborting. */
val NewSensorStep.isPaired: Boolean
    get() = this is NewSensorStep.ConnectingBle || this is NewSensorStep.WarmingUp || this is NewSensorStep.BleFailed

sealed interface NewSensorEvent {
    /** Start, or re-check the gates after coming back (signed in, closed the other app). */
    data class Start(val env: SensorEnv) : NewSensorEvent

    /** "Continue" on a warning, or "Not now" on the notifications step. */
    data object Continue : NewSensorEvent

    data object Cancel : NewSensorEvent

    /** A tag was found; it's being read. */
    data object TagFound : NewSensorEvent

    data class Tapped(val outcome: PairOutcome) : NewSensorEvent

    data class Source(val detail: SensorStatus, val state: SensorState) : NewSensorEvent

    data class Tick(val now: Instant) : NewSensorEvent

    data object Retry : NewSensorEvent

    data object UseJuggluco : NewSensorEvent
}

/** The flow's whole state: the step plus what the gates need. */
data class NewSensorState(
    val step: NewSensorStep = NewSensorStep.Idle,
    val env: SensorEnv? = null,
    val showcaseAcknowledged: Boolean = false,
    val officialAcknowledged: Boolean = false,
    val notificationsSkipped: Boolean = false,
)

/** The pure transition table (NewSensorMachineTest). The receiver-ID order is chunk 12's (`ReceiverIds`). */
object NewSensorMachine {
    /** How long Bluetooth may take to give the first sign of life after a tap. */
    val BLE_TIMEOUT: Duration = Duration.ofMinutes(2)

    /** Steps where (re)starting re-checks the gates; later steps ignore it (a tap is already under way). */
    private val GATES = setOf(
        NewSensorStep.Idle,
        NewSensorStep.CoreMissing,
        NewSensorStep.NeedsLibreViewSignIn,
        NewSensorStep.ShowcaseWarning,
        NewSensorStep.OfficialAppWarning,
        NewSensorStep.NoNfc,
        NewSensorStep.NfcOff,
        NewSensorStep.NeedsBluetoothPermission,
        NewSensorStep.BluetoothOff,
        NewSensorStep.NeedsNotifications,
    )

    fun reduce(state: NewSensorState, event: NewSensorEvent, now: Instant): NewSensorState {
        val step = state.step
        if (step.isFinal) return state
        return when (event) {
            NewSensorEvent.Cancel -> when {
                // the tap is under way: stopping now could leave a started sensor without its pairing
                step == NewSensorStep.ReadingNfc -> state
                step.isPaired -> state.copy(step = NewSensorStep.LeftRunning)
                else -> state.copy(step = NewSensorStep.Cancelled)
            }
            NewSensorEvent.UseJuggluco ->
                if (step == NewSensorStep.ReadingNfc) state else state.copy(step = NewSensorStep.UsingJuggluco)
            is NewSensorEvent.Start -> if (step in GATES) gate(state.copy(env = event.env)) else state
            NewSensorEvent.Continue -> when (step) {
                NewSensorStep.ShowcaseWarning -> gate(state.copy(showcaseAcknowledged = true))
                NewSensorStep.OfficialAppWarning -> gate(state.copy(officialAcknowledged = true))
                NewSensorStep.NeedsNotifications -> gate(state.copy(notificationsSkipped = true))
                else -> state
            }
            NewSensorEvent.TagFound -> if (step.acceptsTap) state.copy(step = NewSensorStep.ReadingNfc) else state
            is NewSensorEvent.Tapped ->
                if (step == NewSensorStep.ReadingNfc) state.copy(step = afterTap(event.outcome, now)) else state
            is NewSensorEvent.Source -> state.copy(step = afterSource(step, event.detail, event.state, now))
            is NewSensorEvent.Tick -> state.copy(step = afterTick(step, event.now))
            NewSensorEvent.Retry -> state.copy(step = retry(step, now))
        }
    }

    /**
     * The gates in order, all before anything is sent to a sensor: core → NFC → LibreView account → showcase →
     * official app → Bluetooth permission → Bluetooth on → notifications (skippable) → tap.
     */
    private fun gate(state: NewSensorState): NewSensorState {
        val env = state.env ?: return state
        val next = when {
            !env.coreAvailable -> NewSensorStep.CoreMissing
            !env.nfcAvailable -> NewSensorStep.NoNfc
            !env.nfcOn -> NewSensorStep.NfcOff
            !env.signedIn -> NewSensorStep.NeedsLibreViewSignIn
            env.showcaseInstalled && !state.showcaseAcknowledged -> NewSensorStep.ShowcaseWarning
            env.officialAppInstalled && !state.officialAcknowledged -> NewSensorStep.OfficialAppWarning
            !env.bluetoothPermission -> NewSensorStep.NeedsBluetoothPermission
            !env.bluetoothOn -> NewSensorStep.BluetoothOff
            !env.notificationsAllowed && !state.notificationsSkipped -> NewSensorStep.NeedsNotifications
            else -> NewSensorStep.WaitingForTap
        }
        return state.copy(step = next)
    }

    private fun afterTap(outcome: PairOutcome, now: Instant): NewSensorStep = when (outcome) {
        is PairOutcome.Paired -> NewSensorStep.ConnectingBle(outcome.serial, now, outcome.activated)
        is PairOutcome.NeedsLibreViewSignIn -> NewSensorStep.NeedsLibreViewSignIn
        is PairOutcome.OwnedByAnotherApp -> NewSensorStep.TakeoverFailed(outcome.showcaseInstalled)
        is PairOutcome.Ended -> NewSensorStep.NfcFailed(NfcFailure.SensorEnded)
        PairOutcome.Libre2Unsupported, is PairOutcome.Unsupported -> NewSensorStep.NfcFailed(NfcFailure.NotLibre3)
        is PairOutcome.Failed -> NewSensorStep.NfcFailed(NfcFailure.MovedTooSoon)
    }

    /**
     * Only the sensor this tap paired counts (the source may still report the previous one for a moment): a step moves
     * on when the detail names [serial], or when the problem can't be about another sensor (permission, ownership).
     */
    private fun afterSource(step: NewSensorStep, detail: SensorStatus, state: SensorState, now: Instant): NewSensorStep {
        val serial = when (step) {
            is NewSensorStep.ConnectingBle -> step.serial
            is NewSensorStep.WarmingUp -> step.serial
            else -> return step
        }
        return when (detail) {
            SensorStatus.BluetoothPermissionMissing -> NewSensorStep.BleFailed(serial, BleFailure.PermissionMissing)
            is SensorStatus.OwnedByAnotherApp -> NewSensorStep.TakeoverFailed(detail.showcaseInstalled)
            is SensorStatus.Libre3 -> if (detail.serial != serial) step else afterLink(step, serial, detail.link, state, now)
            else -> step
        }
    }

    private fun afterLink(step: NewSensorStep, serial: String, link: LinkStatus, state: SensorState, now: Instant): NewSensorStep =
        when (link) {
            LinkStatus.Live -> NewSensorStep.Ready
            LinkStatus.SensorEnded -> NewSensorStep.NfcFailed(NfcFailure.SensorEnded)
            LinkStatus.WaitingForBluetooth -> NewSensorStep.BleFailed(serial, BleFailure.BluetoothOff)
            LinkStatus.PermissionMissing -> NewSensorStep.BleFailed(serial, BleFailure.PermissionMissing)
            is LinkStatus.WarmingUp -> NewSensorStep.WarmingUp(
                serial,
                (state as? SensorState.WarmingUp)?.readyAt ?: now.plus(Duration.ofMinutes(link.minutesLeft.toLong())),
            )
            else -> step
        }

    private fun afterTick(step: NewSensorStep, now: Instant): NewSensorStep =
        if (step is NewSensorStep.ConnectingBle && !now.isBefore(step.since.plus(BLE_TIMEOUT))) {
            NewSensorStep.BleFailed(step.serial, BleFailure.Timeout)
        } else {
            step
        }

    private fun retry(step: NewSensorStep, now: Instant): NewSensorStep = when (step) {
        // after stopping the other app (showcase: Settings › Sensor › Stop), the same sensor can be scanned again
        is NewSensorStep.NfcFailed, is NewSensorStep.TakeoverFailed -> NewSensorStep.WaitingForTap
        is NewSensorStep.BleFailed -> NewSensorStep.ConnectingBle(step.serial, now, activated = false)
        else -> step
    }
}
