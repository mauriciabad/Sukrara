// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.sensor.newsensor

import app.mauri.sukrara.sensor.SensorProfile
import app.mauri.sukrara.sensor.ble.LinkStatus
import java.time.Duration
import java.time.Instant

/**
 * Where the person is in the whole flow, for the step bar: Sensor · Scan (or Set up for a family found by a Bluetooth
 * scan) · Pair · Readings. Pure (NewSensorProgressTest).
 */
enum class FlowStage { Sensor, Scan, Pair, Readings }

/** The bar's stage for [this] step; null for the steps that close the screen or leave the flow (no bar). */
val NewSensorStep.stage: FlowStage?
    get() =
        when (this) {
            NewSensorStep.Idle, is NewSensorStep.ChooseSensor, is NewSensorStep.ConfirmUntested -> FlowStage.Sensor

            is NewSensorStep.ConnectingBle, is NewSensorStep.BleFailed -> FlowStage.Pair

            is NewSensorStep.WarmingUp, NewSensorStep.Ready -> FlowStage.Readings

            NewSensorStep.UsingJuggluco, NewSensorStep.LeftRunning, NewSensorStep.Cancelled -> null

            // the gates, the setup, the tap and its problems
            else -> FlowStage.Scan
        }

/** The second stage is "Set up" (an id, a PIN or the vendor app) for a family found by a scan, "Scan" for the tap. */
fun NewSensorStep.setsUp(chosen: SensorProfile?): Boolean =
    when (this) {
        is NewSensorStep.SensorSetupStep -> true
        is NewSensorStep.ConnectingBle -> profile.needsScan
        is NewSensorStep.ChooseSensor -> selected.needsScan
        is NewSensorStep.ConfirmUntested -> profile.needsScan
        else -> chosen?.needsScan == true
    }

/** The three pairing steps shown while connecting, each ticked off as the link moves on. */
enum class PairProgress { Connecting, Securing, Starting }

/** Which pairing step the link is on (null = no word from the link yet: connecting). */
fun pairProgress(link: LinkStatus?): PairProgress =
    when (link) {
        LinkStatus.Pairing, LinkStatus.EnterPin, LinkStatus.Authorizing -> PairProgress.Securing
        LinkStatus.Authorized, LinkStatus.Live, is LinkStatus.WarmingUp -> PairProgress.Starting
        else -> PairProgress.Connecting
    }

/** No reading yet this long after the tap / Pair: the page says what usually holds it up ("Taking longer?"). */
val SLOW_AFTER: Duration = Duration.ofSeconds(45)

/** Connecting for [SLOW_AFTER] or more at [now]. */
fun NewSensorStep.isSlow(now: Instant): Boolean = this is NewSensorStep.ConnectingBle && !now.isBefore(since.plus(SLOW_AFTER))

/** On the tap page with nothing read for this long, the tips open by themselves. */
val TIPS_AFTER: Duration = Duration.ofSeconds(10)

/** The warm-up's length, for the countdown ring (a Libre 3 warms up for an hour; a longer wait fills from 0). */
val WARM_UP: Duration = Duration.ofMinutes(60)

/** How far the warm-up is (0..1) with [readyAt] ahead of [now]. */
fun warmUpDone(readyAt: Instant, now: Instant): Float {
    val left = Duration.between(now, readyAt).toMillis().coerceAtLeast(0)
    val total = maxOf(WARM_UP.toMillis(), left)
    return (1f - left.toFloat() / total).coerceIn(0f, 1f)
}

/** The screen stays on while the phone is held to the arm or pairing; the warm-up and the end let it lock. */
val NewSensorStep.keepsScreenOn: Boolean
    get() = !isFinal && this !is NewSensorStep.WarmingUp
