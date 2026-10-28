// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.monitor.alerts

import app.mauri.sukrara.alerts.AlertEffect
import app.mauri.sukrara.alerts.AlertInput
import app.mauri.sukrara.alerts.AlertKind
import app.mauri.sukrara.alerts.Rules
import app.mauri.sukrara.core.model.AlertNote
import app.mauri.sukrara.core.model.AlertNoteType
import kotlinx.serialization.Serializable
import java.time.Instant

/** [AlertNote] as saved in the alert record. */
@Serializable
data class SavedNote(
    val t: Long,
    val type: String,
    val kind: String? = null,
    val mgdl: Int? = null,
    val minutes: Int? = null,
    val remote: Boolean = false,
) {
    /** null = a type this build doesn't know (dropped). */
    fun note(): AlertNote? =
        AlertNoteType.entries.firstOrNull { it.name == type }?.let {
            AlertNote(Instant.ofEpochSecond(t), it, kind, mgdl, minutes, remote)
        }

    companion object {
        fun of(n: AlertNote) = SavedNote(n.at.epochSecond, n.type.name, n.kind, n.mgdl, n.minutes, n.remote)
    }
}

/**
 * The alerts' recent history (Settings › Alerts › Lately): what one input to the rules and its effects add, newest
 * last, kept to [MAX] lines. Only the first ring of an alert counts (not its repeats, nor a silent re-post after a
 * restart).
 */
object AlertNotes {
    const val MAX = 20

    fun of(
        input: AlertInput?,
        effects: List<AlertEffect>,
        now: Long,
    ): List<AlertNote> {
        val at = Instant.ofEpochSecond(now)
        val remote = input is AlertInput.Remote
        val out = ArrayList<AlertNote>()
        val refired = effects.filterIsInstance<AlertEffect.Fire>().map { it.firing.kind.group }.toSet()
        when (input) {
            is AlertInput.Mute -> {
                out += AlertNote(at, AlertNoteType.Muted, input.kind.wire, minutes = input.minutes)
            }

            is AlertInput.Unmute -> {
                out += AlertNote(at, AlertNoteType.Unmuted, input.kind.wire)
            }

            is AlertInput.RemindLess -> {
                out += AlertNote(at, AlertNoteType.RemindLess, input.kind.wire)
            }

            is AlertInput.RemindAsUsual -> {
                out += AlertNote(at, AlertNoteType.RemindAsUsual, input.kind.wire)
            }

            is AlertInput.MuteAll -> {
                val minutes = if (input.until >= Rules.NEVER) null else ((input.until - now + 59) / 60).toInt()
                out += AlertNote(at, AlertNoteType.AllMuted, minutes = minutes)
            }

            is AlertInput.UnmuteAll -> {
                out += AlertNote(at, AlertNoteType.AllUnmuted)
            }

            else -> {
                Unit
            }
        }
        val byPerson = out.isNotEmpty()
        for (e in effects) {
            when (e) {
                is AlertEffect.Fire -> {
                    if (!e.silent && e.firing.n <= 1) {
                        out += AlertNote(at, AlertNoteType.Fired, e.firing.kind.wire, e.firing.reading?.mgdl, remote = remote)
                    }
                }

                // a repeat or a change of kind replaces the notification, and a mute hides it (its own line says
                // so): only a Clear the rules decided alone ends the alert
                is AlertEffect.Clear -> {
                    if (e.kind.group !in refired && !byPerson) out += AlertNote(at, AlertNoteType.Cleared, e.kind.wire, remote = remote)
                }

                is AlertEffect.Back -> {
                    AlertKind.entries.firstOrNull { it.side == e.side }?.let { out += AlertNote(at, AlertNoteType.Back, it.wire) }
                }

                else -> {
                    Unit
                }
            }
        }
        return out
    }

    /** [saved] with [added] appended, the oldest dropped past [MAX]. */
    fun append(
        saved: List<SavedNote>,
        added: List<AlertNote>,
    ): List<SavedNote> = if (added.isEmpty()) saved else (saved + added.map(SavedNote::of)).takeLast(MAX)
}
