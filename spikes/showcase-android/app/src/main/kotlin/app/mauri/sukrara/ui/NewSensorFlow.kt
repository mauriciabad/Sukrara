// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.ui

import app.mauri.sukrara.core.Pairing
import app.mauri.sukrara.core.SensorInfo
import app.mauri.sukrara.core.SensorProfile
import app.mauri.sukrara.core.SensorProfiles
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The New sensor flow's steps (ui/NewSensorScreen.kt), as plain data so the rules are unit-tested
 * (NewSensorFlowTest): sensor (which family; the Libre 3 preselected) → scan (NFC tap, or the family's setup: a typed
 * id, the vendor app, the PIN to have ready) → pair (Bluetooth, from the direct source's status) → readings (warm-up
 * for a new sensor, the first reading for one taken over).
 */
object NewSensorFlow {
    /** The steps shown at the top of the screen. */
    enum class Step { SENSOR, SCAN, PAIR, READINGS }

    /** The brand step's choice when the screen opens: the Libre 3, so its flow stays the default (one tap on). */
    val DEFAULT_PROFILE: SensorProfile = SensorProfiles.LIBRE_3

    /** The families the brand step lists, the Libre 3 first. */
    val choices: List<SensorProfile> get() = SensorProfiles.all

    /** Picking [profile] on the brand step leads to the NFC scan (the Libre 3) or to its own setup. */
    fun afterChoice(profile: SensorProfile): Stage = if (profile.pairing == Pairing.NfcTap) Stage.Waiting else Stage.Setup(profile.id)

    /** Choosing an untested family asks first ([UntestedConfirm]); the Libre 3 never does. */
    fun needsConfirm(profile: SensorProfile) = !profile.verified

    /**
     * *Continue* on the brand step (the user, 2026-10-19: the warning belongs here, not at pairing): an untested
     * family opens the confirmation ([Continue.Confirm]; Cancel stays on the brand step, accept goes to [afterChoice]),
     * the Libre 3 goes straight on.
     */
    fun onContinue(profile: SensorProfile): Continue =
        if (needsConfirm(profile)) Continue.Confirm(profile) else Continue.Go(afterChoice(profile))

    sealed interface Continue {
        data class Go(val stage: Stage) : Continue
        data class Confirm(val profile: SensorProfile) : Continue
    }

    /** The Bluetooth part, from the direct source's status words (direct/GattLink and direct/Libre3Driver `onStatus`). */
    enum class PairStep { CONNECTING, SECURING, STARTING }

    /** What stops the flow. Only [FAILED] is an error; the others are states of the sensor or the phone. */
    enum class Trouble { NO_NFC, NFC_OFF, ENDED, LIBRE2, UNSUPPORTED, FAILED, BLUETOOTH_OFF,
        /** An untested family's Bluetooth pairing failed with a reason (wrong id, refused PIN): back to its setup. */
        PAIR_FAILED }

    sealed interface Stage {
        val step: Step
        /** Which sensor are you starting? [confirm]: only in previews, that family selected with its confirmation open. */
        data class Choose(val confirm: String? = null) : Stage { override val step = Step.SENSOR }
        /** An untested family's setup ([SensorProfile.pairing]): its input and "Pair". */
        data class Setup(val profileId: String) : Stage { override val step = Step.SCAN }
        /** Reader mode on, waiting for a tap. */
        data object Waiting : Stage { override val step = Step.SCAN }
        /** A tag is being read. */
        data object Reading : Stage { override val step = Step.SCAN }
        /** The sensor needs a LibreView account's receiver ID first (nothing was sent to it). */
        data class SignIn(val serial: String, val newSensor: Boolean) : Stage { override val step = Step.SCAN }
        /** [slow]: no progress for [SLOW_S]; the screen then names the usual cause (another app holds the sensor). */
        data class Pairing(val serial: String, val activated: Boolean, val pair: PairStep, val slow: Boolean = false) : Stage {
            override val step = Step.PAIR
        }
        data class WarmingUp(val serial: String, val startEpoch: Long, val readyAt: Long) : Stage { override val step = Step.READINGS }
        /** The first reading arrived: the flow is done. */
        data class Ready(val serial: String, val endEpoch: Long?) : Stage { override val step = Step.READINGS }
        data class Problem(val trouble: Trouble, val serial: String? = null, val detail: String? = null) : Stage {
            override val step = if (trouble == Trouble.BLUETOOTH_OFF || trouble == Trouble.PAIR_FAILED) Step.PAIR else Step.SCAN
        }
    }

    /**
     * A successful tap (or an untested family's "Pair"): the saved sensor's serial, whether it was started now, when
     * (epoch s), its family, and what the screen calls it ([label]: the serial, or the family's name when there is
     * none yet, e.g. a Dexcom G7 read along with the Dexcom app).
     */
    data class Tapped(val serial: String, val activated: Boolean, val at: Long, val profile: SensorProfile = SensorProfiles.LIBRE_3,
                      val label: String = serial)

    /** Pairing that takes longer than this (s) shows the "another app holds the sensor" hint. */
    const val SLOW_S = 45L

    /** A reading at most this long (s) before the tap counts as the new sensor's first (the sensor's clock is per minute). */
    private const val FIRST_READING_SLACK_S = 90L

