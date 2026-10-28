// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.sensor.newsensor

import app.mauri.sukrara.core.model.SensorState
import app.mauri.sukrara.sensor.SensorProfile
import app.mauri.sukrara.sensor.SensorSetup
import app.mauri.sukrara.sensor.ble.FailReason
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
    /** A LibreView account receiver ID is known (never start a sensor with a random ID). */
    val signedIn: Boolean,
    /** LibreLink / Libre by Abbott is installed: running both loses the sensor. */
    val officialAppInstalled: Boolean,
    /** Nearby devices (BLUETOOTH_CONNECT) is granted. */
    val bluetoothPermission: Boolean,
    val bluetoothOn: Boolean,
    /** Notifications are allowed (the "Sensor ready" notification needs them). */
    val notificationsAllowed: Boolean,
    /** Nearby devices also covers scanning (BLUETOOTH_SCAN): the families found by a scan need it. */
    val scanPermission: Boolean = bluetoothPermission,
)

enum class NfcFailure {
    /** The tap didn't complete (moved away too soon, no answer). Scanning again is safe. */
    MovedTooSoon,

    /** Not a Libre 3 / 3 Plus (a Libre 2, Lingo, another security version). */
    NotLibre3,

    /** The sensor has ended. */
    SensorEnded,
}

enum class BleFailure {
    BluetoothOff,
    PermissionMissing,
    Timeout,

    /** A scanned family: the typed id isn't this device's. Back to the setup. */
    WrongId,

    /** A scanned family refused the authentication. */
    Refused,

    /** Android's Bluetooth pairing (the PIN on the cap) didn't complete. */
    BondFailed,
    ;

    /** Pairing again (the setup) fixes it, not reconnecting. */
    val needsSetup: Boolean get() = this == WrongId || this == Refused || this == BondFailed
}

/** One screen of the new-sensor flow. Every step is visible and has a way out (`Cancel` or `Done`). */
sealed interface NewSensorStep {
    data object Idle : NewSensorStep

    /** Which sensor? The Libre 3 preselected; the families never tested on a real sensor are tagged "Untested". */
    data class ChooseSensor(val selected: SensorProfile) : NewSensorStep

    /**
     * The person chose a family never tested on a real sensor: a warning whose "Continue anyway" is enabled only
     * [UNTESTED_WAIT] after [since] (so it gets read). Cancel goes back to the choice.
     */
    data class ConfirmUntested(val profile: SensorProfile, val since: Instant) : NewSensorStep

    /** A scanned family's setup: the id to type, the vendor app to have running, or the PIN to have ready. */
    data class SensorSetupStep(val profile: SensorProfile, val id: String = "") : NewSensorStep {
        val typedId: SensorSetup.TypedId? get() = profile.setup as? SensorSetup.TypedId

        /** "Pair" is possible: no id needed, or a valid one typed. */
        val canPair: Boolean get() = typedId?.valid(id) ?: true
    }

    /** This build has no Libre 3 core: start and takeover are blocked (use Juggluco). */
    data object CoreMissing : NewSensorStep

    /** This phone has no NFC: a Libre 3 can't be started or taken over here (use Juggluco). */
    data object NoNfc : NewSensorStep

    data object NfcOff : NewSensorStep

    /** No LibreView receiver ID: nothing is sent to the sensor until the person signs in. */
    data object NeedsLibreViewSignIn : NewSensorStep

    data object OfficialAppWarning : NewSensorStep

    data object NeedsBluetoothPermission : NewSensorStep

    data object BluetoothOff : NewSensorStep

    /** Notifications are off: the person can allow them or go on without the "ready" notification. */
    data object NeedsNotifications : NewSensorStep

    data object WaitingForTap : NewSensorStep

    data object ReadingNfc : NewSensorStep

    data class NfcFailed(val cause: NfcFailure) : NewSensorStep

    /** Another receiver (another app or account) owns the sensor; nothing changed on it. */
    data object TakeoverFailed : NewSensorStep

    /**
     * After the tap (Libre 3) or "Pair" (a scanned family: [profile] set): [serial] is the pairing's key
     * (`SensorStatus.Direct.key`), [link] the latest link state a scanned family's page explains (scanning, PIN, the
     * Dexcom app, no session).
     */
    data class ConnectingBle(
        val serial: String,
        val since: Instant,
        val activated: Boolean,
        val profile: SensorProfile = SensorProfile.Libre3,
        val link: LinkStatus? = null,
    ) : NewSensorStep

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
    get() =
        this == NewSensorStep.Ready || this == NewSensorStep.UsingJuggluco || this == NewSensorStep.Cancelled ||
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

