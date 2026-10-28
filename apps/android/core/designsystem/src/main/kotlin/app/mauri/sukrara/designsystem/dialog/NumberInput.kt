// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.designsystem.dialog

import app.mauri.sukrara.settings.NumberSpec

/**
 * What [NumberDialog] offers and accepts for one setting, without UI (NumberDialogTest). [presets] (default: the
 * spec's) may be narrowed by the caller; [customMin] raises the lowest custom value; [reject] returns a message for
 * a value that is valid but not allowed here (e.g. a line that's already drawn).
 */
class NumberInput(
    val spec: NumberSpec,
    presets: List<Double> = spec.presets,
    val customMin: Double = spec.min,
    private val reject: (Double) -> String? = { null },
) {
    /** The radio options: special values (sorted, e.g. 0 = "Off") first, then the presets. */
    val options: List<Double> = (spec.special.keys.filter { k -> presets.none { same(it, k) } }.sorted() + presets).distinct()

    /** Whether the dialog shows a "Custom" choice with a number field. */
    val hasCustom: Boolean get() = spec.custom

    /** Whether [value] is one of the radio options (else "Custom" starts selected with [value] in the field). */
    fun isOption(value: Double): Boolean = options.any { same(it, value) }

    /** Whether two stored values are the same option (doubles from storage never compare exactly). */
    fun same(
        a: Double,
        b: Double,
    ): Boolean = kotlin.math.abs(a - b) < EPS

    /** The text the field starts with for the current [value]. */
    fun initialText(value: Double): String = if (isOption(value)) "" else spec.text(value)

    /** Keeps only what a number field takes: digits, and one decimal separator when the spec has decimals. */
    fun filter(typed: String): String {
        var separator = false
        return typed
            .filter { c ->
                when {
                    c.isDigit() -> true
                    spec.decimals > 0 && (c == '.' || c == ',') && !separator -> true.also { separator = true }
                    else -> false
                }
            }.take(MAX_CHARS)
    }

    /** The result of the typed [text]: the stored value to save, or why it can't be saved. */
    fun check(text: String): Check {
        if (text.isEmpty()) return Check.Empty
        val v = spec.parse(text)?.takeIf { it >= customMin - EPS } ?: return Check.OutOfRange
        return reject(v)?.let { Check.Rejected(it) } ?: Check.Ok(v)
    }

    /** The lowest custom value as shown (for the range text). */
    val shownMin: String get() = spec.text(maxOf(customMin, spec.min))

    val shownMax: String get() = spec.text(spec.max)

    sealed interface Check {
        data object Empty : Check

        data object OutOfRange : Check

        data class Rejected(
            val message: String,
        ) : Check

        data class Ok(
            val value: Double,
        ) : Check
    }

    private companion object {
        const val MAX_CHARS = 6
        const val EPS = 1e-9
    }
}
