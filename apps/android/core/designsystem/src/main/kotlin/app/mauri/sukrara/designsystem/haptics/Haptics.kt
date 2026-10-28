// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.designsystem.haptics

import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView

/**
 * Haptics that confirm what the person just did. Only call these from a user action (a tap, a toggle, a saved
 * value) — never from a passive update such as a new reading: alerts own the phone's vibration for glucose.
 * Goes through [View.performHapticFeedback], which honours the system's touch-feedback setting.
 */
class Haptics internal constructor(
    private val view: View,
) {
    /** A change was accepted (saved, toggled, added). */
    fun confirm() {
        view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
    }

    /** An action was refused (invalid value, nothing to undo). */
    fun reject() {
        view.performHapticFeedback(HapticFeedbackConstants.REJECT)
    }

    /** One option of a set picked (a shuffle, a swatch): a light tick. */
    fun tick() {
        view.performHapticFeedback(HapticFeedbackConstants.SEGMENT_TICK)
    }

    /** A toggle switched on / off. */
    fun toggle(on: Boolean) {
        view.performHapticFeedback(if (on) HapticFeedbackConstants.TOGGLE_ON else HapticFeedbackConstants.TOGGLE_OFF)
    }
}

/** The [Haptics] of the current view. */
@Composable
fun rememberHaptics(): Haptics {
    val view = LocalView.current
    return remember(view) { Haptics(view) }
}