    /** A family picked on the choice step. */
    data class Choose(val profile: SensorProfile) : NewSensorEvent

    /** The id typed on a scanned family's setup (normalized: letters and digits, upper case). */
    data class IdTyped(val text: String) : NewSensorEvent

    /** A scanned family's pairing was saved: [key] names it in the source's status. */
    data class PairedScanned(val key: String) : NewSensorEvent

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
    /** The family chosen on the first step; null until then. */
    val profile: SensorProfile? = null,
    val env: SensorEnv? = null,
    val officialAcknowledged: Boolean = false,
    val notificationsSkipped: Boolean = false,
)

/** The pure transition table (NewSensorMachineTest). The receiver-ID order is in `ReceiverIds`. */
object NewSensorMachine {
    /** How long Bluetooth may take to give the first sign of life after a tap. */
    val BLE_TIMEOUT: Duration = Duration.ofMinutes(2)

    /**
     * The same for a scanned family: scan windows (30 s on, 60 s off), Android's PIN dialog and the Dexcom app's
     * session all take longer than a Libre 3 that the tap pointed at.
     */
    val SCANNED_TIMEOUT: Duration = Duration.ofMinutes(10)

    /** How long "Continue anyway" stays disabled on the untested-sensor warning. */
    val UNTESTED_WAIT: Duration = Duration.ofSeconds(10)

    /**
     * Steps where (re)starting re-checks the gates (also the tap page: NFC turned off or a sign-out meanwhile sends the
     * person back to that gate); later steps ignore it (a tap is under way or done).
     */
    private val GATES =
        setOf(
            NewSensorStep.WaitingForTap,
            NewSensorStep.Idle,
            NewSensorStep.CoreMissing,
            NewSensorStep.NeedsLibreViewSignIn,
            NewSensorStep.OfficialAppWarning,
            NewSensorStep.NoNfc,
            NewSensorStep.NfcOff,
            NewSensorStep.NeedsBluetoothPermission,
            NewSensorStep.BluetoothOff,
            NewSensorStep.NeedsNotifications,
        )

    private fun NewSensorStep.isGate() = this in GATES || this is NewSensorStep.SensorSetupStep

    fun reduce(state: NewSensorState, event: NewSensorEvent, now: Instant): NewSensorState {
        val step = state.step
        if (step.isFinal) return state
        return when (event) {
            NewSensorEvent.Cancel -> {
                when {
                    // the tap is under way: stopping now could leave a started sensor without its pairing
                    step == NewSensorStep.ReadingNfc -> state

                    step.isPaired -> state.copy(step = NewSensorStep.LeftRunning)

                    // the warning's Cancel goes back to the choice, never on
                    step is NewSensorStep.ConfirmUntested -> state.copy(step = NewSensorStep.ChooseSensor(step.profile))

                    else -> state.copy(step = NewSensorStep.Cancelled)
                }
            }

            NewSensorEvent.UseJuggluco -> {
                if (step == NewSensorStep.ReadingNfc) state else state.copy(step = NewSensorStep.UsingJuggluco)
            }

            is NewSensorEvent.Start -> {
                when {
                    state.profile == null && step == NewSensorStep.Idle -> {
                        state.copy(env = event.env, step = NewSensorStep.ChooseSensor(SensorProfile.Libre3))
                    }

                    // a setup keeps what was typed when the gates still pass
                    step is NewSensorStep.SensorSetupStep -> {
                        gate(
                            state.copy(env = event.env),
                        ).let { if (it.step is NewSensorStep.SensorSetupStep) state.copy(env = event.env) else it }
                    }

                    step.isGate() -> {
                        gate(state.copy(env = event.env))
                    }

                    else -> {
                        state.copy(env = event.env)
                    }
                }
            }

            is NewSensorEvent.Choose -> {
                if (step is NewSensorStep.ChooseSensor) state.copy(step = NewSensorStep.ChooseSensor(event.profile)) else state
            }

            is NewSensorEvent.IdTyped -> {
                if (step is NewSensorStep.SensorSetupStep) {
                    val typed = step.typedId ?: return state
                    state.copy(step = step.copy(id = typed.normalize(event.text).take(typed.length)))
                } else {
                    state
                }
            }

            is NewSensorEvent.PairedScanned -> {
                if (step is NewSensorStep.SensorSetupStep && step.canPair) {
                    state.copy(step = NewSensorStep.ConnectingBle(event.key, now, activated = false, profile = step.profile))
                } else {
                    state
                }
            }

            NewSensorEvent.Continue -> {
                when (step) {
                    is NewSensorStep.ChooseSensor -> {
                        if (step.selected.verified) {
                            gate(state.copy(profile = step.selected))
                        } else {
                            state.copy(step = NewSensorStep.ConfirmUntested(step.selected, now))
                        }
                    }

                    is NewSensorStep.ConfirmUntested -> {
                        if (now.isBefore(step.since.plus(UNTESTED_WAIT))) state else gate(state.copy(profile = step.profile))
                    }

                    NewSensorStep.OfficialAppWarning -> {
                        gate(state.copy(officialAcknowledged = true))
                    }

                    NewSensorStep.NeedsNotifications -> {
                        gate(state.copy(notificationsSkipped = true))
                    }

                    else -> {
                        state
                    }
                }
            }

            NewSensorEvent.TagFound -> {
                if (step.acceptsTap) state.copy(step = NewSensorStep.ReadingNfc) else state
            }

            is NewSensorEvent.Tapped -> {
                if (step == NewSensorStep.ReadingNfc) state.copy(step = afterTap(event.outcome, now)) else state
            }

            is NewSensorEvent.Source -> {
                state.copy(step = afterSource(step, event.detail, event.state, now))
            }

            is NewSensorEvent.Tick -> {
                state.copy(step = afterTick(step, event.now))
            }

            NewSensorEvent.Retry -> {
                retry(state, now)
            }
        }
    }