    /**
     * After a successful tap: where the flow is, from the direct source's [source] status ("direct: …"), the newest
     * reading's time and the sensor the source reported ([sensor], null until it did).
     */
    fun afterTap(tap: Tapped, source: String, lastReadingT: Long?, sensor: SensorInfo?, now: Long, detail: String? = null): Stage {
        val s = source.removePrefix("direct: ")
        val info = sensor?.takeIf { it.serial == tap.serial }
        val bleUp = s == "live" || s.startsWith("warming up")
        // a started sensor has no reading before its warm-up ends; one taken over has its current minute at once
        val first = lastReadingT != null && (if (tap.activated) lastReadingT > tap.at else lastReadingT >= tap.at - FIRST_READING_SLACK_S)
        val name = tap.label
        return when {
            s == "sensor ended" -> Stage.Problem(Trouble.ENDED, name)
            s == "unsupported sensor" -> Stage.Problem(Trouble.UNSUPPORTED, name)
            s == "waiting for Bluetooth" -> Stage.Problem(Trouble.BLUETOOTH_OFF, name)
            s == "failed" && tap.profile.pairing != Pairing.NfcTap -> Stage.Problem(Trouble.PAIR_FAILED, name, detail)
            bleUp && first -> Stage.Ready(name, info?.endEpoch)
            bleUp && (tap.activated || s.startsWith("warming up")) ->
                Stage.WarmingUp(name, info?.startEpoch ?: tap.at, info?.warmupEndEpoch ?: (tap.at + tap.profile.warmup.inWholeSeconds))
            bleUp -> Stage.Pairing(name, tap.activated, PairStep.STARTING)   // live, the first reading on its way
            else -> Stage.Pairing(name, tap.activated, pairStep(s), slow = now - tap.at > SLOW_S)
        }
    }

    fun pairStep(status: String): PairStep = when {
        status == "pairing with the sensor" || status == "authorizing" -> PairStep.SECURING
        status == "authorized, subscribing" -> PairStep.STARTING
        // the untested families (direct/BrandDriver and its drivers)
        status in BRAND_SECURING || status.startsWith("pairing") -> PairStep.SECURING
        status == "reading" -> PairStep.STARTING
        else -> PairStep.CONNECTING   // scanning, connecting, discovering services, waiting for sensor, reconnecting, no sensor yet
    }

    private val BRAND_SECURING = setOf("authenticating", "exchanging keys", "waiting for the Dexcom app")

    /** Debug builds: a stage forced by `DebugReceiver --es cmd newSensor` (screenshots on an emulator without NFC). */
    val preview = MutableStateFlow<Stage?>(null)

    /** Parses the debug command's `--es stage …` (see DebugReceiver). */
    fun previewStage(name: String, now: Long): Stage? = when (name) {
        "waiting" -> Stage.Waiting
        "reading" -> Stage.Reading
        "signin" -> Stage.SignIn("3MH00TEST", newSensor = true)
        "pairing" -> Stage.Pairing("3MH00TEST", activated = true, PairStep.SECURING)
        "slow" -> Stage.Pairing("3MH00TEST", activated = false, PairStep.CONNECTING, slow = true)
        "warmup" -> Stage.WarmingUp("3MH00TEST", now - 17 * 60, now + 43 * 60)
        "ready" -> Stage.Ready("3MH00TEST", now + 15 * 86_400)
        "ended" -> Stage.Problem(Trouble.ENDED, "3MH00TEST")
        "libre2" -> Stage.Problem(Trouble.LIBRE2)
        "failed" -> Stage.Problem(Trouble.FAILED, detail = "Tag was lost")
        "bluetooth" -> Stage.Problem(Trouble.BLUETOOTH_OFF, "3MH00TEST")
        "nfcoff" -> Stage.Problem(Trouble.NFC_OFF)
        "choose" -> Stage.Choose()
        "untested" -> Stage.Choose(confirm = SensorProfiles.DEXCOM_G7.id)
        "pairfailed" -> Stage.Problem(Trouble.PAIR_FAILED, "8GABCD", "wrong transmitter id for this transmitter")
        else -> name.removePrefix("setup-").takeIf { name.startsWith("setup-") && SensorProfiles.byId(it)?.verified == false }
            ?.let { Stage.Setup(it) }
    }
}

/** Whether home shows "Pair new sensor" (ui/OwnerScreens `NewSensorButton`), and how loudly. */
enum class NewSensorDue {
    NO, FIRST, NOW, SOON;

    companion object {
        /** The last day of a sensor: the default end reminder's "have a new sensor ready" (sensorend/SensorEndPlan). */
        const val SOON_S = 86_400L

        fun of(source: String, sensor: SensorInfo?, now: Long): NewSensorDue = when {
            source == "direct: no sensor" -> FIRST
            source == "direct: sensor ended" || source == "direct: unsupported sensor" -> NOW
            sensor != null && app.mauri.sukrara.core.SensorLife.ended(sensor.endEpoch, now) -> NOW
            sensor != null && sensor.endEpoch - now <= SOON_S -> SOON
            else -> NO
        }
    }
}

/**
 * The warning when choosing an untested family (the user's ask, 2026-10-19): "Continue anyway" stays disabled for
 * [WAIT_S] seconds with the count in its label, so the text gets read; Cancel is always enabled and is the default.
 * Every opening starts the wait again. 10 s since 0.11.28 (5 s was too short for the user).
 */
object UntestedConfirm {
    const val WAIT_S = 10

    /** Whole seconds until "Pair anyway" is enabled, [elapsedMs] after the dialog opened (rounded up; 0 = enabled). */
    fun secondsLeft(elapsedMs: Long, waitS: Int = WAIT_S): Int =
        ((waitS * 1000L - elapsedMs.coerceAtLeast(0) + 999) / 1000).toInt().coerceAtLeast(0)

    fun enabled(elapsedMs: Long, waitS: Int = WAIT_S) = secondsLeft(elapsedMs, waitS) == 0
}
