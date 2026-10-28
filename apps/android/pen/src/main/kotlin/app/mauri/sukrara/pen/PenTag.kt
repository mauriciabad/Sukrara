// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.pen

import android.content.Context
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.nfc.tech.Ndef
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.View
import app.mauri.sukrara.pen.opennov.NotAPenException
import app.mauri.sukrara.pen.opennov.OpenNovReader
import app.mauri.sukrara.pen.opennov.PenRead

/**
 * One NFC tag → a pen read (blocking: call off the main thread). The pen is an NFC Forum Type 4 tag whose NDEF check
 * times out in Android, so it arrives as IsoDep + NfcA like bank and transit cards and passports: only the pen's
 * application selection tells a pen apart ([Outcome.NotPen], said nothing about). A pen that breaks off mid-transfer
 * throws: nothing of a partial read is used.
 */
object PenTag {
    enum class Outcome { Pen, NotPen, Failed }

    /** A read attempt: a pen read, a tag that never answered the pen's selection, or a pen that answered and failed. */
    fun outcome(
        selected: Boolean,
        error: Throwable?,
    ): Outcome =
        when {
            error == null && selected -> Outcome.Pen
            !selected || error is NotAPenException -> Outcome.NotPen
            else -> Outcome.Failed
        }

    /** IsoDep, and not some other NDEF tag type (an NDEF sticker that isn't Type 4). */
    fun isPen(tag: Tag): Boolean = IsoDep.get(tag) != null && (Ndef.get(tag)?.type?.let { it == Ndef.NFC_FORUM_TYPE_4 } ?: true)

    /** [onSelected]: the tag answered the pen's selection (it is a pen). */
    fun read(
        tag: Tag,
        onSelected: () -> Unit,
        progress: (Float) -> Unit,
        stopBefore: (String) -> Long?,
    ): PenRead? {
        if (!isPen(tag)) return null
        val iso = IsoDep.get(tag)
        iso.connect()
        try {
            iso.timeout = 3000
            return OpenNovReader.read(
                { iso.transceive(it) },
                log = { Log.d("SukraraPen", it) },
                stopBeforeFor = stopBefore,
                progress = progress,
                onSelected = onSelected,
            )
        } finally {
            runCatching { iso.close() }
        }
    }
}

/**
 * The pen's haptics: composition primitives where the vibrator has them all, else the `HapticFeedbackConstants`
 * fallback. Both follow the system's touch-feedback setting.
 */
object PenHaptics {
    enum class Moment { Tap, Segment, Success, Nothing, Failure, Undo }

    private var lastSegment = 0L

    fun play(
        view: View,
        m: Moment,
    ) {
        if (m == Moment.Segment) {
            // feel the data flowing, at most one tick per 80 ms
            val now = System.currentTimeMillis()
            if (now - lastSegment < 80) return
            lastSegment = now
        }
        val prims =
            when (m) {
                Moment.Tap -> {
                    listOf(VibrationEffect.Composition.PRIMITIVE_CLICK to 0.6f)
                }

                Moment.Segment -> {
                    listOf(VibrationEffect.Composition.PRIMITIVE_LOW_TICK to 0.3f)
                }

                Moment.Success -> {
                    listOf(
                        VibrationEffect.Composition.PRIMITIVE_CLICK to 1.0f,
                        VibrationEffect.Composition.PRIMITIVE_TICK to 0.5f,
                    )
                }

                Moment.Nothing -> {
                    listOf(VibrationEffect.Composition.PRIMITIVE_TICK to 0.4f)
                }

                Moment.Failure -> {
                    listOf(VibrationEffect.Composition.PRIMITIVE_THUD to 0.8f)
                }

                Moment.Undo -> {
                    listOf(VibrationEffect.Composition.PRIMITIVE_TICK to 0.6f)
                }
            }
        val v = view.context.getSystemService(Vibrator::class.java)

        // the ids come from the Composition constants above (lint can't follow them through the list)
        @Suppress("WrongConstant")
        val supported = v != null && v.areAllPrimitivesSupported(*prims.map { it.first }.toIntArray())
        if (view.isHapticFeedbackEnabled && touchFeedbackOn(view.context) && supported) {
            val c = VibrationEffect.startComposition()
            prims.forEachIndexed { i, (id, scale) -> c.addPrimitive(id, scale, if (i == 0) 0 else 60) }
            runCatching { v.vibrate(c.compose()) }
            return
        }
        view.performHapticFeedback(
            when (m) {
                Moment.Tap -> HapticFeedbackConstants.CONTEXT_CLICK
                Moment.Segment, Moment.Nothing -> HapticFeedbackConstants.CLOCK_TICK
                Moment.Success -> HapticFeedbackConstants.CONFIRM
                Moment.Failure -> HapticFeedbackConstants.REJECT
                Moment.Undo -> HapticFeedbackConstants.GESTURE_END
            },
        )
    }

    private fun touchFeedbackOn(ctx: Context) =
        runCatching { Settings.System.getInt(ctx.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) != 0 }.getOrDefault(true)
}
