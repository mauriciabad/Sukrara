// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.monitor.alerts

import app.mauri.sukrara.alerts.AlertKind
import app.mauri.sukrara.alerts.AlertReading
import app.mauri.sukrara.alerts.Firing
import app.mauri.sukrara.core.model.Durations
import app.mauri.sukrara.designsystem.alerts.AlertWords
import app.mauri.sukrara.designsystem.alerts.MuteText
import app.mauri.sukrara.graph.TrendAngle
import java.util.Locale

/** An alert notification's words in the app's language (the mute lines are [MuteText]'s). */
object AlertCopy {
    /**
     * An alert notification's body: the value and arrow (`69 mg/dL ↘`), with the rate for falling / rising fast, or for
     * no data how long and the last value. Never a made-up number: without a reading it says so (no "0 mg/dL"), and
     * without a rate the fast alerts show only the value.
     */
    fun body(f: Firing, w: AlertWords): String {
        val r = f.reading
        if (f.kind == AlertKind.NO_DATA) {
            val since = Durations.text(((f.at - (r?.t ?: f.at)) / 60).coerceAtLeast(f.threshold.toLong()), w.durations)
            return if (r == null) w.get("alert_text_no_data").format(since) else w.get("alert_text_no_data_last").format(since, value(r, w))
        }
        if (r == null) return w.get("alert_text_no_reading")
        val value = w.get("alert_text_value").format(value(r, w), TrendAngle.text(r.rate))
        val rate = r.rate?.let { String.format(Locale.getDefault(), "%.1f", kotlin.math.abs(it)) } ?: return value
        return when (f.kind) {
            AlertKind.FALLING_FAST -> w.get("alert_text_falling").format(value, rate)
            AlertKind.RISING_FAST -> w.get("alert_text_rising").format(value, rate)
            else -> value
        }
    }

    /** `69 mg/dL`, or the sensor's LO / HI with what it means (never its stored 39 / 401). */
    private fun value(r: AlertReading, w: AlertWords): String =
        when {
            r.isLo -> w.get("alert_text_lo")
            r.isHi -> w.get("alert_text_hi")
            else -> w.get("alert_text_mgdl").format(r.mgdl)
        }
}