    /**
     * The gates in order, all before anything is sent to a sensor: core → NFC → LibreView account → official app
     * → Bluetooth permission → Bluetooth on → notifications (skippable) → tap.
     */
    private fun gate(state: NewSensorState): NewSensorState {
        val env = state.env ?: return state
        val profile = state.profile ?: SensorProfile.Libre3
        if (profile.needsScan) return state.copy(step = scannedGate(state, env, profile))
        val next =
            when {
                !env.coreAvailable -> NewSensorStep.CoreMissing
                !env.nfcAvailable -> NewSensorStep.NoNfc
                !env.nfcOn -> NewSensorStep.NfcOff
                !env.signedIn -> NewSensorStep.NeedsLibreViewSignIn
                env.officialAppInstalled && !state.officialAcknowledged -> NewSensorStep.OfficialAppWarning
                !env.bluetoothPermission -> NewSensorStep.NeedsBluetoothPermission
                !env.bluetoothOn -> NewSensorStep.BluetoothOff
                !env.notificationsAllowed && !state.notificationsSkipped -> NewSensorStep.NeedsNotifications
                else -> NewSensorStep.WaitingForTap
            }
        return state.copy(step = next)
    }

    /** A scanned family: Bluetooth (permission incl. scanning, on) → notifications (skippable) → its setup. */
    private fun scannedGate(
        state: NewSensorState,
        env: SensorEnv,
        profile: SensorProfile,
    ): NewSensorStep =
        when {
            !env.bluetoothPermission || !env.scanPermission -> {
                NewSensorStep.NeedsBluetoothPermission
            }

            !env.bluetoothOn -> {
                NewSensorStep.BluetoothOff
            }

            !env.notificationsAllowed && !state.notificationsSkipped -> {
                NewSensorStep.NeedsNotifications
            }

            else -> {
                (state.step as? NewSensorStep.SensorSetupStep)?.takeIf { it.profile == profile }
                    ?: NewSensorStep.SensorSetupStep(profile)
            }
        }

    private fun afterTap(outcome: PairOutcome, now: Instant): NewSensorStep =
        when (outcome) {
            is PairOutcome.Paired -> NewSensorStep.ConnectingBle(outcome.serial, now, outcome.activated)
            is PairOutcome.NeedsLibreViewSignIn -> NewSensorStep.NeedsLibreViewSignIn
            is PairOutcome.OwnedByAnotherApp -> NewSensorStep.TakeoverFailed
            is PairOutcome.Ended -> NewSensorStep.NfcFailed(NfcFailure.SensorEnded)
            PairOutcome.Libre2Unsupported, is PairOutcome.Unsupported -> NewSensorStep.NfcFailed(NfcFailure.NotLibre3)
            is PairOutcome.Failed -> NewSensorStep.NfcFailed(NfcFailure.MovedTooSoon)
        }

