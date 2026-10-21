// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.hook

import app.mauri.sukrara.graph.Freshness
import app.mauri.sukrara.graph.GlucoseLook
import app.mauri.sukrara.graph.TrendMark

/**
 * What the SystemUI hook's text ([SystemUiHook], LSPosed) shows, decided from [Freshness.look] (kept out of the module
 * class so the JVM tests can run it without the Xposed API).
 */
object HookText {
    /** The mark after the value: the arrow, the "no trend" ring, the sensor-ended flag, the "no readings" icon. */
    enum class Mark { ARROW, RING, FLAG, NO_READINGS }

    /** The value ([text], struck through, at [alpha]) and the [mark] after it, in [color]; [desc] for TalkBack. */
    data class Render(val text: String, val strike: Boolean, val alpha: Float, val color: Int, val mark: Mark, val desc: String)

    /**
     * The hook's text for a [look]: the value in the range colour ([rangeColor], when [colorOn]) or [base] with its
     * arrow / the "no trend" ring; struck through at half strength from 5 min; from 20 min no value, only the "no
     * readings" icon; the flag once the sensor has ended. English (SystemUI process).
     */
    fun of(look: GlucoseLook, colorOn: Boolean, rangeColor: Int?, base: Int): Render = when (look.state) {
        GlucoseLook.State.ENDED -> Render("", false, 1f, base, Mark.FLAG, "Sensor ended")   // i18n-ok: SystemUI process
        GlucoseLook.State.NO_READINGS, GlucoseLook.State.NONE -> Render("", false, 1f, base, Mark.NO_READINGS, "No glucose readings")   // i18n-ok: SystemUI process
        GlucoseLook.State.VALUE -> Render(look.text!!, look.strike, if (look.strike) 0.5f else 1f,
            if (look.strike || !colorOn) base else rangeColor ?: base,
            if (look.trend == TrendMark.ARROW) Mark.ARROW else Mark.RING,
            "Glucose ${look.text}${if (look.strike) " (old)" else ""}")   // i18n-ok: SystemUI process
    }
}
