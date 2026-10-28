// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.designsystem.alerts

import android.annotation.SuppressLint
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import app.mauri.sukrara.alerts.AlertKind
import app.mauri.sukrara.alerts.MuteCopy
import app.mauri.sukrara.alerts.MuteLine
import app.mauri.sukrara.alerts.QuietReason
import app.mauri.sukrara.alerts.QuietSide
import app.mauri.sukrara.alerts.Rules
import app.mauri.sukrara.alerts.Side
import app.mauri.sukrara.alerts.TreatmentKind
import app.mauri.sukrara.core.model.AlertQuietReason
import app.mauri.sukrara.core.model.AlertQuietSide
import app.mauri.sukrara.core.model.AlertUiState
import app.mauri.sukrara.core.model.DurationWords
import app.mauri.sukrara.core.model.Durations
import kotlinx.coroutines.delay
import java.time.Instant

/**
 * The app's words by string-resource name (the merged resources at run time; tests read the `values/` and `values-es/`
 * XML), so the wording is testable on the JVM against contracts/alerts/copy.json, and the `duration_*` formats of THE
 * duration formatter ([Durations], strings in `:core:graph`).
 */
interface AlertWords {
    fun get(name: String): String

    val durations: DurationWords

    companion object {
        /** [ctx]'s strings, read on each call: the app's language may change while the caller lives. */
        fun of(ctx: Context): AlertWords =
            object : AlertWords {
                @SuppressLint("DiscouragedApi") // the lines are picked by name, the same way the JVM test reads the XML
                override fun get(name: String) = ctx.getString(ctx.resources.getIdentifier(name, "string", ctx.packageName))

                override val durations: DurationWords
                    get() =
                        DurationWords(
                            minutes = get("duration_minutes"),
                            hours = get("duration_hours"),
                            hoursMinutes = get("duration_hours_minutes"),
                            days = get("duration_days"),
                            textMinutes = get("duration_text_minutes"),
                            textHours = get("duration_text_hours"),
                            textHoursMinutes = get("duration_text_hours_minutes"),
                        )
            }
    }
}

/**
 * THE mute / remind-less lines ([MuteLine], contracts/alerts/copy.json) in the app's language: the alert
 * notifications, home's Mute all strip and the Settings strips all word them here (one formatter, en + es).
 */
object MuteText {
    fun sideName(
        side: Side,
        w: AlertWords,
    ) = w.get("side_${side.wire}")

    /** [base]'s one / many form: a duration of [min] that reads as one ("1 h", "1 min": Spanish "queda") or many. */
    private fun left(
        base: String,
        min: Long,
        w: AlertWords,
    ) = w.get(base + if (Durations.textQuantity(min) == 1) "_one" else "_other")

    fun line(
        l: MuteLine,
        w: AlertWords,
        clockTime: (Long) -> String = { error("no clock formatter") },
    ): String =
        when (l) {
            is MuteLine.SideMuted -> {
                when (val min = l.leftMin) {
                    null -> w.get("mute_side").format(sideName(l.side, w))
                    else -> left("mute_side_left", min, w).format(sideName(l.side, w), Durations.text(min, w.durations))
                }
            }

            is MuteLine.SideAfter -> {
                left(if (l.treatment == TreatmentKind.CARBS) "mute_after_carbs" else "mute_after_insulin", l.leftMin, w)
                    .format(sideName(l.side, w), Durations.text(l.leftMin, w.durations))
            }

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

    /** Mute all's line from the hub's state ("Alerts muted · 1 h 12 min left"), or null while it is off or over. */
    fun muteAll(
        alerts: AlertUiState,
        now: Instant,
        w: AlertWords,
    ): String? =
        alerts.mutedUntil
            ?.let { endOf(alerts.mutedUntilTurnedOff, it) }
            ?.takeIf { it > now.epochSecond }
            ?.let { line(MuteCopy.all(it, now.epochSecond), w) }

    /** A quiet or relaxed side's line from the hub's state ("Low alerts muted after carbs · 12 min left"). */
    fun side(
        q: AlertQuietSide,
        now: Instant,
        w: AlertWords,
    ): String? {
        val kind = AlertKind.fromWire(q.kind) ?: return null
        val reason =
            when (q.reason) {
                AlertQuietReason.Muted -> QuietReason.NOTIFICATION
                AlertQuietReason.AfterCarbs -> QuietReason.CARBS
                AlertQuietReason.AfterInsulin -> QuietReason.INSULIN
                AlertQuietReason.FallingBack -> QuietReason.FALLING
                null -> null
            }
        // null = no end: until turned off (a mute) or none at all (falling back, remind less)
        val until = q.until?.epochSecond ?: Rules.NEVER
        // a timed quiet that is over shows nothing until the hub republishes (never "0 min left")
        if (reason != null && reason != QuietReason.FALLING && until <= now.epochSecond) return null
        return line(MuteCopy.of(QuietSide(kind, q.matching, reason, until, q.relaxMin, q.canRemindLess), now.epochSecond), w)
    }

    /** When the line for a mute ending at [until] next changes (every surface ticks on the same minute), or null. */
    fun nextChangeAt(
        until: Instant?,
        now: Instant,
    ): Instant? =
        until
            ?.takeIf { it != AlertUiState.UNTIL_TURNED_OFF }
            ?.let { MuteCopy.nextChangeAt(it.epochSecond, now.epochSecond) }
            ?.let(Instant::ofEpochSecond)

    private fun endOf(
        forever: Boolean,
        until: Instant,
    ) = if (forever) Rules.NEVER else until.epochSecond
}

/** The app's words for [MuteText], again when the language changes. */
@Composable
fun rememberAlertWords(): AlertWords {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    return remember(context, configuration) { AlertWords.of(context) }
}

/** [now] moved on to each minute a countdown ending at [until] shows ([MuteText.nextChangeAt]). */
@Composable
fun rememberCountdownNow(
    until: Instant?,
    now: () -> Instant,
): Instant {
    val ticking by produceState(now(), until) {
        while (true) {
            val n = now()
            value = n
            val next = MuteText.nextChangeAt(until, n) ?: break
            delay((next.toEpochMilli() - n.toEpochMilli()).coerceAtLeast(1))
        }
    }
    return ticking
}
