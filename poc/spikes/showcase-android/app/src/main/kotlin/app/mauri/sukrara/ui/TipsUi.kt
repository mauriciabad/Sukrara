// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.mauri.sukrara.R
import app.mauri.sukrara.features.Features
import app.mauri.sukrara.features.SystemAccess
import app.mauri.sukrara.features.Tips
import app.mauri.sukrara.widgets.WidgetPresence

/**
 * Undo after a tip card is closed (round f33): an M3 snackbar with one action, as the guidance has it for an action the
 * user can take back ("Snackbars … can contain a single action", m3.material.io/components/snackbar/guidelines;
 * developer.android.com/develop/ui/compose/components/snackbar). Long duration; the M3 host stretches it for users who
 * asked for longer timeouts (TalkBack: Accessibility → Time to take action). Settings → *Show closed tips* stays the
 * way back once it's gone.
 */
@Composable
fun TipUndoSnackbar(host: SnackbarHostState = remember { SnackbarHostState() }) {
    val ctx = LocalContext.current
    val closed by Tips.justClosed.collectAsState()
    val message = stringResource(R.string.tips_closed)
    val undo = stringResource(R.string.tips_undo)
    LaunchedEffect(closed) {
        val t = closed ?: return@LaunchedEffect
        when (host.showSnackbar(message, actionLabel = undo, duration = SnackbarDuration.Long)) {
            SnackbarResult.ActionPerformed -> Tips.undo(ctx, t)
            SnackbarResult.Dismissed -> Tips.consumed(t)
        }
    }
    Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.BottomCenter) { SnackbarHost(host) }
}

/** The tips closed now, kept current while any of their stores changes. */
@Composable
fun closedTips(): List<Tips.Tip> {
    val ctx = LocalContext.current
    // read so a change in any store recomposes the caller
    Features.state.collectAsState().value
    WidgetPresence.state.collectAsState().value
    SystemAccess.dismissed.collectAsState().value
    SystemAccess.status.collectAsState().value
    return Tips.closed(ctx)
}

/**
 * Settings → *Show closed tips* (both roles' overviews): every closed card comes back. Unavailable (greyed, *No closed
 * tips*) while none is closed; a tip shows again only where its own conditions hold (no widget placed, …).
 */
@Composable
fun showClosedTipsRow(): Triple<String, String, Boolean> {
    val n = closedTips().size
    return Triple(stringResource(R.string.tips_show_again),
        if (n == 0) stringResource(R.string.tips_none_closed) else pluralStringResource(R.plurals.tips_closed_count, n, n), n > 0)
}
