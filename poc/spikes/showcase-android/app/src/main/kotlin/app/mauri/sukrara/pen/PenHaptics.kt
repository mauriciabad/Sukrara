// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.pen

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.HapticFeedbackConstants
import android.view.View

/**
 * The pen's one haptics helper (PRODUCT §4.11 Feel): `VibrationEffect` composition primitives where the vibrator has
 * them all (Pixel), else the `HapticFeedbackConstants` fallback. Both follow the system's touch-feedback setting (the
 * view's haptic path, and primitives only through it being enabled).
 */
object PenHaptics {
    enum class Moment { TAP, SEGMENT, SUCCESS, NOTHING, FAILURE, UNDO, FLIP }

    private var lastSegment = 0L

    fun play(view: View, m: Moment) {
        if (m == Moment.SEGMENT) {   // feel the data flowing, at most one per 80 ms
            val now = System.currentTimeMillis()
            if (now - lastSegment < 80) return
            lastSegment = now
        }
        val v = view.context.getSystemService(Vibrator::class.java)
        val prims = when (m) {
            Moment.TAP -> listOf(VibrationEffect.Composition.PRIMITIVE_CLICK to 0.6f)
            Moment.SEGMENT -> listOf(VibrationEffect.Composition.PRIMITIVE_LOW_TICK to 0.3f)
            Moment.SUCCESS -> listOf(VibrationEffect.Composition.PRIMITIVE_CLICK to 1.0f, VibrationEffect.Composition.PRIMITIVE_TICK to 0.5f)
            Moment.NOTHING -> listOf(VibrationEffect.Composition.PRIMITIVE_TICK to 0.4f)
            Moment.FAILURE -> listOf(VibrationEffect.Composition.PRIMITIVE_THUD to 0.8f)
            Moment.UNDO -> listOf(VibrationEffect.Composition.PRIMITIVE_TICK to 0.6f)
            Moment.FLIP -> emptyList()
        }
        val enabled = view.isHapticFeedbackEnabled && touchFeedbackOn(view.context)
        if (prims.isNotEmpty() && enabled && v != null && v.areAllPrimitivesSupported(*prims.map { it.first }.toIntArray())) {
            val c = VibrationEffect.startComposition()
            prims.forEachIndexed { i, (id, scale) -> c.addPrimitive(id, scale, if (i == 0) 0 else 60) }
            runCatching { v.vibrate(c.compose()) }
            return
        }
        view.performHapticFeedback(when (m) {
            Moment.TAP -> HapticFeedbackConstants.CONTEXT_CLICK
            Moment.SEGMENT -> HapticFeedbackConstants.CLOCK_TICK
            Moment.SUCCESS -> HapticFeedbackConstants.CONFIRM
            Moment.NOTHING -> HapticFeedbackConstants.CLOCK_TICK
            Moment.FAILURE -> HapticFeedbackConstants.REJECT
            Moment.UNDO -> HapticFeedbackConstants.GESTURE_END
            Moment.FLIP -> HapticFeedbackConstants.SEGMENT_TICK
        })
    }

    private fun touchFeedbackOn(ctx: Context) =
        runCatching { android.provider.Settings.System.getInt(ctx.contentResolver, android.provider.Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) != 0 }.getOrDefault(true)

    /** A read worked / failed (the dialog and the read card). */
    fun read(view: View, ok: Boolean) = play(view, if (ok) Moment.SUCCESS else Moment.FAILURE)

    /** Android's *Remove animations*: no slides, shakes or count-ups (texts final at once; haptics stay). */
    fun reducedMotion(ctx: Context) =
        runCatching { android.provider.Settings.Global.getFloat(ctx.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false)
}