    /**
     * Only the sensor this tap paired counts (the source may still report the previous one for a while: it doesn't
     * clear its detail on a restart): a step moves on when the detail names [serial], or when the problem can't be
     * about another sensor (permission, ownership). `SensorStatus.Ended` names no sensor, so it counts only once this
     * sensor was seen warming up (the previous, ended sensor is the usual reason for a new one). A failed step recovers
     * by itself when the link comes back (Bluetooth turned on, permission granted).
     */
    private fun afterSource(step: NewSensorStep, detail: SensorStatus, state: SensorState, now: Instant): NewSensorStep {
        val serial =
            when (step) {
                is NewSensorStep.ConnectingBle -> step.serial
                is NewSensorStep.WarmingUp -> step.serial
                is NewSensorStep.BleFailed -> step.serial
                else -> return step
            }
        return when (detail) {
            SensorStatus.BluetoothPermissionMissing -> NewSensorStep.BleFailed(serial, BleFailure.PermissionMissing)
            is SensorStatus.OwnedByAnotherApp -> NewSensorStep.TakeoverFailed
            SensorStatus.Ended -> if (step is NewSensorStep.WarmingUp) NewSensorStep.NfcFailed(NfcFailure.SensorEnded) else step
            is SensorStatus.Direct -> if (detail.key != serial) step else afterLink(step, serial, detail.profile, detail.link, state, now)
            else -> step
        }
    }

    private fun afterLink(
        step: NewSensorStep,
        serial: String,
        profile: SensorProfile,
        link: LinkStatus,
        state: SensorState,
        now: Instant,
    ): NewSensorStep =
        when (link) {
            LinkStatus.Live -> {
                NewSensorStep.Ready
            }

            LinkStatus.WaitingForBluetooth -> {
                NewSensorStep.BleFailed(serial, BleFailure.BluetoothOff)
            }

            is LinkStatus.Failed -> {
                NewSensorStep.BleFailed(
                    serial,
                    when (link.why) {
                        FailReason.WrongId -> BleFailure.WrongId
                        FailReason.Refused -> BleFailure.Refused
                        FailReason.BondFailed -> BleFailure.BondFailed
                    },
                )
            }

            // a scanned family's page explains where it is (scanning, the PIN, the Dexcom app, no session)
            LinkStatus.Scanning, LinkStatus.SensorNotFound, LinkStatus.EnterPin, LinkStatus.WaitingForVendorApp,
            LinkStatus.NoSession,
            -> {
                if (step is NewSensorStep.ConnectingBle) step.copy(link = link) else step
            }

            is LinkStatus.WarmingUp -> {
                NewSensorStep.WarmingUp(
                    serial,
                    // the hub's end of the warm-up wins; else keep the one shown (no new page on every status)
                    (state as? SensorState.WarmingUp)?.readyAt
                        ?: (step as? NewSensorStep.WarmingUp)?.readyAt
                        ?: now.plus(Duration.ofMinutes(link.minutesLeft.toLong())),
                )
            }

            // Bluetooth / permission back: connecting again (its 2-min clock starts over); a timed-out link that is still
            // trying stays "not answering" until it is live or warming up
            else -> {
                if (step is NewSensorStep.BleFailed && step.cause != BleFailure.Timeout) {
                    NewSensorStep.ConnectingBle(serial, now, activated = false, profile = profile)
                } else {
                    step
                }
            }
        }

    private fun afterTick(step: NewSensorStep, now: Instant): NewSensorStep =
        if (step is NewSensorStep.ConnectingBle &&
            !now.isBefore(step.since.plus(if (step.profile.needsScan) SCANNED_TIMEOUT else BLE_TIMEOUT))
        ) {
            NewSensorStep.BleFailed(step.serial, BleFailure.Timeout)
        } else {
            step
        }

    private fun retry(state: NewSensorState, now: Instant): NewSensorState =
        when (val step = state.step) {
            // after stopping the other app, the same sensor can be scanned again —
            // through the gates, which may have changed meanwhile
            is NewSensorStep.NfcFailed, NewSensorStep.TakeoverFailed -> {
                gate(state)
            }

            is NewSensorStep.BleFailed -> {
                val profile = state.profile ?: SensorProfile.Libre3
                if (step.cause.needsSetup) {
                    state.copy(step = NewSensorStep.SensorSetupStep(profile))
                } else {
                    state.copy(step = NewSensorStep.ConnectingBle(step.serial, now, activated = false, profile = profile))
                }
            }

            else -> {
                state
            }
        }
}
