// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.sharing.share

import android.content.ClipData
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.res.stringResource
import app.mauri.sukrara.designsystem.haptics.rememberHaptics
import app.mauri.sukrara.designsystem.state.StateKind
import app.mauri.sukrara.designsystem.state.StateScreen
import app.mauri.sukrara.feature.sharing.R
import kotlinx.coroutines.launch

/**
 * An invite link opened on this phone. Following from an Android phone isn't in this version, so it says where the
 * link works (Sukrara for Mac) and offers Copy link; Back leaves. Nothing is sent and the invite stays unused.
 */
@Composable
fun InviteHereScreen(
    link: String,
    modifier: Modifier = Modifier,
) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    val label = stringResource(R.string.sh_invite_here_title)
    StateScreen(
        kind = StateKind.Empty,
        title = label,
        reason = stringResource(R.string.sh_invite_here_body),
        action = stringResource(R.string.sh_invite_here_copy),
        onAction = {
            scope.launch {
                clipboard.setClipEntry(ClipData.newPlainText(label, link).toClipEntry())
                haptics.confirm()
            }
        },
        modifier = modifier.fillMaxSize(),
    )
}
