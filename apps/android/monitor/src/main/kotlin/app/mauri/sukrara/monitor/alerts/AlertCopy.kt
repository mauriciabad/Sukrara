// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.monitor.alerts

import app.mauri.sukrara.alerts.MuteLine
import app.mauri.sukrara.alerts.Side
import app.mauri.sukrara.alerts.TreatmentKind
import app.mauri.sukrara.core.model.DurationWords
import app.mauri.sukrara.core.model.Durations

/**
 * The app's words by string-resource name (Android: `Resources.getIdentifier` in [AlertNotifier]; tests read the
 * `values/` and `values-es/` XML), so the wording is testable on the JVM against contracts/alerts/copy.json, and the
 * `duration_*` formats of THE duration formatter ([Durations], strings in `:core:graph`).
 */
interface AlertWords {
    fun get(name: String): String

    val durations: DurationWords
}

/** Whether a duration of [min] reads as one ("1 h", "1 min": Spanish "queda") rather than many ("quedan"). */
internal fun readsAsOne(min: Long): Boolean = Durations.textQuantity(min) == 1

/** THE mute / remind-less lines ([MuteLine], contracts/alerts/copy.json) in the app's language. */
object AlertCopy {
    fun sideName(side: Side, w: AlertWords) = w.get("side_${side.wire}")

    private fun left(base: String, min: Long, w: AlertWords) = w.get(base + if (readsAsOne(min)) "_one" else "_other")

    fun line(l: MuteLine, w: AlertWords, clockTime: (Long) -> String = { error("no clock formatter") }): String = when (l) {
        is MuteLine.SideMuted -> when (val min = l.leftMin) {
            null -> w.get("mute_side").format(sideName(l.side, w))
            else -> left("mute_side_left", min, w).format(sideName(l.side, w), Durations.text(min, w.durations))
        }
        is MuteLine.SideAfter -> left(if (l.treatment == TreatmentKind.CARBS) "mute_after_carbs" else "mute_after_insulin", l.leftMin, w)
            .format(sideName(l.side, w), Durations.text(l.leftMin, w.durations))
        is MuteLine.SideFalling -> w.get("mute_falling").format(sideName(l.side, w))
        is MuteLine.SideLess -> w.get("mute_less").format(sideName(l.side, w), Durations.text(l.intervalMin.toLong(), w.durations))
        is MuteLine.AllTimed -> left("mute_all_left", l.leftMin, w).format(Durations.text(l.leftMin, w.durations))
        MuteLine.AllForever -> w.get("mute_all_forever")
        is MuteLine.NotifUntil -> w.get("mute_notif_until").format(clockTime(l.until))
        is MuteLine.Pill -> w.get("mute_pill").format(Durations.compact(l.leftMin, w.durations))
        MuteLine.PillForever -> w.get("mute_pill_forever")
        is MuteLine.Choice -> w.get("mute_choice").format(Durations.text(l.min.toLong(), w.durations))
        MuteLine.ChoiceForever -> w.get("mute_choice_forever")
    }
}
