// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.sharing.conflict

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.mauri.sukrara.feature.sharing.R
import app.mauri.sukrara.feature.sharing.setup.NoteCard
import app.mauri.sukrara.feature.sharing.setup.StepButtons
import app.mauri.sukrara.feature.sharing.setup.StepHeader
import app.mauri.sukrara.share.owner.OwnerShareState

/**
 * A3: another device publishes as this identity. It blocks the sharing area only — readings and alerts keep running on
 * this phone. Exits: Retry after turning the other device's sharing off, or leave ([onClose]). There is no "replace
 * identity" (reinstall).
 */
@Composable
fun IdentityConflictScreen(
    state: OwnerShareState.AnotherAppSharingAsYou,
    onRetry: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        StepHeader(stringResource(R.string.sh_conflict_title), null)
        ConflictContent(state, onRetry)
        StepButtons(null, {}, secondary = listOf(stringResource(R.string.sh_conflict_close) to onClose))
    }
}

/** What happened, whether followers still get readings, and the ways out (shared with the setup identity step). */
@Composable
internal fun ColumnScope.ConflictContent(
    state: OwnerShareState.AnotherAppSharingAsYou,
    onRetry: () -> Unit,
) {
    Text(stringResource(R.string.sh_conflict_body))
    NoteCard(
        stringResource(
            if (state.followersReceiving) R.string.sh_conflict_followers_receiving else R.string.sh_conflict_followers_not_receiving,
        ),
    )
    Text(stringResource(R.string.sh_conflict_what_to_do))
    StepButtons(stringResource(R.string.sh_conflict_retry), onRetry)
}
