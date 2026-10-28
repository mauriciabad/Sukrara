// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.monitor.alerts

import android.content.Context
import android.os.VibrationEffect
import android.os.VibrationEffect.Composition
import android.os.VibratorManager
import app.mauri.sukrara.alerts.AlertKind

/**
 * One vibration per alert kind, so the person can tell which alert it is with the phone in a pocket: lows fall and
 * thud, highs rise, no data ticks away (developer.android.com/develop/ui/views/haptics/haptics-principles). Devices
 * without the composition primitives get an on/off waveform with the same rhythm.
 */
object AlertHaptics {
    private val USED =
        intArrayOf(
            Composition.PRIMITIVE_QUICK_FALL,
            Composition.PRIMITIVE_THUD,
            Composition.PRIMITIVE_CLICK,
            Composition.PRIMITIVE_QUICK_RISE,
            Composition.PRIMITIVE_SPIN,
            Composition.PRIMITIVE_LOW_TICK,
            Composition.PRIMITIVE_SLOW_RISE,
            Composition.PRIMITIVE_TICK,
        )

    fun effect(ctx: Context, kind: AlertKind): VibrationEffect {
        val v = ctx.getSystemService(VibratorManager::class.java).defaultVibrator
        return if (v.areAllPrimitivesSupported(*USED)) composed(kind) else waveform(kind)
    }

    /** (primitive, scale, delay ms) steps of [kind]'s pattern. */
    private fun steps(kind: AlertKind): List<Triple<Int, Float, Int>> =
        when (kind) {
            AlertKind.VERY_LOW -> {
                listOf(
                    Triple(Composition.PRIMITIVE_QUICK_FALL, 1f, 0),
                    Triple(Composition.PRIMITIVE_QUICK_FALL, 0.7f, 180),
                    Triple(Composition.PRIMITIVE_QUICK_FALL, 0.5f, 260),
                    Triple(Composition.PRIMITIVE_THUD, 1f, 360),
                    Triple(Composition.PRIMITIVE_THUD, 1f, 500),
                )
            }

            AlertKind.LOW -> {
                listOf(
                    Triple(Composition.PRIMITIVE_QUICK_FALL, 0.9f, 0),
                    Triple(Composition.PRIMITIVE_QUICK_FALL, 0.8f, 200),
                    Triple(Composition.PRIMITIVE_THUD, 0.9f, 280),
                )
            }

            AlertKind.HIGH -> {
                listOf(
                    Triple(Composition.PRIMITIVE_CLICK, 0.7f, 0),
                    Triple(Composition.PRIMITIVE_CLICK, 0.75f, 140),
                    Triple(Composition.PRIMITIVE_CLICK, 0.8f, 100),
                    Triple(Composition.PRIMITIVE_CLICK, 0.85f, 70),
                    Triple(Composition.PRIMITIVE_CLICK, 0.9f, 50),
                    Triple(Composition.PRIMITIVE_QUICK_RISE, 1f, 60),
                )
            }

            AlertKind.VERY_HIGH -> {
                listOf(
                    Triple(Composition.PRIMITIVE_THUD, 0.8f, 0),
                    Triple(Composition.PRIMITIVE_CLICK, 1f, 90),
                    Triple(Composition.PRIMITIVE_THUD, 0.8f, 220),
                    Triple(Composition.PRIMITIVE_CLICK, 1f, 90),
                    Triple(Composition.PRIMITIVE_SPIN, 1f, 220),
                )
            }

            AlertKind.EXTREMELY_HIGH -> {
                listOf(
                    Triple(Composition.PRIMITIVE_THUD, 1f, 0),
                    Triple(Composition.PRIMITIVE_THUD, 1f, 120),
                    Triple(Composition.PRIMITIVE_CLICK, 1f, 90),
                    Triple(Composition.PRIMITIVE_THUD, 1f, 200),
                    Triple(Composition.PRIMITIVE_THUD, 1f, 120),
                    Triple(Composition.PRIMITIVE_CLICK, 1f, 90),
                    Triple(Composition.PRIMITIVE_SPIN, 1f, 220),
                    Triple(Composition.PRIMITIVE_QUICK_RISE, 1f, 100),
                )
            }

            AlertKind.FALLING_FAST -> {
                listOf(Triple(Composition.PRIMITIVE_QUICK_FALL, 1f, 0), Triple(Composition.PRIMITIVE_LOW_TICK, 1f, 120))
            }

            AlertKind.RISING_FAST -> {
                listOf(Triple(Composition.PRIMITIVE_SLOW_RISE, 1f, 0), Triple(Composition.PRIMITIVE_TICK, 1f, 60))
            }

            AlertKind.NO_DATA -> {
                listOf(
                    Triple(Composition.PRIMITIVE_TICK, 1f, 0),
                    Triple(Composition.PRIMITIVE_TICK, 0.6f, 160),
                    Triple(Composition.PRIMITIVE_LOW_TICK, 0.4f, 160),
                )
            }
        }

    private fun composed(kind: AlertKind): VibrationEffect =
        steps(kind).fold(VibrationEffect.startComposition()) { c, (p, scale, delay) -> c.addPrimitive(p, scale, delay) }.compose()

    /** The same rhythm as off/on pulses: each step a 60 ms pulse after its delay (thuds and spins longer). */
    private fun waveform(kind: AlertKind): VibrationEffect {
        val timings = mutableListOf<Long>()
        val amplitudes = mutableListOf<Int>()
        for ((p, scale, delay) in steps(kind)) {
            timings += delay.toLong()
            amplitudes += 0
            timings += if (p == Composition.PRIMITIVE_THUD || p == Composition.PRIMITIVE_SPIN) 200L else 60L
            amplitudes += (scale * 255).toInt().coerceIn(1, 255)
        }
        return VibrationEffect.createWaveform(timings.toLongArray(), amplitudes.toIntArray(), -1)
    }
}
