// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.mauri.sukrara.alerts.AlertKind
import app.mauri.sukrara.core.model.AlertNote
import app.mauri.sukrara.core.model.AlertNoteType
import app.mauri.sukrara.core.model.Clock
import app.mauri.sukrara.core.model.Durations
import app.mauri.sukrara.designsystem.alerts.AlertWords
import app.mauri.sukrara.designsystem.alerts.MuteText
import app.mauri.sukrara.designsystem.alerts.rememberAlertWords
import app.mauri.sukrara.designsystem.rows.InfoRow
import app.mauri.sukrara.designsystem.rows.SettingsGroup
import java.time.Duration

/** How many lines Lately shows, and how far back (older lines stay saved but aren't "lately"). */
internal const val LATELY_LINES = 5
internal val LATELY_WINDOW: Duration = Duration.ofHours(24)

/** The lines Lately shows: the newest [LATELY_LINES] of the last [LATELY_WINDOW] (newest first, as published). */
internal fun latelyOf(
    notes: List<AlertNote>,
    clock: Clock,
): List<AlertNote> {
    val since = clock.now() - LATELY_WINDOW
    return notes.filter { it.at >= since }.take(LATELY_LINES)
}

/**
 * Settings › Alerts › Lately: what alerted and what was muted on this phone in the last day, each with its time. Only
 * shown while there is something; nothing to act on here (the strips and pages above are the ways out).
 */
@Composable
internal fun AlertsLately(
    notes: List<AlertNote>,
    clock: Clock,
) {
    val lines = latelyOf(notes, clock)
    if (lines.isEmpty()) return
    val words = rememberAlertWords()
    SettingsGroup(title = stringResource(R.string.st_lately_title)) {
        for (n in lines) InfoRow(noteText(n, words), summary = clockTime(n.at, clock.zone))
    }
}

@Composable
private fun noteText(
    n: AlertNote,
    w: AlertWords,
): String {
    val kind = n.kind?.let(AlertKind::fromWire)
    val name = kind?.let { kindName(it) }.orEmpty()
    val side = kind?.let { MuteText.sideName(it.side, w) }.orEmpty()
    return when (n.type) {
        AlertNoteType.Fired -> {
            val text = if (n.mgdl != null) stringResource(R.string.st_note_fired, name, n.mgdl!!) else name
            if (n.remote) stringResource(R.string.st_note_remote, text) else text
        }

        AlertNoteType.Cleared -> {
            stringResource(if (n.remote) R.string.st_note_cleared_remote else R.string.st_note_cleared, name)
        }

        AlertNoteType.Muted -> {
            stringResource(R.string.st_note_muted, side, Durations.text((n.minutes ?: 0).toLong(), w.durations))
        }

        AlertNoteType.Unmuted -> {
            stringResource(R.string.st_note_unmuted, side)
        }

        AlertNoteType.RemindLess -> {
            stringResource(R.string.st_note_remind_less, side)
        }

        AlertNoteType.RemindAsUsual -> {
            stringResource(R.string.st_note_remind_as_usual, side)
        }

        AlertNoteType.Back -> {
            stringResource(R.string.st_note_back, side)
        }

        AlertNoteType.AllMuted -> {
            n.minutes?.let { stringResource(R.string.st_note_all_muted, Durations.text(it.toLong(), w.durations)) }
                ?: stringResource(R.string.st_note_all_muted_off)
        }

        AlertNoteType.AllUnmuted -> {
            stringResource(R.string.st_note_all_unmuted)
        }
    }
}
