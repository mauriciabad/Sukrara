// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.ui

import app.mauri.sukrara.core.SensorInfo
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The New sensor flow's steps (ui/NewSensorScreen.kt), as plain data so the rules are unit-tested
 * (NewSensorFlowTest): scan (NFC) → pair (Bluetooth, from the direct source's status) → readings (warm-up for a new
 * sensor, the first reading for one taken over).
 */
object NewSensorFlow {
    /** The three steps shown at the top of the screen. */
    enum class Step { SCAN, PAIR, READINGS }

    /** The Bluetooth part, from the direct source's status words (direct/Libre3Client `onStatus`). */
    enum class PairStep { CONNECTING, SECURING, STARTING }

    /** What stops the flow. Only [FAILED] is an error; the others are states of the sensor or the phone. */
    enum class Trouble { NO_NFC, NFC_OFF, ENDED, LIBRE2, UNSUPPORTED, FAILED, BLUETOOTH_OFF }

    sealed interface Stage {
        val step: Step
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
            override val step = if (trouble == Trouble.BLUETOOTH_OFF) Step.PAIR else Step.SCAN
        }
    }

    /** A successful tap: the sensor's serial, whether it was started now, and when (epoch s). */
    data class Tapped(val serial: String, val activated: Boolean, val at: Long)

    /** Pairing that takes longer than this (s) shows the "another app holds the sensor" hint. */
    const val SLOW_S = 45L

    /** A reading at most this long (s) before the tap counts as the new sensor's first (the sensor's clock is per minute). */
    private const val FIRST_READING_SLACK_S = 90L

    /**
     * After a successful tap: where the flow is, from the direct source's [source] status ("direct: …"), the newest
     * reading's time and the sensor the source reported ([sensor], null until it did).
     */
    fun afterTap(tap: Tapped, source: String, lastReadingT: Long?, sensor: SensorInfo?, now: Long): Stage {
        val s = source.removePrefix("direct: ")
        val info = sensor?.takeIf { it.serial == tap.serial }
        val bleUp = s == "live" || s.startsWith("warming up")
        // a started sensor has no reading before its warm-up ends; one taken over has its current minute at once
        val first = lastReadingT != null && (if (tap.activated) lastReadingT > tap.at else lastReadingT >= tap.at - FIRST_READING_SLACK_S)
        return when {
            s == "sensor ended" -> Stage.Problem(Trouble.ENDED, tap.serial)
            s == "unsupported sensor" -> Stage.Problem(Trouble.UNSUPPORTED, tap.serial)
            s == "waiting for Bluetooth" -> Stage.Problem(Trouble.BLUETOOTH_OFF, tap.serial)
            bleUp && first -> Stage.Ready(tap.serial, info?.endEpoch)
            bleUp && (tap.activated || s.startsWith("warming up")) ->
                Stage.WarmingUp(tap.serial, info?.startEpoch ?: tap.at, info?.warmupEndEpoch ?: (tap.at + 3_600))
            bleUp -> Stage.Pairing(tap.serial, tap.activated, PairStep.STARTING)   // live, the first reading on its way
            else -> Stage.Pairing(tap.serial, tap.activated, pairStep(s), slow = now - tap.at > SLOW_S)
        }
    }

    fun pairStep(status: String): PairStep = when {
        status == "pairing with the sensor" || status == "authorizing" -> PairStep.SECURING
        status == "authorized, subscribing" -> PairStep.STARTING
        else -> PairStep.CONNECTING   // connecting, discovering services, waiting for sensor, reconnecting, no sensor yet
    }

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
        else -> null
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
