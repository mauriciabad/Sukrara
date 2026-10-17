// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.alerts

import android.content.Context
import android.os.VibrationEffect
import android.os.VibratorManager
import android.os.VibrationEffect.Composition

/**
 * A distinct vibration per alert kind, so the kind can be told apart in a pocket with the sound off, shaped like the
 * kind's sounds (falls for lows, speeding-up clicks for high…). It's the channel's vibration
 * (`NotificationChannel.setVibrationEffect`, API 36): the system plays it with the sound, delayed to line up with it,
 * and alone in vibrate mode. Haptics *inside* the sound file don't work for notifications (SystemUI plays them with
 * FLAG_MUTE_HAPTIC, docs/15).
 *
 * Primitives only (a channel persists "a composition created exclusively via addPrimitive", not one mixed with
 * waveforms), delays ≥ 50 ms and intensity steps of 0.5 / 0.7 / 1.0 (developer.android.com/develop/ui/views/haptics/
 * haptics-principles). Devices without these primitives get an on/off waveform with the same rhythm.
 */
object AlertHaptics {
    private fun c() = VibrationEffect.startComposition()

    private val USED = intArrayOf(Composition.PRIMITIVE_QUICK_FALL, Composition.PRIMITIVE_THUD, Composition.PRIMITIVE_CLICK,
        Composition.PRIMITIVE_QUICK_RISE, Composition.PRIMITIVE_SPIN, Composition.PRIMITIVE_LOW_TICK,
        Composition.PRIMITIVE_SLOW_RISE, Composition.PRIMITIVE_TICK)

    fun effect(ctx: Context, kind: String): VibrationEffect {
        val v = ctx.getSystemService(VibratorManager::class.java).defaultVibrator
        return if (v.areAllPrimitivesSupported(*USED)) rich(kind) else fallback(kind)
    }

    /** Same rhythm as [rich], as off/on milliseconds with amplitudes. */
    private fun fallback(kind: String): VibrationEffect {
        val (t, a) = when (kind) {
            Kind.URGENT_LOW -> longArrayOf(0, 120, 120, 120, 180, 120, 260, 400, 200, 400) to intArrayOf(0, 255, 0, 220, 0, 190, 0, 255, 0, 255)
            Kind.LOW -> longArrayOf(0, 120, 160, 120, 220, 350) to intArrayOf(0, 230, 0, 200, 0, 230)
            Kind.HIGH -> longArrayOf(0, 40, 140, 40, 100, 40, 70, 40, 50, 40, 60, 160) to intArrayOf(0, 180, 0, 190, 0, 200, 0, 215, 0, 230, 0, 255)
            Kind.VERY_HIGH -> longArrayOf(0, 200, 90, 40, 200, 200, 90, 40, 220, 300) to intArrayOf(0, 200, 0, 255, 0, 200, 0, 255, 0, 255)
            Kind.EXTREMELY_HIGH -> longArrayOf(0, 200, 120, 200, 90, 40, 200, 200, 120, 200, 90, 40, 220, 300, 100, 150) to
                intArrayOf(0, 255, 0, 255, 0, 255, 0, 255, 0, 255, 0, 255, 0, 255, 0, 255)
            Kind.FALLING_FAST -> longArrayOf(0, 150, 120, 30) to intArrayOf(0, 230, 0, 150)
            Kind.RISING_FAST -> longArrayOf(0, 400, 60, 20) to intArrayOf(0, 200, 0, 255)
            else -> longArrayOf(0, 20, 160, 20, 160, 20) to intArrayOf(0, 255, 0, 160, 0, 100)
        }
        return VibrationEffect.createWaveform(t, a, -1)
    }

    private fun rich(kind: String): VibrationEffect = when (kind) {
        // "game over": three falls slowing down, then a heavy thud
        Kind.URGENT_LOW -> c()
            .addPrimitive(Composition.PRIMITIVE_QUICK_FALL, 1f)
            .addPrimitive(Composition.PRIMITIVE_QUICK_FALL, 0.7f, 180)
            .addPrimitive(Composition.PRIMITIVE_QUICK_FALL, 0.5f, 260)
            .addPrimitive(Composition.PRIMITIVE_THUD, 1f, 360)
            .addPrimitive(Composition.PRIMITIVE_THUD, 1f, 500)
            .compose()
        // "lost a life": two falls and a thud
        Kind.LOW -> c()
            .addPrimitive(Composition.PRIMITIVE_QUICK_FALL, 0.9f)
            .addPrimitive(Composition.PRIMITIVE_QUICK_FALL, 0.8f, 200)
            .addPrimitive(Composition.PRIMITIVE_THUD, 0.9f, 280)
            .compose()
        // "final lap": clicks speeding up, then a rise
        Kind.HIGH -> c()
            .addPrimitive(Composition.PRIMITIVE_CLICK, 0.7f)
            .addPrimitive(Composition.PRIMITIVE_CLICK, 0.75f, 140)
            .addPrimitive(Composition.PRIMITIVE_CLICK, 0.8f, 100)
            .addPrimitive(Composition.PRIMITIVE_CLICK, 0.85f, 70)
            .addPrimitive(Composition.PRIMITIVE_CLICK, 0.9f, 50)
            .addPrimitive(Composition.PRIMITIVE_QUICK_RISE, 1f, 60)
            .compose()
        // "boss incoming": double hits, twice, then a spin
        Kind.VERY_HIGH -> c()
            .addPrimitive(Composition.PRIMITIVE_THUD, 0.8f)
            .addPrimitive(Composition.PRIMITIVE_CLICK, 1f, 90)
            .addPrimitive(Composition.PRIMITIVE_THUD, 0.8f, 220)
            .addPrimitive(Composition.PRIMITIVE_CLICK, 1f, 90)
            .addPrimitive(Composition.PRIMITIVE_SPIN, 1f, 220)
            .compose()
        // "final boss": two double hits with a click, then a spin and a rise
        Kind.EXTREMELY_HIGH -> c()
            .addPrimitive(Composition.PRIMITIVE_THUD, 1f)
            .addPrimitive(Composition.PRIMITIVE_THUD, 1f, 120)
            .addPrimitive(Composition.PRIMITIVE_CLICK, 1f, 90)
            .addPrimitive(Composition.PRIMITIVE_THUD, 1f, 200)
            .addPrimitive(Composition.PRIMITIVE_THUD, 1f, 120)
            .addPrimitive(Composition.PRIMITIVE_CLICK, 1f, 90)
            .addPrimitive(Composition.PRIMITIVE_SPIN, 1f, 220)
            .addPrimitive(Composition.PRIMITIVE_QUICK_RISE, 1f, 100)
            .compose()
        Kind.FALLING_FAST -> c()
            .addPrimitive(Composition.PRIMITIVE_QUICK_FALL, 1f)
            .addPrimitive(Composition.PRIMITIVE_LOW_TICK, 1f, 120)
            .compose()
        Kind.RISING_FAST -> c()
            .addPrimitive(Composition.PRIMITIVE_SLOW_RISE, 1f)
            .addPrimitive(Composition.PRIMITIVE_TICK, 1f, 60)
            .compose()
        // "connection lost": ticks fading out
        else -> c()
            .addPrimitive(Composition.PRIMITIVE_TICK, 1f)
            .addPrimitive(Composition.PRIMITIVE_TICK, 0.6f, 160)
            .addPrimitive(Composition.PRIMITIVE_LOW_TICK, 0.4f, 160)
            .compose()
    }
}
