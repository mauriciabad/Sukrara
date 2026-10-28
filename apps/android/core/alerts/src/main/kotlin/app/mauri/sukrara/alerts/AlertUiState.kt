// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.alerts

/** Why a side is quiet (SPEC §2 "States and exits"). */
enum class QuietReason { NOTIFICATION, CARBS, INSULIN, FALLING }

/**
 * A side the user can act on now (quiet or relaxed), for the home sheet, Active now and the muted notification:
 * [kind] = its episode's current kind (the side's main kind without an episode), [matching] = it still matches,
 * [reason] null = not quiet, [until] the quiet's end (0 = no fixed end), [relaxMin] > 0 = relaxed, [canRelax] = a
 * *Remind less* would slow it down. Every state listed here has its exits in the UI (Unmute / Alert me anyway,
 * Change time, Remind as usual).
 */
data class QuietSide(
    val kind: AlertKind,
    val matching: Boolean,
    val reason: QuietReason?,
    val until: Long,
    val relaxMin: Int,
    val canRelax: Boolean,
)

/** What every surface shows about alerts: the quiet sides and Mute all (0 = off, [Rules.NEVER] = until turned off). */
data class AlertUiState(val quiet: List<QuietSide> = emptyList(), val muteAllUntil: Long = 0) {
    /** Side → muted until (this device's Mute, and Mute all on every side): the badge's [AlertStatus.Paused]. */
    fun paused(): Map<Side, Long> {
        val m = quiet.filter { it.reason == QuietReason.NOTIFICATION }.associate { it.kind.side to it.until }.toMutableMap()
        if (muteAllUntil > 0) Side.entries.forEach { m[it] = maxOf(m[it] ?: 0, muteAllUntil) }
        return m
    }

    companion object {
        /** The UI state of a mirroring follower's own actions ([WearerMirror]; the wearer's rules are not here). */
        fun ofMirror(m: WearerMirror, s: AlertSettings, now: Long): AlertUiState =
            AlertUiState(
                m.snoozes(now).filterKeys { it != Side.NO_DATA }.map { (side, sn) ->
                    QuietSide(
                        side.mainKind,
                        m.showing(side.mainKind.group),
                        if (sn.until > 0) QuietReason.NOTIFICATION else null,
                        sn.until,
                        sn.relaxMin,
                        sn.relaxMin == 0 && s.canRelax(side.mainKind),
                    )
                },
                m.muteAllUntil.takeIf { it > now } ?: 0,
            )

        /** The UI state of [engine] at [now] (the local rules of a wearer or an own-rules follower). */
        fun of(engine: AlertEngine, s: AlertSettings, d: Doses, now: Long): AlertUiState {
            val out = mutableListOf<QuietSide>()
            val snoozes = engine.snoozes(now)
            val allMuted = engine.allMuted(now)
            val seen = HashSet<Side>()
            for (e in engine.episodes().values) {
                seen += e.side
                val dose = Rules.coveringDose(e.kind, s, d, now, e.lifted)
                val by = s.mutedBy(e.kind)
                val muteEnd = snoozes[e.side]?.until?.takeIf { it > 0 }
                val relax = snoozes[e.side]?.relaxMin ?: 0
                val (reason, until) =
                    when {
                        dose != null && by != null -> {
                            (if (by == TreatmentKind.RAPID) QuietReason.INSULIN else QuietReason.CARBS) to
                                maxOf(dose + s.quietSeconds(by), muteEnd ?: 0)
                        }

                        muteEnd != null -> {
                            QuietReason.NOTIFICATION to muteEnd
                        }

                        // quiet only because of Mute all is Mute all's state, not a falling mute to "Alert me anyway" out of
                        e.on && e.quiet != null && (allMuted == 0L || Rules.fallingBackIntoRange(e.kind, engine.last, s)) -> {
                            QuietReason.FALLING to
                                0L
                        }

                        else -> {
                            null to 0L
                        }
                    }
                if (reason == null && relax == 0) continue
                out += QuietSide(e.kind, e.on, reason, until, relax, relax == 0 && s.canRelax(e.kind))
            }
            for ((side, sn) in snoozes) {
                if (side in seen) continue
                val k = side.mainKind
                out +=
                    QuietSide(
                        k,
                        false,
                        if (sn.until >
                            0
                        ) {
                            QuietReason.NOTIFICATION
                        } else {
                            null
                        },
                        sn.until,
                        sn.relaxMin,
                        sn.relaxMin == 0 && s.canRelax(k),
                    )
            }
            return AlertUiState(out, allMuted)
        }
    }
}

/**
 * What the glucose needs from the user now, for the badge under the value (every surface draws the same answer):
 * [None] in range, [Needed] act, [Taken] a treatment's window covers it, [Paused] muted on this device, [Muted]
 * in range while Mute all is on. Same treatment rules as [AlertEngine].
 */
sealed interface AlertStatus {
    data object None : AlertStatus

    data class Needed(val kind: AlertKind) : AlertStatus

    data class Taken(val kind: AlertKind, val treatment: TreatmentKind, val at: Long, val until: Long) : AlertStatus

    data class Paused(val kind: AlertKind, val until: Long) : AlertStatus

    data class Muted(val until: Long) : AlertStatus

    companion object {
        fun of(r: AlertReading?, s: AlertSettings, d: Doses, now: Long, ui: AlertUiState = AlertUiState()): AlertStatus {
            val all = ui.muteAllUntil.takeIf { it > now } ?: 0
            val k = Rules.levelOf(r, s, now) ?: return if (all > 0 && s.on) Muted(all) else None
            val by = s.mutedBy(k)
            val dose = Rules.coveringDose(k, s, d, now)
            if (by != null && dose != null) return Taken(k, by, dose, dose + s.quietSeconds(by))
            val until = maxOf(ui.paused()[k.side] ?: 0, all).takeIf { it > now }
            return if (until != null) Paused(k, until) else Needed(k)
        }
    }
}
